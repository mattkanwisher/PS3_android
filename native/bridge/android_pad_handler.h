#pragma once

// Pad handler fed by Android input events (KeyEvent / MotionEvent) forwarded
// from the app through the JNI bridge. Upstream's handlers all talk raw HID or
// evdev, neither of which can see a handheld's built-in controller — Android
// delivers those to the focused activity instead.
//
// The app pushes a flat button/axis snapshot via EmuBridge.setPadState(); this
// handler publishes it to the core through the normal PadHandlerBase polling
// path, so remapping, deadzones and pressure sensitivity all keep working.

#include "Emu/Io/PadHandler.h"

#include <array>
#include <memory>

namespace chrysalis
{
	// Button indices in the state snapshot shared with Kotlin.
	// Keep in sync with EmuBridge.kt.
	enum android_pad_button : u32
	{
		btn_none = 0,

		btn_cross,
		btn_circle,
		btn_square,
		btn_triangle,
		btn_l1,
		btn_r1,
		btn_l3,
		btn_r3,
		btn_start,
		btn_select,
		btn_ps,
		btn_dpad_up,
		btn_dpad_down,
		btn_dpad_left,
		btn_dpad_right,

		// Analog axes are reported separately but share the keycode space so
		// the generic mapping path can address them by name.
		axis_l2,
		axis_r2,
		axis_ls_x_neg,
		axis_ls_x_pos,
		axis_ls_y_neg,
		axis_ls_y_pos,
		axis_rs_x_neg,
		axis_rs_x_pos,
		axis_rs_y_neg,
		axis_rs_y_pos,

		button_count
	};

	// Snapshot pushed from the Java side. Values are 0..255 so analog triggers
	// and sticks keep their resolution; digital buttons use 0 or 255.
	struct android_pad_state
	{
		std::array<u8, button_count> values{};
		bool connected = false;
	};

	// Called from the JNI bridge whenever the app observes an input event.
	void set_android_pad_state(const android_pad_state& state);
	void set_android_pad_connected(bool connected);
}

class android_pad_handler final : public PadHandlerBase
{
public:
	android_pad_handler();

	void init_config(cfg_pad* cfg) override;
	std::vector<pad_list_entry> list_devices() override;
	bool Init() override;

private:
	std::shared_ptr<PadDevice> get_device(const std::string& device) override;
	connection update_connection(const std::shared_ptr<PadDevice>& device) override;
	std::unordered_map<u32, u16> get_button_values(const std::shared_ptr<PadDevice>& device) override;

	bool get_is_left_trigger(const std::shared_ptr<PadDevice>& device, u32 keyCode) override;
	bool get_is_right_trigger(const std::shared_ptr<PadDevice>& device, u32 keyCode) override;
	bool get_is_left_stick(const std::shared_ptr<PadDevice>& device, u32 keyCode) override;
	bool get_is_right_stick(const std::shared_ptr<PadDevice>& device, u32 keyCode) override;
};

// Factory used by pad_thread on Android (see patches/0012).
std::shared_ptr<PadHandlerBase> make_android_pad_handler();
