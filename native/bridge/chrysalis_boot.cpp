// Boot support: a Qt-free port of headless_application::InitializeCallbacks()
// plus the non-Qt subset of main_application::CreateCallbacks(). Bring-up
// configuration: Null video renderer (forced), cubeb audio with Null fallback,
// Null keyboard/mouse, upstream pad_thread.

#include "stdafx.h"

#include "Emu/Audio/Cubeb/CubebBackend.h"
#include "Emu/Audio/Cubeb/cubeb_enumerator.h"
#include "Emu/Audio/Null/NullAudioBackend.h"
#include "Emu/Audio/Null/null_enumerator.h"
#include "Emu/Cell/Modules/cellMsgDialog.h"
#include "Emu/Cell/Modules/cellOskDialog.h"
#include "Emu/Cell/Modules/cellSaveData.h"
#include "Emu/Cell/Modules/sceNpTrophy.h"
#include "Emu/Io/KeyboardHandler.h"
#include "Emu/Io/MouseHandler.h"
#include "Emu/Io/Null/NullKeyboardHandler.h"
#include "Emu/Io/Null/NullMouseHandler.h"
#include "Emu/Io/Null/null_camera_handler.h"
#include "Emu/Io/Null/null_music_handler.h"
#include "Emu/RSX/Null/NullGSRender.h"
#include "Emu/System.h"
#include "Emu/system_config.h"
#include "Emu/vfs_config.h"
#include "Input/pad_thread.h"
#include "util/logs.hpp"
#include "util/video_source.h"

#include <functional>

LOG_CHANNEL(chrysalis_boot_log, "CHRYSALIS");

void qt_events_aware_op(int repeat_duration_ms, std::function<bool()> wrapped_op); // core_compat.cpp

namespace chrysalis
{

void init_callbacks()
{
	EmuCallbacks callbacks{};

	// No GUI event loop exists; run "main thread" work inline on the caller.
	callbacks.call_from_main_thread = [](std::function<void()> func, atomic_t<u32>* wake_up)
	{
		func();

		if (wake_up)
		{
			*wake_up = true;
			wake_up->notify_one();
		}
	};

	callbacks.try_to_quit = [](bool force_quit, std::function<void()> on_exit) -> bool
	{
		chrysalis_boot_log.notice("try_to_quit(force_quit=%d)", force_quit);

		if (force_quit && on_exit)
		{
			on_exit();
		}

		return force_quit;
	};

	callbacks.update_emu_settings = []() {};
	callbacks.save_emu_settings = []()
	{
		Emulator::SaveSettings(g_cfg.to_string(), Emu.GetTitleID());
	};

	callbacks.init_kb_handler = []()
	{
		ensure(g_fxo->init<KeyboardHandlerBase, NullKeyboardHandler>(Emu.DeserialManager()));
	};

	callbacks.init_mouse_handler = []()
	{
		ensure(g_fxo->init<MouseHandlerBase, NullMouseHandler>(Emu.DeserialManager()));
	};

	callbacks.init_pad_handler = [](std::string_view title_id)
	{
		ensure(g_fxo->init<named_thread<pad_thread>>(nullptr, nullptr, title_id));

		qt_events_aware_op(0, []() { return !!pad::g_started; });
	};

	callbacks.init_gs_render = [](utils::serial* ar)
	{
		// Bring-up: always the Null renderer; the Vulkan/ANativeWindow frame
		// arrives with the real UI (M3).
		if (const video_renderer type = g_cfg.video.renderer; type != video_renderer::null)
		{
			chrysalis_boot_log.warning("Configured renderer is %s; forcing Null renderer for headless bring-up", type);
		}

		g_fxo->init<rsx::thread, named_thread<NullGSRender>>(ar);
	};

	callbacks.get_gs_frame = []() -> std::unique_ptr<GSFrameBase> { return {}; };
	callbacks.close_gs_frame = []() {};

	callbacks.get_audio = []() -> std::shared_ptr<AudioBackend>
	{
		std::shared_ptr<AudioBackend> result = std::make_shared<CubebBackend>();

		if (!result->Initialized())
		{
			chrysalis_boot_log.error("Cubeb audio backend failed to initialize, falling back to Null");
			result = std::make_shared<NullAudioBackend>();
		}

		return result;
	};

	callbacks.get_audio_enumerator = [](u64 renderer) -> std::shared_ptr<audio_device_enumerator>
	{
		switch (static_cast<audio_renderer>(renderer))
		{
		case audio_renderer::cubeb: return std::make_shared<cubeb_enumerator>();
		default: return std::make_shared<null_enumerator>();
		}
	};

	callbacks.get_camera_handler = []() -> std::shared_ptr<camera_handler_base> { return std::make_shared<null_camera_handler>(); };
	callbacks.get_music_handler = []() -> std::shared_ptr<music_handler_base> { return std::make_shared<null_music_handler>(); };

	callbacks.get_msg_dialog                 = []() -> std::shared_ptr<MsgDialogBase> { return {}; };
	callbacks.get_osk_dialog                 = []() -> std::shared_ptr<OskDialogBase> { return {}; };
	callbacks.get_save_dialog                = []() -> std::unique_ptr<SaveDialogBase> { return {}; };
	callbacks.get_trophy_notification_dialog = []() -> std::unique_ptr<TrophyNotificationBase> { return {}; };

	callbacks.on_run    = [](bool) {};
	callbacks.on_pause  = []() {};
	callbacks.on_resume = []() {};
	callbacks.on_stop   = []() {};
	callbacks.on_ready  = []() {};

	callbacks.on_emulation_stop_no_response = [](std::shared_ptr<atomic_t<bool>> closed_successfully, int seconds_waiting_already)
	{
		if (!closed_successfully || !*closed_successfully)
		{
			chrysalis_boot_log.error("Emulation stop is taking too long (%d s)...", seconds_waiting_already);
		}
	};

	callbacks.on_save_state_progress = [](std::shared_ptr<atomic_t<bool>>, stx::shared_ptr<utils::serial>, stx::atomic_ptr<std::string>*, std::shared_ptr<void>) {};

	callbacks.enable_disc_eject  = [](bool) {};
	callbacks.enable_disc_insert = [](bool) {};
	callbacks.on_missing_fw = []() { chrysalis_boot_log.error("No PS3 firmware installed"); };
	callbacks.handle_taskbar_progress = [](s32, s32) {};

	callbacks.get_localized_string    = [](localized_string_id, const char*) -> std::string { return {}; };
	callbacks.get_localized_u32string = [](localized_string_id, const char*) -> std::u32string { return {}; };
	callbacks.get_localized_setting   = [](const cfg::_base*, u32) -> std::string { return {}; };

	callbacks.play_sound = [](const std::string&, std::optional<f32>) {};
	callbacks.add_breakpoint = [](u32) {};

	callbacks.display_sleep_control_supported = []() { return false; };
	callbacks.enable_display_sleep = [](bool) {};
	callbacks.check_microphone_permissions = []() {};
	callbacks.make_video_source = []() -> std::unique_ptr<video_source> { return nullptr; };

	// Image decoding upstream goes through Qt; return failure until the app
	// provides a BitmapFactory-backed implementation.
	callbacks.get_image_info = [](const std::string&, std::string&, s32&, s32&, s32&) -> bool { return false; };
	callbacks.get_scaled_image = [](const std::string&, s32, s32, s32&, s32&, u8*, bool) -> bool { return false; };

	Emu.SetCallbacks(std::move(callbacks));
}

std::string boot_game(const std::string& path)
{
	Emu.SetForceBoot(true);

	const game_boot_result result = Emu.BootGame(path, "", true);

	if (result != game_boot_result::no_errors)
	{
		return fmt::format("%s", result);
	}

	return {};
}

} // namespace chrysalis
