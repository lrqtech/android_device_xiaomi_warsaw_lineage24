#pragma once

#include <binder/IBinder.h>
#include <utils/Errors.h>
#include <private/android_filesystem_config.h>
#include <cmath>
#include <functional>
#include <map>
#include <mutex>
#include <utility>

namespace android {

class WarsawGameFrameRateLease final : public IBinder::DeathRecipient {
public:
    using Apply = std::function<void(uid_t, float)>;
    explicit WarsawGameFrameRateLease(Apply apply) : mApply(std::move(apply)) {}

    status_t setBase(uid_t uid, float fps) {
        if (!valid(fps)) return BAD_VALUE;
        std::lock_guard lock(mMutex);
        if (fps == 0) mBase.erase(uid);
        else mBase[uid] = fps;
        if (mOwner == nullptr || mUid != uid) mApply(uid, fps);
        return NO_ERROR;
    }

    status_t setLease(const sp<IBinder>& owner, uid_t uid, float fps) {
        if (owner == nullptr || !valid(fps) || uid % AID_USER_OFFSET < AID_APP_START
                || fps > 165.0f) return BAD_VALUE;
        std::lock_guard lock(mMutex);
        if (mOwner != nullptr && (owner != mOwner || uid != mUid)) return ALREADY_EXISTS;
        if (fps == 0) {
            if (mOwner != nullptr) releaseLocked(false);
            return NO_ERROR;
        }
        if (mOwner == nullptr) {
            status_t linked = owner->linkToDeath(sp<IBinder::DeathRecipient>::fromExisting(this));
            if (linked != NO_ERROR) return linked;
            mOwner = owner;
            mUid = uid;
        }
        mFps = fps;
        mApply(uid, fps);
        return NO_ERROR;
    }

    bool matches(const sp<IBinder>& owner, uid_t uid, float fps) const {
        std::lock_guard lock(mMutex);
        return mOwner != nullptr && mOwner == owner && mUid == uid && mFps == fps;
    }

    void binderDied(const wp<IBinder>& who) override {
        std::lock_guard lock(mMutex);
        if (mOwner != nullptr && who == wp<IBinder>(mOwner)) releaseLocked(true);
    }

    ~WarsawGameFrameRateLease() override {
        if (mOwner != nullptr) mOwner->unlinkToDeath(wp<IBinder::DeathRecipient>::fromExisting(this));
    }

private:
    static bool valid(float fps) { return std::isfinite(fps) && (fps == 0 || fps >= 1); }
    void releaseLocked(bool died) {
        auto base = mBase.find(mUid);
        mApply(mUid, base == mBase.end() ? 0.0f : base->second);
        if (!died) mOwner->unlinkToDeath(wp<IBinder::DeathRecipient>::fromExisting(this));
        mOwner.clear();
        mUid = 0;
        mFps = 0;
    }
    const Apply mApply;
    mutable std::mutex mMutex;
    std::map<uid_t, float> mBase;
    sp<IBinder> mOwner;
    uid_t mUid = 0;
    float mFps = 0;
};
}
