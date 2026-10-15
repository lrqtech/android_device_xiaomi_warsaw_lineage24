#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "AuthenticatedUnixChannel.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <climits>
#include <cstddef>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace warsaw::cameramind {
namespace {
constexpr char kSocketName[] = "intentaware_socket";
constexpr auto kOperationLimit = std::chrono::seconds(2);
constexpr size_t kMaxContext = 4096;
constexpr size_t kMaxFrameBytes = (1U << 20) + 12;
constexpr int kScmPidfd = 0x04;
#ifdef SCM_PIDFD
static_assert(SCM_PIDFD == kScmPidfd);
#endif

class OwnedFd final {
public:
    explicit OwnedFd(int fd) : mFd(fd) {}
    ~OwnedFd() { if (mFd >= 0) ::close(mFd); }
    int release() { const int fd = mFd; mFd = -1; return fd; }
private:
    int mFd;
};

bool validRequirement(const CameraMindPeerRequirement& required) {
    return required.uid != static_cast<uid_t>(-1) && !required.securityContext.empty() &&
            required.securityContext.size() < kMaxContext &&
            required.securityContext.find('\0') == std::string::npos;
}

bool discardAncillary(msghdr& message) {
    bool present = (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) != 0;
    for (cmsghdr* c = CMSG_FIRSTHDR(&message); c != nullptr; c = CMSG_NXTHDR(&message, c)) {
        present = true;
        const auto offset = reinterpret_cast<unsigned char*>(c) -
                static_cast<unsigned char*>(message.msg_control);
        if (c->cmsg_len < CMSG_LEN(0) || c->cmsg_len > message.msg_controllen - offset) break;
        if (c->cmsg_level != SOL_SOCKET ||
                (c->cmsg_type != SCM_RIGHTS && c->cmsg_type != kScmPidfd)) continue;
        const size_t count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t index = 0; index < count; ++index) {
            int fd;
            std::memcpy(&fd, CMSG_DATA(c) + index * sizeof(int), sizeof(fd));
            if (fd >= 0) ::close(fd);
        }
    }
    return present;
}

bool validFrameHeader(std::string_view frame) {
    if (frame.size() < 12 || frame.size() > kMaxFrameBytes) return false;
    const auto* b = reinterpret_cast<const uint8_t*>(frame.data());
    if (b[0] != 64 || b[1] != 0 || b[2] != 0 || b[3] != 0) return false;
    const uint32_t length = (uint32_t(b[8]) << 24) | (uint32_t(b[9]) << 16) |
            (uint32_t(b[10]) << 8) | uint32_t(b[11]);
    return length == frame.size() - 12;
}
}

AuthenticatedUnixChannel::Result AuthenticatedUnixChannel::waitReady(
        int fd, short events, Clock::time_point deadline, const std::atomic<bool>* stopped) {
    for (;;) {
        if (stopped != nullptr && stopped->load(std::memory_order_acquire))
            return {Status::Cancelled};
        const auto now = Clock::now();
        if (now >= deadline) return {Status::Deadline};
        const auto remaining = std::chrono::ceil<std::chrono::milliseconds>(deadline - now).count();
        pollfd item{fd, events, 0};
        const int ready = ::poll(&item, 1, static_cast<int>(std::min<int64_t>(remaining, INT_MAX)));
        if (ready < 0) {
            if (errno == EINTR) continue;
            return {Status::System, errno};
        }
        if (stopped != nullptr && stopped->load(std::memory_order_acquire))
            return {Status::Cancelled};
        if (Clock::now() >= deadline) return {Status::Deadline};
        if (ready == 0) continue;
        if ((item.revents & POLLNVAL) != 0) return {Status::System, EBADF};
        if ((item.revents & (events | POLLERR | POLLHUP)) != 0) return {Status::Ok};
    }
}

std::unique_ptr<AuthenticatedUnixChannel> AuthenticatedUnixChannel::connect(
        const CameraMindPeerRequirement& required, Result* result) {
    if (result == nullptr) return nullptr;
    *result = {Status::InvalidArgument};
    if (!validRequirement(required)) return nullptr;
    const auto deadline = Clock::now() + kOperationLimit;
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) { *result = {Status::System, errno}; return nullptr; }
    OwnedFd owned(fd);
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    static_assert(sizeof(kSocketName) <= sizeof(address.sun_path));
    std::memcpy(address.sun_path + 1, kSocketName, sizeof(kSocketName) - 1);
    const auto length = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + sizeof(kSocketName));
    if (::connect(fd, reinterpret_cast<const sockaddr*>(&address), length) < 0) {
        const int error = errno;
        if (error != EINPROGRESS) { *result = {Status::System, error}; return nullptr; }
        *result = waitReady(fd, POLLOUT, deadline, nullptr);
        if (result->status != Status::Ok) return nullptr;
        int pending = 0;
        socklen_t size = sizeof(pending);
        if (::getsockopt(fd, SOL_SOCKET, SO_ERROR, &pending, &size) < 0) {
            *result = {Status::System, errno}; return nullptr;
        }
        if (size != sizeof(pending) || pending != 0) {
            *result = {Status::System, pending != 0 ? pending : EPROTO}; return nullptr;
        }
    }
    if (Clock::now() >= deadline) { *result = {Status::Deadline}; return nullptr; }
    auto channel = adoptConnected(owned.release(), required, result);
    if (channel != nullptr && Clock::now() >= deadline) {
        *result = {Status::Deadline};
        return nullptr;
    }
    return channel;
}

std::unique_ptr<AuthenticatedUnixChannel> AuthenticatedUnixChannel::adoptConnected(
        int ownedFd, const CameraMindPeerRequirement& required, Result* result) {
    OwnedFd owned(ownedFd);
    if (result == nullptr) return nullptr;
    *result = {Status::InvalidArgument};
    if (ownedFd < 0 || !validRequirement(required)) return nullptr;
    int type = 0;
    socklen_t size = sizeof(type);
    sockaddr_un peerAddress{};
    socklen_t addressSize = sizeof(peerAddress);
    if (::getsockopt(ownedFd, SOL_SOCKET, SO_TYPE, &type, &size) < 0 ||
            ::getpeername(ownedFd, reinterpret_cast<sockaddr*>(&peerAddress), &addressSize) < 0) {
        *result = {Status::System, errno}; return nullptr;
    }
    if (size != sizeof(type) || type != SOCK_STREAM || addressSize < sizeof(sa_family_t) ||
            peerAddress.sun_family != AF_UNIX) return nullptr;
    // Credentials and the exact peer label remain mandatory in either
    // global mode. An unavailable SO_PEERSEC still fails closed below.
    ucred credentials{};
    size = sizeof(credentials);
    if (::getsockopt(ownedFd, SOL_SOCKET, SO_PEERCRED, &credentials, &size) < 0) {
        *result = {Status::Identity, errno}; return nullptr;
    }
    if (size != sizeof(credentials) || credentials.pid <= 0 || credentials.uid != required.uid) {
        *result = {Status::Identity}; return nullptr;
    }
    std::array<char, kMaxContext> context{};
    size = context.size();
    if (::getsockopt(ownedFd, SOL_SOCKET, SO_PEERSEC, context.data(), &size) < 0) {
        *result = {Status::Identity, errno}; return nullptr;
    }
    if (size == 0 || size > context.size()) { *result = {Status::Identity}; return nullptr; }
    size_t contextLength = size;
    if (context[contextLength - 1] == '\0') --contextLength;
    if (std::string_view(context.data(), contextLength) != required.securityContext) {
        *result = {Status::Identity}; return nullptr;
    }
    const int flags = ::fcntl(ownedFd, F_GETFL);
    const int fdFlags = ::fcntl(ownedFd, F_GETFD);
    if (flags < 0 || fdFlags < 0 || ::fcntl(ownedFd, F_SETFL, flags | O_NONBLOCK) < 0 ||
            ::fcntl(ownedFd, F_SETFD, fdFlags | FD_CLOEXEC) < 0) {
        *result = {Status::System, errno}; return nullptr;
    }
    auto channel = std::unique_ptr<AuthenticatedUnixChannel>(new AuthenticatedUnixChannel(ownedFd));
    owned.release();
    *result = {Status::Ok};
    return channel;
}

AuthenticatedUnixChannel::~AuthenticatedUnixChannel() {
    cancel();
    ::close(mFd);
}

void AuthenticatedUnixChannel::cancel() noexcept {
    if (!mCancelled.exchange(true, std::memory_order_acq_rel)) ::shutdown(mFd, SHUT_RDWR);
}

AuthenticatedUnixChannel::Result AuthenticatedUnixChannel::fatal(Status status, int error, size_t bytes) {
    cancel();
    return {status, error, bytes};
}

AuthenticatedUnixChannel::Result AuthenticatedUnixChannel::readFrame(
        CameraMindReceivedFrame* out, std::chrono::milliseconds idleWait) {
    if (out == nullptr || idleWait.count() <= 0 || idleWait > std::chrono::seconds(60))
        return {Status::InvalidArgument};
    std::lock_guard<std::mutex> lock(mReadLock);
    CameraMindFrameDecoder decoder;
    auto deadline = Clock::now() + idleWait;
    bool started = false;
    size_t total = 0;
    for (;;) {
        Result ready = waitReady(mFd, POLLIN, deadline, &mCancelled);
        if (ready.status != Status::Ok) {
            if (ready.status == Status::Deadline && !started) return {Status::Idle};
            return fatal(ready.status, ready.error, total);
        }
        std::array<uint8_t, 4096> data;
        const size_t capacity = std::min(data.size(), decoder.bytesNeeded());
        if (capacity == 0) return fatal(Status::Protocol, 0, total);
        iovec io{data.data(), capacity};
        alignas(cmsghdr) std::array<unsigned char, CMSG_SPACE(253 * sizeof(int))> control{};
        msghdr message{};
        message.msg_iov = &io;
        message.msg_iovlen = 1;
        message.msg_control = control.data();
        message.msg_controllen = control.size();
        const auto beforeRead = Clock::now();
        const ssize_t count = ::recvmsg(mFd, &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        const int error = errno;
        if (count < 0) {
            if (error == EINTR || error == EAGAIN || error == EWOULDBLOCK) continue;
            return fatal(Status::System, error, total);
        }
        const bool ancillary = discardAncillary(message);
        if (count > 0) total += static_cast<size_t>(count);
        if (ancillary) return fatal(Status::Ancillary, 0, total);
        if (cancelled()) return {Status::Cancelled, 0, total};
        if (Clock::now() >= deadline) {
            return fatal(Status::Deadline, 0, total);
        }
        if (count == 0) return fatal(decoder.finish() ? Status::Eof : Status::Protocol, 0, total);
        if (!started) { started = true; deadline = beforeRead + kOperationLimit; }
        CameraMindReceivedFrame candidate;
        size_t consumed = 0;
        const auto decoded = decoder.feed(data.data(), static_cast<size_t>(count), &consumed, &candidate);
        if (consumed != static_cast<size_t>(count) || decoded == CameraMindDecodeResult::Error)
            return fatal(Status::Protocol, 0, total);
        if (Clock::now() >= deadline) return fatal(Status::Deadline, 0, total);
        if (cancelled()) return {Status::Cancelled, 0, total};
        if (decoded == CameraMindDecodeResult::FrameReady) {
            *out = std::move(candidate);
            return {Status::Ok, 0, total};
        }
    }
}

AuthenticatedUnixChannel::Result AuthenticatedUnixChannel::sendFrame(std::string_view frame) {
    if (!validFrameHeader(frame)) return {Status::InvalidArgument};
    std::lock_guard<std::mutex> lock(mWriteLock);
    const auto deadline = Clock::now() + kOperationLimit;
    size_t sent = 0;
    while (sent < frame.size()) {
        const Result ready = waitReady(mFd, POLLOUT, deadline, &mCancelled);
        if (ready.status != Status::Ok) return fatal(ready.status, ready.error, sent);
        const ssize_t count = ::send(mFd, frame.data() + sent, frame.size() - sent,
                                     MSG_DONTWAIT | MSG_NOSIGNAL);
        const int error = errno;
        if (count < 0) {
            if (error == EINTR || error == EAGAIN || error == EWOULDBLOCK) continue;
            return fatal(Status::System, error, sent);
        }
        if (count == 0) return fatal(Status::System, EPIPE, sent);
        sent += static_cast<size_t>(count);
        if (Clock::now() >= deadline) return fatal(Status::Deadline, 0, sent);
        if (cancelled()) return {Status::Cancelled, 0, sent};
    }
    return {Status::Ok, 0, sent};
}

}
