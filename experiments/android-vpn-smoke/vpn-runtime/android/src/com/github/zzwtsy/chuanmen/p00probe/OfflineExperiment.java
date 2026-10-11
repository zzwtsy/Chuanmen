package com.github.zzwtsy.chuanmen.p00probe;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.IBinder;
import android.os.SystemClock;
import com.easytier.jni.EasyTierJNI;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Activity 所有的独立工作线程跨越无线 ADB 断开；只把结果存入本包私有目录。 */
final class OfflineExperiment implements ServiceConnection {
    private final BoundaryActivity activity;
    private final String host, runId;
    private final CountDownLatch connected = new CountDownLatch(1);
    private BoundaryVpnService service;
    private OfflineExperiment(BoundaryActivity activity, String host, String runId) {
        this.activity = activity; this.host = host; this.runId = runId;
    }
    static void start(BoundaryActivity activity, String host, String runId) {
        OfflineExperiment probe = new OfflineExperiment(activity, host, runId);
        if (!host.matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}") || runId == null || !runId.matches("p00-[0-9a-f]{12}"))
            throw new IllegalStateException("无网络实验参数不合法");
        activity.startProbe();
        if (!activity.bindService(new Intent(activity, BoundaryVpnService.class), probe, Context.BIND_AUTO_CREATE))
            throw new IllegalStateException("服务绑定失败");
        new Thread(new Runnable() { @Override public void run() { probe.run(); } }, "p00-offline-owner").start();
    }
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((BoundaryVpnService.LocalBinder) binder).service(); connected.countDown();
    }
    @Override public void onServiceDisconnected(ComponentName name) { }
    private int physicalNetworks() {
        ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class); int count = 0;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) count++;
        }
        return count;
    }
    private void save(JSONObject report) throws Exception {
        // 同一 runId 的检查点原子替换；宿主保留原始 ready 和最终报告，不覆盖其他实验文件。
        java.io.File temporary = new java.io.File(activity.getFilesDir(), "p00-offline.tmp");
        try (OutputStream stream = new java.io.FileOutputStream(temporary)) {
            stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!temporary.renameTo(new java.io.File(activity.getFilesDir(), "p00-offline.json"))) throw new IllegalStateException("结果替换失败");
    }
    private String digest(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    // 条件：真实 peer 与回显已可达；操作：人工关闭全部物理网络；结果：实际业务失败后工作线程关闭核心。
    private void run() {
        JSONObject report = new JSONObject();
        try {
            report.put("layer", "android_native_offline").put("status", "failed").put("run_id", runId)
                .put("core_commit", "7592951b808c4839cb9d77cc4bc642f124915951")
                .put("stop_policy_owner", "experiment_worker");
            if (!connected.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("服务连接超时");
            P00NativeBridge.resetObservations(); service.startCore("p00-datapath", DatapathInstrumentation.configuration(host), host);
            ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
            long deadline = SystemClock.elapsedRealtime() + 10000; boolean ready = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                Network active = manager.getActiveNetwork();
                NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
                JSONObject instance = NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)).getJSONObject("map").optJSONObject("p00-datapath");
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && instance != null && instance.optJSONArray("routes") != null && instance.getJSONArray("routes").length() > 0) { ready = true; break; }
                SystemClock.sleep(50);
            }
            if (!ready || physicalNetworks() == 0) throw new IllegalStateException("真实 VPN、路由或物理网络未就绪");
            byte[] request = (runId + ":offline:before\n").getBytes(StandardCharsets.UTF_8);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("10.222.0.1", 31271), 5000); socket.setSoTimeout(5000);
                socket.getOutputStream().write(request); byte[] response = new byte[request.length]; int received = 0;
                while (received < response.length) { int n = socket.getInputStream().read(response, received, response.length - received); if (n < 0) break; received += n; }
                if (!Arrays.equals(request, response)) throw new IllegalStateException("断网前真实回显失败");
            }
            report.put("before_request_sha256", digest(request)).put("before_network_infos", NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)))
                .put("physical_networks_before", physicalNetworks()).put("status", "waiting_offline"); save(report);
            deadline = SystemClock.elapsedRealtime() + 120000;
            while (physicalNetworks() != 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            if (physicalNetworks() != 0) throw new IllegalStateException("未观察到物理网络全部断开");
            report.put("physical_networks_after", 0).put("network_loss_observed", true)
                .put("network_loss_elapsed_ns", SystemClock.elapsedRealtimeNanos());
            boolean failed = false;
            try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("10.222.0.1", 31271), 3000); }
            catch (java.io.IOException error) { failed = true; report.put("offline_connect_error", error.toString()); }
            if (!failed) throw new IllegalStateException("物理网络断开后新业务连接仍成功");
            report.put("closed", service.stopCore()).put("instances_after", new JSONObject(EasyTierJNI.listInstances(16)))
                .put("observations", new JSONObject(P00NativeBridge.observations())).put("permission_granted_after", VpnService.prepare(activity) == null)
                .put("status", "passed");
        } catch (Throwable error) {
            try { report.put("status", "failed").put("error", error.toString()); if (service != null) report.put("failure_closed", service.stopCore()); }
            catch (Exception cleanup) { try { report.put("cleanup_error", cleanup.toString()); } catch (Exception ignored) { } }
        } finally {
            activity.runOnUiThread(new Runnable() { @Override public void run() {
                activity.unbindService(OfflineExperiment.this); activity.stopService(new Intent(activity, BoundaryVpnService.class));
            } });
            try {
                boolean destroyed = BoundaryVpnService.destroyed.await(30, TimeUnit.SECONDS);
                report.put("service_destroyed", destroyed).put("cleanup_error", BoundaryVpnService.cleanupError == null ? JSONObject.NULL : BoundaryVpnService.cleanupError);
                long deadline = SystemClock.elapsedRealtime() + 2000;
                while (activity.getSystemService(NotificationManager.class).getActiveNotifications().length > 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                int notifications = activity.getSystemService(NotificationManager.class).getActiveNotifications().length;
                report.put("own_active_notifications_after", notifications);
                if (!destroyed || BoundaryVpnService.cleanupError != null || notifications != 0) report.put("status", "failed");
                save(report);
            } catch (Exception error) { android.util.Log.e("P00Offline", "无法保存本包实验结果", error); }
        }
    }
}
