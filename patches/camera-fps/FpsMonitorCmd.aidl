package android.gui;

parcelable FpsMonitorCmd {
    float fps;
    float maxInterval;
    float minInterval;
    int frameCount;
    double variance;
    long[] timestamps;
}
