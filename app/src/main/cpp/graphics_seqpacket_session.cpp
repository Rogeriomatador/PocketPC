#include <jni.h>

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

namespace {

constexpr uint32_t kMagic = 0x31484750u;  // PGH1 little-endian.
constexpr uint16_t kVersion = 1;
constexpr size_t kTokenBytes = 32;
constexpr size_t kHandshakeBytes = 48;
constexpr size_t kMaxSessions = 8;
constexpr size_t kMaxSocketNameBytes = 80;
constexpr int kMinTimeoutMillis = 100;
constexpr int kMaxTimeoutMillis = 120000;

constexpr uint32_t kPgtMagic = 0x31544750u;  // PGT1 little-endian.
constexpr uint16_t kPgtVersion = 1;
constexpr uint16_t kPgtResourceOfferType = 1;
constexpr uint32_t kPgtResourceOfferPayloadBytes = 96;
constexpr size_t kPgtResourceOfferFrameBytes = 120;
constexpr uint32_t kPgtOfferedToGuestState = 2;
constexpr uint32_t kPgtMaxDimension = 16384;
constexpr uint32_t kPgtMaxLayers = 256;

constexpr uint32_t kPgaMagic = 0x31414750u;  // PGA1 little-endian.
constexpr uint16_t kPgaVersion = 1;
constexpr size_t kPgaPacketBytes = 48;
constexpr uint16_t kPgaFirstStage = 1;
constexpr uint16_t kPgaLastStage = 4;
constexpr int32_t kPgaStatusOk = 0;
constexpr int kPgaReadyMask = 0x0f;

using Clock = std::chrono::steady_clock;

struct Session {
    int listen_fd = -1;
    std::string socket_name;
    std::array<uint8_t, kTokenBytes> token {};
};

std::mutex g_mutex;
std::unordered_map<uint64_t, Session> g_sessions;
std::atomic<uint64_t> g_next_id {1};

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

void PutU16Le(uint8_t* p, uint16_t value) {
    p[0] = static_cast<uint8_t>(value & 0xffu);
    p[1] = static_cast<uint8_t>((value >> 8u) & 0xffu);
}

void PutU32Le(uint8_t* p, uint32_t value) {
    p[0] = static_cast<uint8_t>(value & 0xffu);
    p[1] = static_cast<uint8_t>((value >> 8u) & 0xffu);
    p[2] = static_cast<uint8_t>((value >> 16u) & 0xffu);
    p[3] = static_cast<uint8_t>((value >> 24u) & 0xffu);
}

void PutU64Le(uint8_t* p, uint64_t value) {
    for (unsigned int index = 0; index < 8u; ++index) {
        p[index] = static_cast<uint8_t>((value >> (index * 8u)) & 0xffu);
    }
}

bool ConstantTimeEqual(const uint8_t* a, const uint8_t* b, size_t size) {
    uint8_t diff = 0;
    for (size_t i = 0; i < size; ++i) diff |= static_cast<uint8_t>(a[i] ^ b[i]);
    return diff == 0;
}

void CloseFd(int* fd) {
    if (fd && *fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

bool IsSeqpacket(int fd) {
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);
    return fd >= 0 &&
        getsockopt(fd, SOL_SOCKET, SO_TYPE, &socket_type, &length) == 0 &&
        socket_type == SOCK_SEQPACKET;
}

bool SendPacketExact(int fd, const uint8_t* payload, size_t payload_size) {
    if (!IsSeqpacket(fd) || !payload || payload_size == 0) return false;
    ssize_t sent;
    do {
        sent = send(fd, payload, payload_size, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    return sent == static_cast<ssize_t>(payload_size);
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

int RemainingMillis(const Clock::time_point& deadline) {
    const auto now = Clock::now();
    if (now >= deadline) return 0;
    const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now);
    const auto value = remaining.count();
    if (value <= 0) return 1;
    if (value > kMaxTimeoutMillis) return kMaxTimeoutMillis;
    return static_cast<int>(value);
}

int WaitReadable(int fd, const Clock::time_point& deadline) {
    if (fd < 0) return -1;
    for (;;) {
        const int timeout_ms = RemainingMillis(deadline);
        if (timeout_ms <= 0) return 0;

        pollfd descriptor {};
        descriptor.fd = fd;
        descriptor.events = POLLIN;
        const int result = poll(&descriptor, 1, timeout_ms);
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

int CreateAbstractSeqpacketServer(const std::string& name) {
    if (name.empty() || name.size() > kMaxSocketNameBytes) return -1;

    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -2;

    sockaddr_un address {};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, name.data(), name.size());
    const socklen_t address_len = static_cast<socklen_t>(
        offsetof(sockaddr_un, sun_path) + 1 + name.size());

    if (bind(fd, reinterpret_cast<const sockaddr*>(&address), address_len) != 0) {
        CloseFd(&fd);
        return -3;
    }
    if (listen(fd, 1) != 0) {
        CloseFd(&fd);
        return -4;
    }
    return fd;
}

int AcceptAuthenticated(const Session& session, int timeout_millis) {
    if (timeout_millis < kMinTimeoutMillis || timeout_millis > kMaxTimeoutMillis) return -10;
    const auto deadline = Clock::now() + std::chrono::milliseconds(timeout_millis);

    const int listen_ready = WaitReadable(session.listen_fd, deadline);
    if (listen_ready == 0) return -11;
    if (listen_ready < 0) return -12;

    int accepted;
    do {
        accepted = accept4(session.listen_fd, nullptr, nullptr, SOCK_CLOEXEC);
    } while (accepted < 0 && errno == EINTR);
    if (accepted < 0) return -1;

    const int peer_ready = WaitReadable(accepted, deadline);
    if (peer_ready == 0) {
        close(accepted);
        return -13;
    }
    if (peer_ready < 0) {
        close(accepted);
        return -14;
    }

    uint8_t payload[kHandshakeBytes] {};
    ssize_t read_bytes;
    do {
        read_bytes = recv(accepted, payload, sizeof(payload), MSG_TRUNC);
    } while (read_bytes < 0 && errno == EINTR);
    if (read_bytes != static_cast<ssize_t>(sizeof(payload))) {
        close(accepted);
        return -2;
    }

    if (GetU32Le(payload + 0) != kMagic ||
        GetU16Le(payload + 4) != kVersion ||
        GetU16Le(payload + 6) != 0 ||
        GetU32Le(payload + 12) != 0) {
        close(accepted);
        return -3;
    }

    const uint32_t claimed_pid = GetU32Le(payload + 8);
    if (claimed_pid == 0 || !ConstantTimeEqual(payload + 16, session.token.data(), kTokenBytes)) {
        close(accepted);
        return -4;
    }

    ucred peer {};
    socklen_t peer_len = sizeof(peer);
    if (getsockopt(accepted, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) != 0 ||
        peer_len != sizeof(peer) || peer.pid <= 0 ||
        static_cast<uint32_t>(peer.pid) != claimed_pid) {
        close(accepted);
        return -5;
    }

    return accepted;
}

bool SendResourceOffer(
        int fd,
        uint64_t resource_id,
        uint64_t generation,
        uint32_t width,
        uint32_t height,
        uint32_t layers,
        uint32_t pixel_format,
        uint64_t usage,
        uint32_t producer_pid,
        uint64_t process_namespace,
        uint64_t sequence,
        uint32_t ownership_state) {
    if (fd < 0 || resource_id == 0 || generation == 0 || sequence == 0 ||
        width == 0 || width > kPgtMaxDimension ||
        height == 0 || height > kPgtMaxDimension ||
        layers == 0 || layers > kPgtMaxLayers || pixel_format == 0 || usage == 0 ||
        producer_pid == 0 || process_namespace == 0 ||
        ownership_state != kPgtOfferedToGuestState) {
        return false;
    }

    std::array<uint8_t, kPgtResourceOfferFrameBytes> packet {};
    PutU32Le(packet.data() + 0, kPgtMagic);
    PutU16Le(packet.data() + 4, kPgtVersion);
    PutU16Le(packet.data() + 6, kPgtResourceOfferType);
    PutU32Le(packet.data() + 8, kPgtResourceOfferPayloadBytes);
    PutU32Le(packet.data() + 12, 0u);
    PutU64Le(packet.data() + 16, sequence);

    uint8_t* descriptor = packet.data() + 24;
    PutU64Le(descriptor + 0, resource_id);
    PutU64Le(descriptor + 8, generation);
    PutU32Le(descriptor + 16, width);
    PutU32Le(descriptor + 20, height);
    PutU32Le(descriptor + 24, layers);
    PutU32Le(descriptor + 28, pixel_format);
    PutU64Le(descriptor + 32, usage);
    PutU32Le(descriptor + 40, producer_pid);
    PutU32Le(descriptor + 44, 0u);
    PutU64Le(descriptor + 48, process_namespace);
    PutU64Le(descriptor + 56, sequence);

    uint8_t* ownership = descriptor + 64;
    PutU64Le(ownership + 0, resource_id);
    PutU64Le(ownership + 8, generation);
    PutU64Le(ownership + 16, sequence);
    PutU32Le(ownership + 24, ownership_state);
    PutU32Le(ownership + 28, 0u);

    return SendPacketExact(fd, packet.data(), packet.size());
}

int ReceiveAck(
        int fd,
        const Clock::time_point& deadline,
        uint16_t expected_stage,
        uint64_t resource_id,
        uint64_t generation,
        uint64_t sequence) {
    if (!IsSeqpacket(fd) || expected_stage < kPgaFirstStage || expected_stage > kPgaLastStage ||
        resource_id == 0 || generation == 0 || sequence == 0) {
        return -20;
    }

    const int ready = WaitReadable(fd, deadline);
    if (ready == 0) return -21;
    if (ready < 0) return -22;

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

    if (received != static_cast<ssize_t>(packet.size())) return -23;
    if ((message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0) return -24;
    if (had_control) return -25;

    const uint32_t magic = GetU32Le(packet.data() + 0);
    const uint16_t version = GetU16Le(packet.data() + 4);
    const uint16_t stage = GetU16Le(packet.data() + 6);
    const int32_t status = static_cast<int32_t>(GetU32Le(packet.data() + 8));
    const uint32_t reserved0 = GetU32Le(packet.data() + 12);
    const uint64_t ack_resource_id = GetU64Le(packet.data() + 16);
    const uint64_t ack_generation = GetU64Le(packet.data() + 24);
    const uint64_t ack_sequence = GetU64Le(packet.data() + 32);
    const uint32_t reserved1 = GetU32Le(packet.data() + 44);

    if (magic != kPgaMagic || version != kPgaVersion) return -26;
    if (stage != expected_stage) return -27;
    if (status != kPgaStatusOk) return -28;
    if (reserved0 != 0u || reserved1 != 0u) return -29;
    if (ack_resource_id != resource_id || ack_generation != generation || ack_sequence != sequence)
        return -30;

    return 1 << (stage - 1u);
}

int AwaitImportAcks(
        int fd,
        uint64_t resource_id,
        uint64_t generation,
        uint64_t sequence,
        int timeout_millis) {
    if (timeout_millis < kMinTimeoutMillis || timeout_millis > kMaxTimeoutMillis) return -31;
    if (!IsSeqpacket(fd) || resource_id == 0 || generation == 0 || sequence == 0) return -32;

    const auto deadline = Clock::now() + std::chrono::milliseconds(timeout_millis);
    int mask = 0;
    for (uint16_t stage = kPgaFirstStage; stage <= kPgaLastStage; ++stage) {
        const int result = ReceiveAck(fd, deadline, stage, resource_id, generation, sequence);
        if (result < 0) return result;
        mask |= result;
    }
    return mask == kPgaReadyMask ? mask : -33;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_pocketpc_core_runtime_GraphicsSeqpacketSessionHost_nativeCreateServer(
        JNIEnv* env,
        jobject,
        jstring socket_name,
        jbyteArray token) {
    if (!socket_name || !token || env->GetArrayLength(token) != static_cast<jsize>(kTokenBytes)) return 0;

    const char* chars = env->GetStringUTFChars(socket_name, nullptr);
    if (!chars) return 0;
    std::string name(chars);
    env->ReleaseStringUTFChars(socket_name, chars);
    if (name.empty() || name.size() > kMaxSocketNameBytes || name.find('\0') != std::string::npos) return 0;

    Session session;
    env->GetByteArrayRegion(token, 0, kTokenBytes, reinterpret_cast<jbyte*>(session.token.data()));
    if (env->ExceptionCheck()) return 0;
    session.socket_name = name;
    session.listen_fd = CreateAbstractSeqpacketServer(name);
    if (session.listen_fd < 0) return 0;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sessions.size() >= kMaxSessions) {
        CloseFd(&session.listen_fd);
        return 0;
    }
    uint64_t id = g_next_id.fetch_add(1);
    if (id == 0) id = g_next_id.fetch_add(1);
    if (id == 0 || g_sessions.find(id) != g_sessions.end()) {
        CloseFd(&session.listen_fd);
        return 0;
    }
    g_sessions.emplace(id, std::move(session));
    return static_cast<jlong>(id);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketpc_core_runtime_GraphicsSeqpacketSessionHost_nativeAcceptAuthenticated(
        JNIEnv*, jobject, jlong session_id, jint timeout_millis) {
    if (session_id <= 0) return -1;
    if (timeout_millis < kMinTimeoutMillis || timeout_millis > kMaxTimeoutMillis) return -10;

    Session snapshot;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_sessions.find(static_cast<uint64_t>(session_id));
        if (found == g_sessions.end()) return -1;
        snapshot.socket_name = found->second.socket_name;
        snapshot.token = found->second.token;
        snapshot.listen_fd = dup(found->second.listen_fd);
    }
    if (snapshot.listen_fd < 0) return -1;

    const int accepted = AcceptAuthenticated(snapshot, timeout_millis);
    CloseFd(&snapshot.listen_fd);
    return accepted;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_pocketpc_core_runtime_GraphicsSeqpacketSessionHost_nativeSendResourceOffer(
        JNIEnv*, jobject, jint fd, jlong resource_id, jlong generation,
        jint width, jint height, jint layers, jint pixel_format, jlong usage,
        jint producer_pid, jlong process_namespace, jlong sequence,
        jint ownership_state) {
    if (resource_id <= 0 || generation <= 0 || width <= 0 || height <= 0 ||
        layers <= 0 || pixel_format <= 0 || usage <= 0 || producer_pid <= 0 ||
        process_namespace <= 0 || sequence <= 0) {
        return JNI_FALSE;
    }

    return SendResourceOffer(
        fd,
        static_cast<uint64_t>(resource_id),
        static_cast<uint64_t>(generation),
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        static_cast<uint32_t>(layers),
        static_cast<uint32_t>(pixel_format),
        static_cast<uint64_t>(usage),
        static_cast<uint32_t>(producer_pid),
        static_cast<uint64_t>(process_namespace),
        static_cast<uint64_t>(sequence),
        static_cast<uint32_t>(ownership_state)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_pocketpc_core_runtime_GraphicsSeqpacketSessionHost_nativeAwaitImportAcks(
        JNIEnv*, jobject, jint fd, jlong resource_id, jlong generation,
        jlong sequence, jint timeout_millis) {
    if (resource_id <= 0 || generation <= 0 || sequence <= 0) return -32;
    return AwaitImportAcks(
        fd,
        static_cast<uint64_t>(resource_id),
        static_cast<uint64_t>(generation),
        static_cast<uint64_t>(sequence),
        timeout_millis);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pocketpc_core_runtime_GraphicsSeqpacketSessionHost_nativeCloseAcceptedFd(
        JNIEnv*, jobject, jint fd) {
    if (fd >= 0) close(fd);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_pocketpc_core_runtime_GraphicsSeqpacketSessionHost_nativeCloseServer(
        JNIEnv*, jobject, jlong session_id) {
    if (session_id <= 0) return;
    Session session;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_sessions.find(static_cast<uint64_t>(session_id));
        if (found == g_sessions.end()) return;
        session = std::move(found->second);
        g_sessions.erase(found);
    }
    CloseFd(&session.listen_fd);
}
