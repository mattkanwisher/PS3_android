package nu.hyperworks.chrysalis;

/** Thin JNI surface over librpcs3_emu via libchrysalis.so. */
public final class NativeCore {
    static {
        System.loadLibrary("chrysalis");
    }

    private NativeCore() {}

    /** Must be called once, before anything else, with the app's data dir. */
    public static native void init(String dataDir);

    /** Version string of the installed PS3 firmware, or "" if none. */
    public static native String firmwareVersion();

    /** Installs firmware from a PUP file. Returns "" on success, else an error message. Blocking. */
    public static native String installFirmware(String pupPath);
}
