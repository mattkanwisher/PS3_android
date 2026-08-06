package nu.hyperworks.cellstation

import android.content.Context
import android.os.Build

/**
 * Per-device defaults for known handhelds.
 *
 * Handhelds differ in ways we can't infer at runtime — most importantly the
 * printed face-button layout, since a Nintendo-labelled pad reports key codes
 * by *label* (A on the right) while players expect the PS3 button in the same
 * *position*. Guessing wrong makes confirm/cancel feel swapped.
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
        /** Face buttons are positionally mirrored (Nintendo-style labels). */
        val nintendoLayout: Boolean
    )

    /**
     * Known handhelds. Matching is on Build.MANUFACTURER + Build.MODEL, both
     * lowercased and compared loosely so regional model-name variants match.
     */
    private val KNOWN: List<Pair<(String, String) -> Boolean, Profile>> = listOf(
        // AYN's handhelds (Thor, Odin 2, Odin 2 Portal, Odin 3) all print the
        // Nintendo-style face layout on their built-in pads.
        matcher("ayn") to Profile("ayn-nintendo-layout", "AYN handheld", nintendoLayout = true),

        // Retroid Pocket handhelds ship the same layout.
        matcher("retroid") to Profile("retroid-nintendo-layout", "Retroid Pocket", nintendoLayout = true),

        // Anbernic RG-series.
        matcher("anbernic") to Profile("anbernic-nintendo-layout", "Anbernic handheld", nintendoLayout = true)
    )

    private fun matcher(vendorFragment: String): (String, String) -> Boolean =
        { manufacturer, model -> manufacturer.contains(vendorFragment) || model.contains(vendorFragment) }

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

        Settings.setNintendoLayout(context, profile.nintendoLayout)
        Settings.setAppliedDeviceProfile(context, profile.id)
        return profile
    }
}
