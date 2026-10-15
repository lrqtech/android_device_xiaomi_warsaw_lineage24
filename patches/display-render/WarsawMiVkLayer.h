#pragma once

#include <cstddef>

namespace vulkan::warsaw {

inline constexpr char kMiVkPath[] = "/system_ext/lib64/libmivk.so";
inline constexpr char kMiVkFilename[] = "libmivk.so";

void* miVkHandle();
const char* miVkLayerName();
bool miVkDebuggable();

void* miVkSymbol(const char* standardName);

}

namespace vulkan::api {
const char* GetWarsawMiVkLayerName();
}
