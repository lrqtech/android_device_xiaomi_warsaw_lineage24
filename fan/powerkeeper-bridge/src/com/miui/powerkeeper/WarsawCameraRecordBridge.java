package com.miui.powerkeeper;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import java.util.Arrays;

public final class WarsawCameraRecordBridge extends BroadcastReceiver {
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String TARGET_PACKAGE = "com.android.settings";
    private static final String TARGET_ACTION =
            "org.lineageos.warsaw.fan.action.CAMERA_RECORD_STATE";
    private static final String TARGET_PERMISSION =
            "org.lineageos.warsaw.fan.permission.CAMERA_RECORD_STATE";
    private static final String START = "record_start";
    private static final String END = "record_end";

    @Override
    public void onReceive(Context context, Intent source) {
        if (!isExactCameraSender(context, getSentFromUid(), getSentFromPackage())) return;
        String action = source.getAction();
        if (!START.equals(action) && !END.equals(action)) return;

        int state = 99;
        if (START.equals(action)) {
            int quality = source.getIntExtra("quality", 0);
            int fps = source.getIntExtra("fps", 0);
            state = mapRecordState(quality, fps);
        }
        Intent target = new Intent(TARGET_ACTION)
                .setPackage(TARGET_PACKAGE)
                .putExtra("state", state);
        BroadcastOptions options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true);
        context.sendBroadcast(target, TARGET_PERMISSION, options.toBundle());
    }

    private static boolean isExactCameraSender(Context context, int uid, String senderPackage) {
        if (uid < Process.FIRST_APPLICATION_UID || !CAMERA_PACKAGE.equals(senderPackage)) {
            return false;
        }
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length != 1
                || !CAMERA_PACKAGE.equals(packages[0])) return false;
        return pm.checkSignatures(context.getPackageName(), CAMERA_PACKAGE)
                == PackageManager.SIGNATURE_MATCH;
    }

    static int mapRecordState(int quality, int fps) {
        if (quality == 8) return fps == 60 ? 2 : 1;
        if (quality == 3001) return 2;
        return 99;
    }
}
