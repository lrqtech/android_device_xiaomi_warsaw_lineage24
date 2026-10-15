#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace android {
struct FpsMonitorCmd {
    float fps = 0;
    float maxInterval = 0;
    float minInterval = 0;
    int32_t frameCount = 0;
    double variance = 0;
    std::vector<int64_t> timestamps;
};
static_assert(offsetof(FpsMonitorCmd, fps) == 0);
static_assert(offsetof(FpsMonitorCmd, maxInterval) == 4);
static_assert(offsetof(FpsMonitorCmd, minInterval) == 8);
static_assert(offsetof(FpsMonitorCmd, frameCount) == 12);
static_assert(offsetof(FpsMonitorCmd, variance) == 16);
static_assert(offsetof(FpsMonitorCmd, timestamps) == 24);
#if INTPTR_MAX == INT64_MAX
static_assert(sizeof(FpsMonitorCmd) == 48);
#endif
}
