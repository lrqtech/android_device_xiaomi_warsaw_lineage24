status_t SurfaceComposerClient::sentFpsMonitorCmd(
        int command, const String16& layerName, FpsMonitorCmd* output) {
    if (!output) return BAD_VALUE;
    static const sp<IBinder> owner = sp<BBinder>::make();
    const auto service = ComposerServiceAIDL::getComposerService();
    if (!service) return DEAD_OBJECT;
    gui::FpsMonitorCmd result;
    const auto status = service->sentWarsawFpsMonitorCmd(command, layerName, owner, &result);
    const status_t error = statusTFromBinderStatus(status);
    if (error != OK) return error;
    output->fps = result.fps;
    output->maxInterval = result.maxInterval;
    output->minInterval = result.minInterval;
    output->frameCount = result.frameCount;
    output->variance = result.variance;
    output->timestamps = std::move(result.timestamps);
    return OK;
}
