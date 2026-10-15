package com.android.settings.biometrics.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.settings.R;
import com.google.android.setupdesign.GlifLayout;

import java.util.List;

@MainThread
public final class WarsawSwipeEnrollment {
    private final Context mContext;
    private int mTitle = R.string.warsaw_fp_place;
    private CharSequence mDescription;
    private boolean mHasLiveEvent;
    private boolean mRestoredProgress;

    private WarsawSwipeEnrollment(Context context) {
        mContext = context;
        mDescription = context.getText(R.string.warsaw_fp_start);
    }

    @Nullable
    public static WarsawSwipeEnrollment create(Context context) {
        if (!"warsaw".equals(Build.DEVICE)) return null;
        FingerprintManager manager = context.getSystemService(FingerprintManager.class);
        if (manager == null) return null;
        try {
            List<FingerprintSensorPropertiesInternal> sensors = manager.getSensorPropertiesInternal();
            if (sensors == null || sensors.size() != 1) return null;
            FingerprintSensorPropertiesInternal sensor = sensors.get(0);
            if (sensor.sensorId != 5
                    || sensor.sensorType != FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC) {
                return null;
            }
            return new WarsawSwipeEnrollment(context);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Nullable
    public CharSequence onHelp(int code, @Nullable CharSequence fallback) {
        mHasLiveEvent = true;
        int title;
        int description;
        switch (code) {
            case 1:
                title = R.string.warsaw_fp_area;
                description = R.string.warsaw_fp_area_detail;
                break;
            case 2:
                title = R.string.warsaw_fp_retry;
                description = R.string.warsaw_fp_start;
                break;
            case 3:
                title = R.string.warsaw_fp_clean;
                description = R.string.warsaw_fp_clean_detail;
                break;
            case 5:
            case 1058:
                title = R.string.warsaw_fp_slower;
                description = R.string.warsaw_fp_slide_detail;
                break;
            case 1025:
                title = R.string.warsaw_fp_adjust;
                description = R.string.warsaw_fp_adjust_detail;
                break;
            case 1063:
                title = R.string.warsaw_fp_faster;
                description = R.string.warsaw_fp_slide_detail;
                break;
            case 1064:
                title = R.string.warsaw_fp_lift;
                description = R.string.warsaw_fp_lift_detail;
                break;
            default:
                if (fallback == null || fallback.length() == 0) return fallback;
                mTitle = R.string.warsaw_fp_continue;
                mDescription = fallback.toString();
                return mDescription;
        }
        mTitle = title;
        mDescription = mContext.getText(description);
        return getAccessibilityTitle();
    }

    public void onProgress(int remaining) {
        if (remaining < 0) return;
        mHasLiveEvent = true;
        showProgress(remaining);
    }

    public void restoreProgress(int remaining) {
        if (remaining < 0 || mHasLiveEvent || mRestoredProgress) return;
        mRestoredProgress = true;
        showProgress(remaining);
    }

    private void showProgress(int remaining) {
        if (remaining == 0) {
            mTitle = R.string.warsaw_fp_finishing;
            mDescription = mContext.getText(R.string.warsaw_fp_finishing_detail);
        } else {
            mTitle = R.string.warsaw_fp_continue;
            mDescription = mContext.getResources().getQuantityString(
                    R.plurals.warsaw_fp_remaining, remaining, remaining);
        }
    }

    public void render(GlifLayout layout) {
        CharSequence title = mContext.getText(mTitle);
        if (!TextUtils.equals(layout.getHeaderTextView().getText(), title)) {
            layout.setHeaderText(title);
        }
        layout.getHeaderTextView().setContentDescription(null);
        if (!TextUtils.equals(layout.getDescriptionTextView().getText(), mDescription)) {
            layout.setDescriptionText(mDescription);
        }
        layout.getDescriptionTextView().setVisibility(View.VISIBLE);
    }

    public CharSequence getAccessibilityTitle() {
        return mContext.getText(mTitle) + ". " + mDescription;
    }
}
