#include "CameraMindCommand.h"

#include <limits>
#include <set>
#include <string>
#include <utility>

namespace warsaw::cameramind {
namespace {

enum class Type { Null, Boolean, Number, String, Object, Array };

struct Value {
    Type type = Type::Null;
    bool isInt32 = false;
    int32_t integer = 0;
    std::string string;
    std::vector<std::pair<std::string, size_t>> members;
    std::vector<size_t> elements;
};

class Parser final {
public:
    explicit Parser(std::string_view raw) : mRaw(raw) {}

    bool parse(size_t* root) {
        if (!value(0, root)) return false;
        whitespace();
        return mPosition == mRaw.size();
    }

    const Value& at(size_t index) const { return mValues[index]; }

    const Value* member(const Value& object, std::string_view name) const {
        for (const auto& entry : object.members) {
            if (std::string_view(entry.first) == name) return &at(entry.second);
        }
        return nullptr;
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
        switch (mRaw[mPosition++]) {
            case '"': output->push_back('"'); return true;
            case '\\': output->push_back('\\'); return true;
            case '/': output->push_back('/'); return true;
            case 'b': output->push_back('\b'); return true;
            case 'f': output->push_back('\f'); return true;
            case 'n': output->push_back('\n'); return true;
            case 'r': output->push_back('\r'); return true;
            case 't': output->push_back('\t'); return true;
            case 'u': break;
            default: return false;
        }
        uint32_t cp;
        if (!hex4(&cp) || cp == 0) return false;
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (!take('\\') || !take('u')) return false;
            uint32_t low;
            if (!hex4(&low) || low < 0xdc00 || low > 0xdfff) return false;
            cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00;
        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
            return false;
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
                output->push_back(static_cast<char>(c));
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
                const auto continuation = static_cast<unsigned char>(mRaw[mPosition++]);
                if ((continuation & 0xc0) != 0x80) return false;
                cp = (cp << 6) | (continuation & 0x3f);
            }
            if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) {
                return false;
            }
            output->append(mRaw.data() + start, mPosition - start);
        }
        return false;
    }

    bool number(Value* output) {
        const bool negative = take('-');
        const size_t digitsStart = mPosition;
        if (take('0')) {
            if (mPosition < mRaw.size() && digit(mRaw[mPosition])) return false;
        } else {
            if (mPosition == mRaw.size() || mRaw[mPosition] < '1'
                    || mRaw[mPosition] > '9') return false;
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
            if (mPosition < mRaw.size() && (mRaw[mPosition] == '+' || mRaw[mPosition] == '-')) {
                ++mPosition;
            }
            if (mPosition == mRaw.size() || !digit(mRaw[mPosition])) return false;
            do { ++mPosition; } while (mPosition < mRaw.size() && digit(mRaw[mPosition]));
        }
        output->type = Type::Number;
        if (!integerToken) return true;
        const uint32_t limit = negative ? uint32_t{2147483648u} : uint32_t{2147483647u};
        uint32_t magnitude = 0;
        for (size_t i = digitsStart; i < digitsEnd; ++i) {
            const uint32_t n = static_cast<uint32_t>(mRaw[i] - '0');
            if (magnitude > (limit - n) / 10) return true;
            magnitude = magnitude * 10 + n;
        }
        output->isInt32 = true;
        if (negative && magnitude == uint32_t{2147483648u}) {
            output->integer = std::numeric_limits<int32_t>::min();
        } else {
            output->integer = negative ? -static_cast<int32_t>(magnitude)
                                       : static_cast<int32_t>(magnitude);
        }
        return true;
    }

    bool object(size_t depth, Value* output) {
        output->type = Type::Object;
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
            size_t child;
            if (!value(depth, &child)) return false;
            output->members.emplace_back(std::move(key), child);
            whitespace();
            if (take('}')) return true;
            if (!take(',')) return false;
            whitespace();
        }
    }

    bool array(size_t depth, Value* output) {
        output->type = Type::Array;
        ++mPosition;
        whitespace();
        if (take(']')) return true;
        for (;;) {
            size_t child;
            if (!value(depth, &child)) return false;
            output->elements.push_back(child);
            whitespace();
            if (take(']')) return true;
            if (!take(',')) return false;
        }
    }

    bool value(size_t containerDepth, size_t* index) {
        if (mValueCount == kMaxCameraMindJsonValues) return false;
        ++mValueCount;
        whitespace();
        if (mPosition == mRaw.size()) return false;
        Value next;
        const char c = mRaw[mPosition];
        if (c == '{' || c == '[') {
            if (containerDepth == kMaxCameraMindJsonDepth) return false;
            if (c == '{' ? !object(containerDepth + 1, &next)
                         : !array(containerDepth + 1, &next)) return false;
        } else if (c == '"') {
            next.type = Type::String;
            if (!string(&next.string)) return false;
        } else if (c == 't' || c == 'f') {
            next.type = Type::Boolean;
            if (!literal(c == 't' ? "true" : "false")) return false;
        } else if (c == 'n') {
            if (!literal("null")) return false;
        } else if (!number(&next)) {
            return false;
        }
        *index = mValues.size();
        mValues.push_back(std::move(next));
        return true;
    }

    const std::string_view mRaw;
    size_t mPosition = 0;
    size_t mValueCount = 0;
    std::vector<Value> mValues;
};

bool flag(const Parser& parser, const Value& data, std::string_view key) {
    const Value* value = parser.member(data, key);
    return value && value->type == Type::String && value->string == "1";
}

}

bool parseCameraMindCommand(std::string_view raw, CameraMindCommand* out) {
    if (!out || raw.empty() || raw.size() > kMaxCameraMindCommandBytes
            || raw.find('\0') != std::string_view::npos) return false;
    Parser parser(raw);
    size_t rootIndex;
    if (!parser.parse(&rootIndex)) return false;
    const Value& root = parser.at(rootIndex);
    if (root.type != Type::Object) return false;
    const Value* what = parser.member(root, "what");
    const Value* data = parser.member(root, "data");
    if (!what || what->type != Type::Number || !what->isInt32
            || !data || data->type != Type::Object) return false;

    CameraMindCommand candidate;
    if (what->integer == 2006) {
        candidate.kind = CameraMindCommand::Kind::ReportFlags2006;
        candidate.reportFlags.reportPreviewAvgFps = flag(parser, *data, "reportPreviewAvgFps");
        candidate.reportFlags.reportExposureTime = flag(parser, *data, "reportExposureTime");
        candidate.reportFlags.reportFrameDuration = flag(parser, *data, "reportFrameDuration");
        candidate.reportFlags.reportFlickerScene = flag(parser, *data, "reportFlickerScene");
        candidate.reportFlags.reportMotionVelocity = flag(parser, *data, "reportMotionVelocity");
        candidate.reportFlags.traceOn = flag(parser, *data, "traceOn");
    } else if (what->integer == 2016) {
        candidate.kind = CameraMindCommand::Kind::Thresholds2016;
        const Value* thresholds = parser.member(*data, "exp-times");
        if (!thresholds || thresholds->type != Type::Array
                || thresholds->elements.size() > kMaxCameraMindThresholds) return false;
        candidate.thresholdsMillis.reserve(thresholds->elements.size());
        for (size_t index : thresholds->elements) {
            const Value& threshold = parser.at(index);
            if (threshold.type != Type::Number || !threshold.isInt32) return false;
            candidate.thresholdsMillis.push_back(threshold.integer);
        }
    } else {
        return false;
    }
    *out = std::move(candidate);
    return true;
}

}
