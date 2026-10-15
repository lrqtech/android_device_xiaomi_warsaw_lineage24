#include "WarsawMiVkLayer.h"

#include <array>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <vulkan/vulkan.h>

#if !defined(__aarch64__) || !defined(__LP64__)
#error "The reviewed MiVK binary contract is arm64 only"
#endif

namespace vulkan::warsaw {
namespace {

struct Export {
    const char* standardName;
    const char* stockName;
    void* address = nullptr;
};

struct State {
    void* resident = nullptr;
    void* instance = nullptr;
    bool admitted = false;
    bool debug = false;
    std::array<char, VK_MAX_EXTENSION_NAME_SIZE> layerName{};
    std::array<Export, 6> exports{{
        {"vkGetInstanceProcAddr", "MiVkGetInstanceProcAddr"},
        {"vkGetDeviceProcAddr", "MiVkGetDeviceProcAddr"},
        {"vkEnumerateInstanceExtensionProperties", "MiVkEnumerateInstanceExtensionProperties"},
        {"vkEnumerateDeviceExtensionProperties", "MiVkEnumerateDeviceExtensionProperties"},
        {"vkEnumerateInstanceLayerProperties", "MiVkEnumerateInstanceLayerProperties"},
        {"vkEnumerateDeviceLayerProperties", "MiVkEnumerateDeviceLayerProperties"},
    }};
};

State gState;
std::once_flag gOnce;
thread_local bool gInitializing = false;

template <typename T>
T load(void* handle, const char* name) {
    return reinterpret_cast<T>(dlsym(handle, name));
}

__attribute__((no_sanitize("cfi-icall")))
void initialize() {
    gInitializing = true;
    struct Leave {
        ~Leave() { gInitializing = false; }
    } leave;

    void* handle = dlopen(kMiVkPath, RTLD_NOW | RTLD_LOCAL);
    if (!handle) return;
    const auto create = load<void* (*)()>(handle, "createInstance");
    const auto enabled = load<bool (*)(void*)>(
            handle, "_ZN6vulkan12MiVkStubImpl9isEnabledEv");
    const auto debug = load<bool (*)(void*)>(
            handle, "_ZN6vulkan12MiVkStubImpl12isDebuggableEv");
    const auto name = load<const char* (*)(void*)>(
            handle, "_ZN6vulkan12MiVkStubImpl12getLayerNameEv");
    bool complete = create && enabled && debug && name;
    for (auto& entry : gState.exports) {
        entry.address = dlsym(handle, entry.stockName);
        complete = complete && entry.address;
    }
    gState.resident = handle;
    if (!complete) return;
    gState.instance = create();
    if (!gState.instance || !enabled(gState.instance)) return;

    const char* layer = name(gState.instance);
    if (!layer) return;
    const size_t length = strnlen(layer, gState.layerName.size());
    if (length == 0 || length == gState.layerName.size()) return;

    const auto enumerateInstance = reinterpret_cast<PFN_vkEnumerateInstanceLayerProperties>(
            gState.exports[4].address);
    const auto enumerateDevice = reinterpret_cast<PFN_vkEnumerateDeviceLayerProperties>(
            gState.exports[5].address);
    uint32_t instances = 0;
    uint32_t devices = 0;
    if (enumerateInstance(&instances, nullptr) != VK_SUCCESS || instances != 1 ||
        enumerateDevice(VK_NULL_HANDLE, &devices, nullptr) != VK_SUCCESS || devices != 1) {
        return;
    }
    VkLayerProperties instanceProperties{};
    VkLayerProperties deviceProperties{};
    if (enumerateInstance(&instances, &instanceProperties) != VK_SUCCESS || instances != 1 ||
        enumerateDevice(VK_NULL_HANDLE, &devices, &deviceProperties) != VK_SUCCESS || devices != 1 ||
        memcmp(&instanceProperties, &deviceProperties, sizeof(instanceProperties)) != 0 ||
        strnlen(instanceProperties.layerName, sizeof(instanceProperties.layerName)) != length ||
        memcmp(layer, instanceProperties.layerName, length) != 0) {
        return;
    }
    memcpy(gState.layerName.data(), layer, length);
    gState.debug = debug(gState.instance);
    gState.admitted = true;
}

bool ready() {
    if (gInitializing) return false;
    std::call_once(gOnce, initialize);
    return gState.admitted;
}

}

void* miVkHandle() {
    return ready() ? gState.resident : nullptr;
}

const char* miVkLayerName() {
    return ready() ? gState.layerName.data() : nullptr;
}

bool miVkDebuggable() {
    return ready() && gState.debug;
}

void* miVkSymbol(const char* standardName) {
    if (!standardName || !ready()) return nullptr;
    for (const auto& entry : gState.exports) {
        if (strcmp(standardName, entry.standardName) == 0) return entry.address;
    }
    return nullptr;
}

}
