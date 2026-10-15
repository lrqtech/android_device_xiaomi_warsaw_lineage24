#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <optional>
#include <string_view>
#include <vector>

namespace android::warsaw {
inline constexpr int64_t kFpsWindowNs = 1'000'000'000;
inline constexpr double kNsPerMs = 1'000'000.0;
inline constexpr int64_t kPending = std::numeric_limits<int64_t>::max();

inline bool matchesLayer(std::string_view name, std::string_view requested) {
    return !requested.empty() && name.find(requested) != std::string_view::npos
            && name.find("Bounds for") == std::string_view::npos
            && name.find("Background for") == std::string_view::npos
            && name.find("ActivityRecord") == std::string_view::npos;
}

inline std::optional<float> layerFps(const std::vector<int64_t>& times) {
    if (times.size() < 2) return std::nullopt;
    int64_t elapsed = 0;
    size_t intervals = 0;
    for (size_t i = times.size() - 1; i > 0; --i) {
        if (times[i] == kPending) continue;
        if (times[i - 1] <= 0 || times[i - 1] == kPending || times[i] <= times[i - 1]) {
            return std::nullopt;
        }
        const int64_t delta = times[i] - times[i - 1];
        if (delta > std::numeric_limits<int64_t>::max() - elapsed) return std::nullopt;
        elapsed += delta;
        ++intervals;
        if (elapsed >= kFpsWindowNs) {
            const float result = static_cast<float>(intervals)
                    / (static_cast<float>(elapsed) / static_cast<float>(kFpsWindowNs));
            return std::isfinite(result) && result > 0 ? std::optional<float>(result) : std::nullopt;
        }
    }
    return std::nullopt;
}

struct IntervalStatistics {
    float maxMs;
    float minMs;
    int32_t frameCount;
    double varianceMsSquared;
};

inline std::optional<IntervalStatistics> intervalStatistics(const std::vector<int64_t>& times) {
    size_t count = times.size();
    while (count && times[count - 1] == kPending) --count;
    if (count < 2 || count > static_cast<size_t>(std::numeric_limits<int32_t>::max())
            || times[0] <= 0 || times[count - 1] <= times[0]) return std::nullopt;
    const int64_t elapsed = times[count - 1] - times[0];
    if (elapsed <= 0) return std::nullopt;
    const float meanMs = static_cast<float>(static_cast<double>(elapsed) / kNsPerMs)
            / static_cast<float>(count - 1);
    float minMs = std::numeric_limits<float>::max();
    float maxMs = 0;
    double sum = 0;
    for (size_t i = 1; i < count; ++i) {
        if (times[i] == kPending || times[i] <= times[i - 1]) return std::nullopt;
        const float intervalMs = static_cast<float>(static_cast<double>(times[i] - times[i - 1]) / kNsPerMs);
        const double deviation = static_cast<double>(std::fabs(intervalMs - meanMs));
        sum += deviation * deviation;
        minMs = std::min(minMs, intervalMs);
        maxMs = std::max(maxMs, intervalMs);
    }
    const double variance = sum / static_cast<double>(count - 1);
    if (!std::isfinite(variance)) return std::nullopt;
    return IntervalStatistics{maxMs, minMs, static_cast<int32_t>(count), variance};
}
}
