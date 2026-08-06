// Vulkan dispatch: fills the trampoline table (vulkan_trampolines.S) from
// either the system libvulkan or an adrenotools-hooked loader carrying a
// custom driver (e.g. Mesa Turnip). Must run before the first Vulkan call —
// the bridge calls it at init, before the core probes renderers.
//
// Why a dispatch layer at all: the core links Vulkan symbols directly, and
// Android has no ICD override mechanism — the only way to substitute a
// driver without root is adrenotools' linker-namespace hook, which hands
// back a *different* libvulkan handle that everything must resolve through.

#include <android/log.h>
#include <dlfcn.h>

#include <adrenotools/driver.h>

#define TAG "CellStation"

extern "C" {
// Pointer table defined in vulkan_trampolines.S
#define VK_SYMBOL(name) extern void* name##_dispatch;
#include "vulkan_symbols.inc"
#undef VK_SYMBOL
}

namespace chrysalis {

static void* g_vulkan_handle = nullptr;

// Returns true if every referenced symbol resolved. `custom_driver_dir` may be
// null for the system driver.
bool vk_dispatch_init(const char* custom_driver_dir,
                      const char* custom_driver_name,
                      const char* hook_lib_dir,
                      const char* tmp_lib_dir)
{
    if (g_vulkan_handle)
    {
        return true; // Already initialized; driver changes need a process restart.
    }

    if (custom_driver_dir && custom_driver_name && hook_lib_dir)
    {
        g_vulkan_handle = adrenotools_open_libvulkan(
            RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmp_lib_dir, hook_lib_dir,
            custom_driver_dir, custom_driver_name, nullptr, nullptr);

        if (g_vulkan_handle)
        {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "Custom GPU driver loaded: %s%s", custom_driver_dir, custom_driver_name);
        }
        else
        {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "adrenotools failed to load %s%s — falling back to the system driver",
                custom_driver_dir, custom_driver_name);
        }
    }

    if (!g_vulkan_handle)
    {
        g_vulkan_handle = dlopen("libvulkan.so", RTLD_NOW);
    }

    if (!g_vulkan_handle)
    {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "libvulkan.so failed to load: %s", dlerror());
        return false;
    }

    bool ok = true;
#define VK_SYMBOL(name) \
    name##_dispatch = dlsym(g_vulkan_handle, #name); \
    if (!name##_dispatch) \
    { \
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Vulkan symbol missing: %s", #name); \
        ok = false; \
    }
#include "vulkan_symbols.inc"
#undef VK_SYMBOL

    return ok;
}

} // namespace chrysalis
