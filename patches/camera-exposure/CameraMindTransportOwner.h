#pragma once

#include <ExposureDamper.h>

#include <android-base/thread_annotations.h>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <utils/Thread.h>
#include <vector>

namespace warsaw::cameramind {

class AuthenticatedUnixChannel;
class WarsawExposureRuntime;

class CameraMindTransportOwner final {
public:
    enum class PublishResult { Queued, Disconnected, Full, Invalid };

    struct Snapshot {
        bool workerStarted = false;
        bool connected = false;
        uint64_t sourceEpoch = 0;
        size_t registeredRuntimes = 0;
        size_t queuedReports = 0;
        uint64_t connects = 0;
        uint64_t connectFailures = 0;
        uint64_t identityFailures = 0;
        uint64_t sourceStartFailures = 0;
        uint64_t disconnects = 0;
        uint64_t controlsApplied = 0;
        uint64_t controlsRejected = 0;
        uint64_t unknownMessages = 0;
        uint64_t malformedMessages = 0;
        uint64_t reportsSent = 0;
        uint64_t reportsDroppedAtBoundary = 0;
        uint64_t sendFailures = 0;
        int lastStatus = 0;
        int lastError = 0;
    };

    static CameraMindTransportOwner& instance();

    bool registerRuntime(WarsawExposureRuntime* runtime);
    void unregisterRuntime(WarsawExposureRuntime* runtime);
    PublishResult publishExposure(
            const std::string& cameraId, const ExposureDamper::Report& report);
    Snapshot snapshot() const;

private:
    static constexpr size_t kReportQueueCapacity = 128;
    struct PendingReport {
        uint64_t sourceEpoch = 0;
        std::string cameraId;
        ExposureDamper::Report report;
    };
    class Worker final : public android::Thread {
    public:
        explicit Worker(CameraMindTransportOwner* owner)
              : android::Thread(false), mOwner(owner) {}
    private:
        bool threadLoop() override;
        CameraMindTransportOwner* const mOwner;
    };

    CameraMindTransportOwner() = default;
    ~CameraMindTransportOwner();
    CameraMindTransportOwner(const CameraMindTransportOwner&) = delete;
    CameraMindTransportOwner& operator=(const CameraMindTransportOwner&) = delete;

    bool step();
    bool stopped() const;
    bool waitForWork();
    bool waitBackoff(std::chrono::milliseconds delay);
    bool beginSourceLocked(uint64_t epoch) REQUIRES(mLock);
    void endSourceLocked(uint64_t epoch) REQUIRES(mLock);
    bool takeReport(uint64_t epoch, PendingReport* out);
    void recordChannelResult(int status, int error);

    mutable std::mutex mLock;
    std::condition_variable mWake;
    std::vector<WarsawExposureRuntime*> mRuntimes GUARDED_BY(mLock);
    std::deque<PendingReport> mReports GUARDED_BY(mLock);
    android::sp<Worker> mWorker GUARDED_BY(mLock);
    AuthenticatedUnixChannel* mActiveChannel GUARDED_BY(mLock) = nullptr;
    bool mStop GUARDED_BY(mLock) = false;
    uint64_t mNextSourceEpoch GUARDED_BY(mLock) = 0;
    uint64_t mConnectedEpoch GUARDED_BY(mLock) = 0;
    uint32_t mWireSession GUARDED_BY(mLock) = 1000;
    int32_t mMessageId GUARDED_BY(mLock) = 0;
    int64_t mIdentifySerial GUARDED_BY(mLock) = 0;
    int64_t mExposureSerial GUARDED_BY(mLock) = 0;
    Snapshot mStats GUARDED_BY(mLock);
};

}
