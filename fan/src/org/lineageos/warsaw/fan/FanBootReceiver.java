package org.lineageos.warsaw.fan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

public final class FanBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            context.startService(new Intent(context, FanService.class).setAction(intent.getAction()));
        }
    }
}
