#pragma once

#include <android-base/properties.h>
#include <binder/IBinder.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <binder/PermissionController.h>
#include <log/log.h>
#include <utils/String16.h>
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <functional>
#include <mutex>
#include <optional>
#include <thread>
#include <string_view>

namespace android {

class WarsawFodDisplayState final {
public:
    WarsawFodDisplayState()
          : mEnabled(base::GetProperty("ro.product.device", "") == "warsaw" &&
                     base::GetProperty("ro.hardware.fp.fod.touch.ctl.version", "") == "2.0") {
        if (mEnabled) mWorker = std::thread([this] { run(); });
    }
    ~WarsawFodDisplayState() { stop(); }
    void stop() {
        {
            std::lock_guard lock(mStateMutex);
            mStopping = true;
        }
        mChanged.notify_one();
        if (mWorker.joinable()) mWorker.join();
    }
    WarsawFodDisplayState(const WarsawFodDisplayState&) = delete;
    WarsawFodDisplayState& operator=(const WarsawFodDisplayState&) = delete;

    bool enabled() const { return mEnabled; }

    bool acceptsInputLayer(uint32_t ownerUid, bool primaryDisplay, bool visible,
                           std::string_view name) const {
        const int32_t systemUiUid = mSystemUiUid.load();
        return mEnabled && systemUiUid >= 0 && ownerUid == static_cast<uint32_t>(systemUiUid)
                && primaryDisplay && visible
                && name.find("WarsawUdfpsControllerOverlay") != std::string_view::npos;
    }

    // Install after Scheduler initialization. The callback only schedules SF main-thread work.
    void setIdentityChangedCallback(std::function<void()> callback) {
        std::function<void()> notify;
        {
            std::lock_guard lock(mStateMutex);
            mIdentityChanged = std::move(callback);
            if (mSystemUiUid.load() >= 0) notify = mIdentityChanged;
        }
        if (notify) notify();
    }

    void updateLayers(bool udfpsInputVisible, bool colorFadeVisible) {
        if (!mEnabled) return;
        {
            std::lock_guard lock(mStateMutex);
            const int32_t ui = udfpsInputVisible ? 1 : 0;
            if (mWanted[0] == ui && mColorFade == colorFadeVisible) return;
            mWanted[0] = ui;
            mColorFade = colorFadeVisible;
            updateColorFadeLocked();
            mDirty = true;
        }
        mChanged.notify_one();
    }

    void prePowerMode(int32_t mode) {
        if (!mEnabled || !isValidPowerMode(mode)) return;
        {
            std::lock_guard lock(mStateMutex);
            mWanted[2] = mode;
        }
        std::lock_guard ioLock(mIoMutex);
        transmitLatest();
    }

    void postPowerMode(int32_t mode) {
        if (!mEnabled || !isValidPowerMode(mode)) return;
        {
            std::lock_guard lock(mStateMutex);
            mPowerMode = mode;
            updateColorFadeLocked();
            mDirty = true;
        }
        mChanged.notify_one();
    }

private:
    static constexpr char kDescriptor[] =
            "vendor.xiaomi.hardware.fingerprintextension.IXiaomiFingerprint";
    static constexpr char kService[] =
            "vendor.xiaomi.hardware.fingerprintextension.IXiaomiFingerprint/default";
    static constexpr uint32_t kExtCmd = IBinder::FIRST_CALL_TRANSACTION;
    static bool isDoze(int32_t mode) { return mode == 1 || mode == 3; }
    static bool isValidPowerMode(int32_t mode) {
        if (mode >= 0 && mode <= 4) return true;
        ALOGE("Warsaw FOD rejected unknown Composer PowerMode %d", mode);
        return false;
    }

    void updateColorFadeLocked() {
        mWanted[1] = (mColorFade || (mPowerMode && isDoze(*mPowerMode))) ? 1 : 0;
    }

    bool send(int32_t command, int32_t value) {
        Parcel data, reply;
        if (data.writeInterfaceToken(String16(kDescriptor)) != OK ||
            data.writeInt32(command) != OK || data.writeInt32(value) != OK) return false;
        const status_t status = mBinder->transact(kExtCmd, data, &reply, 0);
        int32_t result = -1;
        if (status != OK || reply.readExceptionCode() != 0 ||
            reply.readInt32(&result) != OK || result != 0) {
            if (!mReportedFailure) {
                ALOGE("Warsaw FOD command %d rejected: binder=%d result=%d", command, status,
                      result);
                mReportedFailure = true;
            }
            return false;
        }
        return true;
    }

    void transmitLatest() {
        const sp<IBinder> current = defaultServiceManager()->checkService(String16(kService));
        if (current != mBinder) {
            mBinder = current;
            mSent = {};
        }
        if (!mBinder) {
            if (!mReportedFailure) ALOGE("Warsaw FOD extension service is unavailable");
            mReportedFailure = true;
            return;
        }
        std::array<std::optional<int32_t>, 3> wanted;
        {
            std::lock_guard lock(mStateMutex);
            wanted = mWanted;
        }
        for (const size_t index : {size_t{2}, size_t{1}, size_t{0}}) {
            if (!wanted[index] || wanted[index] == mSent[index]) continue;
            if (!send(static_cast<int32_t>(index + 1), *wanted[index])) {
                mSent = {};
                return;
            }
            mSent[index] = wanted[index];
        }
        mReportedFailure = false;
    }

    void refreshSystemUiIdentity() {
        const auto now = std::chrono::steady_clock::now();
        if (now < mNextIdentityCheck) return;
        int32_t uid = -1;
        // PackageManager Binder work is confined to this worker, outside all SF/state/I/O locks.
        if (defaultServiceManager()->checkService(String16("permission")) != nullptr) {
            PermissionController permissions;
            const int candidate = permissions.getPackageUid(String16("com.android.systemui"),
                                                            PermissionController::MATCH_SYSTEM_ONLY);
            if (candidate >= 0 && permissions.checkPermission(
                    String16("android.permission.STATUS_BAR_SERVICE"), -1, candidate)) {
                uid = candidate;
            }
        }
        mNextIdentityCheck = now + (uid >= 0 ? std::chrono::seconds(30)
                                            : std::chrono::seconds(1));
        if (uid < 0 && !mIdentityFailureReported) {
            ALOGE("Warsaw FOD could not authenticate the system SystemUI UID; retrying");
        }
        mIdentityFailureReported = uid < 0;
        if (mSystemUiUid.exchange(uid) == uid) return;
        std::function<void()> notify;
        {
            std::lock_guard lock(mStateMutex);
            if (!mStopping) notify = mIdentityChanged;
        }
        if (notify) notify();
    }

    void run() {
        std::unique_lock stateLock(mStateMutex);
        while (!mStopping) {
            mChanged.wait_for(stateLock, std::chrono::seconds(1),
                              [this] { return mStopping || mDirty; });
            if (mStopping) break;
            mDirty = false;
            stateLock.unlock();
            refreshSystemUiIdentity();
            {
                std::lock_guard ioLock(mIoMutex);
                transmitLatest();
            }
            stateLock.lock();
        }
    }

    const bool mEnabled;
    std::atomic<int32_t> mSystemUiUid{-1};
    std::function<void()> mIdentityChanged;
    std::chrono::steady_clock::time_point mNextIdentityCheck{};
    bool mIdentityFailureReported = false;
    std::mutex mStateMutex;
    std::mutex mIoMutex;
    std::condition_variable mChanged;
    std::thread mWorker;
    bool mStopping = false;
    bool mDirty = false;
    bool mColorFade = false;
    std::optional<int32_t> mPowerMode;
    std::array<std::optional<int32_t>, 3> mWanted;
    sp<IBinder> mBinder;
    std::array<std::optional<int32_t>, 3> mSent;
    bool mReportedFailure = false;
};
}
