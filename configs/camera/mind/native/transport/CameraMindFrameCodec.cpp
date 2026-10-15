#include "CameraMindFrameCodec.h"

#include <algorithm>
#include <limits>
#include <set>
#include <string>
#include <utility>

namespace warsaw::cameramind {
namespace {

enum class JsonType { Null, Boolean, Number, String, Object, Array };
struct Token {
    JsonType type = JsonType::Null;
    size_t start = 0;
    size_t end = 0;
    bool isInt64 = false;
    int64_t integer = 0;
};

class EnvelopeParser final {
public:
    explicit EnvelopeParser(std::string_view raw) : mRaw(raw) {}

    bool parse(CameraMindEnvelope* out) {
        Token root;
        if (!value(0, &root) || root.type != JsonType::Object) return false;
        whitespace();
        if (mPosition != mRaw.size() || !mHasWhat) return false;
        *out = std::move(mEnvelope);
        return true;
    }

private:
    static bool digit(char c) { return c >= '0' && c <= '9'; }

    void whitespace() {
        while (mPosition < mRaw.size()) {
            const char c = mRaw[mPosition];
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
            ++mPosition;
        }
    }

    bool take(char c) {
        if (mPosition == mRaw.size() || mRaw[mPosition] != c) return false;
        ++mPosition;
        return true;
    }

    bool literal(std::string_view text) {
        if (mRaw.substr(mPosition, text.size()) != text) return false;
        mPosition += text.size();
        return true;
    }

    bool hex4(uint32_t* result) {
        if (mRaw.size() - mPosition < 4) return false;
        uint32_t n = 0;
        for (size_t i = 0; i < 4; ++i) {
            const char c = mRaw[mPosition++];
            unsigned v;
            if (c >= '0' && c <= '9') v = c - '0';
            else if (c >= 'a' && c <= 'f') v = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') v = c - 'A' + 10;
            else return false;
            n = (n << 4) | v;
        }
        *result = n;
        return true;
    }

    static void appendUtf8(uint32_t cp, std::string* output) {
        if (!output) return;
        if (cp < 0x80) {
            output->push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            output->push_back(static_cast<char>(0xc0 | (cp >> 6)));
            output->push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        } else if (cp < 0x10000) {
            output->push_back(static_cast<char>(0xe0 | (cp >> 12)));
            output->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
            output->push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        } else {
            output->push_back(static_cast<char>(0xf0 | (cp >> 18)));
            output->push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
            output->push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
            output->push_back(static_cast<char>(0x80 | (cp & 0x3f)));
        }
    }

    bool escape(std::string* output) {
        if (mPosition == mRaw.size()) return false;
        uint32_t cp;
        switch (mRaw[mPosition++]) {
            case '"': cp = '"'; break;
            case '\\': cp = '\\'; break;
            case '/': cp = '/'; break;
            case 'b': cp = '\b'; break;
            case 'f': cp = '\f'; break;
            case 'n': cp = '\n'; break;
            case 'r': cp = '\r'; break;
            case 't': cp = '\t'; break;
            case 'u': {
                if (!hex4(&cp)) return false;
                if (cp >= 0xd800 && cp <= 0xdbff) {
                    if (!take('\\') || !take('u')) return false;
                    uint32_t low;
                    if (!hex4(&low) || low < 0xdc00 || low > 0xdfff) return false;
                    cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
                } else if (cp >= 0xdc00 && cp <= 0xdfff) {
                    return false;
                }
                break;
            }
            default: return false;
        }
        appendUtf8(cp, output);
        return true;
    }

    bool string(std::string* output) {
        if (!take('"')) return false;
        while (mPosition < mRaw.size()) {
            const size_t start = mPosition;
            const auto c = static_cast<unsigned char>(mRaw[mPosition++]);
            if (c == '"') return true;
            if (c < 0x20) return false;
            if (c == '\\') {
                if (!escape(output)) return false;
                continue;
            }
            if (c < 0x80) {
                if (output) output->push_back(static_cast<char>(c));
                continue;
            }
            size_t remaining;
            uint32_t cp;
            uint32_t minimum;
            if (c >= 0xc2 && c <= 0xdf) {
                remaining = 1; cp = c & 0x1f; minimum = 0x80;
            } else if (c >= 0xe0 && c <= 0xef) {
                remaining = 2; cp = c & 0x0f; minimum = 0x800;
            } else if (c >= 0xf0 && c <= 0xf4) {
                remaining = 3; cp = c & 0x07; minimum = 0x10000;
            } else {
                return false;
            }
            if (remaining > mRaw.size() - mPosition) return false;
            for (size_t i = 0; i < remaining; ++i) {
                const auto c2 = static_cast<unsigned char>(mRaw[mPosition++]);
                if ((c2 & 0xc0) != 0x80) return false;
                cp = (cp << 6) | (c2 & 0x3f);
            }
            if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return false;
            if (output) output->append(mRaw.data() + start, mPosition - start);
        }
        return false;
    }

    bool number(Token* output) {
        const bool negative = take('-');
        const size_t digitsStart = mPosition;
        if (take('0')) {
            if (mPosition < mRaw.size() && digit(mRaw[mPosition])) return false;
        } else {
            if (mPosition == mRaw.size() || mRaw[mPosition] < '1' || mRaw[mPosition] > '9') return false;
            do { ++mPosition; } while (mPosition < mRaw.size() && digit(mRaw[mPosition]));
        }
        const size_t digitsEnd = mPosition;
        bool integerToken = true;
        if (take('.')) {
            integerToken = false;
            if (mPosition == mRaw.size() || !digit(mRaw[mPosition])) return false;
            do { ++mPosition; } while (mPosition < mRaw.size() && digit(mRaw[mPosition]));
        }
        if (mPosition < mRaw.size() && (mRaw[mPosition] == 'e' || mRaw[mPosition] == 'E')) {
            ++mPosition;
            integerToken = false;
            if (mPosition < mRaw.size() && (mRaw[mPosition] == '+' || mRaw[mPosition] == '-')) ++mPosition;
            if (mPosition == mRaw.size() || !digit(mRaw[mPosition])) return false;
            do { ++mPosition; } while (mPosition < mRaw.size() && digit(mRaw[mPosition]));
        }
        output->type = JsonType::Number;
        if (!integerToken) return true;
        const uint64_t limit = negative ? uint64_t{1} << 63
                                       : static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
        uint64_t magnitude = 0;
        for (size_t i = digitsStart; i < digitsEnd; ++i) {
            const uint64_t n = static_cast<uint64_t>(mRaw[i] - '0');
            if (magnitude > (limit - n) / 10) return true;
            magnitude = magnitude * 10 + n;
        }
        output->isInt64 = true;
        if (negative && magnitude == (uint64_t{1} << 63)) {
            output->integer = std::numeric_limits<int64_t>::min();
        } else {
            output->integer = negative ? -static_cast<int64_t>(magnitude)
                                       : static_cast<int64_t>(magnitude);
        }
        return true;
    }

    bool envelopeField(std::string_view key, const Token& token) {
        if (key == "data") {
            mEnvelope.dataJson = std::string(mRaw.substr(token.start, token.end - token.start));
            return true;
        }
        const bool narrow = key == "what" || key == "id" || key == "reply";
        const bool wide = key == "serial" || key == "time";
        if (!narrow && !wide) return true;
        if (token.type != JsonType::Number || !token.isInt64) return false;
        if (narrow && (token.integer < std::numeric_limits<int32_t>::min()
                       || token.integer > std::numeric_limits<int32_t>::max())) return false;
        if (key == "what") { mEnvelope.what = static_cast<int32_t>(token.integer); mHasWhat = true; }
        else if (key == "id") mEnvelope.id = static_cast<int32_t>(token.integer);
        else if (key == "reply") mEnvelope.reply = static_cast<int32_t>(token.integer);
        else if (key == "serial") mEnvelope.serial = token.integer;
        else mEnvelope.timeMillis = token.integer;
        return true;
    }

    bool object(size_t depth) {
        ++mPosition;
        whitespace();
        if (take('}')) return true;
        std::set<std::string> keys;
        for (;;) {
            if (keys.size() == kMaxCameraMindObjectKeys) return false;
            std::string key;
            if (!string(&key) || !keys.insert(key).second) return false;
            whitespace();
            if (!take(':')) return false;
            Token child;
            if (!value(depth, &child)) return false;
            if (depth == 1 && !envelopeField(key, child)) return false;
            whitespace();
            if (take('}')) return true;
            if (!take(',')) return false;
            whitespace();
        }
    }

    bool array(size_t depth) {
        ++mPosition;
        whitespace();
        if (take(']')) return true;
        for (;;) {
            Token child;
            if (!value(depth, &child)) return false;
            whitespace();
            if (take(']')) return true;
            if (!take(',')) return false;
        }
    }

    bool value(size_t containerDepth, Token* output) {
        if (mValueCount == kMaxCameraMindJsonValues) return false;
        ++mValueCount;
        whitespace();
        if (mPosition == mRaw.size()) return false;
        output->start = mPosition;
        const char c = mRaw[mPosition];
        if (c == '{' || c == '[') {
            if (containerDepth == kMaxCameraMindJsonDepth) return false;
            output->type = c == '{' ? JsonType::Object : JsonType::Array;
            if (c == '{' ? !object(containerDepth + 1) : !array(containerDepth + 1)) return false;
        } else if (c == '"') {
            output->type = JsonType::String;
            if (!string(nullptr)) return false;
        } else if (c == 't' || c == 'f') {
            output->type = JsonType::Boolean;
            if (!literal(c == 't' ? "true" : "false")) return false;
        } else if (c == 'n') {
            if (!literal("null")) return false;
        } else if (!number(output)) {
            return false;
        }
        output->end = mPosition;
        return true;
    }

    const std::string_view mRaw;
    size_t mPosition = 0;
    size_t mValueCount = 0;
    bool mHasWhat = false;
    CameraMindEnvelope mEnvelope;
};

uint32_t read32(const uint8_t* bytes) {
    return (static_cast<uint32_t>(bytes[0]) << 24)
            | (static_cast<uint32_t>(bytes[1]) << 16)
            | (static_cast<uint32_t>(bytes[2]) << 8)
            | static_cast<uint32_t>(bytes[3]);
}

int32_t signed32(uint32_t n) {
    return static_cast<int32_t>(n <= 0x7fffffffu ? static_cast<int64_t>(n)
                                               : static_cast<int64_t>(n) - 0x100000000LL);
}

}

bool parseCameraMindEnvelope(std::string_view raw, CameraMindEnvelope* out) {
    if (!out || raw.empty() || raw.size() > kMaxCameraMindFrameBytes) return false;
    EnvelopeParser parser(raw);
    CameraMindEnvelope candidate;
    if (!parser.parse(&candidate)) return false;
    *out = std::move(candidate);
    return true;
}

CameraMindDecodeResult CameraMindFrameDecoder::fail(CameraMindFrameError error) {
    mError = error;
    return CameraMindDecodeResult::Error;
}

CameraMindDecodeResult CameraMindFrameDecoder::feed(const uint8_t* bytes, size_t size,
        size_t* consumed, CameraMindReceivedFrame* out) {
    if (consumed) *consumed = 0;
    if (mError != CameraMindFrameError::None) return CameraMindDecodeResult::Error;
    if (!consumed || !out || (!bytes && size != 0)) return fail(CameraMindFrameError::InvalidArgument);
    if (size == 0) return CameraMindDecodeResult::NeedMore;

    if (mHeaderUsed < kCameraMindFrameHeaderBytes) {
        const size_t count = std::min(size, kCameraMindFrameHeaderBytes - mHeaderUsed);
        std::copy_n(bytes, count, mHeader.data() + mHeaderUsed);
        mHeaderUsed += count;
        *consumed += count;
        if (mHeaderUsed < kCameraMindFrameHeaderBytes) return CameraMindDecodeResult::NeedMore;
        if (mHeader[0] != 0x40 || mHeader[1] != 0 || mHeader[2] != 0 || mHeader[3] != 0) {
            return fail(CameraMindFrameError::InvalidHeader);
        }
        const uint32_t length = read32(mHeader.data() + 8);
        if (length > kMaxCameraMindFrameBytes) return fail(CameraMindFrameError::InvalidLength);
        mPayloadExpected = length;
        mPayload.reserve(mPayloadExpected);
    }
    const size_t count = std::min(size - *consumed, mPayloadExpected - mPayload.size());
    mPayload.append(reinterpret_cast<const char*>(bytes + *consumed), count);
    *consumed += count;
    if (mPayload.size() != mPayloadExpected) return CameraMindDecodeResult::NeedMore;

    CameraMindReceivedFrame candidate;
    if (!parseCameraMindEnvelope(mPayload, &candidate.envelope)) {
        return fail(CameraMindFrameError::InvalidJsonEnvelope);
    }
    candidate.wireSessionId = signed32(read32(mHeader.data() + 4));
    if (candidate.envelope.what == 2006 || candidate.envelope.what == 2016) {
        CameraMindCommand command;
        if (parseCameraMindCommand(mPayload, &command)) {
            candidate.controlDisposition = CameraMindControlDisposition::Decoded;
            candidate.command = std::move(command);
        } else {
            candidate.controlDisposition = CameraMindControlDisposition::Invalid;
        }
    }
    candidate.rawJson = std::move(mPayload);
    reset();
    *out = std::move(candidate);
    return CameraMindDecodeResult::FrameReady;
}

bool CameraMindFrameDecoder::finish() {
    if (mError != CameraMindFrameError::None) return false;
    if (hasPartialFrame()) {
        fail(CameraMindFrameError::Truncated);
        return false;
    }
    return true;
}

size_t CameraMindFrameDecoder::bytesNeeded() const noexcept {
    if (mError != CameraMindFrameError::None) return 0;
    if (mHeaderUsed < kCameraMindFrameHeaderBytes) return kCameraMindFrameHeaderBytes - mHeaderUsed;
    return mPayloadExpected - mPayload.size();
}

void CameraMindFrameDecoder::reset() {
    mHeaderUsed = 0;
    mPayloadExpected = 0;
    mPayload.clear();
    mError = CameraMindFrameError::None;
}

}
