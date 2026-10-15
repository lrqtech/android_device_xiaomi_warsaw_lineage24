#include "WarsawExposureRuntime.h"
#include "CameraMindTransportOwner.h"

#include <inttypes.h>
#include <limits>
#include <stdio.h>
#include <utility>
#include <utils/Errors.h>

namespace warsaw::cameramind {

WarsawExposureRuntime::WarsawExposureRuntime(const std::string& cameraId) : mCameraId(cameraId) {}

WarsawExposureRuntime::~WarsawExposureRuntime() {
    if (mTransportRegistered) CameraMindTransportOwner::instance().unregisterRuntime(this);
    {
        std::lock_guard<std::mutex> lock(mQueueLock);
        mAccept = false;
        mQuit = true;
        mPublishedGeneration.store(0, std::memory_order_release);
        mControlSourceEpoch = 0;
    }
    mReady.notify_one();
    if (mWorker != nullptr) mWorker->join();
}

void WarsawExposureRuntime::pushControlLocked(Kind kind, uint64_t sourceEpoch) {
    bool lost = false;
    if (mCount == kQueueCapacity) {
        mControlDrops.fetch_add(mCount, std::memory_order_relaxed);
        mHead = 0;
        mCount = 0;
        mControlSourceEpoch = 0;
        lost = true;
    }
    const uint64_t cameraGeneration = kind == Kind::Configure ? mGeneration :
            (mAccept ? mGeneration : 0);
    mQueue[(mHead + mCount++) % kQueueCapacity] = Event{kind, cameraGeneration, {}, lost, sourceEpoch, {}};
}

bool WarsawExposureRuntime::ensureWorkerLocked() {
    if (mWorker != nullptr) return true;
    auto worker = android::sp<Worker>::make(this);
    if (worker->run("WarsawExposure") != android::OK) {
        mStartFailures.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    mWorker = std::move(worker);
    return true;
}

void WarsawExposureRuntime::configured() {
    if (!mTransportRegistered) {
        mTransportRegistered = CameraMindTransportOwner::instance().registerRuntime(this);
    }
    {
        std::lock_guard<std::mutex> lock(mQueueLock);
        if (mQuit || mGeneration == std::numeric_limits<uint64_t>::max()) {
            mAccept = false;
            mPublishedGeneration.store(0, std::memory_order_release);
            return;
        }
        if (!ensureWorkerLocked()) {
            mAccept = false;
            mPublishedGeneration.store(0, std::memory_order_release);
            return;
        }
        ++mGeneration;
        pushControlLocked(Kind::Configure);
        mAccept = true;
        mPublishedGeneration.store(mGeneration, std::memory_order_release);
    }
    mReady.notify_one();
}

bool WarsawExposureRuntime::beginControlSource(uint64_t sourceEpoch) {
    {
        std::lock_guard<std::mutex> lock(mQueueLock);
        if (mQuit || sourceEpoch == 0 || sourceEpoch <= mLastControlSourceEpoch ||
                !ensureWorkerLocked()) return false;
        pushControlLocked(Kind::SourceBegin, sourceEpoch);
        mLastControlSourceEpoch = sourceEpoch;
        mControlSourceEpoch = sourceEpoch;
    }
    mReady.notify_one();
    return true;
}

void WarsawExposureRuntime::endControlSource(uint64_t sourceEpoch) {
    {
        std::lock_guard<std::mutex> lock(mQueueLock);
        if (mQuit || sourceEpoch == 0 || sourceEpoch != mControlSourceEpoch) return;
        mControlSourceEpoch = 0;
        pushControlLocked(Kind::SourceEnd, sourceEpoch);
    }
    mReady.notify_one();
}

WarsawExposureRuntime::ControlResult WarsawExposureRuntime::submitControl(
        uint64_t sourceEpoch, std::string_view json) {
    CameraMindCommand command;
    CameraMindControlState::Snapshot snapshot;
    if (!parseCameraMindCommand(json, &command) ||
            !CameraMindControlState::copyCommand(command, &snapshot)) {
        mInvalidControls.fetch_add(1, std::memory_order_relaxed);
        return ControlResult::Invalid;
    }
    {
        std::lock_guard<std::mutex> lock(mQueueLock);
        if (mQuit) return ControlResult::Closed;
        if (mControlSourceEpoch == 0) return ControlResult::NoSource;
        if (sourceEpoch == 0 || sourceEpoch != mControlSourceEpoch) return ControlResult::StaleSource;
        if (mCount >= kQueueCapacity - kControlReserve) {
            mFullControls.fetch_add(1, std::memory_order_relaxed);
            return ControlResult::Full;
        }
        Event event{Kind::Command, 0, {}, false, sourceEpoch, snapshot};
        mQueue[(mHead + mCount++) % kQueueCapacity] = event;
    }
    mReady.notify_one();
    return ControlResult::Queued;
}

void WarsawExposureRuntime::close() {
    {
        std::lock_guard<std::mutex> lock(mQueueLock);
        mPublishedGeneration.store(0, std::memory_order_release);
        if (!mAccept) return;
        mAccept = false;
        pushControlLocked(Kind::Close);
    }
    mReady.notify_one();
}

uint64_t WarsawExposureRuntime::snapshotGeneration() const noexcept {
    return mPublishedGeneration.load(std::memory_order_acquire);
}

void WarsawExposureRuntime::observe(
        uint64_t ticket, uint32_t frame, const camera_metadata_t* metadata) {
    std::unique_lock<std::mutex> lock(mQueueLock, std::try_to_lock);
    if (!lock.owns_lock()) {
        mBusyDrops.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (!mAccept || mQuit || ticket == 0 || ticket != mGeneration) {
        mStaleTicketDrops.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    Event event{Kind::Sample, mGeneration, {}, false, 0, {}};
    const auto result = ExposureResultSampler::copyMetadata(mGeneration, frame, metadata, &event.sample);
    if (result == ExposureResultSampler::Result::MissingExposure) return;
    if (result != ExposureResultSampler::Result::Queued) {
        mInvalidResults.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (mCount >= kQueueCapacity - kControlReserve) {
        mFullDrops.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    mQueue[(mHead + mCount++) % kQueueCapacity] = event;
    lock.unlock();
    mReady.notify_one();
}

bool WarsawExposureRuntime::Worker::threadLoop() { return mOwner->step(); }

void WarsawExposureRuntime::discardSourceHistory() {
    const auto pending = mDamper.flush();
    mDamper.reset();
    if (pending) {
        std::lock_guard<std::mutex> lock(mSummaryLock);
        mSummary.sourceBoundaryDiscardedPoints += pending->count;
    }
}

void WarsawExposureRuntime::recordReport(
        const std::optional<ExposureDamper::Report>& report, bool flushed) {
    if (!report) return;
    const auto delivery = CameraMindTransportOwner::instance().publishExposure(mCameraId, *report);
    std::lock_guard<std::mutex> lock(mSummaryLock);
    ++mSummary.reports;
    if (flushed) ++mSummary.flushedReports;
    mSummary.reportSize = report->count;
    switch (delivery) {
        case CameraMindTransportOwner::PublishResult::Queued:
            ++mSummary.reportsTransportQueued;
            break;
        case CameraMindTransportOwner::PublishResult::Disconnected:
            ++mSummary.reportsTransportDisconnected;
            break;
        case CameraMindTransportOwner::PublishResult::Full:
            ++mSummary.reportsTransportFull;
            break;
        case CameraMindTransportOwner::PublishResult::Invalid:
            ++mSummary.reportsTransportInvalid;
            break;
    }
}

bool WarsawExposureRuntime::step() {
    Event event;
    {
        std::unique_lock<std::mutex> lock(mQueueLock);
        mReady.wait(lock, [this] { return mQuit || mCount != 0; });
        if (mCount == 0) {
            lock.unlock();
            recordReport(mDamper.flush(), true);
            return false;
        }
        event = mQueue[mHead];
        mQueue[mHead] = Event{};
        mHead = (mHead + 1) % kQueueCapacity;
        --mCount;
    }
    if (event.resetAfterQueueLoss) {
        discardSourceHistory();
        mControl.resetFlags();
        mWorkerSourceEpoch = 0;
        mWorkerGeneration = event.generation;
        std::lock_guard<std::mutex> lock(mSummaryLock);
        mSummary.controlSourceEpoch = 0;
        mSummary.reportFlags = {};
        mSummary.generation = mWorkerGeneration;
        mSummary.open = mWorkerGeneration != 0;
        mSummary.known = false;
        mSummary.exposureNs = 0;
        mSummary.wallMillis = 0;
    }
    if (event.kind == Kind::SourceBegin || event.kind == Kind::SourceEnd) {
        if (event.kind == Kind::SourceEnd && event.sourceEpoch != mWorkerSourceEpoch) return true;
        discardSourceHistory();
        mControl.resetFlags();
        mWorkerSourceEpoch = event.kind == Kind::SourceBegin ? event.sourceEpoch : 0;
        std::lock_guard<std::mutex> lock(mSummaryLock);
        mSummary.controlSourceEpoch = mWorkerSourceEpoch;
        mSummary.reportFlags = mControl.flags();
        return true;
    }
    if (event.kind == Kind::Command) {
        if (event.sourceEpoch == 0 || event.sourceEpoch != mWorkerSourceEpoch) {
            std::lock_guard<std::mutex> lock(mSummaryLock);
            ++mSummary.staleCommands;
            return true;
        }
        if (!mControl.apply(event.command, mDamper)) {
            mInvalidControls.fetch_add(1, std::memory_order_relaxed);
            return true;
        }
        std::lock_guard<std::mutex> lock(mSummaryLock);
        ++mSummary.commandsApplied;
        mSummary.reportFlags = mControl.flags();
        return true;
    }
    if (event.kind == Kind::Configure || event.kind == Kind::Close) {
        recordReport(mDamper.flush(), true);
        mDamper.reset();
        mWorkerGeneration = event.kind == Kind::Configure ? event.generation : 0;
        std::lock_guard<std::mutex> lock(mSummaryLock);
        mSummary.generation = mWorkerGeneration;
        mSummary.open = mWorkerGeneration != 0;
        mSummary.known = false;
        mSummary.exposureNs = 0;
        mSummary.wallMillis = 0;
        return true;
    }
    if (event.generation == 0 || event.generation != mWorkerGeneration) {
        std::lock_guard<std::mutex> lock(mSummaryLock);
        ++mSummary.staleSamples;
        return true;
    }
    {
        std::lock_guard<std::mutex> lock(mSummaryLock);
        ++mSummary.samples;
        mSummary.known = true;
        mSummary.exposureNs = event.sample.exposureNs;
        mSummary.wallMillis = event.sample.observedWallMillis;
        if (!mControl.exposureEnabled()) {
            ++mSummary.disabledSamples;
            return true;
        }
    }
    recordReport(mDamper.add(event.sample.exposureNs, event.sample.observedWallMillis), false);
    return true;
}

void WarsawExposureRuntime::dump(int fd) const {
    Summary summary;
    {
        std::lock_guard<std::mutex> lock(mSummaryLock);
        summary = mSummary;
    }
    const auto transport = CameraMindTransportOwner::instance().snapshot();
    dprintf(fd, "    Warsaw exposure producer: camera=%s generation=%" PRIu64
            " open=%d known=%d transport=%s epoch=%" PRIu64 "\n", mCameraId.c_str(),
            summary.generation, summary.open, summary.known,
            transport.connected ? "CONNECTED" : "DISCONNECTED", transport.sourceEpoch);
    dprintf(fd, "      samples=%" PRIu64 " reports=%" PRIu64 " flushed=%" PRIu64
            " stale=%" PRIu64 " last_report_size=%zu\n", summary.samples, summary.reports,
            summary.flushedReports, summary.staleSamples, summary.reportSize);
    dprintf(fd, "      transport_queued=%" PRIu64 " disconnected=%" PRIu64
            " full=%" PRIu64 " invalid=%" PRIu64 " process_sent=%" PRIu64
            " process_boundary_drops=%" PRIu64 " queue=%zu\n",
            summary.reportsTransportQueued, summary.reportsTransportDisconnected,
            summary.reportsTransportFull, summary.reportsTransportInvalid,
            transport.reportsSent, transport.reportsDroppedAtBoundary,
            transport.queuedReports);
    dprintf(fd, "      transport_connects=%" PRIu64 " failures=%" PRIu64
            " identity_failures=%" PRIu64 " source_start_failures=%" PRIu64
            " disconnects=%" PRIu64
            " controls_applied=%" PRIu64 " controls_rejected=%" PRIu64
            " malformed=%" PRIu64 " unknown=%" PRIu64 " last_status=%d last_errno=%d\n",
            transport.connects, transport.connectFailures, transport.identityFailures,
            transport.sourceStartFailures, transport.disconnects,
            transport.controlsApplied, transport.controlsRejected,
            transport.malformedMessages, transport.unknownMessages,
            transport.lastStatus, transport.lastError);
    dprintf(fd, "      busy_drops=%" PRIu64 " full_drops=%" PRIu64 " control_queue_losses=%" PRIu64
            " invalid=%" PRIu64 " worker_start_failures=%" PRIu64
            " stale_ticket_drops=%" PRIu64 "\n",
            mBusyDrops.load(), mFullDrops.load(), mControlDrops.load(),
            mInvalidResults.load(), mStartFailures.load(), mStaleTicketDrops.load());
    dprintf(fd, "      control_source_epoch=%" PRIu64 " commands_applied=%" PRIu64
            " stale_commands=%" PRIu64 " invalid_controls=%" PRIu64 " full_controls=%" PRIu64
            " disabled_samples=%" PRIu64 " source_boundary_discarded_points=%" PRIu64 "\n",
            summary.controlSourceEpoch, summary.commandsApplied, summary.staleCommands,
            mInvalidControls.load(), mFullControls.load(), summary.disabledSamples,
            summary.sourceBoundaryDiscardedPoints);
    const auto& flags = summary.reportFlags;
    dprintf(fd, "      flags preview_fps=%d exposure=%d frame_duration=%d flicker=%d motion=%d trace=%d\n",
            flags.reportPreviewAvgFps, flags.reportExposureTime, flags.reportFrameDuration,
            flags.reportFlickerScene, flags.reportMotionVelocity, flags.traceOn);
    if (summary.known) {
        dprintf(fd, "      observed_exposure_ns=%" PRId64 " observed_wall_ms=%" PRId64 "\n",
                summary.exposureNs, summary.wallMillis);
    }
}

}
