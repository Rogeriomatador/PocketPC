#include <jni.h>

#include <android/hardware_buffer.h>
#include <unistd.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>

namespace {

constexpr uint32_t kWidth = 64;
constexpr uint32_t kHeight = 64;
constexpr std::array<uint8_t, 16> kProbePattern = {
    0x50, 0x43, 0x50, 0x43,
    0x2d, 0x41, 0x48, 0x42,
    0x2d, 0x58, 0x50, 0x52,
    0x4f, 0x43, 0x01, 0x04,
};

jstring JString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_NativeRuntimeHost_nativeSendHardwareBufferCrossProcessProbe(
        JNIEnv* env,
        jobject /* thiz */,
        jint socket_fd) {
    std::ostringstream output;
    output << "ahb-xproc-send=";

    if (socket_fd < 0) {
        output << "invalid-fd;pid=" << getpid();
        return JString(env, output.str());
    }

    AHardwareBuffer_Desc requested {};
    requested.width = kWidth;
    requested.height = kHeight;
    requested.layers = 1;
    requested.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    requested.usage =
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
        AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;

    AHardwareBuffer* buffer = nullptr;
    const int allocate_result =
        AHardwareBuffer_allocate(&requested, &buffer);
    if (allocate_result != 0 || buffer == nullptr) {
        output << "allocation-failed"
               << ";pid=" << getpid()
               << ";allocate=" << allocate_result;
        return JString(env, output.str());
    }

    AHardwareBuffer_Desc desc {};
    AHardwareBuffer_describe(buffer, &desc);

    void* mapped = nullptr;
    const int lock_result =
        AHardwareBuffer_lock(
            buffer,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
            -1,
            nullptr,
            &mapped);
    int unlock_result = -1;
    if (lock_result == 0 && mapped != nullptr) {
        std::memcpy(
            mapped,
            kProbePattern.data(),
            kProbePattern.size());
        unlock_result = AHardwareBuffer_unlock(buffer, nullptr);
    }

    int send_result = -1;
    if (lock_result == 0 && unlock_result == 0) {
        send_result =
            AHardwareBuffer_sendHandleToUnixSocket(
                buffer,
                socket_fd);
    }

    output << (
        send_result == 0
            ? "ok"
            : "failed")
           << ";pid=" << getpid()
           << ";width=" << desc.width
           << ";height=" << desc.height
           << ";layers=" << desc.layers
           << ";format=" << desc.format
           << ";stride=" << desc.stride
           << ";lock=" << lock_result
           << ";unlock=" << unlock_result
           << ";send=" << send_result;

    AHardwareBuffer_release(buffer);
    return JString(env, output.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_NativeRuntimeHost_nativeReceiveHardwareBufferCrossProcessProbe(
        JNIEnv* env,
        jobject /* thiz */,
        jint socket_fd) {
    std::ostringstream output;
    output << "ahb-xproc-recv=";

    if (socket_fd < 0) {
        output << "invalid-fd;pid=" << getpid();
        return JString(env, output.str());
    }

    AHardwareBuffer* buffer = nullptr;
    const int receive_result =
        AHardwareBuffer_recvHandleFromUnixSocket(
            socket_fd,
            &buffer);
    if (receive_result != 0 || buffer == nullptr) {
        output << "failed"
               << ";pid=" << getpid()
               << ";recv=" << receive_result;
        if (buffer != nullptr) {
            AHardwareBuffer_release(buffer);
        }
        return JString(env, output.str());
    }

    AHardwareBuffer_Desc desc {};
    AHardwareBuffer_describe(buffer, &desc);

    void* mapped = nullptr;
    const int lock_result =
        AHardwareBuffer_lock(
            buffer,
            AHARDWAREBUFFER_USAGE_CPU_READ_RARELY,
            -1,
            nullptr,
            &mapped);

    bool pattern_match = false;
    if (lock_result == 0 && mapped != nullptr) {
        pattern_match =
            std::memcmp(
                mapped,
                kProbePattern.data(),
                kProbePattern.size()) == 0;
    }

    int unlock_result = -1;
    if (lock_result == 0 && mapped != nullptr) {
        unlock_result = AHardwareBuffer_unlock(buffer, nullptr);
    }

    const bool descriptor_match =
        desc.width == kWidth &&
        desc.height == kHeight &&
        desc.layers == 1 &&
        desc.format ==
            AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    const bool ok =
        descriptor_match &&
        pattern_match &&
        lock_result == 0 &&
        unlock_result == 0;

    output << (ok ? "ok" : "validation-failed")
           << ";pid=" << getpid()
           << ";width=" << desc.width
           << ";height=" << desc.height
           << ";layers=" << desc.layers
           << ";format=" << desc.format
           << ";stride=" << desc.stride
           << ";recv=" << receive_result
           << ";lock=" << lock_result
           << ";unlock=" << unlock_result
           << ";descriptor_match="
           << (descriptor_match ? "yes" : "no")
           << ";pattern_match="
           << (pattern_match ? "yes" : "no");

    AHardwareBuffer_release(buffer);
    return JString(env, output.str());
}
