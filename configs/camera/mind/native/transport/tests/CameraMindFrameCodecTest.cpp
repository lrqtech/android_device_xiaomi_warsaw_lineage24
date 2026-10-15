#include "../CameraMindFrameCodec.h"
#include "../../ExposureWire.h"

#include <gtest/gtest.h>
#include <limits>
#include <string>
#include <vector>

namespace warsaw::cameramind {
namespace {

std::vector<uint8_t> wire(std::string_view json, int32_t session = 1001) {
    std::vector<uint8_t> result{0x40, 0, 0, 0};
    for (uint32_t word : {static_cast<uint32_t>(session), static_cast<uint32_t>(json.size())}) {
        result.push_back(static_cast<uint8_t>(word >> 24));
        result.push_back(static_cast<uint8_t>(word >> 16));
        result.push_back(static_cast<uint8_t>(word >> 8));
        result.push_back(static_cast<uint8_t>(word));
    }
    result.insert(result.end(), json.begin(), json.end());
    return result;
}

CameraMindReceivedFrame decode(std::string_view json, int32_t session = 1001) {
    const auto bytes = wire(json, session);
    CameraMindFrameDecoder decoder;
    CameraMindReceivedFrame result;
    size_t used = 0;
    EXPECT_EQ(decoder.feed(bytes.data(), bytes.size(), &used, &result), CameraMindDecodeResult::FrameReady);
    EXPECT_EQ(used, bytes.size());
    EXPECT_TRUE(decoder.atFrameBoundary());
    EXPECT_TRUE(decoder.finish());
    return result;
}

void expectInvalidEnvelope(std::string_view json) {
    CameraMindEnvelope envelope;
    envelope.what = -73;
    envelope.dataJson = "sentinel";
    EXPECT_FALSE(parseCameraMindEnvelope(json, &envelope));
    EXPECT_EQ(envelope.what, -73);
    ASSERT_TRUE(envelope.dataJson);
    EXPECT_EQ(*envelope.dataJson, "sentinel");
}

TEST(CameraMindFrameCodec, EverySplitAndOutputAtomicity) {
    const std::string json = R"({"id":9,"what":2006,"serial":3,"time":1234,"reply":0,"data":{"reportExposureTime":"1"}})";
    const auto bytes = wire(json, -17);
    for (size_t split = 0; split < bytes.size(); ++split) {
        SCOPED_TRACE(split);
        CameraMindFrameDecoder decoder;
        CameraMindReceivedFrame result;
        result.rawJson = "sentinel";
        result.wireSessionId = 41;
        size_t used = 777;
        ASSERT_EQ(decoder.feed(bytes.data(), split, &used, &result), CameraMindDecodeResult::NeedMore);
        EXPECT_EQ(used, split);
        EXPECT_EQ(result.rawJson, "sentinel");
        EXPECT_EQ(result.wireSessionId, 41);
        ASSERT_EQ(decoder.feed(bytes.data() + split, bytes.size() - split, &used, &result),
                  CameraMindDecodeResult::FrameReady);
        EXPECT_EQ(used, bytes.size() - split);
        EXPECT_EQ(result.rawJson, json);
        EXPECT_EQ(result.wireSessionId, -17);
        EXPECT_EQ(result.envelope.id, std::optional<int32_t>(9));
        EXPECT_EQ(result.envelope.serial, std::optional<int64_t>(3));
        EXPECT_EQ(result.envelope.timeMillis, std::optional<int64_t>(1234));
        EXPECT_EQ(result.envelope.reply, std::optional<int32_t>(0));
        ASSERT_TRUE(result.command);
        EXPECT_TRUE(result.command->reportFlags.reportExposureTime);
    }
}

TEST(CameraMindFrameCodec, BytewiseNeededAndBoundary) {
    const auto bytes = wire(R"({"what":99})");
    CameraMindFrameDecoder decoder;
    CameraMindReceivedFrame result;
    EXPECT_EQ(decoder.bytesNeeded(), 12u);
    EXPECT_TRUE(decoder.atFrameBoundary());
    for (size_t i = 0; i < bytes.size(); ++i) {
        size_t used = 0;
        const auto status = decoder.feed(bytes.data() + i, 1, &used, &result);
        EXPECT_EQ(used, 1u);
        if (i + 1 == bytes.size()) {
            EXPECT_EQ(status, CameraMindDecodeResult::FrameReady);
            EXPECT_TRUE(decoder.atFrameBoundary());
            EXPECT_EQ(decoder.bytesNeeded(), 12u);
        } else {
            EXPECT_EQ(status, CameraMindDecodeResult::NeedMore);
            EXPECT_FALSE(decoder.atFrameBoundary());
            EXPECT_TRUE(decoder.hasPartialFrame());
            EXPECT_EQ(decoder.bytesNeeded(), i + 1 < 12 ? 12 - i - 1 : bytes.size() - i - 1);
        }
    }
}

TEST(CameraMindFrameCodec, CoalescedFramesConsumeOnlyOneAndCountersAreIndependent) {
    const auto a = wire(R"({"id":10,"what":2006,"serial":7,"data":{}})", 500);
    const auto b = wire(R"({"id":2,"what":2016,"serial":0,"data":{"exp-times":[20]}})", 1000);
    std::vector<uint8_t> joined = a;
    joined.insert(joined.end(), b.begin(), b.end());
    joined.insert(joined.end(), a.begin(), a.end());
    CameraMindFrameDecoder decoder;
    CameraMindReceivedFrame result;
    size_t used = 0;
    ASSERT_EQ(decoder.feed(joined.data(), joined.size(), &used, &result), CameraMindDecodeResult::FrameReady);
    EXPECT_EQ(used, a.size());
    EXPECT_EQ(result.wireSessionId, 500);
    ASSERT_EQ(decoder.feed(joined.data() + a.size(), joined.size() - a.size(), &used, &result), CameraMindDecodeResult::FrameReady);
    EXPECT_EQ(used, b.size());
    EXPECT_EQ(result.envelope.serial, std::optional<int64_t>(0));
    ASSERT_EQ(decoder.feed(joined.data() + a.size() + b.size(), a.size(), &used, &result), CameraMindDecodeResult::FrameReady);
    EXPECT_EQ(result.wireSessionId, 500);
    EXPECT_EQ(result.envelope.serial, std::optional<int64_t>(7));
}

TEST(CameraMindFrameCodec, EofAtEveryPrefixAndNoImplicitResynchronization) {
    const auto bytes = wire(R"({"what":99})");
    for (size_t prefix = 0; prefix < bytes.size(); ++prefix) {
        CameraMindFrameDecoder decoder;
        CameraMindReceivedFrame result;
        result.rawJson = "sentinel";
        size_t used = 0;
        EXPECT_EQ(decoder.feed(bytes.data(), prefix, &used, &result), CameraMindDecodeResult::NeedMore);
        EXPECT_EQ(decoder.finish(), prefix == 0);
        if (prefix != 0) {
            EXPECT_EQ(decoder.error(), CameraMindFrameError::Truncated);
            EXPECT_EQ(decoder.bytesNeeded(), 0u);
            EXPECT_EQ(decoder.feed(bytes.data(), bytes.size(), &used, &result), CameraMindDecodeResult::Error);
            EXPECT_EQ(used, 0u);
            EXPECT_EQ(result.rawJson, "sentinel");
            decoder.reset();
            EXPECT_EQ(decoder.feed(bytes.data(), bytes.size(), &used, &result), CameraMindDecodeResult::FrameReady);
        }
    }
}

TEST(CameraMindFrameCodec, HeaderAndSignedLengthRejectedWithoutOutputMutation) {
    const auto original = wire(R"({"what":99})");
    for (size_t byte = 0; byte < 4; ++byte) {
        auto bytes = original;
        bytes[byte] ^= 1;
        CameraMindFrameDecoder decoder;
        CameraMindReceivedFrame result;
        result.rawJson = "sentinel";
        size_t used = 0;
        EXPECT_EQ(decoder.feed(bytes.data(), bytes.size(), &used, &result), CameraMindDecodeResult::Error);
        EXPECT_EQ(used, 12u);
        EXPECT_EQ(decoder.error(), CameraMindFrameError::InvalidHeader);
        EXPECT_EQ(result.rawJson, "sentinel");
    }
    for (uint32_t length : {0x80000000u, 0xffffffffu, 1048577u}) {
        auto bytes = original;
        for (size_t n = 0; n < 4; ++n) bytes[8 + n] = static_cast<uint8_t>(length >> (24 - 8 * n));
        CameraMindFrameDecoder decoder;
        CameraMindReceivedFrame result;
        size_t used = 0;
        EXPECT_EQ(decoder.feed(bytes.data(), bytes.size(), &used, &result), CameraMindDecodeResult::Error);
        EXPECT_EQ(decoder.error(), CameraMindFrameError::InvalidLength);
        EXPECT_EQ(used, 12u);
    }
}

TEST(CameraMindFrameCodec, ZeroPayloadIsNotAJsonEnvelope) {
    const auto bytes = wire("");
    CameraMindFrameDecoder decoder;
    CameraMindReceivedFrame result;
    result.rawJson = "sentinel";
    size_t used = 0;
    EXPECT_EQ(decoder.feed(bytes.data(), bytes.size(), &used, &result), CameraMindDecodeResult::Error);
    EXPECT_EQ(decoder.error(), CameraMindFrameError::InvalidJsonEnvelope);
    EXPECT_EQ(result.rawJson, "sentinel");
}

TEST(CameraMindFrameCodec, ZeroLengthFeedAndInvalidArguments) {
    CameraMindFrameDecoder decoder;
    CameraMindReceivedFrame result;
    result.rawJson = "sentinel";
    size_t used = 9;
    EXPECT_EQ(decoder.feed(nullptr, 0, &used, &result), CameraMindDecodeResult::NeedMore);
    EXPECT_EQ(used, 0u);
    EXPECT_TRUE(decoder.atFrameBoundary());
    EXPECT_EQ(decoder.feed(nullptr, 1, &used, &result), CameraMindDecodeResult::Error);
    EXPECT_EQ(decoder.error(), CameraMindFrameError::InvalidArgument);
    EXPECT_EQ(result.rawJson, "sentinel");
    decoder.reset();
    const uint8_t byte = 0x40;
    EXPECT_EQ(decoder.feed(&byte, 1, nullptr, &result), CameraMindDecodeResult::Error);
    decoder.reset();
    EXPECT_EQ(decoder.feed(&byte, 1, &used, nullptr), CameraMindDecodeResult::Error);
    EXPECT_FALSE(parseCameraMindEnvelope(R"({"what":99})", nullptr));
}

TEST(CameraMindFrameCodec, UnknownWhatAndMissingMetadataAreNotInventedControlSuccess) {
    const auto unknown = decode(R"({"what":-4,"extra":1e999})", 0);
    EXPECT_EQ(unknown.wireSessionId, 0);
    EXPECT_EQ(unknown.envelope.what, -4);
    EXPECT_FALSE(unknown.envelope.id);
    EXPECT_FALSE(unknown.envelope.serial);
    EXPECT_FALSE(unknown.envelope.timeMillis);
    EXPECT_FALSE(unknown.envelope.reply);
    EXPECT_FALSE(unknown.envelope.dataJson);
    EXPECT_EQ(unknown.controlDisposition, CameraMindControlDisposition::UnknownWhat);
    EXPECT_FALSE(unknown.command);
    const auto nullData = decode(R"({"what":-4,"data":null})");
    ASSERT_TRUE(nullData.envelope.dataJson);
    EXPECT_EQ(*nullData.envelope.dataJson, "null");
    const auto stringData = decode(R"({"what":42,"data":  "hello\u0000world"   })");
    EXPECT_EQ(*stringData.envelope.dataJson, R"("hello\u0000world")");
}

TEST(CameraMindFrameCodec, FullIntegerWidthsAreExactWithoutPositivityOrFreshnessRules) {
    const auto result = decode(R"({"what":2147483647,"id":-2147483648,"reply":2147483647,"serial":9223372036854775807,"time":-9223372036854775808})", std::numeric_limits<int32_t>::min());
    EXPECT_EQ(result.wireSessionId, std::numeric_limits<int32_t>::min());
    EXPECT_EQ(result.envelope.id, std::optional<int32_t>(std::numeric_limits<int32_t>::min()));
    EXPECT_EQ(result.envelope.serial, std::optional<int64_t>(std::numeric_limits<int64_t>::max()));
    EXPECT_EQ(result.envelope.timeMillis, std::optional<int64_t>(std::numeric_limits<int64_t>::min()));
    const auto maxSession = decode(R"({"what":-2147483648,"serial":-1})", std::numeric_limits<int32_t>::max());
    EXPECT_EQ(maxSession.wireSessionId, std::numeric_limits<int32_t>::max());
}

TEST(CameraMindFrameCodec, RecognizedMetadataRequiresIntegerTokensAndExactWidths) {
    for (const char* json : {
            R"({"what":2147483648})", R"({"what":-2147483649})", R"({"what":"2006"})",
            R"({"what":2006.0})", R"({"what":2.006e3})", R"({"what":true})",
            R"({"what":1,"id":2147483648})", R"({"what":1,"reply":null})",
            R"({"what":1,"serial":9223372036854775808})", R"({"what":1,"time":-9223372036854775809})",
            R"({"what":1,"serial":1e0})", R"({"what":1,"id":"1"})"}) expectInvalidEnvelope(json);
}

TEST(CameraMindFrameCodec, InvalidControlIsDifferentFromInvalidFrameAndUnknownWhat) {
    for (const char* json : {
            R"({"what":2006})", R"({"what":2006,"data":null})",
            R"({"what":2006,"data":[]})", R"({"what":2016,"data":{}})",
            R"({"what":2016,"data":{"exp-times":[20.0]}})",
            R"({"what":2016,"data":{"exp-times":[2147483648]}})",
            R"({"what":2006,"data":{"unknown":"\u0000"}})"}) {
        const auto result = decode(json);
        EXPECT_EQ(result.controlDisposition, CameraMindControlDisposition::Invalid);
        EXPECT_FALSE(result.command);
        EXPECT_EQ(result.rawJson, json);
    }
}

TEST(CameraMindFrameCodec, ControlsReuseExactStringFlagsAndSignedMillisRules) {
    const auto flags = decode(R"({"what":2006,"data":{"reportExposureTime":"\u0031","reportPreviewAvgFps":true,"traceOn":1}})");
    ASSERT_EQ(flags.controlDisposition, CameraMindControlDisposition::Decoded);
    ASSERT_TRUE(flags.command);
    EXPECT_TRUE(flags.command->reportFlags.reportExposureTime);
    EXPECT_FALSE(flags.command->reportFlags.reportPreviewAvgFps);
    EXPECT_FALSE(flags.command->reportFlags.traceOn);
    const auto thresholds = decode(R"({"what":2016,"data":{"exp-times":[-2147483648,0,20,2147483647]}})");
    ASSERT_TRUE(thresholds.command);
    EXPECT_EQ(thresholds.command->thresholdsMillis, (std::vector<int32_t>{std::numeric_limits<int32_t>::min(), 0, 20, std::numeric_limits<int32_t>::max()}));
    const auto clear = decode(R"({"what":2016,"data":{"exp-times":[]}})");
    ASSERT_TRUE(clear.command);
    EXPECT_TRUE(clear.command->thresholdsMillis.empty());
}

TEST(CameraMindFrameCodec, StrictUnicodeDecodedDuplicateKeysAndTrailingBytes) {
    for (const char* json : {
            "", "{}", "[]", "null", "{\"what\":1}garbage", "{\"what\":1}{\"what\":2}",
            R"({"what":1,"\u0077hat":2})", R"({"what":1,"x":{"a":0,"\u0061":1}})",
            R"({"what":1,"x":"\ud800"})", R"({"what":1,"x":"\udc00"})",
            R"({"what":1,"x":"\ud800\u0020"})", R"({"what":1,"x":NaN})",
            R"({"what":1,"x":01})", R"({"what":1,"x":[1,]})"}) expectInvalidEnvelope(json);
    for (const std::string& bad : {std::string("\xc0\x80", 2), std::string("\xed\xa0\x80", 3),
                                  std::string("\xf4\x90\x80\x80", 4), std::string("\xe2\x82", 2),
                                  std::string(1, '\0')}) {
        expectInvalidEnvelope("{\"what\":1,\"x\":\"" + bad + "\"}");
    }
    const auto valid = decode(R"({"what":1,"data":{"text":"\ud83d\ude80","nested":[true,false,null,1e999]}})");
    EXPECT_EQ(valid.controlDisposition, CameraMindControlDisposition::UnknownWhat);
}

TEST(CameraMindFrameCodec, JsonLimitsAreLocalAndExact) {
    CameraMindEnvelope out;
    std::string json = R"({"what":99,"data":""})";
    json.insert(json.size() - 2, kMaxCameraMindFrameBytes - json.size(), 'x');
    EXPECT_EQ(json.size(), kMaxCameraMindFrameBytes);
    EXPECT_TRUE(parseCameraMindEnvelope(json, &out));
    json.push_back(' ');
    expectInvalidEnvelope(json);

    json = "{\"what\":1,\"data\":" + std::string(15, '[') + "0" + std::string(15, ']') + "}";
    EXPECT_TRUE(parseCameraMindEnvelope(json, &out));
    expectInvalidEnvelope("{\"what\":1,\"data\":" + std::string(16, '[') + "0" + std::string(16, ']') + "}");

    json = "{\"what\":1";
    for (size_t i = 0; i < 255; ++i) json += ",\"k" + std::to_string(i) + "\":0";
    EXPECT_TRUE(parseCameraMindEnvelope(json + "}", &out));
    expectInvalidEnvelope(json + ",\"overflow\":0}");

    json = "{\"what\":1,\"data\":[";
    for (size_t i = 0; i < 4093; ++i) { if (i) json += ','; json += '0'; }
    EXPECT_TRUE(parseCameraMindEnvelope(json + "]}", &out));
    expectInvalidEnvelope(json + ",0]}");
}

TEST(CameraMindFrameCodec, CommandSizeBoundDoesNotSilentlyAcceptOversizedControl) {
    std::string json = R"({"what":2006,"data":{},"padding":""})";
    json.insert(json.size() - 2, kMaxCameraMindCommandBytes + 1 - json.size(), 'x');
    const auto result = decode(json);
    EXPECT_EQ(result.controlDisposition, CameraMindControlDisposition::Invalid);
    EXPECT_FALSE(result.command);
}

TEST(CameraMindFrameCodec, ExposureEncoderRoundTripPreservesFullEnvelopeAndPayload) {
    ExposureDamper::Report report;
    report.count = 2;
    report.points[0] = {1234567890123LL, 20000001};
    report.points[1] = {1234567890124LL, 9007199254740993LL};
    const auto encoded = encodeExposureFrame(77, 18, 99, 1234567890130LL, "0", report);
    ASSERT_TRUE(encoded);
    CameraMindFrameDecoder decoder;
    CameraMindReceivedFrame result;
    size_t used = 0;
    ASSERT_EQ(decoder.feed(encoded->data(), encoded->size(), &used, &result), CameraMindDecodeResult::FrameReady);
    EXPECT_EQ(used, encoded->size());
    EXPECT_EQ(result.wireSessionId, 77);
    EXPECT_EQ(result.envelope.what, 1066);
    EXPECT_EQ(result.envelope.id, std::optional<int32_t>(18));
    EXPECT_EQ(result.envelope.serial, std::optional<int64_t>(99));
    EXPECT_EQ(result.envelope.timeMillis, std::optional<int64_t>(1234567890130LL));
    EXPECT_EQ(result.envelope.reply, std::optional<int32_t>(0));
    ASSERT_TRUE(result.envelope.dataJson);
    EXPECT_NE(result.envelope.dataJson->find("9007199254740993"), std::string::npos);
    EXPECT_EQ(result.controlDisposition, CameraMindControlDisposition::UnknownWhat);
    EXPECT_FALSE(result.command);
}

}
}
