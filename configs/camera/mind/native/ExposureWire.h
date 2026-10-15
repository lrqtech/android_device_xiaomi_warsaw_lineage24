#pragma once

#include "ExposureDamper.h"
#include <optional>
#include <string>
#include <vector>

namespace warsaw::cameramind {

std::optional<std::vector<uint8_t>> encodeExposureFrame(
        int32_t wireSession, int32_t messageId, int64_t serial, int64_t wallMillis,
        const std::string& cameraId, const ExposureDamper::Report& report);

std::optional<std::vector<uint8_t>> encodeIdentityFrame(
        int32_t wireSession, int32_t messageId, int64_t serial, int64_t wallMillis,
        const std::string& processName);

}
