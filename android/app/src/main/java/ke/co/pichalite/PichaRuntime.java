package ke.co.pichalite;

/** Rivium's registered JNI lifecycle contract. Keep this exact class name. */
public final class PichaRuntime {
    static { System.loadLibrary("pichalite"); }
    public static native int nativeStart(String[] args);
    public static native int nativeStop(long timeoutMillis);
    public static native int nativeStatus();
    public static native String nativeLastError();
    public static native String nativeVersion();
}
