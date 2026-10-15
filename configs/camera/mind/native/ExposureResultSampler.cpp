#include "ExposureResultSampler.h"

#include <chrono>
#include <limits>
#include <utility>
#include <system/camera_metadata.h>

namespace warsaw::cameramind {

ExposureResultSampler::ExposureResultSampler(std::string cameraId)
      : mCameraId(std::move(cameraId)) {}

uint64_t ExposureResultSampler::beginSession() {
    std::lock_guard<std::mutex> lock(mMutex);
    mActive = false;
    mCount = 0;
    mFullDrops = 0;
    if (mCameraId.empty() || mCameraId.size() > 128 ||
        mEpoch == std::numeric_limits<uint64_t>::max()) return 0;
    ++mEpoch;
    mActive = true;
    return mEpoch;
}

void ExposureResultSampler::endSession(uint64_t session) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (session != mEpoch) return;
    mActive = false;
    mCount = 0;
    mFullDrops = 0;
}

bool ExposureResultSampler::isCurrent(uint64_t session) const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mActive && session != 0 && session == mEpoch;
}

ExposureResultSampler::Result ExposureResultSampler::observe(
        uint64_t session, uint32_t frame, const camera_metadata_t* metadata) {
    std::unique_lock<std::mutex> lock(mMutex, std::try_to_lock);
    if (!lock.owns_lock()) return Result::Busy;
    if (!mActive) return Result::Inactive;
    if (session == 0 || session != mEpoch) return Result::StaleSession;
    Sample sample{};
    const auto decoded = copyMetadata(session, frame, metadata, &sample);
    if (decoded != Result::Queued) return decoded;
    if (mCount == kCapacity) {
        if (mFullDrops != std::numeric_limits<uint64_t>::max()) ++mFullDrops;
        return Result::Full;
    }
    mPending[mCount++] = sample;
    return Result::Queued;
}

ExposureResultSampler::Result ExposureResultSampler::copyMetadata(
        uint64_t session, uint32_t frame, const camera_metadata_t* metadata, Sample* output) {
    if (output == nullptr) return Result::InvalidExposure;
    if (metadata == nullptr) return Result::MissingExposure;

    camera_metadata_ro_entry exposure{};
    if (find_camera_metadata_ro_entry(metadata, ANDROID_SENSOR_EXPOSURE_TIME, &exposure) != 0 ||
        exposure.count == 0) return Result::MissingExposure;
    if (exposure.type != TYPE_INT64 || exposure.count != 1 || exposure.data.i64 == nullptr ||
        exposure.data.i64[0] <= 0) return Result::InvalidExposure;

    const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    Sample sample{session, frame, exposure.data.i64[0], now, 0, false};
    camera_metadata_ro_entry timestamp{};
    if (find_camera_metadata_ro_entry(metadata, ANDROID_SENSOR_TIMESTAMP, &timestamp) == 0 &&
        timestamp.type == TYPE_INT64 && timestamp.count == 1 && timestamp.data.i64 != nullptr &&
        timestamp.data.i64[0] > 0) {
        sample.sensorTimestampNs = timestamp.data.i64[0];
        sample.hasSensorTimestamp = true;
    }
    *output = sample;
    return Result::Queued;
}

ExposureResultSampler::Batch ExposureResultSampler::take(uint64_t session) {
    std::lock_guard<std::mutex> lock(mMutex);
    Batch result;
    if (!mActive || session == 0 || session != mEpoch) return result;
    result.current = true;
    result.count = mCount;
    result.fullDrops = mFullDrops;
    for (size_t i = 0; i < mCount; ++i) result.samples[i] = mPending[i];
    mCount = 0;
    mFullDrops = 0;
    return result;
}

}
