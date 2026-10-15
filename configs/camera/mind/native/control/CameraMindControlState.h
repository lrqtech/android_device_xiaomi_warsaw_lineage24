#pragma once

#include "CameraMindCommand.h"
#include "../ExposureDamper.h"
#include <array>
#include <type_traits>

namespace warsaw::cameramind {

class CameraMindControlState final {
public:
    struct Snapshot {
        CameraMindCommand::Kind kind = CameraMindCommand::Kind::ReportFlags2006;
        CameraMindCommand::ReportFlags reportFlags{};
        std::array<int32_t, ExposureDamper::kMaxThresholds> thresholdsMillis{};
        size_t thresholdCount = 0;
    };
    static_assert(std::is_trivially_copyable_v<Snapshot>);
    static bool copyCommand(const CameraMindCommand& command, Snapshot* output);
    const CameraMindCommand::ReportFlags& flags() const { return mFlags; }
    bool exposureEnabled() const { return mFlags.reportExposureTime; }
    void resetFlags() { mFlags = {}; }
    bool apply(const CameraMindCommand& command, ExposureDamper& damper);
    bool apply(const Snapshot& command, ExposureDamper& damper);

private:
    CameraMindCommand::ReportFlags mFlags{};
};

}
