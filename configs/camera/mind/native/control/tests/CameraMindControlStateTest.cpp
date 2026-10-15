#include "control/CameraMindControlState.h"
#include <gtest/gtest.h>
#include <limits>

namespace warsaw::cameramind {
namespace {

TEST(CameraMindControlState, DefaultOffAndEveryFlagSnapshotReplacesThePreviousOne) {
    CameraMindControlState state;
    ExposureDamper damper;
    EXPECT_FALSE(state.exposureEnabled());
    CameraMindCommand command;
    command.kind = CameraMindCommand::Kind::ReportFlags2006;
    command.reportFlags = {true, true, true, true, true, true};
    ASSERT_TRUE(state.apply(command, damper));
    EXPECT_TRUE(state.exposureEnabled());
    command.reportFlags = {};
    command.reportFlags.reportFrameDuration = true;
    ASSERT_TRUE(state.apply(command, damper));
    EXPECT_FALSE(state.exposureEnabled());
    EXPECT_FALSE(state.flags().reportPreviewAvgFps);
    EXPECT_TRUE(state.flags().reportFrameDuration);
    EXPECT_FALSE(state.flags().reportFlickerScene);
    EXPECT_FALSE(state.flags().reportMotionVelocity);
    EXPECT_FALSE(state.flags().traceOn);
}

TEST(CameraMindControlState, DisableAndThresholdUpdatesDoNotResetBufferedHistory) {
    CameraMindControlState state;
    ExposureDamper damper;
    CameraMindCommand flags;
    flags.kind = CameraMindCommand::Kind::ReportFlags2006;
    flags.reportFlags.reportExposureTime = true;
    ASSERT_TRUE(state.apply(flags, damper));
    ASSERT_TRUE(damper.add(1000000, 0));
    EXPECT_FALSE(damper.add(2000000, 1));
    flags.reportFlags = {};
    ASSERT_TRUE(state.apply(flags, damper));
    CameraMindCommand thresholds;
    thresholds.kind = CameraMindCommand::Kind::Thresholds2016;
    thresholds.thresholdsMillis = {std::numeric_limits<int32_t>::min(),
                                  std::numeric_limits<int32_t>::max()};
    ASSERT_TRUE(state.apply(thresholds, damper));
    EXPECT_FALSE(state.exposureEnabled());
    flags.reportFlags.reportExposureTime = true;
    ASSERT_TRUE(state.apply(flags, damper));
    EXPECT_FALSE(damper.add(2000500, 2));
    const auto pending = damper.flush();
    ASSERT_TRUE(pending);
    ASSERT_EQ(pending->count, 1u);
    EXPECT_EQ(pending->points[0].exposureNs, 2000000);
}

TEST(CameraMindControlState, SourceFlagResetDoesNotPretendToAuthenticateOrFlush) {
    CameraMindControlState state;
    ExposureDamper damper;
    CameraMindCommand flags;
    flags.kind = CameraMindCommand::Kind::ReportFlags2006;
    flags.reportFlags.reportExposureTime = true;
    ASSERT_TRUE(state.apply(flags, damper));
    ASSERT_TRUE(damper.add(1000000, 0));
    EXPECT_FALSE(damper.add(2000000, 1));
    state.resetFlags();
    EXPECT_FALSE(state.exposureEnabled());
    ASSERT_TRUE(damper.flush());
}

TEST(CameraMindControlState, QueueSnapshotOwnsFixedThresholdStorage) {
    static_assert(std::is_trivially_copyable_v<CameraMindControlState::Snapshot>);
    CameraMindCommand parsed;
    parsed.kind = CameraMindCommand::Kind::Thresholds2016;
    parsed.thresholdsMillis = {10};
    CameraMindControlState::Snapshot snapshot;
    ASSERT_TRUE(CameraMindControlState::copyCommand(parsed, &snapshot));
    parsed.thresholdsMillis[0] = 1000;
    EXPECT_EQ(snapshot.thresholdCount, 1u);
    EXPECT_EQ(snapshot.thresholdsMillis[0], 10);
    CameraMindControlState state;
    ExposureDamper damper;
    ASSERT_TRUE(state.apply(snapshot, damper));
    ASSERT_TRUE(damper.add(9500000, 0));
    EXPECT_TRUE(damper.add(10000000, 1));
}

TEST(CameraMindControlState, BadFixedSnapshotAndPointerBoundsPreservePriorThresholds) {
    CameraMindCommand parsed;
    parsed.kind = CameraMindCommand::Kind::Thresholds2016;
    parsed.thresholdsMillis = {10};
    CameraMindControlState::Snapshot snapshot;
    ASSERT_TRUE(CameraMindControlState::copyCommand(parsed, &snapshot));
    parsed.thresholdsMillis.assign(257, 30);
    EXPECT_FALSE(CameraMindControlState::copyCommand(parsed, &snapshot));
    EXPECT_EQ(snapshot.thresholdCount, 1u);
    EXPECT_EQ(snapshot.thresholdsMillis[0], 10);
    EXPECT_FALSE(CameraMindControlState::copyCommand(parsed, nullptr));
    CameraMindControlState state;
    ExposureDamper damper;
    ASSERT_TRUE(state.apply(snapshot, damper));
    snapshot.thresholdCount = 257;
    EXPECT_FALSE(state.apply(snapshot, damper));
    EXPECT_FALSE(damper.setThresholdsMillis(nullptr, 1));
    ASSERT_TRUE(damper.add(9500000, 0));
    EXPECT_TRUE(damper.add(10000000, 1));
    EXPECT_TRUE(damper.setThresholdsMillis(nullptr, 0));
    EXPECT_FALSE(damper.add(9999999, 2));
}

}
}
