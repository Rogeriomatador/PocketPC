#include <jni.h>

#include <sys/socket.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSend(
    JNIEnv*, jobject, jlong, jlong, jint, jlong);
extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeOpen(
    JNIEnv*, jobject, jlong, jlong, jlong, jint, jint, jint, jlong, jlong, jint, jlong);
extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeAwaitGuestReady(
    JNIEnv*, jobject, jlong, jlong, jlong, jlong, jlong, jlong);
extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeReadback(
    JNIEnv*, jobject, jlong, jlong, jlong, jlong, jintArray);
extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeSignalHostConsumed(
    JNIEnv*, jobject, jlong, jlong, jlong, jlong, jlong);
extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeClose(
    JNIEnv*, jobject, jlong);

namespace {

constexpr uint32_t kPviMagic = 0x31495650u;
constexpr uint16_t kPviVersion = 1;
constexpr size_t kPviBytes = 64;
constexpr uint32_t kExpectedUsage = 0x17u;
constexpr uint32_t kExpectedFormat = 37u;
constexpr uint32_t kMaxDimension = 4096u;

uint16_t GetU16Le(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) |
        (static_cast<uint16_t>(p[1]) << 8u);
}

uint32_t GetU32Le(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8u) |
        (static_cast<uint32_t>(p[2]) << 16u) |
        (static_cast<uint32_t>(p[3]) << 24u);
}

uint64_t GetU64Le(const uint8_t* p) {
    uint64_t value = 0;
    for (unsigned i = 0; i < 8; ++i) {
        value |= static_cast<uint64_t>(p[i]) << (i * 8u);
    }
    return value;
}

std::string FromJString(JNIEnv* env, jstring value) {
    if (!env || !value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring Fail(JNIEnv* env, const char* reason) {
    std::string value = "vulkan-continuous-present-broker-open=failed;reason=";
    value += reason ? reason : "unknown";
    return env->NewStringUTF(value.c_str());
}

bool ReceivePvi(int socket_fd, std::array<uint8_t, kPviBytes>* payload) {
    if (socket_fd < 0 || !payload) return false;

    iovec iov {};
    iov.iov_base = payload->data();
    iov.iov_len = payload->size();

    std::array<uint8_t, CMSG_SPACE(sizeof(int) * 4)> control {};
    msghdr message {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control.data();
    message.msg_controllen = control.size();

    ssize_t received;
    do {
        received = recvmsg(socket_fd, &message, 0);
    } while (received < 0 && errno == EINTR);

    if (received != static_cast<ssize_t>(payload->size()) ||
        (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0) {
        return false;
    }

    int accepted_fd = -1;
    size_t fd_count = 0;
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(&message); cmsg != nullptr;
         cmsg = CMSG_NXTHDR(&message, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) continue;
        if (cmsg->cmsg_len < CMSG_LEN(sizeof(int))) continue;
        const size_t bytes = cmsg->cmsg_len - CMSG_LEN(0);
        const size_t count = bytes / sizeof(int);
        const int* fds = reinterpret_cast<const int*>(CMSG_DATA(cmsg));
        for (size_t i = 0; i < count; ++i) {
            ++fd_count;
            if (fd_count == 1) accepted_fd = fds[i];
            else if (fds[i] >= 0) close(fds[i]);
        }
    }

    const bool valid = fd_count == 1 && accepted_fd >= 0;
    if (accepted_fd >= 0) close(accepted_fd);
    return valid;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentBrokerPort_nativeOpenFromBroker(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id,
        jlong generation,
        jlong offer_sequence,
        jlong timeout_nanos) {
    if (!env || resource_id <= 0 || generation <= 0 || offer_sequence <= 0 ||
        timeout_nanos <= 0) {
        return Fail(env, "invalid-argument");
    }

    int sockets[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets) != 0) {
        return Fail(env, "socketpair");
    }

    jstring send_result =
        Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSend(
            env,
            nullptr,
            resource_id,
            generation,
            sockets[0],
            offer_sequence);
    const std::string send_status = FromJString(env, send_result);
    if (send_result) env->DeleteLocalRef(send_result);

    std::array<uint8_t, kPviBytes> payload {};
    const bool received = ReceivePvi(sockets[1], &payload);
    close(sockets[0]);
    close(sockets[1]);

    if (send_status.rfind("vulkan-external-image-fd-send=ok;", 0) != 0 || !received) {
        return Fail(env, "pvi1-reexport");
    }

    const uint8_t* p = payload.data();
    const uint64_t rid = static_cast<uint64_t>(resource_id);
    const uint64_t gen = static_cast<uint64_t>(generation);
    const uint64_t offer = static_cast<uint64_t>(offer_sequence);
    const uint32_t width = GetU32Le(p + 32);
    const uint32_t height = GetU32Le(p + 36);
    const uint32_t format = GetU32Le(p + 40);
    const uint32_t usage = GetU32Le(p + 44);
    const uint64_t allocation_size = GetU64Le(p + 48);
    const uint32_t memory_type_bits = GetU32Le(p + 56);
    const uint32_t memory_type_index = GetU32Le(p + 60);

    const bool identity_valid =
        GetU32Le(p + 0) == kPviMagic &&
        GetU16Le(p + 4) == kPviVersion &&
        GetU16Le(p + 6) == 0u &&
        GetU64Le(p + 8) == rid &&
        GetU64Le(p + 16) == gen &&
        GetU64Le(p + 24) == offer &&
        width > 0u && width <= kMaxDimension &&
        height > 0u && height <= kMaxDimension &&
        format == kExpectedFormat &&
        usage == kExpectedUsage &&
        allocation_size > 0u &&
        memory_type_bits > 0u &&
        memory_type_index < 32u &&
        (memory_type_bits & (1u << memory_type_index)) != 0u;
    if (!identity_valid) {
        return Fail(env, "pvi1-identity");
    }

    return Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeOpen(
        env,
        nullptr,
        resource_id,
        generation,
        offer_sequence,
        static_cast<jint>(width),
        static_cast<jint>(height),
        static_cast<jint>(format),
        static_cast<jlong>(allocation_size),
        static_cast<jlong>(memory_type_bits),
        static_cast<jint>(memory_type_index),
        timeout_nanos);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentBrokerPort_nativeAwaitGuestReady(
        JNIEnv* env,
        jobject,
        jlong session_id,
        jlong resource_id,
        jlong generation,
        jlong frame_sequence,
        jlong target_value,
        jlong timeout_nanos) {
    return Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeAwaitGuestReady(
        env, nullptr, session_id, resource_id, generation, frame_sequence, target_value, timeout_nanos);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentBrokerPort_nativeReadback(
        JNIEnv* env,
        jobject,
        jlong session_id,
        jlong resource_id,
        jlong generation,
        jlong frame_sequence,
        jintArray output_argb) {
    return Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeReadback(
        env, nullptr, session_id, resource_id, generation, frame_sequence, output_argb);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentBrokerPort_nativeSignalHostConsumed(
        JNIEnv* env,
        jobject,
        jlong session_id,
        jlong resource_id,
        jlong generation,
        jlong frame_sequence,
        jlong signal_value) {
    return Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeSignalHostConsumed(
        env, nullptr, session_id, resource_id, generation, frame_sequence, signal_value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentBrokerPort_nativeClose(
        JNIEnv* env,
        jobject,
        jlong session_id) {
    return Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeClose(
        env, nullptr, session_id);
}
