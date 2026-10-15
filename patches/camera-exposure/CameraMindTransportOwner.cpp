#include "CameraMindTransportOwner.h"

#include "WarsawExposureRuntime.h"

#include <AuthenticatedUnixChannel.h>
#include <ExposureWire.h>
#include <binder/PermissionController.h>
#include <utils/String16.h>

#include <algorithm>
#include <chrono>
#include <limits>
#include <memory>
#include <utility>

namespace warsaw::cameramind {
namespace {

using Channel = AuthenticatedUnixChannel;
using namespace std::chrono_literals;

constexpr char kCameraMindPackage[] = "com.xiaomi.cameramind";
constexpr char kCameraMindContext[] = "u:r:cameramind_app:s0";
constexpr char kCameraServerProcess[] = "cameraserver";
constexpr uint32_t kCounterLimit = static_cast<uint32_t>(INT32_MAX - 10);
constexpr int64_t kSerialLimit = INT64_MAX - 10;

int64_t wallMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
}

bool completeServerEnvelope(const CameraMindReceivedFrame& frame) {
    return frame.wireSessionId >= 1000 &&
            frame.envelope.id && *frame.envelope.id >= 0 &&
            frame.envelope.serial && *frame.envelope.serial >= 0 &&
            frame.envelope.timeMillis && *frame.envelope.timeMillis >= 0 &&
            frame.envelope.reply.has_value();
}

}

CameraMindTransportOwner& CameraMindTransportOwner::instance() {
    static CameraMindTransportOwner* const owner = new CameraMindTransportOwner();
    return *owner;
}

CameraMindTransportOwner::~CameraMindTransportOwner() {
    android::sp<Worker> worker;
    {
        std::lock_guard<std::mutex> lock(mLock);
        mStop = true;
        if (mActiveChannel != nullptr) mActiveChannel->cancel();
        worker = mWorker;
    }
    mWake.notify_all();
    if (worker != nullptr) worker->join();
}

bool CameraMindTransportOwner::registerRuntime(WarsawExposureRuntime* runtime) {
    if (runtime == nullptr) return false;
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mStop) return false;
        const bool known = std::find(mRuntimes.begin(), mRuntimes.end(), runtime) != mRuntimes.end();
        if (!known) {
            mRuntimes.push_back(runtime);
            if (mConnectedEpoch != 0 && !runtime->beginControlSource(mConnectedEpoch)) {
                mRuntimes.pop_back();
                ++mStats.sourceStartFailures;
                mStats.registeredRuntimes = mRuntimes.size();
                return false;
            }
        }
        if (mWorker == nullptr) {
            auto worker = android::sp<Worker>::make(this);
            if (worker->run("WarsawMindIO") == android::OK) {
                mWorker = std::move(worker);
                mStats.workerStarted = true;
            } else {
                ++mStats.connectFailures;
                if (!known) mRuntimes.pop_back();
                mStats.registeredRuntimes = mRuntimes.size();
                return false;
            }
        }
        mStats.registeredRuntimes = mRuntimes.size();
    }
    mWake.notify_all();
    return true;
}

void CameraMindTransportOwner::unregisterRuntime(WarsawExposureRuntime* runtime) {
    if (runtime == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(mLock);
        auto item = std::find(mRuntimes.begin(), mRuntimes.end(), runtime);
        if (item == mRuntimes.end()) return;
        if (mConnectedEpoch != 0) runtime->endControlSource(mConnectedEpoch);
        mRuntimes.erase(item);
        mStats.registeredRuntimes = mRuntimes.size();
        if (mRuntimes.empty() && mActiveChannel != nullptr) mActiveChannel->cancel();
    }
    mWake.notify_all();
}

CameraMindTransportOwner::PublishResult CameraMindTransportOwner::publishExposure(
        const std::string& cameraId, const ExposureDamper::Report& report) {
    if (cameraId.empty() || cameraId.size() > 128 || report.count == 0 ||
            report.count > ExposureDamper::kMaxPending) return PublishResult::Invalid;
    for (size_t i = 0; i < report.count; ++i) {
        if (report.points[i].exposureNs <= 0) return PublishResult::Invalid;
    }
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mConnectedEpoch == 0 || mActiveChannel == nullptr) return PublishResult::Disconnected;
        if (mReports.size() == kReportQueueCapacity) return PublishResult::Full;
        mReports.push_back(PendingReport{mConnectedEpoch, cameraId, report});
        mStats.queuedReports = mReports.size();
    }
    mWake.notify_all();
    return PublishResult::Queued;
}

CameraMindTransportOwner::Snapshot CameraMindTransportOwner::snapshot() const {
    std::lock_guard<std::mutex> lock(mLock);
    Snapshot result = mStats;
    result.connected = mConnectedEpoch != 0 && mActiveChannel != nullptr;
    result.sourceEpoch = mConnectedEpoch;
    result.registeredRuntimes = mRuntimes.size();
    result.queuedReports = mReports.size();
    return result;
}

bool CameraMindTransportOwner::Worker::threadLoop() { return mOwner->step(); }

bool CameraMindTransportOwner::stopped() const {
    std::lock_guard<std::mutex> lock(mLock);
    return mStop;
}

bool CameraMindTransportOwner::waitForWork() {
    std::unique_lock<std::mutex> lock(mLock);
    mWake.wait(lock, [this] { return mStop || !mRuntimes.empty(); });
    return !mStop;
}

bool CameraMindTransportOwner::waitBackoff(std::chrono::milliseconds delay) {
    std::unique_lock<std::mutex> lock(mLock);
    mWake.wait_for(lock, delay, [this] { return mStop || mRuntimes.empty(); });
    return !mStop;
}

bool CameraMindTransportOwner::beginSourceLocked(uint64_t epoch) {
    mConnectedEpoch = epoch;
    size_t started = 0;
    for (; started < mRuntimes.size(); ++started) {
        if (!mRuntimes[started]->beginControlSource(epoch)) {
            while (started != 0) mRuntimes[--started]->endControlSource(epoch);
            mConnectedEpoch = 0;
            return false;
        }
    }
    return true;
}

void CameraMindTransportOwner::endSourceLocked(uint64_t epoch) {
    if (epoch == 0 || epoch != mConnectedEpoch) return;
    for (WarsawExposureRuntime* runtime : mRuntimes) runtime->endControlSource(epoch);
    mStats.reportsDroppedAtBoundary += mReports.size();
    mReports.clear();
    mStats.queuedReports = 0;
    mConnectedEpoch = 0;
}

bool CameraMindTransportOwner::takeReport(uint64_t epoch, PendingReport* out) {
    std::lock_guard<std::mutex> lock(mLock);
    while (!mReports.empty() && mReports.front().sourceEpoch != epoch) {
        mReports.pop_front();
        ++mStats.reportsDroppedAtBoundary;
    }
    mStats.queuedReports = mReports.size();
    if (mReports.empty()) return false;
    *out = std::move(mReports.front());
    mReports.pop_front();
    mStats.queuedReports = mReports.size();
    return true;
}

void CameraMindTransportOwner::recordChannelResult(int status, int error) {
    std::lock_guard<std::mutex> lock(mLock);
    mStats.lastStatus = status;
    mStats.lastError = error;
}

bool CameraMindTransportOwner::step() {
    if (!waitForWork()) return false;

    Channel::Result channelResult{Channel::Status::InvalidArgument};
    // Resolve the installed system package on every reconnect; app IDs are
    // allocated by PackageManager and are not the OEM private AID 6120.
    // The exact SELinux domain additionally requires the platform signer.
    android::PermissionController permissions;
    const int uid = permissions.getPackageUid(android::String16(kCameraMindPackage),
            android::PermissionController::MATCH_SYSTEM_ONLY);
    if (uid <= 0) {
        recordChannelResult(static_cast<int>(Channel::Status::Identity), 0);
        {
            std::lock_guard<std::mutex> lock(mLock);
            ++mStats.identityFailures;
        }
        return waitBackoff(500ms);
    }
    const CameraMindPeerRequirement required{static_cast<uid_t>(uid), kCameraMindContext};
    std::unique_ptr<Channel> channel = Channel::connect(required, &channelResult);
    recordChannelResult(static_cast<int>(channelResult.status), channelResult.error);
    if (channel == nullptr) {
        {
            std::lock_guard<std::mutex> lock(mLock);
            ++mStats.connectFailures;
            if (channelResult.status == Channel::Status::Identity) ++mStats.identityFailures;
        }
        return waitBackoff(500ms);
    }

    uint32_t handshakeSession;
    int32_t handshakeId;
    int64_t handshakeSerial;
    {
        std::lock_guard<std::mutex> lock(mLock);
        mWireSession = mWireSession >= kCounterLimit ? 1000 : mWireSession + 1;
        handshakeSession = mWireSession;
        handshakeId = mMessageId;
        mMessageId = static_cast<uint32_t>(mMessageId) >= kCounterLimit ? 0 : mMessageId + 1;
        handshakeSerial = mIdentifySerial;
        mIdentifySerial = mIdentifySerial > kSerialLimit ? 0 : mIdentifySerial + 1;
    }
    auto identity = encodeIdentityFrame(static_cast<int32_t>(handshakeSession), handshakeId,
                                        handshakeSerial, wallMillis(), kCameraServerProcess);
    if (!identity) {
        std::lock_guard<std::mutex> lock(mLock);
        ++mStats.identityFailures;
        return !mStop;
    }
    channelResult = channel->sendFrame(std::string_view(
            reinterpret_cast<const char*>(identity->data()), identity->size()));
    recordChannelResult(static_cast<int>(channelResult.status), channelResult.error);
    if (channelResult.status != Channel::Status::Ok) {
        std::lock_guard<std::mutex> lock(mLock);
        ++mStats.sendFailures;
        return !mStop;
    }

    uint64_t epoch;
    bool sourceReady;
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mStop || mRuntimes.empty() || mNextSourceEpoch == UINT64_MAX) {
            channel->cancel();
            return !mStop;
        }
        epoch = ++mNextSourceEpoch;
        mActiveChannel = channel.get();
        sourceReady = beginSourceLocked(epoch);
        if (!sourceReady) {
            mActiveChannel = nullptr;
            ++mStats.sourceStartFailures;
        } else {
            ++mStats.connects;
        }
    }

    if (!sourceReady) {
        channel->cancel();
        return waitBackoff(500ms);
    }

    while (!stopped()) {
        PendingReport pending;
        if (takeReport(epoch, &pending)) {
            uint32_t wireSession;
            int32_t messageId;
            int64_t serial;
            {
                std::lock_guard<std::mutex> lock(mLock);
                if (mConnectedEpoch != epoch || mActiveChannel != channel.get()) break;
                mWireSession = mWireSession >= kCounterLimit ? 1000 : mWireSession + 1;
                wireSession = mWireSession;
                messageId = mMessageId;
                mMessageId = static_cast<uint32_t>(mMessageId) >= kCounterLimit ? 0 : mMessageId + 1;
                serial = mExposureSerial;
                mExposureSerial = mExposureSerial > kSerialLimit ? 0 : mExposureSerial + 1;
            }
            auto encoded = encodeExposureFrame(static_cast<int32_t>(wireSession), messageId,
                    serial, wallMillis(), pending.cameraId, pending.report);
            if (!encoded) {
                std::lock_guard<std::mutex> lock(mLock);
                ++mStats.malformedMessages;
                continue;
            }
            channelResult = channel->sendFrame(std::string_view(
                    reinterpret_cast<const char*>(encoded->data()), encoded->size()));
            recordChannelResult(static_cast<int>(channelResult.status), channelResult.error);
            if (channelResult.status != Channel::Status::Ok) {
                std::lock_guard<std::mutex> lock(mLock);
                ++mStats.sendFailures;
                break;
            }
            {
                std::lock_guard<std::mutex> lock(mLock);
                ++mStats.reportsSent;
            }
            continue;
        }

        CameraMindReceivedFrame received;
        channelResult = channel->readFrame(&received, 100ms);
        if (channelResult.status == Channel::Status::Idle) continue;
        recordChannelResult(static_cast<int>(channelResult.status), channelResult.error);
        if (channelResult.status != Channel::Status::Ok) break;
        if (!completeServerEnvelope(received)) {
            std::lock_guard<std::mutex> lock(mLock);
            ++mStats.malformedMessages;
            break;
        }
        if (received.controlDisposition == CameraMindControlDisposition::UnknownWhat) {
            std::lock_guard<std::mutex> lock(mLock);
            ++mStats.unknownMessages;
            continue;
        }
        if (received.controlDisposition != CameraMindControlDisposition::Decoded) {
            std::lock_guard<std::mutex> lock(mLock);
            ++mStats.controlsRejected;
            continue;
        }
        std::lock_guard<std::mutex> lock(mLock);
        if (mConnectedEpoch != epoch || mActiveChannel != channel.get()) break;
        for (WarsawExposureRuntime* runtime : mRuntimes) {
            const auto result = runtime->submitControl(epoch, received.rawJson);
            if (result == WarsawExposureRuntime::ControlResult::Queued) ++mStats.controlsApplied;
            else ++mStats.controlsRejected;
        }
    }

    channel->cancel();
    {
        std::lock_guard<std::mutex> lock(mLock);
        if (mActiveChannel == channel.get()) {
            endSourceLocked(epoch);
            mActiveChannel = nullptr;
            ++mStats.disconnects;
        }
    }
    return !stopped();
}

}
