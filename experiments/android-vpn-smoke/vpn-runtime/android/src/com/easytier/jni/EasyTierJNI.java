package com.easytier.jni;

/** 与固定上游 Kotlin 声明等价的签名；不添加虚构的官方 SDK 入口。 */
public final class EasyTierJNI {
    static { System.loadLibrary("easytier_android_jni"); }
    private EasyTierJNI() { }
    public static native int parseConfig(String config);
    public static native int runNetworkInstance(String config);
    public static native int deleteNetworkInstance(String name);
    public static native int setTunFd(String name, int fd);
    public static native String listInstances(int maxLength);
    public static native String collectNetworkInfos(int maxLength);
    public static native String getLastError();
}
