package com.github.zzwtsy.chuanmen.p00probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.easytier.jni.EasyTierJNI;
import java.io.FileDescriptor;
import java.util.concurrent.CountDownLatch;
import org.json.JSONObject;

/** 服务拥有 PFD；原生补丁必须确认 I/O 任务退出后，本服务才关闭它。 */
public final class BoundaryVpnService extends VpnService {
    static volatile CountDownLatch destroyed = new CountDownLatch(0);
    static volatile String cleanupError;
    volatile boolean rejectProtect;
    volatile boolean throwProtect;
    volatile boolean callbackOnMain;
    volatile boolean revoked;
    final CountDownLatch revocationFinished = new CountDownLatch(1);
    volatile JSONObject revocationClosed;
    private volatile boolean foreground;
    private final LocalBinder binder = new LocalBinder();
    private ParcelFileDescriptor tun;
    private String instanceName;
    private ParcelFileDescriptor replacingOldTun;
    private long addressGeneration;
    private String appliedAddress;
    final class LocalBinder extends Binder { BoundaryVpnService service() { return BoundaryVpnService.this; } }

    @Override public void onCreate() {
        super.onCreate(); destroyed = new CountDownLatch(1); cleanupError = null;
    }
    @Override public IBinder onBind(Intent intent) {
        return SERVICE_INTERFACE.equals(intent.getAction()) ? super.onBind(intent) : binder;
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (prepare(this) != null || revoked) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(
            "p00", "P00 原生实验", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "p00") : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("P00 原生实验正在执行").setContentText("连接结论由真实实验报告提供").setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        else startForeground(100, notification);
        foreground = true; return START_NOT_STICKY;
    }

    /** native 当前线程同步取得真实 Boolean 回执；拒绝和抛异常只用于明确的负向探针。 */
    public boolean protectForNative(int fd) {
        if (Looper.myLooper() == Looper.getMainLooper()) callbackOnMain = true;
        if (throwProtect) throw new IllegalStateException("P00 synthetic callback failure");
        return !rejectProtect && !revoked && prepare(this) == null && protect(fd);
    }

    /** 只对本包和私有实验路由建立 TUN；不借排除本包来掩盖回环。 */
    synchronized void startCore(String name, String configuration, String outerHost) throws Exception {
        if (!foreground || revoked || prepare(this) != null || tun != null || instanceName != null) {
            throw new IllegalStateException("会话前置条件不成立");
        }
        if (P00NativeBridge.installProtector(this) != 0 || EasyTierJNI.parseConfig(configuration) != 0) {
            throw new IllegalStateException("原生前置检查失败");
        }
        instanceName = name;
        if (EasyTierJNI.runNetworkInstance(configuration) != 0) throw new IllegalStateException("启动返回错误");
        Builder builder = new Builder().setSession("Chuanmen-P00-native")
            .setMtu(1380).addAddress("10.222.0.2", 24).addRoute("10.222.0.0", 24)
            .addAllowedApplication(getPackageName());
        // P00-05 故意覆盖外层对端地址：必须依靠真实 protect 绕过，不能靠路由遗漏通过。
        if (outerHost != null) builder.addRoute(outerHost, 32);
        tun = builder.establish();
        if (tun == null) throw new IllegalStateException("TUN 建立失败");
        int fd = tun.getFd();
        if (EasyTierJNI.setTunFd(name, fd) != 0) throw new IllegalStateException("fd 未入队");
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject observation = new JSONObject(P00NativeBridge.observations());
            if ("installed".equals(observation.getJSONObject("tun").optString(Integer.toString(fd)))) return;
            SystemClock.sleep(25);
        }
        throw new IllegalStateException("真实 TUN 安装确认超时");
    }

    /** P00-06 专用入口：核心实际地址稳定后才建 TUN；这些方法全部属于本项目实验。 */
    synchronized JSONObject startAddressCore(String configuration, String outerHost, String expectedAddress,
            int mtu, String dns, boolean extraRoute) throws Exception {
        if (!foreground || revoked || prepare(this) != null || tun != null || instanceName != null)
            throw new IllegalStateException("地址实验前置条件不成立");
        if (P00NativeBridge.installProtector(this) != 0 || EasyTierJNI.parseConfig(configuration) != 0)
            throw new IllegalStateException("地址实验核心解析失败");
        instanceName = "p00-address"; addressGeneration++;
        if (EasyTierJNI.runNetworkInstance(configuration) != 0) throw new IllegalStateException("地址实验核心启动失败");
        org.json.JSONArray samples = new org.json.JSONArray(); long start = SystemClock.elapsedRealtimeNanos();
        long deadline = SystemClock.elapsedRealtime() + 30000, stableSince = 0; String previous = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject info = NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16));
            JSONObject instance = info.getJSONObject("map").optJSONObject(instanceName);
            JSONObject node = instance == null ? null : instance.optJSONObject("my_node_info");
            JSONObject prefix = node == null ? null : node.optJSONObject("virtual_ipv4");
            String ip = null;
            if (prefix != null && prefix.optJSONObject("address") != null && prefix.optInt("network_length") == 24) {
                long address = prefix.getJSONObject("address").getLong("addr");
                ip = ((address >> 24) & 255) + "." + ((address >> 16) & 255) + "." + ((address >> 8) & 255) + "." + (address & 255);
            }
            samples.put(new JSONObject().put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0)
                .put("network_infos", info).put("actual_address", ip == null ? JSONObject.NULL : ip));
            // 自动分配必须取受控对端所在网段；不把 bootstrap 地址或配置草稿当实际地址。
            boolean valid = ip != null && ip.startsWith("10.222.0.") && !ip.equals("10.222.0.1")
                && (expectedAddress == null || ip.equals(expectedAddress));
            if (valid && ip.equals(previous)) {
                if (SystemClock.elapsedRealtime() - stableSince >= 500) { appliedAddress = ip; break; }
            } else { previous = ip; stableSince = SystemClock.elapsedRealtime(); }
            SystemClock.sleep(100);
        }
        if (appliedAddress == null) throw new IllegalStateException("核心实际地址未稳定");
        tun = addressBuilder(outerHost, appliedAddress, mtu, dns, extraRoute).establish();
        if (tun == null || EasyTierJNI.setTunFd(instanceName, tun.getFd()) != 0) throw new IllegalStateException("地址实验 TUN 接入失败");
        awaitInstalled(tun.getFd());
        return new JSONObject().put("generation", addressGeneration).put("actual_address", appliedAddress)
            .put("native_samples", samples).put("installed_fd", tun.getFd());
    }

    private Builder addressBuilder(String host, String ip, int mtu, String dns, boolean extraRoute) throws Exception {
        Builder builder = new Builder().setSession("Chuanmen-P00-address").setMtu(mtu)
            .addAddress(ip, 24).addRoute("10.222.0.0", 24).addRoute(host, 32).addAllowedApplication(getPackageName());
        if (dns != null) builder.addDnsServer(dns);
        if (extraRoute) builder.addRoute("10.223.0.0", 24);
        return builder;
    }
    private void awaitInstalled(int fd) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if ("installed".equals(new JSONObject(P00NativeBridge.observations()).getJSONObject("tun").optString(Integer.toString(fd)))) return;
            SystemClock.sleep(25);
        }
        throw new IllegalStateException("新 TUN 安装未确认");
    }

    /** 同一核心替换平台参数；旧 PFD 保留到原生明确结束借用，失败时也交统一关闭路径。 */
    synchronized JSONObject replaceAddressTun(String host, int mtu, String dns, boolean extraRoute) throws Exception {
        if (tun == null || replacingOldTun != null || instanceName == null) throw new IllegalStateException("TUN 替换前置条件失败");
        ParcelFileDescriptor next = addressBuilder(host, appliedAddress, mtu, dns, extraRoute).establish();
        if (next == null) throw new IllegalStateException("替换 TUN 建立失败");
        replacingOldTun = tun;
        tun = next;
        int oldFd = replacingOldTun.getFd(), nextFd = tun.getFd();
        if (EasyTierJNI.setTunFd(instanceName, nextFd) != 0) throw new IllegalStateException("替换 fd 入队失败");
        awaitInstalled(nextFd);
        String state = new JSONObject(P00NativeBridge.observations()).getJSONObject("tun").optString(Integer.toString(oldFd));
        FileDescriptor oldDescriptor = replacingOldTun.getFileDescriptor();
        JSONObject result = new JSONObject().put("old_fd", oldFd).put("new_fd", nextFd).put("old_native_state", state)
            .put("old_valid_before_close", oldDescriptor.valid());
        if (!"released".equals(state) || !oldDescriptor.valid()) throw new IllegalStateException("旧借用终止未证实");
        replacingOldTun.close(); replacingOldTun = null;
        result.put("old_valid_after_close", oldDescriptor.valid());
        if (oldDescriptor.valid()) throw new IllegalStateException("旧 PFD 关闭失败");
        return result;
    }

    /** 实验宿主的轮询结果带代次；验证真实旧快照迟到不能覆盖新实例的实际地址。 */
    synchronized boolean applyAddressSnapshot(long generation, String actualAddress) {
        if (generation != addressGeneration || instanceName == null) return false;
        appliedAddress = actualAddress; return true;
    }
    synchronized String currentAppliedAddress() { return appliedAddress; }

    synchronized JSONObject stopCore() throws Exception {
        JSONObject observed = new JSONObject();
        if (instanceName != null) {
            if (EasyTierJNI.deleteNetworkInstance(instanceName) != 0) throw new IllegalStateException("原生停止失败");
            instanceName = null;
            addressGeneration++; appliedAddress = null;
        }
        if (replacingOldTun != null) {
            String state = new JSONObject(P00NativeBridge.observations()).getJSONObject("tun").optString(Integer.toString(replacingOldTun.getFd()));
            if (!"released".equals(state)) throw new IllegalStateException("失败替换的旧借用未终止");
            replacingOldTun.close(); replacingOldTun = null;
        }
        if (tun != null) {
            int fd = tun.getFd();
            FileDescriptor descriptor = tun.getFileDescriptor();
            String state = new JSONObject(P00NativeBridge.observations()).getJSONObject("tun").optString(Integer.toString(fd));
            observed.put("fd", fd).put("native_state", state).put("valid_before_owner_close", descriptor.valid());
            if (!"released".equals(state) || !descriptor.valid()) {
                // 未证实借用终止时保留 PFD；宿主失败清理由终止整个实验进程释放双方。
                throw new IllegalStateException("借用终止或 PFD 所有权未证实");
            }
            tun.close(); tun = null;
            observed.put("valid_after_owner_close", descriptor.valid());
            if (descriptor.valid()) throw new IllegalStateException("平台关闭后 fd 仍有效");
        }
        P00NativeBridge.clearProtector();
        if (new JSONObject(EasyTierJNI.listInstances(16)).length() != 0) throw new IllegalStateException("残留核心实例");
        return observed;
    }

    @Override public void onRevoke() {
        revoked = true;
        // 生命周期线程调用同步关闭路径；生产会话的串行协调另行工程化。
        try { revocationClosed = stopCore(); } catch (Exception error) { cleanupError = error.toString(); }
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf(); super.onRevoke();
        revocationFinished.countDown();
    }
    @Override public void onDestroy() {
        try { stopCore(); } catch (Exception error) { cleanupError = error.toString(); }
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false;
        destroyed.countDown(); super.onDestroy();
    }
}
