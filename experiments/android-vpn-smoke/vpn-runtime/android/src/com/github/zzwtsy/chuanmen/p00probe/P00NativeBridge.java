package com.github.zzwtsy.chuanmen.p00probe;

/** 仅由本仓库 P00 补丁实现的实验 JNI；这些方法不属于 EasyTier 上游。 */
public final class P00NativeBridge {
    static { System.loadLibrary("easytier_android_jni"); }
    private P00NativeBridge() { }
    public static native int installProtector(Object callback);
    public static native void clearProtector();
    public static native void resetObservations();
    public static native String observations();
    public static native String probeProtectedSockets();
    public static native void abortForExperiment();
}
