#pragma once

#include "../control/CameraMindCommand.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace warsaw::cameramind {

inline constexpr size_t kCameraMindFrameHeaderBytes = 12;
inline constexpr size_t kMaxCameraMindFrameBytes = 1u << 20;

struct CameraMindEnvelope {
    int32_t what = 0;
    std::optional<int32_t> id;
    std::optional<int64_t> serial;
    std::optional<int64_t> timeMillis;
    std::optional<int32_t> reply;
    std::optional<std::string> dataJson;
};

enum class CameraMindControlDisposition {
    UnknownWhat,
    Decoded,
    Invalid,
};

struct CameraMindReceivedFrame {
    int32_t wireSessionId = 0;
    std::string rawJson;
    CameraMindEnvelope envelope;
    CameraMindControlDisposition controlDisposition = CameraMindControlDisposition::UnknownWhat;
    std::optional<CameraMindCommand> command;
};

enum class CameraMindDecodeResult { NeedMore, FrameReady, Error };
enum class CameraMindFrameError {
    None,
    InvalidArgument,
    InvalidHeader,
    InvalidLength,
    InvalidJsonEnvelope,
    Truncated,
};

bool parseCameraMindEnvelope(std::string_view raw, CameraMindEnvelope* out);

class CameraMindFrameDecoder final {
public:
    CameraMindDecodeResult feed(const uint8_t* bytes, size_t size,
                               size_t* consumed, CameraMindReceivedFrame* out);
    bool finish();
    void reset();
    bool hasPartialFrame() const { return mHeaderUsed != 0; }
    size_t bytesNeeded() const noexcept;
    bool atFrameBoundary() const noexcept {
        return mError == CameraMindFrameError::None && mHeaderUsed == 0;
    }
    CameraMindFrameError error() const { return mError; }

private:
    CameraMindDecodeResult fail(CameraMindFrameError error);
    std::array<uint8_t, kCameraMindFrameHeaderBytes> mHeader{};
    size_t mHeaderUsed = 0;
    size_t mPayloadExpected = 0;
    std::string mPayload;
    CameraMindFrameError mError = CameraMindFrameError::None;
};

}
