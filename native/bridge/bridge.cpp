// CellStation JNI bridge: thin marshalling layer between the Kotlin app and
// the unmodified RPCS3 core (rpcs3_emu). Modeled on upstream's
// headless_application/main_application callback wiring and the archived
// rpcs3-android alpha's JNI surface (see docs/PATCH-INVENTORY.md).
//
// CellStation is based on RPCS3 (GPL-2.0), (c) RPCS3 team and contributors.

#include "stdafx.h"
#include "util/types.hpp"
#include "util/logs.hpp"
#include "util/asm.hpp"
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
#include "android_pad_handler.h"
#include "Loader/ISO.h"
#include "Loader/PUP.h"
#include "Loader/TAR.h"
#include "Crypto/unself.h"
#include "Crypto/key_vault.h"
#include "util/video_source.h"
#include "rpcs3_version.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <sys/resource.h>

#include <deque>

#include <mutex>
#include <condition_variable>

#include "Emu/Io/pad_config.h"
#include "Input/mouse_gyro_handler.h"

LOG_CHANNEL(cellstation_log, "CELLSTATION");

namespace chrysalis { std::string describe_current_exception(); } // fatal_report.cpp

// mouse_gyro_handler's implementation lives in a Qt translation unit upstream
// (QEvent-driven desktop feature). pad_thread only needs these two members;
// mouse-based gyro stays inert on Android.
void mouse_gyro_handler::set_enabled(bool enabled)
{
	m_enabled = enabled;
}

void mouse_gyro_handler::apply_gyro(const std::shared_ptr<Pad>&)
{
}

// Upstream implements this in the Qt layer (main_window.cpp) with a
// QEventLoop on the GUI thread; emucore calls it to poll a condition.
// This mirrors upstream's own non-GUI-thread branch.
void qt_events_aware_op(int repeat_duration_ms, std::function<bool()> wrapped_op)
{
	ensure(wrapped_op);

	while (!wrapped_op())
	{
		if (repeat_duration_ms == 0)
		{
			std::this_thread::yield();
		}
		else if (thread_ctrl::get_current())
		{
			thread_ctrl::wait_for(repeat_duration_ms * 1000);
		}
		else
		{
			std::this_thread::sleep_for(std::chrono::milliseconds(repeat_duration_ms));
		}
	}
}

// Defaults cached by Emu.Init() (Emu/System.cpp), as used by rpcs3qt.
extern std::string g_cfg_defaults;

// Defined in Utilities/File.cpp for the Android embedder to fill in.
extern std::string g_android_executable_dir;
extern std::string g_android_config_dir;
extern std::string g_android_cache_dir;

// Upstream defines these in Qt-side translation units (rpcs3.cpp,
// rpcs3qt/pad_settings_dialog.cpp); the emucore/Input objects reference them.
std::string g_input_config_override;
cfg_input_configurations g_cfg_input_configs;

// RtMidi's Android backend calls JNI_GetCreatedJavaVMs, which is not a public
// NDK export. We know our VM from JNI_OnLoad, so provide the symbol here.
static JavaVM* g_java_vm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
	g_java_vm = vm;
	return JNI_VERSION_1_6;
}

extern "C" jint JNI_GetCreatedJavaVMs(JavaVM** vm_buf, jsize buf_len, jsize* n_vms)
{
	jsize count = 0;
	if (g_java_vm && buf_len > 0)
	{
		vm_buf[0] = g_java_vm;
		count = 1;
	}
	if (n_vms)
		*n_vms = count;
	return JNI_OK;
}

[[noreturn]] void report_fatal_error(std::string_view text, bool /*is_html*/ = false, bool /*include_help_text*/ = true)
{
	__android_log_print(ANDROID_LOG_FATAL, "CellStation", "FATAL: %.*s", static_cast<int>(text.size()), text.data());

	// The core routes std::terminate here, so an unhandled exception is still
	// active and carries the only description of what actually went wrong.
	// Without this the crash reads as a bare "abnormally terminated".
	if (const std::string what = chrysalis::describe_current_exception(); !what.empty())
	{
		__android_log_print(ANDROID_LOG_FATAL, "CellStation", "FATAL: unhandled exception -> %s", what.c_str());
	}

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

		// Every std::function member must be callable: the tree builds with
		// -fno-exceptions, so an empty one aborts via bad_function_call.
		callbacks.update_emu_settings = []() {};
		callbacks.save_emu_settings = []() { Emulator::SaveSettings(g_cfg.to_string(), Emu.GetTitleID()); };
		callbacks.get_sendmessage_dialog = []() -> std::shared_ptr<SendMessageDialogBase> { return {}; };
		callbacks.get_recvmessage_dialog = []() -> std::shared_ptr<RecvMessageDialogBase> { return {}; };
		callbacks.get_photo_path = [](std::string_view) -> std::string { return {}; };
		callbacks.get_image_info = [](const std::string&, std::string&, s32&, s32&, s32&) -> bool { return false; };
		callbacks.get_scaled_image = [](const std::string&, s32, s32, s32&, s32&, u8*, bool) -> bool { return false; };
		callbacks.get_font_dirs = []() -> std::vector<std::string> { return {}; };
		callbacks.on_install_pkgs = [](const std::vector<std::string>&) { return false; };
		callbacks.enable_gamemode = [](bool) {};
		callbacks.get_database_config = [](const std::string&) -> std::string { return {}; };

		Emu.SetCallbacks(std::move(callbacks));
	}

	// ---- global config (config.yml) ----------------------------------------
	// Settings the app exposes are read-modify-written on a scratch cfg_root
	// (upstream does the same in rpcs3qt/emu_settings.cpp) instead of on g_cfg:
	// the live config is reset by Emu.Init(), reloaded from disk by every
	// Emu.Load(), and read by the RSX thread each frame — so editing it in
	// place would either be silently discarded or write a defaults-derived
	// file over the user's config.yml.
	std::string global_config_path()
	{
		return fs::get_config_dir(true) + "config.yml";
	}

	std::unique_ptr<cfg_root> load_global_config()
	{
		auto cfg = std::make_unique<cfg_root>();

		// Emu.Init() caches the defaults *after* picking the default renderer;
		// start from those so keys missing from the file keep that choice.
		if (!g_cfg_defaults.empty())
		{
			cfg->from_string(g_cfg_defaults);
		}

		if (const fs::file file{global_config_path()})
		{
			if (!cfg->from_string(file.to_string()))
			{
				cellstation_log.error("Failed to parse %s, falling back to defaults", global_config_path());
			}
		}

		return cfg;
	}

	void save_global_config(const cfg_root& cfg)
	{
		Emulator::SaveSettings(cfg.to_string(), {});
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

namespace chrysalis
{
	bool vk_dispatch_init(const char* custom_driver_dir, const char* custom_driver_name,
		const char* hook_lib_dir, const char* tmp_lib_dir);
}

namespace
{
	// Custom GPU driver selection, stashed by setGpuDriver() until initialize()
	// wires the Vulkan dispatch (driver changes need a process restart).
	std::string g_gpu_driver_dir, g_gpu_driver_name, g_gpu_hook_dir, g_gpu_tmp_dir;
}

// ---- JNI surface (nu.hyperworks.cellstation.EmuBridge) ---------------------

extern "C"
{

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_setGpuDriver(
	JNIEnv* env, jclass, jstring dir, jstring name, jstring hook_dir, jstring tmp_dir)
{
	g_gpu_driver_dir = dir ? jstr(env, dir) : std::string();
	g_gpu_driver_name = name ? jstr(env, name) : std::string();
	g_gpu_hook_dir = hook_dir ? jstr(env, hook_dir) : std::string();
	g_gpu_tmp_dir = tmp_dir ? jstr(env, tmp_dir) : std::string();
}

JNIEXPORT jboolean JNICALL Java_nu_hyperworks_cellstation_EmuBridge_initialize(JNIEnv* env, jclass, jstring root_dir, jstring user)
{
	static bool s_initialized = false;
	if (s_initialized)
		return JNI_TRUE;

	// Before any Vulkan call: route the core's Vulkan imports through either
	// the system loader or an adrenotools-hooked one (custom driver).
	chrysalis::vk_dispatch_init(
		g_gpu_driver_dir.empty() ? nullptr : g_gpu_driver_dir.c_str(),
		g_gpu_driver_name.empty() ? nullptr : g_gpu_driver_name.c_str(),
		g_gpu_hook_dir.empty() ? nullptr : g_gpu_hook_dir.c_str(),
		g_gpu_tmp_dir.empty() ? nullptr : g_gpu_tmp_dir.c_str());

	const std::string root = jstr(env, root_dir);
	// Trailing slashes are load-bearing: fs::get_config_dir()/get_cache_dir()
	// return these verbatim and the core appends names directly
	// ("configdev_hdd0/" vs "config/dev_hdd0/").
	g_android_executable_dir = root;
	g_android_config_dir = root + "/config/";
	g_android_cache_dir = root + "/cache/";
	fs::create_path(g_android_config_dir);
	fs::create_path(g_android_cache_dir);

	raise_rlimits();

	logs::listener::add(&g_logcat);

	std::string usr = jstr(env, user);
	if (usr.empty())
		usr = "00000001";

	Emu.SetHasGui(false);
	Emu.SetUsr(usr);
	// Without this the core's supported-renderer list stays at its {null}
	// default and System.cpp forces any Vulkan config back to Null.
	Emu.SetSupportedRenderers({
		video_renderer::null,
#if defined(HAVE_VULKAN)
		video_renderer::vulkan,
#endif
	});

	// Fresh installs get their config.yml from these defaults, so a Null
	// default means every first-run boots to a black screen. Mirror
	// main_application: enumerate Vulkan and make it the default when a
	// device is present (mandatory on the Snapdragon targets).
	Emu.SetDefaultRenderer(video_renderer::null);
#if defined(HAVE_VULKAN)
	{
		vk::instance vulkan_probe;
		if (vulkan_probe.create("CellStation", true))
		{
			vulkan_probe.bind();
			if (const auto& gpus = vulkan_probe.enumerate_devices(); !gpus.empty())
			{
				const std::string adapter = gpus[0].get_name();
				cellstation_log.notice("Default renderer: Vulkan ('%s')", adapter);
				Emu.SetDefaultRenderer(video_renderer::vulkan);
				Emu.SetDefaultGraphicsAdapter(adapter);
			}
		}
	}
#endif
	// Emu.Init() writes config.yml when it is missing, so a first run has to be
	// detected before it, not after.
	const bool fresh_config = !fs::is_file(fs::get_config_dir(true) + "config.yml");

	Emu.Init();

	// Android-appropriate defaults, applied only to a fresh config so a user's
	// own settings are never overwritten.
	//
	// llvm_threads=0 means "one compiler per core". Each PPU LLVM worker holds
	// a whole module (thousands of functions in a big title), so on an 8-core
	// phone that spikes past what malloc can map and aborts mid-compile —
	// heavy titles never finish their first boot. Two workers keep the compile
	// parallel without the spike.
	if (fresh_config)
	{
		g_cfg.core.llvm_threads.set(2);
		Emulator::SaveSettings(g_cfg.to_string(), {});
		cellstation_log.notice("Fresh config: Max LLVM Compile Threads defaulted to 2");
	}

#ifdef ARCH_ARM64
	// Scale busy-wait budgets to the (usually 19.2MHz) arm generic timer,
	// as upstream's main() does.
	utils::init_arm_timer_scale();
#endif

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
	// Note: reopening a SAF/content-provider fd via /proc/self/fd fails with
	// EACCES on scoped-storage FUSE mounts; prefer installFirmwarePath with a
	// file the app itself can open (e.g. a copy in cacheDir).
	return install_firmware(fmt::format("/proc/self/fd/%d", fd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_nu_hyperworks_cellstation_EmuBridge_installFirmwarePath(JNIEnv* env, jclass, jstring jpath)
{
	return install_firmware(jstr(env, jpath)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_nu_hyperworks_cellstation_EmuBridge_firmwareVersion(JNIEnv* env, jclass)
{
	return env->NewStringUTF(utils::get_firmware_version().c_str());
}

// Deletes a game's compiled caches: rpcs3 keys them by TITLE_ID under
// <cache>/cache/<serial>/ (PPU LLVM object cache, SPU cache, shader cache).
// Deleting makes the next boot recompile from scratch — the fix for a cache
// poisoned by a crash mid-compile, or one built by an older core.
// Returns the number of bytes freed.
JNIEXPORT jlong JNICALL Java_nu_hyperworks_cellstation_EmuBridge_clearGameCache(JNIEnv* env, jclass, jstring jserial)
{
	const std::string serial = jstr(env, jserial);

	// Empty serial would resolve to the whole cache root; refuse rather than
	// wiping every game's caches from a per-game action.
	if (serial.empty() || serial.find('/') != std::string::npos || !Emu.IsStopped())
		return 0;

	const std::string dir = rpcs3::utils::get_cache_dir() + serial + "/";
	if (!fs::is_dir(dir))
		return 0;

	u64 freed = 0;
	for (const auto& entry : fs::dir(dir))
	{
		if (!entry.is_directory)
		{
			freed += entry.size;
			continue;
		}
		if (entry.name != "." && entry.name != "..")
		{
			for (const auto& sub : fs::dir(dir + entry.name))
			{
				if (!sub.is_directory) freed += sub.size;
			}
		}
	}

	if (!fs::remove_all(dir, true))
	{
		cellstation_log.error("Failed to clear cache for %s", serial);
		return 0;
	}

	cellstation_log.success("Cleared %s cache (%lluK)", serial, freed / 1024);
	return static_cast<jlong>(freed);
}

// Pulls PARAM.SFO / ICON0.PNG out of a disc image into out_dir using the
// core's own ISO reader, so the library can show real titles and tile art
// for .iso games. Uses the same virtual-device slot as booting an ISO does,
// hence the stopped-emulator guard.
JNIEXPORT jboolean JNICALL Java_nu_hyperworks_cellstation_EmuBridge_extractIsoAssets(JNIEnv* env, jclass, jstring jiso, jstring jout)
{
	const std::string iso = jstr(env, jiso);
	const std::string out = jstr(env, jout);

	if (!Emu.IsStopped() || !is_iso_file(iso))
		return JNI_FALSE;

	fs::set_virtual_device("iso_overlay_fs_dev", stx::make_shared<iso_device>(iso));

	bool any = false;
	for (const char* name : {"PS3_GAME/PARAM.SFO", "PS3_GAME/ICON0.PNG"})
	{
		fs::file src(iso_device::virtual_device_name + "/" + name);
		if (!src)
			continue;

		const std::string base = std::string(name).substr(std::string(name).find('/') + 1);
		if (fs::file dst(out + "/" + base, fs::rewrite); dst)
		{
			dst.write(src.to_vector<u8>());
			any = true;
		}
	}

	fs::set_virtual_device("iso_overlay_fs_dev", stx::shared_ptr<iso_device>());
	return any ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_nu_hyperworks_cellstation_EmuBridge_boot(JNIEnv* env, jclass, jstring jpath)
{
	const std::string path = jstr(env, jpath);

	// Homebrew boots without installed PS3 firmware: force HLE for every
	// firmware module so Emulator::Load's LLE check does not demand
	// /dev_flash (mirrors native/host/main.cpp). Persisted via SaveSettings
	// because Load() re-reads config.yml. Symmetric: we restore the default
	// only if the config still equals exactly the set we wrote, so a user's
	// hand-tuned library list is never touched.
	{
		extern const std::map<std::string_view, int> g_prx_list;
		std::set<std::string> hle_all;
		for (const auto& [name, flag] : g_prx_list)
		{
			hle_all.emplace(std::string(name) + ":hle");
		}

		const bool no_fw = utils::get_firmware_version().empty();

		auto cfg = load_global_config();
		const bool is_hle_all = cfg->core.libraries_control.get_set() == hle_all;

		if (no_fw && !is_hle_all)
		{
			cellstation_log.notice("No firmware installed: forcing HLE firmware modules for homebrew boot");
			cfg->core.libraries_control.set_set(std::move(hle_all));
			save_global_config(*cfg);
		}
		else if (!no_fw && is_hle_all)
		{
			cellstation_log.notice("Firmware present: restoring default firmware library mode");
			cfg->core.libraries_control.set_set({});
			save_global_config(*cfg);
		}
	}

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
	else
	{
		// Stable marker asserted by ci/integration-test.sh - keep in sync.
		cellstation_log.success("Boot OK: %s", path);
	}
	return static_cast<jint>(result);
}

JNIEXPORT jboolean JNICALL Java_nu_hyperworks_cellstation_EmuBridge_stretchToDisplayArea(JNIEnv*, jclass)
{
	return load_global_config()->video.stretch_to_display_area.get() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_setStretchToDisplayArea(JNIEnv*, jclass, jboolean enabled, jboolean persist)
{
	const bool value = enabled == JNI_TRUE;

	if (persist == JNI_TRUE)
	{
		auto cfg = load_global_config();
		cfg->video.stretch_to_display_area.set(value);
		save_global_config(*cfg);
	}

	// "Stretch To Display Area" is a dynamic setting — VK/GLPresent re-read it
	// on every flip — so assigning it here applies mid-game, and lets a
	// per-launch override take effect without touching the persisted value.
	g_cfg.video.stretch_to_display_area.set(value);

	cellstation_log.notice("Stretch to display area: %s (persisted: %s)", value, persist == JNI_TRUE);
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

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_setPadState(JNIEnv* env, jclass, jbyteArray jvalues)
{
	if (!jvalues) return;

	chrysalis::android_pad_state state{};
	state.connected = true;

	const jsize len = std::min<jsize>(env->GetArrayLength(jvalues), static_cast<jsize>(state.values.size()));
	env->GetByteArrayRegion(jvalues, 0, len, reinterpret_cast<jbyte*>(state.values.data()));

	chrysalis::set_android_pad_state(state);

}

JNIEXPORT void JNICALL Java_nu_hyperworks_cellstation_EmuBridge_setPadConnected(JNIEnv*, jclass, jboolean connected)
{
	chrysalis::set_android_pad_connected(connected == JNI_TRUE);
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
