package com.easytier.jni;

/** 固定上游 Kotlin 声明的 Java 等价签名；仅声明本实验调用的四个实际导出。 */
public final class EasyTierJNI {
    static { System.loadLibrary("easytier_android_jni"); }
    private EasyTierJNI() { }
    public static native int parseConfig(String config);
    public static native String listInstances(int maxLength);
    public static native String collectNetworkInfos(int maxLength);
    public static native String getLastError();
}
