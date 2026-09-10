#include <jni.h>

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {

constexpr uint32_t kMagic = 0x31484750u;  // PGH1 little-endian.
constexpr uint16_t kVersion = 1;
constexpr size_t kTokenBytes = 32;
constexpr size_t kHandshakeBytes = 48;
constexpr size_t kMaxSessions = 8;
constexpr size_t kMaxSocketNameBytes = 80;

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

int AcceptAuthenticated(const Session& session) {
    int accepted;
    do {
        accepted = accept4(session.listen_fd, nullptr, nullptr, SOCK_CLOEXEC);
    } while (accepted < 0 && errno == EINTR);
    if (accepted < 0) return -1;

    uint8_t payload[kHandshakeBytes] {};
    ssize_t read_bytes;
    do {
        read_bytes = recv(accepted, payload, sizeof(payload), 0);
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
        JNIEnv*, jobject, jlong session_id) {
    if (session_id <= 0) return -1;
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
    const int accepted = AcceptAuthenticated(snapshot);
    CloseFd(&snapshot.listen_fd);
    return accepted;
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
