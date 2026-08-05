// CellStation host boot-runner: boots a homebrew ELF on the unmodified rpcs3
// core, natively on the build machine, and reports whether the guest reached
// the running state. Used by local and CI integration tests with the RPCS3
// team's own GPL test ELFs (rpcs3/bin/test) — never commercial content.
//
// Callback wiring mirrors native/bridge/bridge.cpp (the JNI bridge), which in
// turn mirrors upstream's headless_application.
//
// CellStation is based on RPCS3 (GPL-2.0), (c) RPCS3 team and contributors.

#include "stdafx.h"
#include "util/types.hpp"
#include "util/logs.hpp"
#include "Utilities/Thread.h"
#include "Utilities/File.h"
#include "Emu/System.h"
#include "Emu/system_config.h"
#include "Emu/system_utils.hpp"
#include "Emu/IdManager.h"
#include "Emu/VFS.h"
#include "Emu/Io/Null/NullKeyboardHandler.h"
#include "Emu/Io/Null/NullMouseHandler.h"
#include "Emu/Io/KeyboardHandler.h"
#include "Emu/Io/MouseHandler.h"
#include "Emu/Io/Null/null_camera_handler.h"
#include "Emu/Io/Null/null_music_handler.h"
#include "Emu/Io/pad_config.h"
#include "Emu/Audio/AudioBackend.h"
#include "Emu/Audio/Null/NullAudioBackend.h"
#include "Emu/Audio/Null/null_enumerator.h"
#include "Emu/RSX/GSFrameBase.h"
#include "Emu/RSX/Null/NullGSRender.h"
#include "Emu/Cell/Modules/cellMsgDialog.h"
#include "Emu/Cell/Modules/cellOskDialog.h"
#include "Emu/Cell/Modules/cellSaveData.h"
#include "Emu/Cell/Modules/sceNpTrophy.h"
#include "Input/pad_thread.h"
#include "Input/mouse_gyro_handler.h"
#include "util/video_source.h"
#include "rpcs3_version.h"

#include <cstdio>
#include <cstdlib>
#include <deque>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <chrono>

LOG_CHANNEL(host_log, "HOSTRUN");

// Upstream defines these in Qt translation units; same shims as the bridge.
std::string g_input_config_override;
cfg_input_configurations g_cfg_input_configs;

void mouse_gyro_handler::set_enabled(bool enabled) { m_enabled = enabled; }
void mouse_gyro_handler::apply_gyro(const std::shared_ptr<Pad>&) {}

// The Input TUs build in their Android flavor (see CMakeLists), whose hid
// enumeration wraps USB fds via hidapi's libusb backend. On the host the
// Android USB device list is always empty, so this is never reached; the
// hidraw-backend hidapi we link just needs the symbol to exist.
extern "C" void* hid_libusb_wrap_sys_device(intptr_t, int) { return nullptr; }

void qt_events_aware_op(int repeat_duration_ms, std::function<bool()> wrapped_op)
{
	ensure(wrapped_op);
	while (!wrapped_op())
	{
		if (repeat_duration_ms == 0)
			std::this_thread::yield();
		else if (thread_ctrl::get_current())
			thread_ctrl::wait_for(repeat_duration_ms * 1000);
		else
			std::this_thread::sleep_for(std::chrono::milliseconds(repeat_duration_ms));
	}
}

[[noreturn]] void report_fatal_error(std::string_view text, bool /*is_html*/ = false, bool /*include_help_text*/ = true)
{
	std::fprintf(stderr, "HOST-RUNNER FATAL: %.*s\n", static_cast<int>(text.size()), text.data());
	std::fflush(nullptr);
	std::abort();
}

namespace
{
	class stdout_listener final : public logs::listener
	{
	public:
		void log(u64 /*stamp*/, const logs::message& msg, std::string_view prefix, std::string_view text) override
		{
			if (static_cast<logs::level>(msg) > logs::level::notice)
				return; // skip trace spam; keep always..notice
			std::printf("[%.*s] %.*s\n",
				static_cast<int>(prefix.size()), prefix.data(),
				static_cast<int>(text.size()), text.data());
			std::fflush(stdout);
		}
	};

	stdout_listener g_stdout_log;

	struct main_pump
	{
		std::mutex mtx;
		std::condition_variable cv;
		std::deque<std::function<void()>> q;
		atomic_t<bool> quit{false};

		void push(std::function<void()> f)
		{
			{
				std::lock_guard lock(mtx);
				q.emplace_back(std::move(f));
			}
			cv.notify_one();
		}

		void run()
		{
			for (;;)
			{
				std::function<void()> f;
				{
					std::unique_lock lock(mtx);
					cv.wait(lock, [this] { return quit || !q.empty(); });
					if (quit && q.empty())
						return;
					f = std::move(q.front());
					q.pop_front();
				}
				f();
			}
		}

		void stop()
		{
			quit = true;
			cv.notify_one();
		}
	};

	main_pump g_pump;

	void init_callbacks()
	{
		EmuCallbacks callbacks{};

		callbacks.call_from_main_thread = [](std::function<void()> func, atomic_t<u32>* wake_up)
		{
			g_pump.push([func = std::move(func), wake_up]()
			{
				func();
				if (wake_up)
				{
					*wake_up = true;
					wake_up->notify_one();
				}
			});
		};

		callbacks.try_to_quit = [](bool force_quit, std::function<void()> on_exit) -> bool
		{
			if (force_quit)
			{
				if (on_exit)
					on_exit();
				return true;
			}
			return false;
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
			while (!pad::g_started && !Emu.IsStopped())
			{
				std::this_thread::sleep_for(std::chrono::milliseconds(1));
			}
		};

		// Boot tests always use the Null renderer, regardless of config.
		callbacks.init_gs_render = [](utils::serial* ar)
		{
			g_fxo->init<rsx::thread, named_thread<NullGSRender>>(ar);
		};

		callbacks.get_gs_frame = []() -> std::unique_ptr<GSFrameBase> { return {}; };
		callbacks.close_gs_frame = []() {};

		callbacks.get_camera_handler = []() -> std::shared_ptr<camera_handler_base> { return std::make_shared<null_camera_handler>(); };
		callbacks.get_music_handler = []() -> std::shared_ptr<music_handler_base> { return std::make_shared<null_music_handler>(); };

		callbacks.get_audio = []() -> std::shared_ptr<AudioBackend> { return std::make_shared<NullAudioBackend>(); };
		callbacks.get_audio_enumerator = [](u64) -> std::shared_ptr<audio_device_enumerator> { return std::make_shared<null_enumerator>(); };

		callbacks.get_msg_dialog                 = []() -> std::shared_ptr<MsgDialogBase> { return std::shared_ptr<MsgDialogBase>(); };
		callbacks.get_osk_dialog                 = []() -> std::shared_ptr<OskDialogBase> { return std::shared_ptr<OskDialogBase>(); };
		callbacks.get_save_dialog                = []() -> std::unique_ptr<SaveDialogBase> { return std::unique_ptr<SaveDialogBase>(); };
		callbacks.get_trophy_notification_dialog = []() -> std::unique_ptr<TrophyNotificationBase> { return std::unique_ptr<TrophyNotificationBase>(); };

		callbacks.on_run    = [](bool) { host_log.success("HOST-RUNNER: on_run (guest started)"); };
		callbacks.on_pause  = []() {};
		callbacks.on_resume = []() {};
		callbacks.on_stop   = []() {};
		callbacks.on_ready  = []() {};
		callbacks.on_missing_fw = []() { host_log.error("HOST-RUNNER: missing firmware"); };

		callbacks.on_emulation_stop_no_response = [](std::shared_ptr<atomic_t<bool>> closed_successfully, int)
		{
			if (!closed_successfully || !*closed_successfully)
			{
				report_fatal_error("Stopping the emulator took too long (deadlock?)");
			}
		};

		callbacks.on_save_state_progress = [](std::shared_ptr<atomic_t<bool>>, stx::shared_ptr<utils::serial>, stx::atomic_ptr<std::string>*, std::shared_ptr<void>) {};

		callbacks.enable_disc_eject  = [](bool) {};
		callbacks.enable_disc_insert = [](bool) {};
		callbacks.handle_taskbar_progress = [](s32, s32) {};

		callbacks.get_localized_string    = [](localized_string_id, const char*) -> std::string { return {}; };
		callbacks.get_localized_u32string = [](localized_string_id, const char*) -> std::u32string { return {}; };
		callbacks.get_localized_setting   = [](const cfg::_base*, u32) -> std::string { return {}; };

		callbacks.play_sound = [](const std::string&, std::optional<f32>) {};
		callbacks.add_breakpoint = [](u32) {};
		callbacks.display_sleep_control_supported = []() { return false; };
		callbacks.enable_display_sleep = [](bool) {};
		callbacks.check_microphone_permissions = []() {};
		callbacks.make_video_source = []() { return nullptr; };

		Emu.SetCallbacks(std::move(callbacks));
	}
} // namespace

int main(int argc, char** argv)
{
	if (argc < 2)
	{
		std::fprintf(stderr, "usage: %s <homebrew.elf> [run_seconds]\n", argv[0]);
		return 2;
	}

	const std::string boot_path = argv[1];
	const int run_seconds = argc > 2 ? std::atoi(argv[2]) : 20;

	std::printf("HOST-RUNNER: %s\n", rpcs3::get_verbose_version().c_str());
	std::printf("HOST-RUNNER: booting %s (grace %ds)\n", boot_path.c_str(), run_seconds);

	logs::listener::add(&g_stdout_log);

	Emu.SetHasGui(false);
	Emu.SetUsr("00000001");
	Emu.Init();
	init_callbacks();

	// Boot on the pump like the Android bridge does; main() drains the pump.
	atomic_t<u32> boot_done = 0;
	game_boot_result boot_result = game_boot_result::generic_error;

	g_pump.push([&]()
	{
		boot_result = Emu.BootGame(boot_path, "", true);
		boot_done = 1;
	});

	std::thread watcher([&]()
	{
		using clock = std::chrono::steady_clock;
		const auto start = clock::now();
		const auto boot_deadline = start + std::chrono::seconds(120);

		bool ran = false;
		auto running_since = clock::now();

		while (clock::now() < boot_deadline + std::chrono::seconds(run_seconds))
		{
			const system_state state = Emu.GetStatus(false);

			if (boot_done && is_error(boot_result))
			{
				std::printf("HOST-RUNNER: BOOT-FAILED (%d)\n", static_cast<int>(boot_result));
				break;
			}

			if (state == system_state::running && !ran)
			{
				ran = true;
				running_since = clock::now();
				std::printf("HOST-RUNNER: STATE-RUNNING\n");
			}

			if (ran && clock::now() - running_since > std::chrono::seconds(run_seconds))
			{
				std::printf("HOST-RUNNER: BOOT-OK (ran %ds)\n", run_seconds);
				break;
			}

			if (ran && (state == system_state::stopped || state == system_state::stopping))
			{
				// Some test ELFs exit on their own - that still proves the boot.
				std::printf("HOST-RUNNER: BOOT-OK (guest exited)\n");
				break;
			}

			std::this_thread::sleep_for(std::chrono::milliseconds(250));
		}

		if (!ran && !(boot_done && is_error(boot_result)))
		{
			std::printf("HOST-RUNNER: BOOT-TIMEOUT\n");
		}

		std::fflush(stdout);

		g_pump.push([]() { Emu.Kill(false); });
		std::this_thread::sleep_for(std::chrono::seconds(5));
		g_pump.stop();
	});

	g_pump.run();
	watcher.join();

	std::fflush(nullptr);
	// The watcher printed the verdict; grep decides in the harness. Return 0
	// unless the process itself failed catastrophically (abort would apply).
	return 0;
}
