#pragma once

#include "CameraMindFrameCodec.h"

#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <sys/types.h>

namespace warsaw::cameramind {

struct CameraMindPeerRequirement {
    uid_t uid;
    std::string securityContext;
};

class AuthenticatedUnixChannel final {
public:
    enum class Status { Ok, Idle, Eof, Cancelled, Deadline, Protocol, Ancillary,
                        System, Identity, InvalidArgument };
    struct Result {
        Status status;
        int error = 0;
        size_t bytes = 0;
    };

    static std::unique_ptr<AuthenticatedUnixChannel> connect(
            const CameraMindPeerRequirement& required, Result* result);

    static std::unique_ptr<AuthenticatedUnixChannel> adoptConnected(
            int ownedFd, const CameraMindPeerRequirement& required, Result* result);

    ~AuthenticatedUnixChannel();
    AuthenticatedUnixChannel(const AuthenticatedUnixChannel&) = delete;
    AuthenticatedUnixChannel& operator=(const AuthenticatedUnixChannel&) = delete;

    Result readFrame(CameraMindReceivedFrame* out, std::chrono::milliseconds idleWait);
    Result sendFrame(std::string_view frame);
    void cancel() noexcept;
    bool cancelled() const noexcept { return mCancelled.load(std::memory_order_acquire); }

private:
    using Clock = std::chrono::steady_clock;
    explicit AuthenticatedUnixChannel(int fd) : mFd(fd) {}
    static Result waitReady(int fd, short events, Clock::time_point deadline,
                            const std::atomic<bool>* cancelled);
    Result fatal(Status status, int error, size_t bytes);
    const int mFd;
    std::atomic<bool> mCancelled{false};
    std::mutex mReadLock;
    std::mutex mWriteLock;
};

}
