#pragma once

#include <cstddef>
#include <cstdint>
#include <string_view>
#include <vector>

namespace warsaw::cameramind {

inline constexpr size_t kMaxCameraMindCommandBytes = 65536;
inline constexpr size_t kMaxCameraMindJsonDepth = 16;
inline constexpr size_t kMaxCameraMindObjectKeys = 256;
inline constexpr size_t kMaxCameraMindJsonValues = 4096;
inline constexpr size_t kMaxCameraMindThresholds = 256;

struct CameraMindCommand {
    enum class Kind : int32_t { ReportFlags2006 = 2006, Thresholds2016 = 2016 };
    struct ReportFlags {
        bool reportPreviewAvgFps = false;
        bool reportExposureTime = false;
        bool reportFrameDuration = false;
        bool reportFlickerScene = false;
        bool reportMotionVelocity = false;
        bool traceOn = false;
    };

    Kind kind = Kind::ReportFlags2006;
    ReportFlags reportFlags;
    std::vector<int32_t> thresholdsMillis;
};

bool parseCameraMindCommand(std::string_view raw, CameraMindCommand* out);

}
