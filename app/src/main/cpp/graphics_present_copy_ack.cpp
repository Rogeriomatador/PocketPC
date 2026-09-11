#include <jni.h>

#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>

namespace {

constexpr uint32_t kPgaMagic = 0x31414750u;  // PGA1 little-endian.
constexpr uint16_t kPgaVersion = 1;
constexpr uint16_t kPgaPresentCopyCompletedStage = 6;
constexpr int32_t kPgaStatusOk = 0;
constexpr size_t kPgaPacketBytes = 48;
constexpr int kMinTimeoutMillis = 100;
constexpr int kMaxTimeoutMillis = 120000;

using Clock = std::chrono::steady_clock;

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
    for (unsigned int index = 0; index < 8u; ++index) {
        value |= static_cast<uint64_t>(p[index]) << (index * 8u);
    }
    return value;
}

bool IsSeqpacket(int fd) {
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);
    return fd >= 0 &&
        getsockopt(fd, SOL_SOCKET, SO_TYPE, &socket_type, &length) == 0 &&
        socket_type == SOCK_SEQPACKET;
}

int RemainingMillis(const Clock::time_point& deadline) {
    const auto now = Clock::now();
    if (now >= deadline) return 0;
    const auto remaining =
        std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now);
    const auto value = remaining.count();
    if (value <= 0) return 1;
    if (value > kMaxTimeoutMillis) return kMaxTimeoutMillis;
    return static_cast<int>(value);
}

int WaitReadable(int fd, const Clock::time_point& deadline) {
    for (;;) {
        const int timeout_millis = RemainingMillis(deadline);
        if (timeout_millis <= 0) return 0;

        pollfd descriptor {};
        descriptor.fd = fd;
        descriptor.events = POLLIN;
        const int result = poll(&descriptor, 1, timeout_millis);
        if (result > 0) {
            if ((descriptor.revents & POLLIN) != 0) return 1;
            if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) return -1;
            continue;
        }
        if (result == 0) return 0;
        if (errno == EINTR) continue;
        return -1;
    }
}

void CloseAncillaryFds(msghdr* message) {
    if (!message) return;
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(message); cmsg != nullptr;
         cmsg = CMSG_NXTHDR(message, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) continue;
        if (cmsg->cmsg_len < CMSG_LEN(0)) continue;
        const size_t payload_bytes = cmsg->cmsg_len - CMSG_LEN(0);
        const size_t fd_count = payload_bytes / sizeof(int);
        const int* fds = reinterpret_cast<const int*>(CMSG_DATA(cmsg));
        for (size_t index = 0; index < fd_count; ++index) {
            if (fds[index] >= 0) close(fds[index]);
        }
    }
}

int AwaitPresentCopyCompleted(
        int fd,
        uint64_t resource_id,
        uint64_t generation,
        uint64_t sequence,
        int timeout_millis) {
    if (timeout_millis < kMinTimeoutMillis || timeout_millis > kMaxTimeoutMillis)
        return -60;
    if (!IsSeqpacket(fd) || resource_id == 0 || generation == 0 || sequence == 0)
        return -61;

    const auto deadline = Clock::now() + std::chrono::milliseconds(timeout_millis);
    const int ready = WaitReadable(fd, deadline);
    if (ready == 0) return -62;
    if (ready < 0) return -63;

    std::array<uint8_t, kPgaPacketBytes> packet {};
    std::array<uint8_t, CMSG_SPACE(sizeof(int) * 4)> control {};
    iovec iov {};
    iov.iov_base = packet.data();
    iov.iov_len = packet.size();

    msghdr message {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control.data();
    message.msg_controllen = control.size();

    ssize_t received;
    do {
        received = recvmsg(fd, &message, 0);
    } while (received < 0 && errno == EINTR);

    const bool had_control = CMSG_FIRSTHDR(&message) != nullptr;
    CloseAncillaryFds(&message);

    if (received != static_cast<ssize_t>(packet.size())) return -64;
    if ((message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0) return -65;
    if (had_control) return -66;

    const uint32_t magic = GetU32Le(packet.data() + 0);
    const uint16_t version = GetU16Le(packet.data() + 4);
    const uint16_t stage = GetU16Le(packet.data() + 6);
    const int32_t status = static_cast<int32_t>(GetU32Le(packet.data() + 8));
    const uint32_t reserved0 = GetU32Le(packet.data() + 12);
    const uint64_t ack_resource_id = GetU64Le(packet.data() + 16);
    const uint64_t ack_generation = GetU64Le(packet.data() + 24);
    const uint64_t ack_sequence = GetU64Le(packet.data() + 32);
    const uint32_t queue_family = GetU32Le(packet.data() + 40);
    const uint32_t reserved1 = GetU32Le(packet.data() + 44);

    if (magic != kPgaMagic || version != kPgaVersion) return -67;
    if (stage != kPgaPresentCopyCompletedStage) return -68;
    if (status != kPgaStatusOk) return -69;
    if (reserved0 != 0u || reserved1 != 0u) return -70;
    if (ack_resource_id != resource_id ||
        ack_generation != generation || ack_sequence != sequence) {
        return -71;
    }
    if (queue_family > static_cast<uint32_t>(INT32_MAX)) return -72;

    return static_cast<int>(queue_family);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketpc_core_runtime_GraphicsPresentCopyAckHost_nativeAwaitPresentCopyCompleted(
        JNIEnv*, jobject, jint fd, jlong resource_id, jlong generation,
        jlong sequence, jint timeout_millis) {
    if (resource_id <= 0 || generation <= 0 || sequence <= 0) return -61;
    return AwaitPresentCopyCompleted(
        fd,
        static_cast<uint64_t>(resource_id),
        static_cast<uint64_t>(generation),
        static_cast<uint64_t>(sequence),
        timeout_millis);
}
