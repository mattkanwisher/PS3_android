// CellStation JNI bridge: thin marshalling layer between the Kotlin app and
// the unmodified RPCS3 core (rpcs3_emu). Modeled on upstream's
// headless_application/main_application callback wiring and the archived
// rpcs3-android alpha's JNI surface (see docs/PATCH-INVENTORY.md).
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
#include "Emu/vfs_config.h"
#include "Emu/IdManager.h"
#include "Emu/VFS.h"
#include "Emu/Io/Null/NullKeyboardHandler.h"
#include "Emu/Io/Null/NullMouseHandler.h"
#include "Emu/Io/KeyboardHandler.h"
#include "Emu/Io/MouseHandler.h"
#include "Emu/Io/Null/null_camera_handler.h"
#include "Emu/Io/Null/null_music_handler.h"
#include "Emu/Audio/AudioBackend.h"
#include "Emu/Audio/Null/NullAudioBackend.h"
#include "Emu/Audio/Null/null_enumerator.h"
#include "Emu/Audio/Cubeb/CubebBackend.h"
#include "Emu/Audio/Cubeb/cubeb_enumerator.h"
#include "Emu/RSX/GSFrameBase.h"
#include "Emu/RSX/Null/NullGSRender.h"
#if defined(HAVE_VULKAN)
#include "Emu/RSX/VK/VKGSRender.h"
#endif
#include "Emu/Cell/Modules/cellMsgDialog.h"
#include "Emu/Cell/Modules/cellOskDialog.h"
#include "Emu/Cell/Modules/cellSaveData.h"
#include "Emu/Cell/Modules/sceNpTrophy.h"
#include "Input/pad_thread.h"
#include "Loader/PUP.h"
#include "Loader/TAR.h"
#include "Crypto/unself.h"
#include "Crypto/key_vault.h"
#include "util/video_source.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <sys/resource.h>

#include <deque>
#include <mutex>
#include <condition_variable>

LOG_CHANNEL(cellstation_log, "CELLSTATION");

// Defined in Utilities/File.cpp for the Android embedder to fill in.
extern std::string g_android_executable_dir;
extern std::string g_android_config_dir;
extern std::string g_android_cache_dir;

[[noreturn]] void report_fatal_error(std::string_view text, bool /*is_html*/ = false, bool /*include_help_text*/ = true)
{
	__android_log_print(ANDROID_LOG_FATAL, "CellStation", "FATAL: %.*s", static_cast<int>(text.size()), text.data());
	std::abort();
}

namespace
{
	// ---- logcat sink -------------------------------------------------------
	class logcat_listener final : public logs::listener
	{
	public:
		void log(u64 /*stamp*/, const logs::message& msg, std::string_view prefix, std::string_view text) override
		{
			int prio = ANDROID_LOG_INFO;
			switch (static_cast<logs::level>(msg))
			{
			case logs::level::always:  prio = ANDROID_LOG_INFO; break;
			case logs::level::fatal:   prio = ANDROID_LOG_FATAL; break;
			case logs::level::error:   prio = ANDROID_LOG_ERROR; break;
			case logs::level::todo:    prio = ANDROID_LOG_WARN; break;
			case logs::level::success: prio = ANDROID_LOG_INFO; break;
			case logs::level::warning: prio = ANDROID_LOG_WARN; break;
			case logs::level::notice:  prio = ANDROID_LOG_DEBUG; break;
			case logs::level::trace:   prio = ANDROID_LOG_VERBOSE; break;
			}
			__android_log_print(prio, "RPCS3", "%.*s: %.*s",
				static_cast<int>(prefix.size()), prefix.data(),
				static_cast<int>(text.size()), text.data());
		}
	};

	logcat_listener g_logcat;

	// ---- "main thread" pump ------------------------------------------------
	// The core expects a main/UI thread it can queue work onto
	// (EmuCallbacks::call_from_main_thread). A dedicated Java thread calls
	// runMainLoop() which drains this queue forever.
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
	};

	main_pump g_pump;

	// ---- surface state -----------------------------------------------------
	atomic_t<ANativeWindow*> g_native_window{nullptr};

	// ---- GS frame over ANativeWindow --------------------------------------
	class android_gs_frame final : public GSFrameBase
	{
	public:
		void close() override {}
		void reset() override {}
		bool shown() override { return g_native_window != nullptr; }
		void hide() override {}
		void show() override {}
		void toggle_fullscreen() override {}

		void delete_context(draw_context_t) override {}
		draw_context_t make_context() override { return nullptr; }
		void set_current(draw_context_t) override {}
		void flip(draw_context_t, bool /*skip_frame*/) override {}

		int client_width() override
		{
			if (ANativeWindow* w = g_native_window)
				return ANativeWindow_getWidth(w);
			return 1280;
		}

		int client_height() override
		{
			if (ANativeWindow* w = g_native_window)
				return ANativeWindow_getHeight(w);
			return 720;
		}

		f64 client_display_rate() override { return 60.; }
		bool has_alpha() override { return false; }

		display_handle_t handle() const override
		{
			return display_handle_t{g_native_window.load()};
		}

		bool can_consume_frame() const override { return false; }
		void present_frame(std::vector<u8>&&, u32, u32, u32, bool) const override {}
		void take_screenshot(std::vector<u8>&&, u32, u32, bool) override {}
		void update_title(double) override {}
	};

	// ---- helpers -----------------------------------------------------------
	std::string jstr(JNIEnv* env, jstring s)
	{
		if (!s) return {};
		const char* c = env->GetStringUTFChars(s, nullptr);
		std::string out = c ? c : "";
		env->ReleaseStringUTFChars(s, c);
		return out;
	}

	void raise_rlimits()
	{
		rlimit lim{};
		lim.rlim_cur = RLIM_INFINITY;
		lim.rlim_max = RLIM_INFINITY;
		setrlimit(RLIMIT_MEMLOCK, &lim);
		lim.rlim_cur = 65536;
		lim.rlim_max = 65536;
		setrlimit(RLIMIT_NOFILE, &lim);
	}

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

		callbacks.init_gs_render = [](utils::serial* ar)
		{
			switch (const video_renderer type = g_cfg.video.renderer)
			{
			case video_renderer::null:
			{
				g_fxo->init<rsx::thread, named_thread<NullGSRender>>(ar);
				break;
			}
#if defined(HAVE_VULKAN)
			case video_renderer::vulkan:
			{
				g_fxo->init<rsx::thread, named_thread<VKGSRender>>(ar);
				break;
			}
#endif
			default:
			{
				fmt::throw_exception("Invalid video renderer: %s", type);
			}
			}
		};

		callbacks.get_gs_frame = []() -> std::unique_ptr<GSFrameBase>
		{
			return std::make_unique<android_gs_frame>();
		};
		callbacks.close_gs_frame = []() {};

		callbacks.get_camera_handler = []() -> std::shared_ptr<camera_handler_base> { return std::make_shared<null_camera_handler>(); };
		callbacks.get_music_handler = []() -> std::shared_ptr<music_handler_base> { return std::make_shared<null_music_handler>(); };

		callbacks.get_audio = []() -> std::shared_ptr<AudioBackend>
		{
			std::shared_ptr<AudioBackend> result;
			switch (g_cfg.audio.renderer.get())
			{
			case audio_renderer::null: result = std::make_shared<NullAudioBackend>(); break;
			default: result = std::make_shared<CubebBackend>(); break;
			}

			if (!result->Initialized())
			{
				cellstation_log.error("Audio backend %s failed to initialize, falling back to null output", result->GetName());
				result = std::make_shared<NullAudioBackend>();
			}
			return result;
		};

		callbacks.get_audio_enumerator = [](u64 renderer) -> std::shared_ptr<audio_device_enumerator>
		{
			switch (static_cast<audio_renderer>(renderer))
			{
			case audio_renderer::null: return std::make_shared<null_enumerator>();
			default: return std::make_shared<cubeb_enumerator>();
			}
		};

		callbacks.get_msg_dialog                 = []() -> std::shared_ptr<MsgDialogBase> { return std::shared_ptr<MsgDialogBase>(); };
		callbacks.get_osk_dialog                 = []() -> std::shared_ptr<OskDialogBase> { return std::shared_ptr<OskDialogBase>(); };
		callbacks.get_save_dialog                = []() -> std::unique_ptr<SaveDialogBase> { return std::unique_ptr<SaveDialogBase>(); };
		callbacks.get_trophy_notification_dialog = []() -> std::unique_ptr<TrophyNotificationBase> { return std::unique_ptr<TrophyNotificationBase>(); };

		callbacks.on_run    = [](bool) {};
		callbacks.on_pause  = []() {};
		callbacks.on_resume = []() {};
		callbacks.on_stop   = []() {};
		callbacks.on_ready  = []() {};
		callbacks.on_missing_fw = []() { cellstation_log.error("No PS3 firmware installed"); };

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

	// Lean port of main_window::HandlePupInstallation (no dialogs/progress UI).
	bool install_firmware(const std::string& path)
	{
		fs::file pup_f(path);
		if (!pup_f)
		{
			cellstation_log.error("Firmware install: cannot open '%s' (%s)", path, fs::g_tls_error);
			return false;
		}

		pup_object pup(std::move(pup_f));
		if (pup.operator pup_error() != pup_error::ok)
		{
			cellstation_log.error("Firmware install: invalid PUP: %s", pup.get_formatted_error());
			return false;
		}

		fs::file update_files_f = pup.get_file(0x300);
		if (!update_files_f || !update_files_f.size())
		{
			cellstation_log.error("Firmware install: missing update files database");
			return false;
		}

		tar_object update_files(update_files_f);
		auto update_filenames = update_files.get_filenames();
		update_filenames.erase(std::remove_if(update_filenames.begin(), update_filenames.end(),
			[](const std::string& s) { return s.find("dev_flash_") == umax; }), update_filenames.end());

		if (update_filenames.empty())
		{
			cellstation_log.error("Firmware install: no dev_flash_* packages found");
			return false;
		}

		// Used by tar_object::extract() as destination directory
		vfs::mount("/dev_flash", g_cfg_vfs.get_dev_flash());

		for (const auto& update_filename : update_filenames)
		{
			auto update_file_stream = update_files.get_file(update_filename);

			if (update_file_stream->m_file_handler)
			{
				// Forcefully read all the data
				update_file_stream->m_file_handler->handle_file_op(*update_file_stream, 0, update_file_stream->get_size(umax), nullptr);
			}

			fs::file update_file = fs::make_stream(std::move(update_file_stream->data));

			SCEDecrypter self_dec(update_file);
			self_dec.LoadHeaders();
			self_dec.LoadMetadata(SCEPKG_ERK, SCEPKG_RIV);
			self_dec.DecryptData();

			auto dev_flash_tar_f = self_dec.MakeFile();
			if (dev_flash_tar_f.size() < 3)
			{
				cellstation_log.error("Firmware install: PUP contents are invalid (%s)", update_filename);
				return false;
			}

			tar_object dev_flash_tar(dev_flash_tar_f[2]);
			if (!dev_flash_tar.extract())
			{
				cellstation_log.error("Firmware install: TAR extraction failed (%s)", update_filename);
				return false;
			}
		}

		cellstation_log.success("Firmware installed: %s", utils::get_firmware_version());
		return true;
	}
} // namespace

// ---- JNI surface (nu.hyperworks.cellstation.EmuBridge) ---------------------

extern "C"
{

JNIEXPORT jboolean JNICALL Java_nu_hyperworks_cellstation_EmuBridge_initialize(JNIEnv* env, jclass, jstring root_dir, jstring user)
{
	static bool s_initialized = false;
	if (s_initialized)
		return JNI_TRUE;

	const std::string root = jstr(env, root_dir);
	g_android_executable_dir = root;
	g_android_config_dir = root + "/config";
	g_android_cache_dir = root + "/cache";
	fs::create_path(g_android_config_dir);
	fs::create_path(g_android_cache_dir);

	raise_rlimits();

	logs::listener::add(&g_logcat);

	std::string usr = jstr(env, user);
	if (usr.empty())
		usr = "00000001";

	Emu.SetHasGui(false);
	Emu.SetUsr(usr);
	Emu.Init();

	init_callbacks();

	s_initialized = true;
	cellstation_log.success("CellStation core initialized (root=%s)", root);
	return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_runMainLoop(JNIEnv*, jclass)
{
	thread_ctrl::scoped_priority high_prio(+1);
	g_pump.run();
}

JNIEXPORT jboolean JNICALL Java_nu_hyperworks_cellstation_EmuBridge_installFirmware(JNIEnv*, jclass, jint fd)
{
	return install_firmware(fmt::format("/proc/self/fd/%d", fd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_nu_hyperworks_cellstation_EmuBridge_firmwareVersion(JNIEnv* env, jclass)
{
	return env->NewStringUTF(utils::get_firmware_version().c_str());
}

JNIEXPORT jint JNICALL Java_nu_hyperworks_cellstation_EmuBridge_boot(JNIEnv* env, jclass, jstring jpath)
{
	const std::string path = jstr(env, jpath);

	atomic_t<u32> done = 0;
	game_boot_result result = game_boot_result::generic_error;

	g_pump.push([&]()
	{
		result = Emu.BootGame(path, "", true);
		done = 1;
		done.notify_one();
	});

	done.wait(0);
	if (is_error(result))
	{
		cellstation_log.error("Boot failed for '%s': %s", path, result);
	}
	return static_cast<jint>(result);
}

JNIEXPORT jboolean JNICALL Java_nu_hyperworks_cellstation_EmuBridge_surfaceEvent(JNIEnv* env, jclass, jobject surface, jint event)
{
	if (event == 0 /* ready */ && surface)
	{
		ANativeWindow* w = ANativeWindow_fromSurface(env, surface);
		if (ANativeWindow* old = g_native_window.exchange(w))
			ANativeWindow_release(old);
		return JNI_TRUE;
	}

	// destroyed
	if (ANativeWindow* old = g_native_window.exchange(nullptr))
		ANativeWindow_release(old);
	return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_kill(JNIEnv*, jclass)
{
	g_pump.push([]() { Emu.Kill(false); });
}

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_pause(JNIEnv*, jclass)
{
	g_pump.push([]() { Emu.Pause(); });
}

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_resume(JNIEnv*, jclass)
{
	g_pump.push([]() { Emu.Resume(); });
}

JNIEXPORT jint JNICALL Java_nu_hyperworks_cellstation_EmuBridge_getState(JNIEnv*, jclass)
{
	return static_cast<jint>(Emu.GetStatus(false));
}

JNIEXPORT jstring JNICALL Java_nu_hyperworks_cellstation_EmuBridge_getVersion(JNIEnv* env, jclass)
{
	return env->NewStringUTF(rpcs3::get_verbose_version().c_str());
}

} // extern "C"
