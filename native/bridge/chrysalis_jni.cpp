// JNI bridge between nu.hyperworks.chrysalis.NativeCore and the rpcs3 core.
//
// Firmware installation is a Qt-free port of main_window::HandlePupInstallation
// (rpcs3qt/main_window.cpp); the sequence of core calls is kept identical so it
// can be diffed against upstream when the submodule is bumped.

#include "stdafx.h"

#include "Crypto/unself.h"
#include "Emu/System.h"
#include "Emu/VFS.h"
#include "Emu/vfs_config.h"
#include "Loader/PUP.h"
#include "Loader/TAR.h"
#include "Utilities/File.h"
#include "util/logs.hpp"
#include "util/sysinfo.hpp"

#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <string>

LOG_CHANNEL(chrysalis_log, "CHRYSALIS");

// Upstream's Android path hooks (Utilities/File.cpp): on ANDROID,
// fs::get_config_dir/get_cache_dir/get_executable_dir return these verbatim.
extern std::string g_android_executable_dir;
extern std::string g_android_config_dir;
extern std::string g_android_cache_dir;

namespace
{

// Forward every core log message to logcat so `adb logcat -s rpcs3` works.
struct logcat_listener final : logs::listener
{
	void log(u64 /*stamp*/, const logs::message& msg, std::string_view prefix, std::string_view text) override
	{
		int prio = ANDROID_LOG_INFO;
		switch (static_cast<logs::level>(msg))
		{
		case logs::level::always:
		case logs::level::fatal:   prio = ANDROID_LOG_FATAL; break;
		case logs::level::error:   prio = ANDROID_LOG_ERROR; break;
		case logs::level::todo:
		case logs::level::warning: prio = ANDROID_LOG_WARN; break;
		case logs::level::success:
		case logs::level::notice:  prio = ANDROID_LOG_INFO; break;
		case logs::level::trace:   prio = ANDROID_LOG_VERBOSE; break;
		}

		if (prefix.empty())
			__android_log_print(prio, "rpcs3", "%.*s", static_cast<int>(text.size()), text.data());
		else
			__android_log_print(prio, "rpcs3", "{%.*s} %.*s", static_cast<int>(prefix.size()), prefix.data(), static_cast<int>(text.size()), text.data());
	}
};

std::string to_std(JNIEnv* env, jstring s)
{
	if (!s) return {};
	const char* c = env->GetStringUTFChars(s, nullptr);
	std::string r = c ? c : "";
	env->ReleaseStringUTFChars(s, c);
	return r;
}

jstring to_java(JNIEnv* env, const std::string& s)
{
	return env->NewStringUTF(s.c_str());
}

// Returns an empty string on success, or a human-readable error.
std::string install_firmware(const std::string& path)
{
	if (path.empty())
	{
		return "The provided path is empty.";
	}

	fs::file pup_f(path);
	if (!pup_f)
	{
		chrysalis_log.error("Error opening PUP file %s (%s)", path, fs::g_tls_error);
		return "The selected firmware file couldn't be opened.";
	}

	pup_object pup(std::move(pup_f));

	switch (pup.operator pup_error())
	{
	case pup_error::header_read: return "The provided file is empty.";
	case pup_error::header_magic: return "The provided file is not a PUP file.";
	case pup_error::expected_size: return "The provided file is incomplete. Try redownloading it.";
	case pup_error::header_file_count:
	case pup_error::file_entries:
	case pup_error::stream: return "The provided file is corrupted: " + pup.get_formatted_error();
	case pup_error::hash_mismatch: return "The provided file's contents are corrupted (hash check failed).";
	case pup_error::ok: break;
	}

	fs::file update_files_f = pup.get_file(0x300);
	if (!update_files_f || !update_files_f.size())
	{
		return "Couldn't find the installation packages database.";
	}

	std::string version_string;
	if (fs::file version = pup.get_file(0x100))
	{
		version_string = version.to_string();
	}
	if (const usz version_pos = version_string.find('\n'); version_pos != umax)
	{
		version_string.erase(version_pos);
	}
	chrysalis_log.notice("Installing firmware version '%s' from '%s'", version_string, path);

	tar_object update_files(update_files_f);

	auto update_filenames = update_files.get_filenames();
	update_filenames.erase(std::remove_if(
		update_filenames.begin(), update_filenames.end(), [](const std::string& s) { return s.find("dev_flash_") == umax; }),
		update_filenames.end());

	if (update_filenames.empty())
	{
		return "No dev_flash_* packages were found in the file.";
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
			chrysalis_log.error("Error while installing firmware: PUP contents are invalid (package=%s)", update_filename);
			return "Firmware could not be decompressed.";
		}

		tar_object dev_flash_tar(dev_flash_tar_f[2]);
		if (!dev_flash_tar.extract())
		{
			chrysalis_log.error("Error while installing firmware: TAR contents are invalid (package=%s)", update_filename);
			return "The firmware contents could not be extracted.";
		}

		chrysalis_log.notice("Installed firmware package %s", update_filename);
	}

	chrysalis_log.success("Successfully installed PS3 firmware version %s", version_string);
	return {};
}

} // namespace

extern JavaVM* g_chrysalis_vm; // core_compat.cpp

extern "C"
{

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
	g_chrysalis_vm = vm;
	return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_nu_hyperworks_chrysalis_NativeCore_init(JNIEnv* env, jclass, jstring data_dir)
{
	const std::string dir = to_std(env, data_dir);

	// The core resolves every path from these globals on Android; they must be
	// set before any other core call. Trailing slash matters: the core appends
	// names directly.
	g_android_executable_dir = dir;
	g_android_config_dir = dir + "/config/";
	g_android_cache_dir = dir + "/cache/";
	fs::create_path(g_android_config_dir);
	fs::create_path(g_android_cache_dir);

	static logcat_listener s_listener;
	static bool s_added = false;
	if (!s_added)
	{
		s_added = true;
		logs::listener::add(&s_listener);

		// Sets up config dirs and the global fixed-object manager; vfs::mount
		// (and thus firmware installation) requires it (see Emu/VFS.cpp).
		Emu.Init();
	}

	chrysalis_log.success("Chrysalis bridge initialized (data dir: %s)", dir);
}

JNIEXPORT jstring JNICALL Java_nu_hyperworks_chrysalis_NativeCore_firmwareVersion(JNIEnv* env, jclass)
{
	return to_java(env, utils::get_firmware_version());
}

JNIEXPORT jstring JNICALL Java_nu_hyperworks_chrysalis_NativeCore_installFirmware(JNIEnv* env, jclass, jstring pup_path)
{
	return to_java(env, install_firmware(to_std(env, pup_path)));
}

} // extern "C"
