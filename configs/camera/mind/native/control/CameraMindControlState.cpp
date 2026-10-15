#include "CameraMindControlState.h"
#include <algorithm>

namespace warsaw::cameramind {

bool CameraMindControlState::apply(const CameraMindCommand& command, ExposureDamper& damper) {
    Snapshot snapshot;
    return copyCommand(command, &snapshot) && apply(snapshot, damper);
}

bool CameraMindControlState::copyCommand(const CameraMindCommand& command, Snapshot* output) {
    if (!output) return false;
    Snapshot snapshot;
    snapshot.kind = command.kind;
    switch (command.kind) {
        case CameraMindCommand::Kind::ReportFlags2006:
            snapshot.reportFlags = command.reportFlags;
            break;
        case CameraMindCommand::Kind::Thresholds2016:
            if (command.thresholdsMillis.size() > snapshot.thresholdsMillis.size()) return false;
            snapshot.thresholdCount = command.thresholdsMillis.size();
            std::copy(command.thresholdsMillis.begin(), command.thresholdsMillis.end(),
                      snapshot.thresholdsMillis.begin());
            break;
        default:
            return false;
    }
    *output = snapshot;
    return true;
}

bool CameraMindControlState::apply(const Snapshot& command, ExposureDamper& damper) {
    switch (command.kind) {
        case CameraMindCommand::Kind::ReportFlags2006:
            mFlags = command.reportFlags;
            return true;
        case CameraMindCommand::Kind::Thresholds2016:
            return damper.setThresholdsMillis(command.thresholdsMillis.data(), command.thresholdCount);
    }
    return false;
}

}
