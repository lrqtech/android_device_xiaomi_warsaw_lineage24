package com.android.server.display;

final class WarsawThermalPolicy {
    private float mRawTemperature = Float.NaN;
    private float mAppliedTemperature = Float.NaN;
    private float mLux;
    private float mSafety = Float.NaN;
    private int mActualCondition;
    private boolean mHdr;
    private boolean mCameraHdr;
    private boolean mDolbyKnown;
    private boolean mDolby;
    private float mCap = Float.NaN;

    static boolean sameTemperature(float a, float b) {
        if (a == b || (Float.isNaN(a) && Float.isNaN(b))) return true;
        if (Math.abs((int) a - (int) b) < 1) return true;
        return Math.abs(a - b) < 0.5f;
    }

    void setTemperature(float milliCelsius) {
        if (!Float.isFinite(milliCelsius)) return;
        mRawTemperature = milliCelsius / 1000f;
        if (Float.isNaN(mAppliedTemperature)
                || !sameTemperature(mRawTemperature, mAppliedTemperature)) {
            mAppliedTemperature = mRawTemperature;
        }
    }

    void setCondition(int value) { mActualCondition = value; }
    void setHdr(boolean value, boolean camera) { mHdr = value; mCameraHdr = camera; }

    void setDolby(boolean known, boolean enabled) {
        mDolbyKnown = known;
        mDolby = known && enabled;
    }

    void setAmbient(float lux, boolean automatic) {
        if (!automatic) mLux = 6000f;
        else if (Float.isFinite(lux) && lux >= 0f) mLux = lux;
    }

    void setSafety(float value) {
        if (Float.isFinite(value) && (value >= 0f || value == -1f)) {
            mSafety = value == 0f ? Float.NaN : value;
        }
    }

    private int appliedCondition() {
        if (mHdr || mCameraHdr || mDolby) return WarsawThermalTables.HDR_CONDITION;
        for (int id : WarsawThermalTables.CONDITIONS) {
            if (id == mActualCondition) return id;
        }
        return 0;
    }

    static float tableCap(int condition, float lux, float temperature) {
        float lastMax = Float.NEGATIVE_INFINITY;
        float lastMin = Float.NaN;
        float selectedMin = Float.NaN;
        for (float[] row : WarsawThermalTables.ROWS) {
            if (row[0] != condition) continue;
            if (lux >= row[1] && lux < row[2] && Float.isNaN(selectedMin)) {
                selectedMin = row[1];
            }
            if (row[2] > lastMax) { lastMax = row[2]; lastMin = row[1]; }
        }
        if (Float.isNaN(selectedMin) && lux >= lastMax) selectedMin = lastMin;
        for (float[] row : WarsawThermalTables.ROWS) {
            if (row[0] == condition && row[1] == selectedMin
                    && temperature >= row[3] && temperature < row[4]) return row[5];
        }
        return Float.NaN;
    }

    float capNits() {
        float next = tableCap(appliedCondition(), mLux, mAppliedTemperature);
        if (!mDolbyKnown && !mHdr && !mCameraHdr) {
            float possibleHdr = tableCap(WarsawThermalTables.HDR_CONDITION,
                    mLux, mAppliedTemperature);
            if (Float.isNaN(next)) next = possibleHdr;
            else if (!Float.isNaN(possibleHdr)) next = Math.min(next, possibleHdr);
        }
        if (Float.isFinite(mSafety) && mSafety >= 0f) {
            next = Float.isNaN(next) ? mSafety : Math.min(next, mSafety);
        }
        if (Float.isNaN(next) && Float.isNaN(mCap)) return mCap;
        mCap = next;
        return next;
    }

    @Override public String toString() {
        return "rawC=" + mRawTemperature + " appliedC=" + mAppliedTemperature
                + " actualCondition=" + mActualCondition + " appliedCondition="
                + appliedCondition() + " lux=" + mLux + " hdr=" + mHdr + " cameraHdr=" + mCameraHdr
                + " dolby=" + (mDolbyKnown ? Boolean.toString(mDolby) : "UNKNOWN (both tables)")
                + " safetyNits=" + mSafety + " capNits=" + mCap;
    }
}
