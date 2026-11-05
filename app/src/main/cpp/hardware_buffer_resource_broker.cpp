#include <jni.h>

#include <android/hardware_buffer.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <limits>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

namespace {

constexpr uint32_t kProtocolVersion = 1;
constexpr uint32_t kMaxDimension = 16384;
constexpr uint32_t kDefaultLayers = 1;
constexpr uint32_t kDefaultFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
constexpr uint64_t kDefaultUsage =
    AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
    AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
    AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
    AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;

struct ResourceEntry {
    AHardwareBuffer* buffer;
    uint64_t generation;
    AHardwareBuffer_Desc descriptor;
};

std::mutex g_registry_mutex;
std::unordered_map<uint64_t, ResourceEntry> g_registry;
std::atomic<uint64_t> g_next_resource_id {1};

jstring JString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

void AppendPrefix(
        std::ostringstream& output,
        const char* operation,
        const char* status) {
    output << "pocketpc-ahb-broker=" << operation
           << ";status=" << status
           << ";protocol=" << kProtocolVersion;
}

uint64_t NextResourceId() {
    const uint64_t id =
        g_next_resource_id.fetch_add(
            1,
            std::memory_order_relaxed);
    if (id == 0 || id == std::numeric_limits<uint64_t>::max()) {
        return 0;
    }
    return id;
}

bool AcquireResource(
        uint64_t resource_id,
        uint64_t generation,
        ResourceEntry* out) {
    if (out == nullptr || resource_id == 0 || generation == 0) {
        return false;
    }

    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const auto it = g_registry.find(resource_id);
    if (
        it == g_registry.end() ||
        it->second.generation != generation ||
        it->second.buffer == nullptr
    ) {
        return false;
    }

    AHardwareBuffer_acquire(it->second.buffer);
    *out = it->second;
    return true;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_HostGraphicsResourceBroker_nativeCreateBuffer(
        JNIEnv* env,
        jobject /* thiz */,
        jint width,
        jint height) {
    std::ostringstream output;

    if (
        width <= 0 ||
        height <= 0 ||
        static_cast<uint32_t>(width) > kMaxDimension ||
        static_cast<uint32_t>(height) > kMaxDimension
    ) {
        AppendPrefix(output, "create", "invalid-dimensions");
        output << ";width=" << width
               << ";height=" << height;
        return JString(env, output.str());
    }

    const uint64_t resource_id = NextResourceId();
    if (resource_id == 0) {
        AppendPrefix(output, "create", "id-exhausted");
        return JString(env, output.str());
    }

    AHardwareBuffer_Desc requested {};
    requested.width = static_cast<uint32_t>(width);
    requested.height = static_cast<uint32_t>(height);
    requested.layers = kDefaultLayers;
    requested.format = kDefaultFormat;
    requested.usage = kDefaultUsage;

    AHardwareBuffer* buffer = nullptr;
    const int allocate_result =
        AHardwareBuffer_allocate(&requested, &buffer);
    if (allocate_result != 0 || buffer == nullptr) {
        AppendPrefix(output, "create", "allocation-failed");
        output << ";allocate=" << allocate_result;
        if (buffer != nullptr) {
            AHardwareBuffer_release(buffer);
        }
        return JString(env, output.str());
    }

    AHardwareBuffer_Desc actual {};
    AHardwareBuffer_describe(buffer, &actual);

    constexpr uint64_t generation = 1;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        const auto [it, inserted] =
            g_registry.emplace(
                resource_id,
                ResourceEntry {
                    buffer,
                    generation,
                    actual,
                });
        if (!inserted) {
            (void)it;
            AHardwareBuffer_release(buffer);
            AppendPrefix(output, "create", "registry-collision");
            return JString(env, output.str());
        }
    }

    AppendPrefix(output, "create", "ok");
    output << ";resource_id=" << resource_id
           << ";generation=" << generation
           << ";width=" << actual.width
           << ";height=" << actual.height
           << ";layers=" << actual.layers
           << ";pixel_format=" << actual.format
           << ";usage=" << actual.usage
           << ";producer_pid=" << getpid();
    return JString(env, output.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_HostGraphicsResourceBroker_nativeSendBuffer(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id_value,
        jlong generation_value,
        jint socket_fd) {
    std::ostringstream output;
    const uint64_t resource_id =
        static_cast<uint64_t>(resource_id_value);
    const uint64_t generation =
        static_cast<uint64_t>(generation_value);

    if (
        resource_id_value <= 0 ||
        generation_value <= 0 ||
        socket_fd < 0
    ) {
        AppendPrefix(output, "send", "invalid-argument");
        return JString(env, output.str());
    }

    ResourceEntry entry {};
    if (!AcquireResource(resource_id, generation, &entry)) {
        AppendPrefix(output, "send", "stale-or-unknown-resource");
        output << ";resource_id=" << resource_id
               << ";generation=" << generation;
        return JString(env, output.str());
    }

    const int send_result =
        AHardwareBuffer_sendHandleToUnixSocket(
            entry.buffer,
            socket_fd);
    AHardwareBuffer_release(entry.buffer);

    AppendPrefix(
        output,
        "send",
        send_result == 0 ? "ok" : "send-failed");
    output << ";resource_id=" << resource_id
           << ";generation=" << generation
           << ";send=" << send_result;
    return JString(env, output.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_HostGraphicsResourceBroker_nativeReleaseBuffer(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id_value,
        jlong generation_value) {
    std::ostringstream output;
    if (resource_id_value <= 0 || generation_value <= 0) {
        AppendPrefix(output, "release", "invalid-argument");
        return JString(env, output.str());
    }

    const uint64_t resource_id =
        static_cast<uint64_t>(resource_id_value);
    const uint64_t generation =
        static_cast<uint64_t>(generation_value);

    AHardwareBuffer* buffer = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        const auto it = g_registry.find(resource_id);
        if (
            it == g_registry.end() ||
            it->second.generation != generation
        ) {
            AppendPrefix(output, "release", "stale-or-unknown-resource");
            output << ";resource_id=" << resource_id
                   << ";generation=" << generation;
            return JString(env, output.str());
        }

        buffer = it->second.buffer;
        g_registry.erase(it);
    }

    if (buffer != nullptr) {
        AHardwareBuffer_release(buffer);
    }

    AppendPrefix(output, "release", "ok");
    output << ";resource_id=" << resource_id
           << ";generation=" << generation;
    return JString(env, output.str());
}
