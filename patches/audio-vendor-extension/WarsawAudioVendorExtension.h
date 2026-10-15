#pragma once

#include <android-base/properties.h>
#include <aidl/android/media/audio/IHalAdapterVendorExtension.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <dlfcn.h>
#include <log/log.h>

namespace warsaw {

inline void registerAudioVendorExtension() {
    if (!android::base::GetBoolProperty(
                "ro.audio.ihaladaptervendorextension_enabled", false)) {
        return;
    }
    static_assert(sizeof(void*) == 8, "warsaw has a captured 64-bit provider");
    using Adapter = aidl::android::media::audio::IHalAdapterVendorExtension;
    static_assert(sizeof(Adapter::ScopeType) == 1,
                  "stock provider reads ScopeType as one byte");
    static_assert(sizeof(Adapter::ParameterScope) == 32,
                  "stock ParameterScope contains scope then string at +8");
    constexpr char kService[] = "android.media.audio.IHalAdapterVendorExtension/default";
    constexpr char kLibrary[] = "/system_ext/lib64/libaudiohalvendorextn.so";

    AIBinder* existing = AServiceManager_checkService(kService);
    if (existing != nullptr) {
        AIBinder_decStrong(existing);
        LOG_ALWAYS_FATAL("Warsaw audio vendor extension was registered before its startup hook");
    }

    void* handle = dlopen(kLibrary, RTLD_NOW | RTLD_NODELETE);
    LOG_ALWAYS_FATAL_IF(handle == nullptr, "Cannot load warsaw audio vendor extension: %s",
                        dlerror());
    dlerror();
    void* symbol = dlsym(handle, "registerInterface");
    const char* error = dlerror();
    LOG_ALWAYS_FATAL_IF(error != nullptr || symbol == nullptr,
                        "Warsaw audio vendor extension has no registerInterface: %s",
                        error != nullptr ? error : "null export");

    using Registrar = void (*)();
    reinterpret_cast<Registrar>(symbol)();

    AIBinder* registered = AServiceManager_checkService(kService);
    LOG_ALWAYS_FATAL_IF(registered == nullptr,
                        "Warsaw audio vendor extension did not register its Binder service");
    const bool remote = AIBinder_isRemote(registered);
    AIBinder_decStrong(registered);
    LOG_ALWAYS_FATAL_IF(remote,
                        "Warsaw audio vendor extension was registered outside audioserver");
}

}
