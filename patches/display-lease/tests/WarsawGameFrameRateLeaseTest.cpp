#include "WarsawGameFrameRateLease.h"
#include <binder/Binder.h>
#include <gtest/gtest.h>
#include <limits>
#include <map>
#include <thread>
#include <vector>

namespace android {
namespace {
class TestToken final : public BBinder {
public:
    status_t linkToDeath(const sp<DeathRecipient>& recipient, void*, uint32_t) override {
        death = recipient;
        return NO_ERROR;
    }
    status_t unlinkToDeath(const wp<DeathRecipient>&, void*, uint32_t,
                           wp<DeathRecipient>*) override {
        death.clear();
        return NO_ERROR;
    }
    void die() {
        sp<DeathRecipient> target = death.promote();
        death.clear();
        if (target != nullptr) target->binderDied(wp<IBinder>(this));
    }
    wp<DeathRecipient> death;
};

TEST(WarsawGameFrameRateLease, RestoresLatestBaseAfterConcurrentGameManagerUpdates) {
    std::map<uid_t, float> applied;
    auto owner = sp<WarsawGameFrameRateLease>::make([&](uid_t uid, float fps) { applied[uid] = fps; });
    auto token = sp<TestToken>::make();
    ASSERT_EQ(NO_ERROR, owner->setBase(10123, 60));
    ASSERT_EQ(NO_ERROR, owner->setLease(token, 10123, 72.5f));
    std::vector<std::thread> writers;
    for (int i = 0; i < 8; i++) writers.emplace_back([owner, i] { owner->setBase(10123, 40.0f + i); });
    for (auto& writer : writers) writer.join();
    ASSERT_EQ(NO_ERROR, owner->setBase(10123, 90));
    EXPECT_FLOAT_EQ(72.5f, applied[10123]);
    ASSERT_EQ(NO_ERROR, owner->setLease(token, 10123, 0));
    EXPECT_FLOAT_EQ(90, applied[10123]);
}

TEST(WarsawGameFrameRateLease, BinderDeathRestoresBaseWithoutRewritingAnotherUid) {
    std::map<uid_t, float> applied;
    auto owner = sp<WarsawGameFrameRateLease>::make([&](uid_t uid, float fps) { applied[uid] = fps; });
    auto token = sp<TestToken>::make();
    owner->setBase(10123, 60);
    owner->setBase(10124, 120);
    ASSERT_EQ(NO_ERROR, owner->setLease(token, 10123, 60.2f));
    owner->setBase(10123, 0);
    token->die();
    EXPECT_FLOAT_EQ(0, applied[10123]);
    EXPECT_FLOAT_EQ(120, applied[10124]);
    EXPECT_FALSE(owner->matches(token, 10123, 60.2f));
}

TEST(WarsawGameFrameRateLease, RejectsASecondOwnerAndDifferentUidUntilRelease) {
    auto owner = sp<WarsawGameFrameRateLease>::make([](uid_t, float) {});
    auto first = sp<TestToken>::make();
    auto second = sp<TestToken>::make();
    ASSERT_EQ(NO_ERROR, owner->setLease(first, 10123, 83));
    EXPECT_EQ(ALREADY_EXISTS, owner->setLease(second, 10123, 0));
    EXPECT_EQ(ALREADY_EXISTS, owner->setLease(first, 110123, 83));
    ASSERT_EQ(NO_ERROR, owner->setLease(first, 10123, 0));
    EXPECT_EQ(NO_ERROR, owner->setLease(second, 110123, 83));
}

TEST(WarsawGameFrameRateLease, RejectsUnwatchableOwnerAndInvalidInput) {
    auto owner = sp<WarsawGameFrameRateLease>::make([](uid_t, float) {});
    auto local = sp<BBinder>::make();
    auto token = sp<TestToken>::make();
    EXPECT_EQ(INVALID_OPERATION, owner->setLease(local, 10123, 60));
    EXPECT_EQ(BAD_VALUE, owner->setLease(token, 1000, 60));
    EXPECT_EQ(BAD_VALUE, owner->setLease(token, 10123, 166));
    EXPECT_EQ(BAD_VALUE, owner->setLease(token, 10123, std::numeric_limits<float>::quiet_NaN()));
    EXPECT_EQ(BAD_VALUE, owner->setLease(token, 10123, -1));
    EXPECT_EQ(NO_ERROR, owner->setLease(token, 10123, 0));
}
}
}
