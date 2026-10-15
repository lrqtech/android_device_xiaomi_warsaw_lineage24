#include "control/CameraMindCommand.h"

#include <gtest/gtest.h>
#include <limits>
#include <string>

namespace warsaw::cameramind {
namespace {

CameraMindCommand sentinel() {
    CameraMindCommand value;
    value.kind = CameraMindCommand::Kind::Thresholds2016;
    value.reportFlags = {true, true, true, true, true, true};
    value.thresholdsMillis = {-41, 12};
    return value;
}

void expectRejected(std::string_view json) {
    auto value = sentinel();
    EXPECT_FALSE(parseCameraMindCommand(json, &value));
    EXPECT_EQ(value.kind, CameraMindCommand::Kind::Thresholds2016);
    EXPECT_TRUE(value.reportFlags.reportPreviewAvgFps);
    EXPECT_TRUE(value.reportFlags.reportExposureTime);
    EXPECT_TRUE(value.reportFlags.reportFrameDuration);
    EXPECT_TRUE(value.reportFlags.reportFlickerScene);
    EXPECT_TRUE(value.reportFlags.reportMotionVelocity);
    EXPECT_TRUE(value.reportFlags.traceOn);
    EXPECT_EQ(value.thresholdsMillis, (std::vector<int32_t>{-41, 12}));
}

std::string envelope(std::string_view data, int what = 2006) {
    return "{\"what\":" + std::to_string(what) + ",\"data\":" + std::string(data) + "}";
}

TEST(CameraMindCommand, ExactDecodedStringOneAndCompleteFlagReset) {
    auto value = sentinel();
    ASSERT_TRUE(parseCameraMindCommand(envelope(R"({"reportExposureTime":"\u0031",
        "reportPreviewAvgFps":"1","reportFrameDuration":true,
        "reportFlickerScene":1,"reportMotionVelocity":"01","traceOn":"1 "})"), &value));
    EXPECT_EQ(value.kind, CameraMindCommand::Kind::ReportFlags2006);
    EXPECT_TRUE(value.reportFlags.reportExposureTime);
    EXPECT_TRUE(value.reportFlags.reportPreviewAvgFps);
    EXPECT_FALSE(value.reportFlags.reportFrameDuration);
    EXPECT_FALSE(value.reportFlags.reportFlickerScene);
    EXPECT_FALSE(value.reportFlags.reportMotionVelocity);
    EXPECT_FALSE(value.reportFlags.traceOn);
    EXPECT_TRUE(value.thresholdsMillis.empty());
    ASSERT_TRUE(parseCameraMindCommand(envelope("{}"), &value));
    EXPECT_FALSE(value.reportFlags.reportExposureTime);
    EXPECT_FALSE(value.reportFlags.reportPreviewAvgFps);
    EXPECT_FALSE(value.reportFlags.traceOn);
}

TEST(CameraMindCommand, AllSixFlagsUseSameRuleAndUnknownFieldsAreAllowed) {
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(envelope(R"({"reportPreviewAvgFps":"1",
        "reportExposureTime":"1","reportFrameDuration":"1","reportFlickerScene":"1",
        "reportMotionVelocity":"1","traceOn":"1","unknown":{"a":[null,false,1.25e+2]}})"), &value));
    EXPECT_TRUE(value.reportFlags.reportPreviewAvgFps);
    EXPECT_TRUE(value.reportFlags.reportExposureTime);
    EXPECT_TRUE(value.reportFlags.reportFrameDuration);
    EXPECT_TRUE(value.reportFlags.reportFlickerScene);
    EXPECT_TRUE(value.reportFlags.reportMotionVelocity);
    EXPECT_TRUE(value.reportFlags.traceOn);
    for (const char* token : {"true", "false", "1", "1.0", "null", "[]", "{}", "\"\"", "\"true\""}) {
        ASSERT_TRUE(parseCameraMindCommand(envelope(std::string("{\"reportExposureTime\":") + token + "}"), &value));
        EXPECT_FALSE(value.reportFlags.reportExposureTime);
    }
}

TEST(CameraMindCommand, RootDataAndIntegerWhatAreRequiredButEnvelopeOrderIsNot) {
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(R"({"data":{},"what":2006,"name":"cameraserver",
        "id":4,"time":1e9999,"reply":0})", &value));
    for (const char* text : {"", "[]", "null", "true", "{}", R"({"what":2006})",
            R"({"data":{}})", R"({"what":2006,"data":null})",
            R"({"what":2006,"data":[]})", R"({"what":2006,"data":"{}"})"}) {
        expectRejected(text);
    }
    for (const char* what : {"true", "\"2006\"", "2006.0", "2006e0", "2006E+0",
            "2147483648", "-2147483649", "2007"}) {
        expectRejected(std::string("{\"what\":") + what + ",\"data\":{}}");
    }
    EXPECT_FALSE(parseCameraMindCommand(envelope("{}"), nullptr));
}

TEST(CameraMindCommand, ThresholdsPreserveSignedInt32MillisecondsAndEmptyClears) {
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(envelope(R"({"exp-times":[-2147483648,-1,-0,0,1,2147483647]})", 2016), &value));
    EXPECT_EQ(value.kind, CameraMindCommand::Kind::Thresholds2016);
    EXPECT_EQ(value.thresholdsMillis, (std::vector<int32_t>{std::numeric_limits<int32_t>::min(),
            -1, 0, 0, 1, std::numeric_limits<int32_t>::max()}));
    ASSERT_TRUE(parseCameraMindCommand(envelope(R"({"exp-times":[]})", 2016), &value));
    EXPECT_TRUE(value.thresholdsMillis.empty());
}

TEST(CameraMindCommand, InvalidThresholdAnywhereRejectsAtomically) {
    for (const char* bad : {"2147483648", "-2147483649", "99999999999999999999999999",
            "true", "false", "null", "\"1\"", "1.0", "1e0", "{}", "[]"}) {
        expectRejected(envelope(std::string("{\"exp-times\":[3,-2,") + bad + ",4]}", 2016));
    }
    for (const char* data : {"{}", R"({"exp-times":null})", R"({"exp-times":"1"})",
            R"({"exp-times":1})", R"({"exp-times":{}})"}) {
        expectRejected(envelope(data, 2016));
    }
}

TEST(CameraMindCommand, DuplicateKeysUseDecodedNamesAtEveryObject) {
    expectRejected(R"({"what":2006,"\u0077hat":2006,"data":{}})");
    expectRejected(envelope(R"({"reportExposureTime":"1","reportExposure\u0054ime":"0"})"));
    expectRejected(envelope(R"({"ignored":{"a":1,"\u0061":2}})"));
    expectRejected(envelope(R"({"ignored":[{"x":null,"x":false}]})"));
    const std::string smile = "\xf0\x9f\x98\x80";
    expectRejected(envelope("{\"ignored\":{\"" + smile + "\":1,\"\\ud83d\\ude00\":2}}"));
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(envelope(R"({"a":{"same":1},"b":{"same":2}})"), &value));
}

TEST(CameraMindCommand, RawAndEscapedNulRejectEvenInUnknownData) {
    std::string raw = envelope(R"({"reportExposureTime":"1"})");
    raw.insert(raw.find('1') + 1, 1, '\0');
    expectRejected(raw);
    raw = envelope("{}");
    raw.push_back('\0');
    expectRejected(raw);
    expectRejected(envelope(R"({"reportExposureTime":"1\u0000"})"));
    expectRejected(envelope(R"({"ignored":["\u0000"]})"));
    expectRejected(envelope(R"({"a\u0000":0})"));
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(envelope(R"({"ignored":"\\u0000"})"), &value));
}

TEST(CameraMindCommand, Utf8AndSurrogatesAreStrictButValidUnicodeIsAccepted) {
    for (const std::string& bad : {std::string("\x80"), std::string("\xc0\xaf"),
            std::string("\xe0\x80\xaf"), std::string("\xed\xa0\x80"),
            std::string("\xf0\x80\x80\xaf"), std::string("\xf4\x90\x80\x80"),
            std::string("\xf5\x80\x80\x80"), std::string("\xe2\x82")}) {
        expectRejected(envelope("{\"unknown\":\"" + bad + "\"}"));
        expectRejected(envelope("{\"" + bad + "\":0}"));
    }
    for (const char* escaped : {R"("\ud800")", R"("\udc00")", R"("\ud800\u0041")",
            R"("\ud800\ud800")", R"("\u12xz")", R"("\u123")", R"("\x41")"}) {
        expectRejected(envelope(std::string("{\"ignored\":") + escaped + "}"));
    }
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(envelope(R"({"ignored":"\ud83d\ude00\u0001\/\b\f\n\r\t"})"), &value));
    ASSERT_TRUE(parseCameraMindCommand(envelope("{\"ignored\":\"\xc2\xa2\xe2\x82\xac\xf4\x8f\xbf\xbf\"}"), &value));
    expectRejected(envelope(std::string("{\"ignored\":\"line") + '\n' + "break\"}"));
}

TEST(CameraMindCommand, NumberSyntaxAndTrailingGarbageAreRejectedInUnknownFields) {
    for (const char* token : {"01", "-01", "+1", "-", ".1", "1.", "1e", "1e+", "1e-",
            "NaN", "Infinity", "--1", "truefalse"}) {
        expectRejected(envelope(std::string("{\"ignored\":") + token + "}"));
    }
    for (const char* json : {R"({"what":2006,"data":{},})", R"({"what":2006,"data":{"a":[1,]}})",
            R"({"what":2006,"data":{}}{})", R"({"what":2006 "data":{}})"}) {
        expectRejected(json);
    }
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(" \n\t" + envelope("{}") + "\r\n", &value));
}

TEST(CameraMindCommand, MessageBytesBoundIncludesTrailingWhitespace) {
    std::string input = envelope("{}");
    input.resize(kMaxCameraMindCommandBytes, ' ');
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(input, &value));
    input.push_back(' ');
    expectRejected(input);
}

TEST(CameraMindCommand, ContainerDepthIncludesRootAndUnknownContainers) {
    auto nested = [](size_t arrays) {
        return envelope("{\"ignored\":" + std::string(arrays, '[') + "null"
                + std::string(arrays, ']') + "}");
    };
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(nested(kMaxCameraMindJsonDepth - 2), &value));
    expectRejected(nested(kMaxCameraMindJsonDepth - 1));
}

TEST(CameraMindCommand, ObjectKeyAndTotalValueBoundsIncludeUnknownFields) {
    auto object = [](size_t count) {
        std::string result = "{";
        for (size_t i = 0; i < count; ++i) {
            if (i) result += ',';
            result += "\"k" + std::to_string(i) + "\":null";
        }
        return result + "}";
    };
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(envelope(object(kMaxCameraMindObjectKeys)), &value));
    expectRejected(envelope(object(kMaxCameraMindObjectKeys + 1)));
    auto values = [](size_t count) {
        std::string result = "{\"ignored\":[";
        for (size_t i = 0; i < count; ++i) {
            if (i) result += ',';
            result += '0';
        }
        return envelope(result + "]}");
    };
    ASSERT_TRUE(parseCameraMindCommand(values(kMaxCameraMindJsonValues - 4), &value));
    expectRejected(values(kMaxCameraMindJsonValues - 3));
}

TEST(CameraMindCommand, ThresholdArrayHasItsOwnBoundAndPreservesOrderDuplicates) {
    auto thresholds = [](size_t count) {
        std::string result = "{\"exp-times\":[";
        for (size_t i = 0; i < count; ++i) {
            if (i) result += ',';
            result += std::to_string(static_cast<int>(i % 3) - 1);
        }
        return envelope(result + "]}", 2016);
    };
    CameraMindCommand value;
    ASSERT_TRUE(parseCameraMindCommand(thresholds(kMaxCameraMindThresholds), &value));
    EXPECT_EQ(value.thresholdsMillis.size(), kMaxCameraMindThresholds);
    EXPECT_EQ(value.thresholdsMillis[0], -1);
    EXPECT_EQ(value.thresholdsMillis[1], 0);
    EXPECT_EQ(value.thresholdsMillis[2], 1);
    EXPECT_EQ(value.thresholdsMillis[3], -1);
    expectRejected(thresholds(kMaxCameraMindThresholds + 1));
}

}
}
