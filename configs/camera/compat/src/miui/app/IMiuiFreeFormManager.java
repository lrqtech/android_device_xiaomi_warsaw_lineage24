package miui.app;

import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.List;

public interface IMiuiFreeFormManager extends IInterface {
    String DESCRIPTOR = "miui.app.IMiuiFreeFormManager";

    void addFullScreenTasksBehindHome(int taskId) throws RemoteException;
    void clearFullScreenTasksBehindHome() throws RemoteException;
    void dispatchFreeFormStackModeChanged(int action, int stackId) throws RemoteException;
    List<MiuiFreeFormManager$MiuiFreeFormStackInfo> getAllFreeFormStackInfosOnDisplay(
            int displayId) throws RemoteException;
    List<MiuiFreeFormManager$MiuiFreeFormStackInfo> getAllFrontFreeFormStackInfosOnDesktopMode(
            int displayId) throws RemoteException;
    List<MiuiFreeFormManager$MiuiFreeFormStackInfo> getAllPinedFreeFormStackInfosOnDisplay(
            int displayId) throws RemoteException;
    MiuiFreeFormManager$MiuiFreeFormStackInfo getFreeFormStackInfoByActivity(IBinder token)
            throws RemoteException;
    MiuiFreeFormManager$MiuiFreeFormStackInfo getFreeFormStackInfoByStackId(int stackId)
            throws RemoteException;
    MiuiFreeFormManager$MiuiFreeFormStackInfo getFreeFormStackInfoByWindow(IBinder token)
            throws RemoteException;
    MiuiFreeFormManager$MiuiFreeFormStackInfo getFreeFormStackToAvoid(int displayId,
            String packageName) throws RemoteException;
    Rect getFreeformLastPosition(int taskId) throws RemoteException;
    float getFreeformLastScale(int taskId) throws RemoteException;
    float getFreeformRectDesktop(Rect bounds, float scale, int taskId, boolean isLandscape)
            throws RemoteException;
    boolean hasFreeformDesktopMemory(int taskId) throws RemoteException;
    boolean hasMiuiFreeformOnShellFeature() throws RemoteException;
    boolean isFrontFreeFormStackInfo(int taskId) throws RemoteException;
    boolean isFullScreenStrategyNeededInDesktopMode(int taskId) throws RemoteException;
    boolean isHideStackFromFullScreen(int taskId) throws RemoteException;
    boolean isSplitRootTask(int taskId) throws RemoteException;
    boolean isSupportPin() throws RemoteException;
    void notifyCameraStateChanged(String packageName, int state) throws RemoteException;
    boolean openCameraInFreeForm(String packageName) throws RemoteException;
    void registerFreeformCallback(IFreeformCallback callback) throws RemoteException;
    void removeFullScreenTasksBehindHome(int taskId) throws RemoteException;
    void setFrontFreeFormStackInfo(int taskId, boolean front) throws RemoteException;
    void setHideStackFromFullScreen(int taskId, boolean hide) throws RemoteException;
    boolean shouldForegroundPin(int taskId) throws RemoteException;
    int startSmallFreeformFromNotification() throws RemoteException;
    boolean unSupportedFreeformInDesktop(int taskId) throws RemoteException;
    void unregisterFreeformCallback(IFreeformCallback callback) throws RemoteException;
    void updateAutoLayoutModeStatus(boolean enabled) throws RemoteException;

    abstract class Stub extends Binder implements IMiuiFreeFormManager {
        public Stub() { attachInterface(this, DESCRIPTOR); }

        public static IMiuiFreeFormManager asInterface(IBinder binder) {
            if (binder == null) return null;
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            return local instanceof IMiuiFreeFormManager ? (IMiuiFreeFormManager) local : null;
        }

        @Override public IBinder asBinder() { return this; }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
