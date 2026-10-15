#include "ExposureDamper.h"
#include "ExposureResultSampler.h"
#include "ExposureWire.h"

#include <gtest/gtest.h>
#include <memory>
#include <system/camera_metadata.h>

namespace warsaw::cameramind {
namespace {

using Metadata = std::unique_ptr<camera_metadata_t, decltype(&free_camera_metadata)>;
Metadata metadata(int64_t exposure = 0) {
    Metadata result(allocate_camera_metadata(4, 128), free_camera_metadata);
    if (result && exposure != 0) {
        if (add_camera_metadata_entry(result.get(), ANDROID_SENSOR_EXPOSURE_TIME, &exposure, 1) != 0) {
            return Metadata(nullptr, free_camera_metadata);
        }
    }
    return result;
}

TEST(ExposureSampler, CopiesRealInt64AndNeverUsesSensorClockAsWallClock) {
    ExposureResultSampler sampler("0");
    const auto session = sampler.beginSession();
    auto input = metadata(12345678);
    ASSERT_NE(input, nullptr);
    const int64_t sensorTimestamp = 1122334455;
    ASSERT_EQ(add_camera_metadata_entry(input.get(), ANDROID_SENSOR_TIMESTAMP, &sensorTimestamp, 1), 0);
    ASSERT_EQ(sampler.observe(session, 20, input.get()), ExposureResultSampler::Result::Queued);
    input.reset();
    const auto batch = sampler.take(session);
    ASSERT_EQ(batch.count, 1u);
    EXPECT_EQ(batch.samples[0].exposureNs, 12345678);
    EXPECT_EQ(batch.samples[0].sensorTimestampNs, sensorTimestamp);
    EXPECT_TRUE(batch.samples[0].hasSensorTimestamp);
    EXPECT_GT(batch.samples[0].observedWallMillis, 0);
    EXPECT_EQ(batch.samples[0].frame, 20u);
}

TEST(ExposureSampler, MissingPartialDoesNotPreventLaterResultAndNoInventedOrderingGate) {
    ExposureResultSampler sampler("external/2");
    const auto session = sampler.beginSession();
    auto empty = metadata();
    auto full = metadata(5000000);
    ASSERT_NE(empty, nullptr);
    ASSERT_NE(full, nullptr);
    EXPECT_EQ(sampler.observe(session, 20, empty.get()), ExposureResultSampler::Result::MissingExposure);
    EXPECT_EQ(sampler.observe(session, 20, full.get()), ExposureResultSampler::Result::Queued);
    EXPECT_EQ(sampler.observe(session, 19, full.get()), ExposureResultSampler::Result::Queued);
    EXPECT_EQ(sampler.take(session).count, 2u);
}

TEST(ExposureSampler, BoundedQueueAndReopeningInvalidateStaleProducers) {
    ExposureResultSampler sampler("0");
    const auto old = sampler.beginSession();
    auto input = metadata(1000000);
    ASSERT_NE(input, nullptr);
    for (size_t i = 0; i < ExposureResultSampler::kCapacity; ++i) {
        ASSERT_EQ(sampler.observe(old, i, input.get()), ExposureResultSampler::Result::Queued);
    }
    EXPECT_EQ(sampler.observe(old, 65, input.get()), ExposureResultSampler::Result::Full);
    auto batch = sampler.take(old);
    EXPECT_EQ(batch.count, ExposureResultSampler::kCapacity);
    EXPECT_EQ(batch.fullDrops, 1u);
    const auto current = sampler.beginSession();
    ASSERT_NE(old, current);
    EXPECT_EQ(sampler.observe(old, 66, input.get()), ExposureResultSampler::Result::StaleSession);
    sampler.endSession(old);
    EXPECT_TRUE(sampler.isCurrent(current));
    EXPECT_EQ(sampler.take(current).count, 0u);
    sampler.endSession(current);
    EXPECT_FALSE(sampler.isCurrent(current));
    EXPECT_FALSE(sampler.take(current).current);
}

TEST(ExposureDamper, InclusiveCrossingOverridesPrecisionAndEqualValuesRemainFiltered) {
    ExposureDamper damper;
    ASSERT_TRUE(damper.setThresholdsMillis({10}));
    EXPECT_TRUE(damper.add(9500000, 0));
    EXPECT_TRUE(damper.add(10000000, 1));
    EXPECT_FALSE(damper.add(10000000, 2));
    EXPECT_TRUE(damper.add(9999999, 3));
    EXPECT_FALSE(damper.add(9999998, 4));
}

TEST(ExposureDamper, AcceptedSamplesOnlyDriveAgeAndPendingCount) {
    ExposureDamper damper;
    EXPECT_TRUE(damper.add(1000000, 0));
    EXPECT_FALSE(damper.add(2000000, 1));
    EXPECT_FALSE(damper.add(2999999, 100000));
    auto report = damper.add(3000000, 100001);
    ASSERT_TRUE(report);
    EXPECT_EQ(report->count, 2u);
    damper.reset();
    ASSERT_TRUE(damper.add(1000000, 0));
    for (int64_t i = 1; i < 70; ++i) EXPECT_FALSE(damper.add((i + 1) * 1000000, i));
    report = damper.add(71000000, 70);
    ASSERT_TRUE(report);
    EXPECT_EQ(report->count, 70u);
    EXPECT_FALSE(damper.flush());
}

TEST(ExposureWire, RealNsRemainIntegerAndTheHeaderCountsUtf8Bytes) {
    ExposureDamper::Report report;
    report.count = 1;
    report.points[0] = {123, 9007199254740993LL};
    auto encoded = encodeExposureFrame(1001, 9, 4, 200, "external/\"\xe9\x95\x9c", report);
    ASSERT_TRUE(encoded);
    ASSERT_GT(encoded->size(), 12u);
    EXPECT_EQ((*encoded)[0], 0x40);
    EXPECT_EQ((*encoded)[6], 0x03);
    EXPECT_EQ((*encoded)[7], 0xe9);
    uint32_t length = 0;
    for (size_t i = 8; i < 12; ++i) length = (length << 8) | (*encoded)[i];
    EXPECT_EQ(length, encoded->size() - 12);
    const std::string payload(encoded->begin() + 12, encoded->end());
    EXPECT_NE(payload.find("\"values\":[9007199254740993]"), std::string::npos);
    EXPECT_NE(payload.find("\"timestamp\":[123]"), std::string::npos);
    EXPECT_NE(payload.find("\"time\":200"), std::string::npos);
    EXPECT_FALSE(encodeExposureFrame(1001, 0, 0, 1, "\xc0\xaf", report));
}

TEST(ExposureWire, IdentityIsTheRequiredFirstMessageShape) {
    auto encoded = encodeIdentityFrame(1001, 0, 0, 1234, "cameraserver");
    ASSERT_TRUE(encoded);
    const std::string payload(encoded->begin() + 12, encoded->end());
    EXPECT_EQ(payload,
              R"({"id":0,"what":1005,"serial":0,"time":1234,"reply":0,"data":{"my_name":"cameraserver"}})");
    EXPECT_FALSE(encodeIdentityFrame(0, 0, 0, 1, "cameraserver"));
    EXPECT_FALSE(encodeIdentityFrame(1001, -1, 0, 1, "cameraserver"));
}

}
}
