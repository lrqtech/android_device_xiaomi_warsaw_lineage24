package miui.app;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

public final class MiuiFreeFormManager$MiuiFreeFormStackInfo implements Parcelable {
    public boolean avoidFrameForIme;
    public Rect bounds = new Rect();
    public Configuration configuration = new Configuration();
    public int cornerPosition;
    public int displayId;
    public Rect enterMiniFreeformFromRect = new Rect();
    public String enterMiniFreeformReason = "";
    public float freeFormScale;
    public boolean hadHideStackFormFullScreen;
    public boolean inPinMode;
    public boolean isForegroundPin;
    public boolean isLandcapeFreeform;
    public boolean isNormalFreeForm = true;
    public String packageName;
    public Rect pinFloatingWindowPos = new Rect();
    public Rect smallWindowBounds = new Rect();
    public int stackId;
    public long timestamp;
    public int userId;
    public boolean visible;
    public int windowState;

    public MiuiFreeFormManager$MiuiFreeFormStackInfo() {}

    private MiuiFreeFormManager$MiuiFreeFormStackInfo(Parcel source) {
        readFromParcel(source);
    }

    public boolean isInFreeFormMode() { return windowState == 0; }
    public boolean isInMiniFreeFormMode() { return windowState == 1; }

    public void setTo(MiuiFreeFormManager$MiuiFreeFormStackInfo other) {
        if (other == null) return;
        stackId = other.stackId;
        bounds = new Rect(other.bounds);
        packageName = other.packageName;
        displayId = other.displayId;
        userId = other.userId;
        windowState = other.windowState;
        freeFormScale = other.freeFormScale;
        visible = other.visible;
        configuration = new Configuration(other.configuration);
        smallWindowBounds = new Rect(other.smallWindowBounds);
        inPinMode = other.inPinMode;
        isForegroundPin = other.isForegroundPin;
        pinFloatingWindowPos = new Rect(other.pinFloatingWindowPos);
        isNormalFreeForm = other.isNormalFreeForm;
        timestamp = other.timestamp;
        cornerPosition = other.cornerPosition;
        enterMiniFreeformFromRect = new Rect(other.enterMiniFreeformFromRect);
        enterMiniFreeformReason = other.enterMiniFreeformReason;
        isLandcapeFreeform = other.isLandcapeFreeform;
        hadHideStackFormFullScreen = other.hadHideStackFormFullScreen;
        avoidFrameForIme = other.avoidFrameForIme;
    }

    public void readFromParcel(Parcel source) {
        stackId = source.readInt();
        bounds = new Rect(source.readInt(), source.readInt(), source.readInt(), source.readInt());
        displayId = source.readInt();
        userId = source.readInt();
        windowState = source.readInt();
        freeFormScale = source.readFloat();
        packageName = source.readString();
        visible = source.readBoolean();
        configuration.readFromParcel(source);
        smallWindowBounds = new Rect(source.readInt(), source.readInt(), source.readInt(),
                source.readInt());
        inPinMode = source.readBoolean();
        isForegroundPin = source.readBoolean();
        pinFloatingWindowPos = new Rect(source.readInt(), source.readInt(), source.readInt(),
                source.readInt());
        isNormalFreeForm = source.readBoolean();
        timestamp = source.readLong();
        cornerPosition = source.readInt();
        enterMiniFreeformFromRect = new Rect(source.readInt(), source.readInt(), source.readInt(),
                source.readInt());
        enterMiniFreeformReason = source.readString();
        isLandcapeFreeform = source.readBoolean();
        hadHideStackFormFullScreen = source.readBoolean();
        avoidFrameForIme = source.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel target, int flags) {
        target.writeInt(stackId);
        target.writeInt(bounds.left);
        target.writeInt(bounds.top);
        target.writeInt(bounds.right);
        target.writeInt(bounds.bottom);
        target.writeInt(displayId);
        target.writeInt(userId);
        target.writeInt(windowState);
        target.writeFloat(freeFormScale);
        target.writeString(packageName);
        target.writeBoolean(visible);
        configuration.writeToParcel(target, flags);
        target.writeInt(smallWindowBounds.left);
        target.writeInt(smallWindowBounds.top);
        target.writeInt(smallWindowBounds.right);
        target.writeInt(smallWindowBounds.bottom);
        target.writeBoolean(inPinMode);
        target.writeBoolean(isForegroundPin);
        target.writeInt(pinFloatingWindowPos.left);
        target.writeInt(pinFloatingWindowPos.top);
        target.writeInt(pinFloatingWindowPos.right);
        target.writeInt(pinFloatingWindowPos.bottom);
        target.writeBoolean(isNormalFreeForm);
        target.writeLong(timestamp);
        target.writeInt(cornerPosition);
        target.writeInt(enterMiniFreeformFromRect.left);
        target.writeInt(enterMiniFreeformFromRect.top);
        target.writeInt(enterMiniFreeformFromRect.right);
        target.writeInt(enterMiniFreeformFromRect.bottom);
        target.writeString(enterMiniFreeformReason);
        target.writeBoolean(isLandcapeFreeform);
        target.writeBoolean(hadHideStackFormFullScreen);
        target.writeBoolean(avoidFrameForIme);
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public String toString() { return toString(""); }

    public String toString(String prefix) {
        return prefix + "MiuiFreeFormStackInfo{stackId=" + stackId
                + ", packageName=" + packageName + ", windowState=" + windowState
                + ", bounds=" + bounds + ", freeFormScale=" + freeFormScale
                + ", visible=" + visible + ", inPinMode=" + inPinMode + "}";
    }

    public static final Creator<MiuiFreeFormManager$MiuiFreeFormStackInfo> CREATOR =
            new Creator<>() {
                @Override
                public MiuiFreeFormManager$MiuiFreeFormStackInfo createFromParcel(Parcel source) {
                    return new MiuiFreeFormManager$MiuiFreeFormStackInfo(source);
                }

                @Override
                public MiuiFreeFormManager$MiuiFreeFormStackInfo[] newArray(int size) {
                    return new MiuiFreeFormManager$MiuiFreeFormStackInfo[size];
                }
            };
}
