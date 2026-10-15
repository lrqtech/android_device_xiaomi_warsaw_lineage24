package com.android.server.wm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;

import com.android.server.policy.WindowManagerPolicy;

final class WarsawFanSceneHooks {
    private static final String OWNER = "com.android.settings";

    private WarsawFanSceneHooks() { }

    static boolean isKeyguardOccluded(Context context, WindowManagerPolicy policy, int userId) {
        final int caller = Binder.getCallingUid();
        if (!"warsaw".equals(Build.DEVICE) || userId < 0) {
            throw new SecurityException("Warsaw fan scene input unavailable");
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            PackageManager pm = context.getPackageManager();
            final int owner;
            try {
                owner = pm.getPackageUidAsUser(OWNER, PackageManager.MATCH_SYSTEM_ONLY,
                        UserHandle.getUserId(caller));
            } catch (PackageManager.NameNotFoundException missing) {
                throw new SecurityException("Warsaw fan owner absent");
            }
            String[] packages = pm.getPackagesForUid(caller);
            if (caller != Process.SYSTEM_UID || owner != caller || packages == null
                    || !java.util.Arrays.asList(packages).contains(OWNER)
                    || pm.checkSignatures(Process.SYSTEM_UID, caller) != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException("Caller is not the Settings system UID");
            }
            if (ActivityManager.getCurrentUser() != userId) {
                throw new IllegalStateException("Foreground user changed");
            }
            boolean occluded = policy.isKeyguardOccluded();
            if (ActivityManager.getCurrentUser() != userId) {
                throw new IllegalStateException("Foreground user changed during scene read");
            }
            return occluded;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
