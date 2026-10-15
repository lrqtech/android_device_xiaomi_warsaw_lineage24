package com.android.server.am;

import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.Build;
import android.os.UserHandle;

/** Identity opt-in for the stock camera's explicit legacy recording broadcasts. */
final class WarsawCameraBroadcastIdentity {
    private WarsawCameraBroadcastIdentity() { }

    static BroadcastOptions disclose(ProcessRecord app, String callerPackage, Intent intent,
            BroadcastOptions options, boolean sticky, boolean ordered,
            int uid, int pid, int realUid, int realPid, int userId) {
        if (!"warsaw".equals(Build.DEVICE) || app == null || app.info == null
                || !"com.android.camera".equals(callerPackage)
                || !callerPackage.equals(app.info.packageName)
                || !"com.miui.powerkeeper".equals(intent.getPackage())
                || intent.getComponent() != null || sticky || ordered
                || !("record_start".equals(intent.getAction())
                     || "record_end".equals(intent.getAction()))
                || uid != realUid || pid != realPid || uid != app.uid
                || pid != app.getPid() || app.info.uid != uid
                || userId != UserHandle.getUserId(uid)
                || !app.info.isSystemApp() || !app.info.isSignedWithPlatformKey()) {
            return options;
        }
        BroadcastOptions result = options == null ? BroadcastOptions.makeBasic()
                : BroadcastOptions.fromBundle(options.toBundle());
        return result.setShareIdentityEnabled(true);
    }
}
