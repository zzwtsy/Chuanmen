package com.github.zzwtsy.chuanmen.p00probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import com.easytier.jni.EasyTierJNI;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** P00-07 有限风险探针：真实协议请求和释放；Portal 只测绑定，不冒充 WireGuard 互操作。 */
public final class AdvancedInstrumentation extends Instrumentation {
    private BoundaryVpnService service;
    private String host, runId;
    private String portalPrivateKey;
    private final CountDownLatch connected = new CountDownLatch(1);
    @Override public void onCreate(Bundle args) { super.onCreate(args); host = args.getString("peer_host"); runId = args.getString("run_id"); start(); }
    private String config() {
        // 专用随机密钥只在本次进程内存中使用，不写报告、文件或日志。
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        portalPrivateKey = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP);
        Arrays.fill(key, (byte) 0);
        String cfg = DatapathInstrumentation.configuration(host).replace("p00-datapath", "p00-advanced")
            .replace("listeners = []", "listeners = [\"tcp://127.0.0.1:31375\"]\nmapped_listeners = [\"tcp://127.0.0.1:31375\"]\nsocks5_proxy = \"socks5://127.0.0.1:31373\"")
            .replace("[flags]\n", "[flags]\nenable_exit_node = true\n");
        return cfg + "\n[[proxy_network]]\ncidr = \"127.0.0.1/32\"\nmapped_cidr = \"10.223.0.1/32\"\n"
            + "\n[[port_forward]]\nproto = \"tcp\"\nbind_addr = \"127.0.0.1:31371\"\ndst_addr = \"10.222.0.1:31271\"\n"
            + "\n[[port_forward]]\nproto = \"udp\"\nbind_addr = \"127.0.0.1:31372\"\ndst_addr = \"10.222.0.1:31272\"\n"
            + "\n[vpn_portal_config]\nenabled = true\nwireguard_listen = \"127.0.0.1:31374\"\nwireguard_private_key = \"" + portalPrivateKey + "\"\nclients = []\n";
    }
    private String digest(byte[] value) throws Exception {
        StringBuilder sha = new StringBuilder(); for (byte x : MessageDigest.getInstance("SHA-256").digest(value)) sha.append(String.format("%02x", x & 255)); return sha.toString();
    }
    private byte[] read(Socket socket, int size) throws Exception {
        byte[] bytes = new byte[size]; int offset = 0;
        while (offset < size) { int n = socket.getInputStream().read(bytes, offset, size - offset); if (n < 0) throw new IllegalStateException("协议响应提前结束"); offset += n; }
        return bytes;
    }
    private JSONObject exchange(String name, int port, boolean socks) throws Exception {
        byte[] request = (runId + ":advanced:" + name + "\n").getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000); socket.setSoTimeout(10000);
            if (socks) {
                // 条件：真实 SOCKS 服务；操作：无认证协商及 IPv4 CONNECT；结果：收到协议成功与真实对端 nonce。
                socket.getOutputStream().write(new byte[] {5, 1, 0});
                if (!Arrays.equals(read(socket, 2), new byte[] {5, 0})) throw new IllegalStateException("SOCKS 方法协商失败");
                socket.getOutputStream().write(new byte[] {5, 1, 0, 1, 10, (byte) 222, 0, 1, (byte) (31271 >> 8), (byte) 31271});
                byte[] prefix = read(socket, 4);
                if (prefix[0] != 5 || prefix[1] != 0) throw new IllegalStateException("SOCKS CONNECT 失败");
                int size = prefix[3] == 1 ? 4 : prefix[3] == 4 ? 16 : prefix[3] == 3 ? (read(socket, 1)[0] & 255) : -1;
                if (size < 0) throw new IllegalStateException("SOCKS 返回未知地址类型"); read(socket, size + 2);
            }
            socket.getOutputStream().write(request); byte[] response = read(socket, request.length);
            if (!Arrays.equals(request, response)) throw new IllegalStateException("高级 TCP 回包不匹配");
            return new JSONObject().put("name", name).put("status", "passed").put("request_sha256", digest(request)).put("response_sha256", digest(response));
        }
    }
    private JSONObject udp() throws Exception {
        byte[] request = (runId + ":advanced:udp_forward\n").getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(10000); socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("127.0.0.1"), 31372));
            DatagramPacket packet = new DatagramPacket(new byte[4096], 4096); socket.receive(packet);
            byte[] response = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
            if (!Arrays.equals(request, response)) throw new IllegalStateException("UDP 转发回包不匹配");
            return new JSONObject().put("name", "udp_forward").put("status", "passed").put("request_sha256", digest(request)).put("response_sha256", digest(response));
        }
    }
    private boolean tcpBound(int port) throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); return true; }
    }
    private boolean udpUnavailable(int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) { socket.bind(new InetSocketAddress("127.0.0.1", port)); return false; }
        catch (java.net.BindException expected) { return true; }
        catch (Exception error) { throw new IllegalStateException("UDP 绑定观察异常", error); }
    }
    private void assertPortsReleased() throws Exception {
        for (int port : new int[] {31371, 31373, 31375}) try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress("127.0.0.1", port));
        }
        for (int port : new int[] {31372, 31374}) try (DatagramSocket socket = new DatagramSocket(null)) { socket.bind(new InetSocketAddress("127.0.0.1", port)); }
    }
    // 条件：仅受控 LAN 与回环端点；操作：真实 SOCKS、转发、子网和出口请求；结果：两端 nonce 与停止后重绑。
    @Override public void onStart() {
        Context context = getTargetContext(); JSONObject report = new JSONObject(); Bundle result = new Bundle();
        boolean bound = false; int code = Activity.RESULT_CANCELED; AdvancedLoopback endpoints = null;
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { service = ((BoundaryVpnService.LocalBinder) binder).service(); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        try {
            report.put("layer", "android_native_advanced").put("status", "failed").put("run_id", runId);
            if (host == null || !host.matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}") || runId == null || !runId.matches("p00-[0-9a-f]{12}") || VpnService.prepare(context) != null)
                throw new IllegalStateException("高级探针输入或授权失败");
            assertPortsReleased(); endpoints = new AdvancedLoopback(runId);
            Bundle phase = new Bundle(); phase.putString("p00_phase", "awaiting_activity"); sendStatus(1, phase);
            if (!BoundaryActivity.resumed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("实验页面未恢复");
            runOnMainSync(new Runnable() { @Override public void run() { BoundaryActivity.active.startProbe(); } });
            bound = context.bindService(new Intent(context, BoundaryVpnService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("实验服务未绑定");
            P00NativeBridge.resetObservations(); String configuration = config();
            report.put("configuration", configuration.replace(portalPrivateKey, "<ephemeral-key-redacted>"));
            service.startCore("p00-advanced", configuration, host); SystemClock.sleep(1000);
            JSONArray requests = new JSONArray(); report.put("requests", requests);
            requests.put(exchange("tcp_forward", 31371, false)); requests.put(udp()); requests.put(exchange("socks5", 31373, true));
            boolean listener = tcpBound(31375), portal = udpUnavailable(31374);
            report.put("loopback_listener_accepts_tcp", listener).put("portal_udp_bound", portal).put("portal_interoperability", "unverified");
            if (!listener || !portal) throw new IllegalStateException("监听或 Portal 绑定未证实");
            phase.putString("p00_phase", "awaiting_host_advanced_requests"); sendStatus(2, phase);
            long deadline = SystemClock.elapsedRealtime() + 45000;
            java.io.File marker = new java.io.File(context.getFilesDir(), "p00-advanced-host-" + runId + ".done");
            while (!marker.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            if (!marker.exists()) throw new IllegalStateException("未取得宿主高级请求完成信号");
            JSONArray subnet = endpoints.snapshot(); report.put("phone_subnet_echo_records", subnet);
            if (subnet.length() != 2) throw new IllegalStateException("手机未实际收到两协议子网请求");
            report.put("network_infos", NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)))
                .put("observations", new JSONObject(P00NativeBridge.observations())).put("closed", service.stopCore());
            endpoints.close(); endpoints = null; assertPortsReleased();
            report.put("ports_rebound_after_stop", true).put("instances_after", new JSONObject(EasyTierJNI.listInstances(16)))
                .put("status", "passed"); code = Activity.RESULT_OK;
        } catch (Throwable error) {
            // 在删实例前保留真实异步启动状态；JNI 的入队返回不代表核心已经就绪。
            try {
                report.put("error", error.toString()).put("jni_last_error", EasyTierJNI.getLastError())
                    .put("failure_network_infos", NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)))
                    .put("failure_observations", new JSONObject(P00NativeBridge.observations()));
                if (endpoints != null) report.put("phone_subnet_echo_records", endpoints.snapshot());
                if (service != null) report.put("failure_closed", service.stopCore());
            }
            catch (Exception cleanup) { try { report.put("cleanup_error", cleanup.toString()); } catch (Exception ignored) { } }
        } finally {
            try { if (endpoints != null) endpoints.close(); } catch (Exception error) { code = Activity.RESULT_CANCELED; }
            if (bound) context.unbindService(connection); context.stopService(new Intent(context, BoundaryVpnService.class));
            try {
                boolean destroyed = BoundaryVpnService.destroyed.await(30, TimeUnit.SECONDS); int notifications = context.getSystemService(NotificationManager.class).getActiveNotifications().length;
                report.put("service_destroyed", destroyed).put("own_active_notifications_after", notifications).put("cleanup_error", BoundaryVpnService.cleanupError == null ? JSONObject.NULL : BoundaryVpnService.cleanupError);
                if (!destroyed || notifications != 0 || BoundaryVpnService.cleanupError != null) { report.put("status", "failed"); code = Activity.RESULT_CANCELED; }
            } catch (Exception error) { code = Activity.RESULT_CANCELED; }
            result.putString("p00_report", report.toString()); finish(code, result);
        }
    }
}
