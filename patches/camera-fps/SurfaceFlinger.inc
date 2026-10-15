status_t SurfaceFlinger::sentWarsawFpsMonitorCmd(
        int32_t command, const String16& layerName, const sp<IBinder>& owner,
        uid_t caller, gui::FpsMonitorCmd* output) {
    if (!output || command < 1 || command > 8) return BAD_VALUE;
    if (command >= 5) return mWarsawCameraFps->cameraCommand(command, owner, caller, output);

    if (layerName.size() == 0 || layerName.size() > 1024) return BAD_VALUE;
    const std::string requested = String8(layerName).c_str();
    return mWarsawCameraFps->query(
            [this](std::function<void()> task) {
                static_cast<void>(mScheduler->schedule(std::move(task)));
            },
            [this, command, requested](gui::FpsMonitorCmd* result)
                    FTL_FAKE_GUARD(mStateLock) FTL_FAKE_GUARD(kMainThreadContext) {
                float bestFps = 0;
                std::vector<int64_t> bestTimes;
                bool oversized = false;
                traverseLegacyLayers([&](Layer* layer) {
                    if (!warsaw::matchesLayer(layer->getName(), requested)) return;
                    FrameStats stats;
                    layer->getFrameStats(&stats);
                    const auto& actual = stats.actualPresentTimesNano;
                    if (actual.size() > WarsawCameraFps::kMaxSamples) {
                        oversized = true;
                        return;
                    }
                    std::vector<int64_t> times;
                    times.reserve(actual.size());
                    for (size_t i = 0; i < actual.size(); ++i) times.push_back(actual[i]);
                    const auto fps = warsaw::layerFps(times);
                    if (fps && *fps >= bestFps) {
                        bestFps = *fps;
                        bestTimes = std::move(times);
                    }
                });
                if (oversized) return NO_MEMORY;
                if (bestFps <= 0) return INVALID_OPERATION;
                if (command != 2) result->fps = bestFps;
                if (command >= 2) {
                    const auto stats = warsaw::intervalStatistics(bestTimes);
                    if (!stats) return INVALID_OPERATION;
                    result->variance = stats->varianceMsSquared;
                }
                if (command == 4) {
                    std::sort(bestTimes.begin(), bestTimes.end());
                    result->timestamps = std::move(bestTimes);
                }
                return OK;
            }, output);
}

void SurfaceFlinger::recordWarsawCameraFrame(std::string_view layerName) {
    mWarsawCameraFps->presented(layerName);
}

binder::Status SurfaceComposerAIDL::sentWarsawFpsMonitorCmd(
        int32_t command, const String16& layerName, const sp<IBinder>& owner,
        gui::FpsMonitorCmd* output) {
    const status_t permission = checkAccessPermission();
    if (permission != OK) return binderStatusFromStatusT(permission);
    const uid_t caller = IPCThreadState::self()->getCallingUid();
    return binderStatusFromStatusT(
            mFlinger->sentWarsawFpsMonitorCmd(command, layerName, owner, caller, output));
}
