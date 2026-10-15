#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace warsaw::cameramind {

class ExposureDamper final {
public:
    static constexpr size_t kMaxPending = 70;
    static constexpr int64_t kMaxPendingMillis = 5000;
    static constexpr int64_t kPrecisionNs = 1000000;
    static constexpr size_t kMaxThresholds = 256;
    struct Point { int64_t timestampMillis; int64_t exposureNs; };
    struct Report {
        std::array<Point, kMaxPending> points{};
        size_t count = 0;
    };

    bool setThresholdsMillis(const std::vector<int32_t>& thresholds);
    bool setThresholdsMillis(const int32_t* thresholds, size_t count);
    std::optional<Report> add(int64_t exposureNs, int64_t timestampMillis);
    std::optional<Report> flush();
    void reset();

private:
    std::array<int64_t, kMaxThresholds> mThresholds{};
    size_t mThresholdCount = 0;
    Report mPending;
    std::optional<int64_t> mLast;
    bool mFirstReport = true;
};

}
