package nu.hyperworks.cellstation

import android.content.Context
import android.os.Build

/**
 * Per-device defaults for known handhelds.
 *
 * Handhelds differ in ways we can't infer from a key code alone — most
 * importantly the printed face-button layout. A Nintendo-labelled pad that
 * reports by *label* puts KEYCODE_BUTTON_A on the east button, while players
 * expect the PS3 button in the same *position*; a pad that reports by position
 * needs no swap at all. Guessing wrong makes confirm/cancel feel swapped, and
 * applying a swap the hardware already made is just as wrong as applying none.
 *
 * A matching profile is applied **once**, the first time that device is seen.
 * After that the user's settings win permanently: re-running the app never
 * re-applies a profile, so any customisation sticks. Adding a device later
 * still applies to existing installs (the marker records *which* profile was
 * applied, not merely that setup happened).
 */
object DeviceProfile {

    data class Profile(
        /** Stable id stored in prefs; change it only to intentionally re-apply. */
        val id: String,
        val displayName: String,
        /**
         * Whether the face buttons need the positional swap, decided when the
         * profile is applied. It is a function rather than a constant because
         * some handhelds expose an OS-level switch that changes what the pad
         * reports, and guessing either way double-corrects the other case.
         */
        val nintendoLayout: (Context) -> Boolean
    )

    /**
     * Known handhelds. Matching is on Build.MANUFACTURER + Build.MODEL, both
     * lowercased and compared loosely so regional model-name variants match.
     */
    private val KNOWN: List<Pair<(String, String) -> Boolean, Profile>> = listOf(
        // AYN's handhelds (Thor, Odin 2, Odin 2 Portal, Odin 3) print the
        // Nintendo-style face layout, but the pad's key codes follow whichever
        // "Controller style" the OS is set to — see [aynFaceButtonsAreLabelled].
        matcher("ayn") to Profile("ayn-face-buttons-v2", "AYN handheld", ::aynFaceButtonsAreLabelled),

        // Retroid Pocket handhelds ship the same layout.
        matcher("retroid") to Profile("retroid-nintendo-layout", "Retroid Pocket", fixed(true)),

        // Anbernic RG-series.
        matcher("anbernic") to Profile("anbernic-nintendo-layout", "Anbernic handheld", fixed(true))
    )

    private fun matcher(vendorFragment: String): (String, String) -> Boolean =
        { manufacturer, model -> manufacturer.contains(vendorFragment) || model.contains(vendorFragment) }

    private fun fixed(value: Boolean): (Context) -> Boolean = { value }

    /**
     * AYN's Android builds ship a "Controller style" switch — the quick
     * settings tile, the Handle Style entry in Odin Settings, or OdinTools —
     * that changes the key codes the built-in pad emits:
     *
     *   Odin / "Standard" style: codes follow the *printed* labels, so the
     *       button marked A (east) reports KEYCODE_BUTTON_A. Positional play
     *       needs our swap.
     *   Xbox style: codes follow the Android convention, so the *south* button
     *       reports KEYCODE_BUTTON_A. The hardware has already done the swap
     *       and applying ours on top puts every face button back where it
     *       should not be.
     *
     * The setting names and values are Odin Settings' own (verified against
     * com.odin.settings' ControllerStylePreference and OdinTools' public
     * ControllerStyle table): flip_button_layout = 1 means Xbox style,
     * no_create_gamepad_button_layout = 1 means the pad is switched off.
     * Anything else — including the setting being absent on a non-AYN build
     * that happens to match the vendor string — is Odin style.
     */
    private fun aynFaceButtonsAreLabelled(context: Context): Boolean =
        systemSetting(context, SETTING_FLIP_BUTTON_LAYOUT) != 1

    private fun systemSetting(context: Context, name: String): Int = try {
        android.provider.Settings.System.getInt(context.contentResolver, name, 0)
    } catch (_: Exception) {
        0
    }

    private const val SETTING_FLIP_BUTTON_LAYOUT = "flip_button_layout"

    /** The profile for this hardware, or null if we don't know it. */
    fun current(): Profile? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        return KNOWN.firstOrNull { (matches, _) -> matches(manufacturer, model) }?.second
    }

    /**
     * Applies the matching profile if it has never been applied on this
     * install. Returns the profile when it was applied now, else null.
     * Call before the first screen reads any of the settings it touches.
     */
    fun applyOnce(context: Context): Profile? {
        val profile = current() ?: return null
        if (Settings.appliedDeviceProfile(context) == profile.id) return null

        Settings.setNintendoLayout(context, profile.nintendoLayout(context))
        Settings.setAppliedDeviceProfile(context, profile.id)
        return profile
    }
}
