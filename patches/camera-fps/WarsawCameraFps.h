#pragma once

#include "WarsawFpsStatistics.h"
#include <android/gui/FpsMonitorCmd.h>
#include <binder/IBinder.h>
#include <utils/Errors.h>
#include <utils/Timers.h>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <sys/types.h>
#include <utility>

namespace android {
class WarsawCameraFps final : public IBinder::DeathRecipient {
public:
    using Result = gui::FpsMonitorCmd;
    using Work = std::function<status_t(Result*)>;
    using Schedule = std::function<void(std::function<void()>)>;
    static constexpr size_t kMaxSamples = 4096;
    static constexpr std::string_view kCameraLayer =
            "SurfaceView[com.android.camera/com.android.camera.Camera](BLAST)";

    status_t query(const Schedule& schedule, Work work, Result* output) {
        auto request = std::make_shared<Request>();
        {
            std::lock_guard lock(mQueryMutex);
            const auto now = std::chrono::steady_clock::now();
            if (mBusy || now < mNextQuery) return WOULD_BLOCK;
            mBusy = true;
            mNextQuery = now + std::chrono::milliseconds(250);
        }
        const sp<WarsawCameraFps> self = sp<WarsawCameraFps>::fromExisting(this);
        schedule([self, request, work = std::move(work)]() {
            Result result;
            const status_t status = work(&result);
            {
                std::lock_guard lock(request->mutex);
                request->result = std::move(result);
                request->status = status;
                request->done = true;
            }
            {
                std::lock_guard lock(self->mQueryMutex);
                self->mBusy = false;
            }
            request->condition.notify_one();
        });
        std::unique_lock lock(request->mutex);
        if (!request->condition.wait_for(lock, std::chrono::milliseconds(500),
                                        [&] { return request->done; })) return TIMED_OUT;
        if (request->status == OK) *output = std::move(request->result);
        return request->status;
    }

    void presented(std::string_view name) {
        if (name.find(kCameraLayer) == std::string_view::npos) return;
        std::lock_guard lock(mCameraMutex);
        if (!mOwner || mOverflow) return;
        if (mCameraTimes.size() == kMaxSamples) {
            mOverflow = true;
            return;
        }
        mCameraTimes.push_back(systemTime(SYSTEM_TIME_MONOTONIC));
    }

    status_t cameraCommand(int32_t command, const sp<IBinder>& token, uid_t caller, Result* out) {
        if (!token || !token->remoteBinder()) return BAD_VALUE;
        std::lock_guard lock(mCameraMutex);
        if (mOwner && (mOwner != token || mOwnerUid != caller)) return WOULD_BLOCK;
        if (command == 6) {
            if (!mOwner) {
                const status_t linked = token->linkToDeath(
                        sp<IBinder::DeathRecipient>::fromExisting(this));
                if (linked != OK) return linked;
                mOwner = token;
                mOwnerUid = caller;
            }
            clearSamplesLocked();
            return OK;
        }
        if (!mOwner) return INVALID_OPERATION;
        if (command == 7) {
            mOwner->unlinkToDeath(wp<IBinder::DeathRecipient>::fromExisting(this));
            mOwner.clear();
            clearSamplesLocked();
            return OK;
        }
        if (command == 8) {
            clearSamplesLocked();
            return OK;
        }
        if (command != 5) return BAD_VALUE;
        const auto stats = mOverflow ? std::nullopt : warsaw::intervalStatistics(mCameraTimes);
        clearSamplesLocked();
        if (!stats) return INVALID_OPERATION;
        out->maxInterval = stats->maxMs;
        out->minInterval = stats->minMs;
        out->frameCount = stats->frameCount;
        out->variance = stats->varianceMsSquared;
        return OK;
    }

    void binderDied(const wp<IBinder>& who) override {
        std::lock_guard lock(mCameraMutex);
        if (wp<IBinder>(mOwner) != who) return;
        mOwner.clear();
        clearSamplesLocked();
    }

private:
    struct Request {
        std::mutex mutex;
        std::condition_variable condition;
        Result result;
        status_t status = INVALID_OPERATION;
        bool done = false;
    };
    void clearSamplesLocked() {
        mCameraTimes.clear();
        mOverflow = false;
    }
    std::mutex mQueryMutex;
    bool mBusy = false;
    std::chrono::steady_clock::time_point mNextQuery{};
    std::mutex mCameraMutex;
    sp<IBinder> mOwner;
    uid_t mOwnerUid = 0;
    std::vector<int64_t> mCameraTimes;
    bool mOverflow = false;
};
}
