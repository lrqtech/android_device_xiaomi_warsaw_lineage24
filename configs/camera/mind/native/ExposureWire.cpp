#include "ExposureWire.h"

namespace warsaw::cameramind {
namespace {

bool validUtf8(const std::string& text) {
    for (size_t i = 0; i < text.size();) {
        const auto first = static_cast<uint8_t>(text[i++]);
        if (first < 0x80) continue;
        size_t continuation;
        uint32_t value;
        uint32_t minimum;
        if (first >= 0xc2 && first <= 0xdf) {
            continuation = 1; value = first & 0x1f; minimum = 0x80;
        } else if (first >= 0xe0 && first <= 0xef) {
            continuation = 2; value = first & 0x0f; minimum = 0x800;
        } else if (first >= 0xf0 && first <= 0xf4) {
            continuation = 3; value = first & 7; minimum = 0x10000;
        } else return false;
        if (text.size() - i < continuation) return false;
        for (size_t n = 0; n < continuation; ++n) {
            const auto byte = static_cast<uint8_t>(text[i++]);
            if ((byte & 0xc0) != 0x80) return false;
            value = (value << 6) | (byte & 0x3f);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
    }
    return true;
}

std::string quote(const std::string& text) {
    constexpr char hex[] = "0123456789abcdef";
    std::string out = "\"";
    for (const unsigned char byte : text) {
        if (byte == '"' || byte == '\\') {
            out += '\\'; out += static_cast<char>(byte);
        } else if (byte < 0x20) {
            out += "\\u00"; out += hex[byte >> 4]; out += hex[byte & 15];
        } else out += static_cast<char>(byte);
    }
    return out + '"';
}

void append32(std::vector<uint8_t>& bytes, uint32_t value) {
    bytes.push_back(static_cast<uint8_t>(value >> 24));
    bytes.push_back(static_cast<uint8_t>(value >> 16));
    bytes.push_back(static_cast<uint8_t>(value >> 8));
    bytes.push_back(static_cast<uint8_t>(value));
}

std::vector<uint8_t> frame(int32_t wireSession, const std::string& payload) {
    std::vector<uint8_t> result{0x40, 0, 0, 0};
    result.reserve(12 + payload.size());
    append32(result, static_cast<uint32_t>(wireSession));
    append32(result, static_cast<uint32_t>(payload.size()));
    result.insert(result.end(), payload.begin(), payload.end());
    return result;
}

}

std::optional<std::vector<uint8_t>> encodeExposureFrame(
        int32_t wireSession, int32_t messageId, int64_t serial, int64_t wallMillis,
        const std::string& cameraId, const ExposureDamper::Report& report) {
    if (wireSession <= 0 || messageId < 0 || serial < 0 || cameraId.empty() ||
        cameraId.size() > 128 || !validUtf8(cameraId) || report.count == 0 ||
        report.count > ExposureDamper::kMaxPending) return std::nullopt;
    std::string values = "[", timestamps = "[";
    for (size_t i = 0; i < report.count; ++i) {
        if (report.points[i].exposureNs <= 0) return std::nullopt;
        if (i != 0) { values += ','; timestamps += ','; }
        values += std::to_string(report.points[i].exposureNs);
        timestamps += std::to_string(report.points[i].timestampMillis);
    }
    const auto payload = "{\"id\":" + std::to_string(messageId) +
            ",\"what\":1066,\"serial\":" + std::to_string(serial) +
            ",\"time\":" + std::to_string(wallMillis) +
            ",\"reply\":0,\"data\":{\"cameraid\":" + quote(cameraId) +
            ",\"exposure_time\":{\"values\":" + values + "],\"timestamp\":" + timestamps + "]}}}";
    return frame(wireSession, payload);
}

std::optional<std::vector<uint8_t>> encodeIdentityFrame(
        int32_t wireSession, int32_t messageId, int64_t serial, int64_t wallMillis,
        const std::string& processName) {
    if (wireSession <= 0 || messageId < 0 || serial < 0 || wallMillis < 0 ||
            processName.empty() || processName.size() > 128 || !validUtf8(processName)) {
        return std::nullopt;
    }
    const auto payload = "{\"id\":" + std::to_string(messageId) +
            ",\"what\":1005,\"serial\":" + std::to_string(serial) +
            ",\"time\":" + std::to_string(wallMillis) +
            ",\"reply\":0,\"data\":{\"my_name\":" + quote(processName) + "}}";
    return frame(wireSession, payload);
}

}
