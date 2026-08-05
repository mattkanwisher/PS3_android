// Definitions the rpcs3 core expects its embedder (normally the Qt app) to
// provide. Kept separate from the JNI surface so the list of embedder
// obligations stays visible at a glance.

#include "stdafx.h"

#include "Emu/Cell/SPURecompiler.h"
#include "Emu/Io/pad_config.h"
#include "Input/mouse_gyro_handler.h"

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <cstdlib>
#include <functional>
#include <string>
#include <thread>

// Input configuration globals normally defined in the desktop frontend
// (rpcs3.cpp / pad_settings_dialog.cpp).
cfg_input_configurations g_cfg_input_configs;
std::string g_input_config_override;

// Normally defined by the frontend (rpcs3.cpp shows a dialog); here: log + abort.
[[noreturn]] void report_fatal_error(std::string_view text, bool /*is_html*/, bool /*include_help_text*/)
{
	__android_log_print(ANDROID_LOG_FATAL, "rpcs3", "FATAL: %.*s", static_cast<int>(text.size()), text.data());
	std::abort();
}

// Mouse-based gyro emulation is desktop-only (its implementation file needs
// Qt); pad_thread still calls these two entry points.
void mouse_gyro_handler::set_enabled(bool)
{
}

void mouse_gyro_handler::apply_gyro(const std::shared_ptr<Pad>&)
{
}

#ifndef LLVM_AVAILABLE
// Real definition lives in SPULLVMRecompiler.cpp, which is empty without LLVM,
// while its caller (SPUCommonRecompiler.cpp) always references it. Without
// LLVM the SPU LLVM recompiler can never run, so a no-op is correct.
void spu_llvm_set_compile_context(spu_llvm_compile_context*) noexcept
{
}
#endif

// Stored by JNI_OnLoad (chrysalis_jni.cpp); used by JNI_GetCreatedJavaVMs below.
JavaVM* g_chrysalis_vm = nullptr;

// Upstream declares this in Emu/System.cpp and defines it in the Qt GUI, where
// it pumps the event loop while polling. Headless embedders just poll.
void qt_events_aware_op(int repeat_duration_ms, std::function<bool()> wrapped_op)
{
	while (!wrapped_op())
	{
		std::this_thread::sleep_for(std::chrono::milliseconds(repeat_duration_ms));
	}
}

// RtMidi's Android backend calls this, but the NDK doesn't export it: only the
// JVM knows itself. Hand back the VM captured in JNI_OnLoad.
extern "C" jint JNI_GetCreatedJavaVMs(JavaVM** vm_buf, jsize buf_len, jsize* n_vms)
{
	if (!vm_buf || buf_len < 1)
	{
		if (n_vms) *n_vms = 0;
		return JNI_ERR;
	}

	if (g_chrysalis_vm)
	{
		vm_buf[0] = g_chrysalis_vm;
		if (n_vms) *n_vms = 1;
	}
	else if (n_vms)
	{
		*n_vms = 0;
	}

	return JNI_OK;
}
