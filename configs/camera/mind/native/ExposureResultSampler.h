#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>

struct camera_metadata;
typedef struct camera_metadata camera_metadata_t;

namespace warsaw::cameramind {

class ExposureResultSampler final {
public:
    static constexpr size_t kCapacity = 64;
    struct Sample {
        uint64_t session;
        uint32_t frame;
        int64_t exposureNs;
        int64_t observedWallMillis;
        int64_t sensorTimestampNs;
        bool hasSensorTimestamp;
    };
    enum class Result {
        Queued, Busy, Inactive, StaleSession, MissingExposure, InvalidExposure, Full
    };
    struct Batch {
        std::array<Sample, kCapacity> samples{};
        size_t count = 0;
        uint64_t fullDrops = 0;
        bool current = false;
    };

    explicit ExposureResultSampler(std::string cameraId);
    ExposureResultSampler(const ExposureResultSampler&) = delete;
    ExposureResultSampler& operator=(const ExposureResultSampler&) = delete;

    const std::string& cameraId() const { return mCameraId; }
    uint64_t beginSession();
    void endSession(uint64_t session);
    bool isCurrent(uint64_t session) const;

    Result observe(uint64_t session, uint32_t frame, const camera_metadata_t* metadata);
    static Result copyMetadata(uint64_t session, uint32_t frame,
                               const camera_metadata_t* metadata, Sample* sample);
    Batch take(uint64_t session);

private:
    const std::string mCameraId;
    mutable std::mutex mMutex;
    std::array<Sample, kCapacity> mPending{};
    size_t mCount = 0;
    uint64_t mEpoch = 0;
    uint64_t mFullDrops = 0;
    bool mActive = false;
};

}
