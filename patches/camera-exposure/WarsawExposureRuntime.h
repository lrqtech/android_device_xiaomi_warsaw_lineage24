#pragma once

#include <ExposureDamper.h>
#include <ExposureResultSampler.h>
#include <control/CameraMindControlState.h>
#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <string_view>
#include <utils/Thread.h>

namespace warsaw::cameramind {

class WarsawExposureRuntime final {
public:
    explicit WarsawExposureRuntime(const std::string& cameraId);
    ~WarsawExposureRuntime();
    WarsawExposureRuntime(const WarsawExposureRuntime&) = delete;
    WarsawExposureRuntime& operator=(const WarsawExposureRuntime&) = delete;
    void configured();
    void close();
    uint64_t snapshotGeneration() const noexcept;
    void observe(uint64_t ticket, uint32_t frame, const camera_metadata_t* metadata);
    void dump(int fd) const;
    enum class ControlResult { Queued, Invalid, NoSource, StaleSource, Full, Closed };
    bool beginControlSource(uint64_t sourceEpoch);
    void endControlSource(uint64_t sourceEpoch);
    ControlResult submitControl(uint64_t sourceEpoch, std::string_view json);

private:
    static constexpr size_t kQueueCapacity = 128;
    static constexpr size_t kControlReserve = 2;
    static_assert(std::atomic<uint64_t>::is_always_lock_free,
                  "Result-entry generation snapshots must never acquire a lock");
    enum class Kind { Configure, Close, Sample, SourceBegin, SourceEnd, Command };
    struct Event {
        Kind kind = Kind::Close;
        uint64_t generation = 0;
        ExposureResultSampler::Sample sample{};
        bool resetAfterQueueLoss = false;
        uint64_t sourceEpoch = 0;
        CameraMindControlState::Snapshot command{};
    };
    static_assert(std::is_trivially_copyable_v<Event>,
                  "Result callback queue slots must never own allocating command containers");
    struct Summary {
        uint64_t generation = 0;
        uint64_t samples = 0;
        uint64_t reports = 0;
        uint64_t flushedReports = 0;
        uint64_t staleSamples = 0;
        bool open = false;
        bool known = false;
        int64_t exposureNs = 0;
        int64_t wallMillis = 0;
        size_t reportSize = 0;
        uint64_t controlSourceEpoch = 0;
        uint64_t commandsApplied = 0;
        uint64_t staleCommands = 0;
        uint64_t disabledSamples = 0;
        uint64_t sourceBoundaryDiscardedPoints = 0;
        uint64_t reportsTransportQueued = 0;
        uint64_t reportsTransportDisconnected = 0;
        uint64_t reportsTransportFull = 0;
        uint64_t reportsTransportInvalid = 0;
        CameraMindCommand::ReportFlags reportFlags{};
    };
    class Worker final : public android::Thread {
    public:
        explicit Worker(WarsawExposureRuntime* owner) : android::Thread(false), mOwner(owner) {}
    private:
        bool threadLoop() override;
        WarsawExposureRuntime* const mOwner;
    };
    bool step();
    void pushControlLocked(Kind kind, uint64_t sourceEpoch = 0);
    bool ensureWorkerLocked();
    void discardSourceHistory();
    void recordReport(const std::optional<ExposureDamper::Report>& report, bool flushed);

    const std::string mCameraId;
    std::mutex mQueueLock;
    std::condition_variable mReady;
    std::array<Event, kQueueCapacity> mQueue{};
    size_t mHead = 0;
    size_t mCount = 0;
    uint64_t mGeneration = 0;
    std::atomic<uint64_t> mPublishedGeneration{0};
    uint64_t mControlSourceEpoch = 0;
    uint64_t mLastControlSourceEpoch = 0;
    bool mAccept = false;
    bool mQuit = false;
    android::sp<Worker> mWorker;
    ExposureDamper mDamper;
    CameraMindControlState mControl;
    uint64_t mWorkerSourceEpoch = 0;
    uint64_t mWorkerGeneration = 0;
    mutable std::mutex mSummaryLock;
    Summary mSummary;
    std::atomic<uint64_t> mBusyDrops{0};
    std::atomic<uint64_t> mFullDrops{0};
    std::atomic<uint64_t> mControlDrops{0};
    std::atomic<uint64_t> mInvalidResults{0};
    std::atomic<uint64_t> mStartFailures{0};
    std::atomic<uint64_t> mStaleTicketDrops{0};
    std::atomic<uint64_t> mInvalidControls{0};
    std::atomic<uint64_t> mFullControls{0};
    bool mTransportRegistered = false;
};

}
