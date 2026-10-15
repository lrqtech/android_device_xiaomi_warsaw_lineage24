#include "ExposureDamper.h"

#include <algorithm>

namespace warsaw::cameramind {

bool ExposureDamper::setThresholdsMillis(const std::vector<int32_t>& thresholds) {
    return setThresholdsMillis(thresholds.data(), thresholds.size());
}

bool ExposureDamper::setThresholdsMillis(const int32_t* thresholds, size_t count) {
    if (count > kMaxThresholds || (count != 0 && thresholds == nullptr)) return false;
    for (size_t i = 0; i < count; ++i) {
        mThresholds[i] = static_cast<int64_t>(thresholds[i]) * kPrecisionNs;
    }
    mThresholdCount = count;
    return true;
}

std::optional<ExposureDamper::Report> ExposureDamper::add(
        int64_t exposureNs, int64_t timestampMillis) {
    if (exposureNs <= 0) return std::nullopt;
    bool crossing = false;
    if (mLast) {
        if (*mLast != exposureNs) {
            const auto low = std::min(*mLast, exposureNs);
            const auto high = std::max(*mLast, exposureNs);
            for (size_t i = 0; i < mThresholdCount; ++i) {
                crossing |= low <= mThresholds[i] && mThresholds[i] <= high;
            }
        }
        const auto distance = exposureNs >= *mLast ? exposureNs - *mLast : *mLast - exposureNs;
        if (!crossing && distance < kPrecisionNs) return std::nullopt;
    }
    mPending.points[mPending.count++] = {timestampMillis, exposureNs};
    const bool countReady = mPending.count >= kMaxPending;
    const auto first = mPending.points[0].timestampMillis;
    const bool timeReady = timestampMillis >= first &&
            static_cast<uint64_t>(timestampMillis) - static_cast<uint64_t>(first) >=
                    static_cast<uint64_t>(kMaxPendingMillis);
    const bool immediate = crossing || (mFirstReport && mPending.count == 1);
    if (!countReady && !timeReady && immediate) mFirstReport = false;
    auto report = countReady || timeReady || immediate ? flush() : std::nullopt;
    mLast = exposureNs;
    return report;
}

std::optional<ExposureDamper::Report> ExposureDamper::flush() {
    if (mPending.count == 0) return std::nullopt;
    auto result = mPending;
    mPending.count = 0;
    return result;
}

void ExposureDamper::reset() {
    mPending.count = 0;
    mLast.reset();
    mFirstReport = true;
}

}
