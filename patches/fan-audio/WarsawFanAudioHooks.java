package com.android.server.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class WarsawFanAudioHooks {
    private static final String OWNER = "com.android.settings";
    private static final Set<String> COMMUNICATION_PACKAGES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "com.tencent.mm",
                    "com.tencent.mobileqq",
                    "com.alibaba.android.rimet",
                    "com.tencent.wework",
                    "com.whatsapp",
                    "com.ss.android.lark")));

    private WarsawFanAudioHooks() { }

    static void enforceCallerAndTarget(Context context, int targetUid) {
        context.enforceCallingPermission(Manifest.permission.MODIFY_AUDIO_ROUTING,
                "Warsaw fan recorder lifecycle");
        final int caller = Binder.getCallingUid();
        if (!"warsaw".equals(Build.DEVICE)
                || UserHandle.getAppId(targetUid) < Process.FIRST_APPLICATION_UID) {
            throw new SecurityException("Warsaw fan recorder lifecycle unavailable");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final PackageManager pm = context.getPackageManager();
            final int ownerUid;
            try {
                ownerUid = pm.getPackageUidAsUser(OWNER, PackageManager.MATCH_SYSTEM_ONLY,
                        UserHandle.getUserId(caller));
            } catch (PackageManager.NameNotFoundException missing) {
                throw new SecurityException("Warsaw fan owner absent");
            }
            final String[] callerPackages = pm.getPackagesForUid(caller);
            if (caller != Process.SYSTEM_UID || ownerUid != caller || callerPackages == null
                    || !Arrays.asList(callerPackages).contains(OWNER)
                    || pm.checkSignatures(Process.SYSTEM_UID, caller)
                            != PackageManager.SIGNATURE_MATCH) {
                throw new SecurityException(
                        "Caller is not the Settings system UID");
            }

            final String[] targetPackages = pm.getPackagesForUid(targetUid);
            if (targetPackages == null || !Arrays.stream(targetPackages)
                    .anyMatch(COMMUNICATION_PACKAGES::contains)) {
                throw new SecurityException("Target UID is not a reviewed communication app");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
