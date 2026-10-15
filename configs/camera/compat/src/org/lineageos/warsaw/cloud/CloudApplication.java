package org.lineageos.warsaw.cloud;

import android.app.Application;
import android.content.Context;
import android.webkit.WebView;
import java.lang.reflect.InvocationTargetException;

/** Hosts the unchanged OS4 cloud provider, updater and rollback implementation. */
public final class CloudApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        WebView.setDataDirectorySuffix(Application.getProcessName() + "_" + android.os.Process.myUid());
        invoke("com.android.settings.cloud.util.Utils", "scheduleCloudControlJobs");
        invoke("com.android.settings.cloud.PushManager", "registerPush");
    }

    private void invoke(String owner, String method) {
        try {
            Class.forName(owner, true, getClassLoader()).getMethod(method, Context.class)
                    .invoke(null, this);
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception instanceof InvocationTargetException
                    ? ((InvocationTargetException) exception).getCause() : exception;
            throw new IllegalStateException("Cannot initialize official cloud service: " + method, cause);
        }
    }
}
