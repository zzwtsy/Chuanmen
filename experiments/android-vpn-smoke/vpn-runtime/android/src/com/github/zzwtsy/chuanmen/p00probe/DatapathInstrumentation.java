package com.github.zzwtsy.chuanmen.p00probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import com.easytier.jni.EasyTierJNI;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** P00-05：本实验包真实业务 socket 经 Android TUN 和核心到独立开发主机回显服务。 */
public final class DatapathInstrumentation extends Instrumentation {
    private BoundaryVpnService service;
    private final CountDownLatch connected = new CountDownLatch(1);
    private String host, runId;
    private boolean connectedCycles;
    /** establish 与 native installed 都不等于应用的系统路由已经切换，等待实际 VPN 网络。 */
    private void awaitVpnRouting() {
        ConnectivityManager manager = getTargetContext().getSystemService(ConnectivityManager.class);
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("系统未将实验应用切到 VPN 网络");
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); host = args.getString("peer_host"); runId = args.getString("run_id");
        connectedCycles = "true".equals(args.getString("connected_cycles")); start();
    }
    private int count(String path) {
        String[] entries = new File(path).list();
        if (entries == null) throw new IllegalStateException("资源计数不可读");
        return entries.length;
    }
    /** 等待核心真实读回对端虚拟路由，不以 fd 入队或配置草稿冒充已连接。 */
    private void awaitPeerRoute() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject info = NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16))
                .getJSONObject("map").optJSONObject("p00-datapath");
            if (info != null) {
                JSONArray routes = info.optJSONArray("routes");
                if (routes != null) for (int i = 0; i < routes.length(); i++) {
                    JSONObject addr = routes.getJSONObject(i).optJSONObject("ipv4_addr");
                    if (addr != null && addr.getJSONObject("address").getLong("addr") == 182321153L) return;
                }
            }
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("真实对端虚拟路由超时");
    }
    private String digest(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) result.append(String.format("%02x", x & 255));
        return result.toString();
    }
    static String configuration(String host) {
        return "instance_name = \"p00-datapath\"\nhostname = \"p00-android\"\nipv4 = \"10.222.0.2/24\"\n"
            + "dhcp = false\nlisteners = []\nstun_servers = []\ntcp_stun_servers = []\nstun_servers_v6 = []\n"
            + "[[peer]]\nuri = \"tcp://" + host + ":31270\"\n"
            + "[network_identity]\nnetwork_name = \"chuanmen-p00-isolated\"\nnetwork_secret = \"p00-synthetic-public-test-only\"\n"
            + "[flags]\nenable_ipv6 = false\ndisable_upnp = true\ndisable_udp_hole_punching = true\ndisable_tcp_hole_punching = true\n"
            + "disable_p2p = true\nbind_device = false\nprivate_mode = true\n";
    }
    private JSONObject tcp(int index) throws Exception {
        byte[] request = (runId + ":tcp:" + index + "\n").getBytes(StandardCharsets.UTF_8);
        long start = SystemClock.elapsedRealtimeNanos();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("10.222.0.1", 31271), 10000); socket.setSoTimeout(10000);
            socket.getOutputStream().write(request); socket.getOutputStream().flush();
            byte[] response = new byte[request.length]; int received = 0;
            while (received < response.length) {
                int read = socket.getInputStream().read(response, received, response.length - received);
                if (read < 0) throw new IllegalStateException("TCP 响应提前结束"); received += read;
            }
            if (!Arrays.equals(request, response)) throw new IllegalStateException("TCP 回包与 nonce 不符");
            return new JSONObject().put("round", index).put("status", "passed").put("request_sha256", digest(request))
                .put("response_sha256", digest(response)).put("bytes", received)
                .put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0);
        }
    }
    private JSONObject udp(int index) throws Exception {
        byte[] request = (runId + ":udp:" + index + "\n").getBytes(StandardCharsets.UTF_8);
        long start = SystemClock.elapsedRealtimeNanos();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(10000);
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("10.222.0.1"), 31272));
            DatagramPacket packet = new DatagramPacket(new byte[4096], 4096); socket.receive(packet);
            byte[] response = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
            if (!packet.getAddress().getHostAddress().equals("10.222.0.1") || packet.getPort() != 31272
                    || !Arrays.equals(request, response)) throw new IllegalStateException("UDP 地址或 nonce 不符");
            return new JSONObject().put("round", index).put("status", "passed").put("request_sha256", digest(request))
                .put("response_sha256", digest(response)).put("bytes", packet.getLength())
                .put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0);
        }
    }

    // 条件：固定真机、独立对端、测试专用 nonce；操作：TCP/UDP 各 20 次；结果：逐次真实回包及 TUN 计数。
    @Override public void onStart() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle(); Context context = getTargetContext();
        boolean bound = false; int code = Activity.RESULT_CANCELED;
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((BoundaryVpnService.LocalBinder) binder).service(); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { service = null; }
        };
        try {
            report.put("schema_version", 1).put("layer", "android_native_datapath").put("status", "failed")
                .put("run_id", runId).put("peer_host", host).put("virtual_destination", "10.222.0.1")
                .put("core_commit", "7592951b808c4839cb9d77cc4bc642f124915951");
            if (host == null || !host.matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}")
                    || runId == null || !runId.matches("p00-[0-9a-f]{12}")) throw new IllegalStateException("受控输入不合法");
            if (VpnService.prepare(context) != null) throw new IllegalStateException("VPN 未授权");
            Bundle phase = new Bundle(); phase.putString("p00_phase", "awaiting_activity"); sendStatus(1, phase);
            if (!BoundaryActivity.resumed.await(10, TimeUnit.SECONDS) || BoundaryActivity.active == null) throw new IllegalStateException("页面恢复超时");
            runOnMainSync(new Runnable() { @Override public void run() { BoundaryActivity.active.startProbe(); } });
            bound = context.bindService(new Intent(context, BoundaryVpnService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("服务绑定超时");
            P00NativeBridge.resetObservations(); String config = configuration(host); report.put("configuration", config);
            JSONArray tcp = new JSONArray(), udp = new JSONArray(); report.put("tcp", tcp).put("udp", udp);
            if (connectedCycles) {
                // 条件：真实对端在线；操作：预热后 20 次启动、两协议回显、停止；结果：借用结束且资源不增长。
                service.startCore("p00-datapath", config, host); awaitVpnRouting(); awaitPeerRoute();
                try (Socket preloadTcp = new Socket(); DatagramSocket preloadUdp = new DatagramSocket()) { }
                report.put("warmup_closed", service.stopCore()); SystemClock.sleep(1000);
                int baselineFd = count("/proc/self/fd"), baselineThreads = count("/proc/self/task");
                report.put("baseline_fd", baselineFd).put("baseline_threads", baselineThreads);
                P00NativeBridge.resetObservations(); JSONArray rounds = new JSONArray(); report.put("rounds", rounds);
                for (int i = 1; i <= 20; i++) {
                    long start = SystemClock.elapsedRealtimeNanos(); service.startCore("p00-datapath", config, host); awaitVpnRouting(); awaitPeerRoute();
                    tcp.put(tcp(i)); udp.put(udp(i));
                    JSONObject info = NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16));
                    JSONObject closed = service.stopCore(); SystemClock.sleep(300);
                    int fd = count("/proc/self/fd"), threads = count("/proc/self/task");
                    JSONObject round = new JSONObject().put("round", i).put("closed", closed)
                        .put("fd_after", fd).put("threads_after", threads).put("network_infos", info)
                        .put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0)
                        .put("status", "failed");
                    rounds.put(round); report.put("closed", closed);
                    if (fd > baselineFd || threads > baselineThreads) throw new IllegalStateException("连接中关闭后资源增长");
                    round.put("status", "passed");
                }
            } else {
                long connectStart = SystemClock.elapsedRealtimeNanos(); service.startCore("p00-datapath", config, host);
                report.put("tun_install_ms", (SystemClock.elapsedRealtimeNanos() - connectStart) / 1000000.0);
                awaitVpnRouting(); awaitPeerRoute();
                for (int i = 1; i <= 20; i++) tcp.put(tcp(i));
                for (int i = 1; i <= 20; i++) udp.put(udp(i));
            }
            JSONObject io = new JSONObject(P00NativeBridge.observations());
            report.put("observations", io).put("network_infos", NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)));
            if (io.getInt("read_packets") <= 0 || io.getInt("write_packets") <= 0
                    || io.getJSONObject("destinations").optInt(host) != 0
                    || io.getJSONObject("destinations").optInt("10.222.0.1") <= 0) throw new IllegalStateException("TUN 流量或回环证据不符");
            JSONArray protections = io.getJSONArray("protections");
            if (protections.length() == 0) throw new IllegalStateException("没有外层保护回调");
            for (int i = 0; i < protections.length(); i++) if (!protections.getJSONObject(i).getBoolean("success")) throw new IllegalStateException("保护失败");
            if (!connectedCycles) report.put("closed", service.stopCore());
            report.put("status", "passed").put("connected_cycles", connectedCycles); code = Activity.RESULT_OK;
        } catch (Throwable error) {
            try {
                report.put("error", error.getClass().getName() + ": " + error.getMessage());
                if (service != null) {
                    report.put("failure_io", new JSONObject(P00NativeBridge.observations()))
                        .put("failure_network_infos", NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)));
                    Bundle diagnostics = new Bundle(); diagnostics.putString("p00_diagnostic", report.toString()); sendStatus(2, diagnostics);
                    // 先在 instrumentation 工作线程停止核心，不把阻塞 native 调用推给服务主线程。
                    report.put("failure_closed", service.stopCore());
                }
            }
            catch (Exception diagnosticError) {
                try { report.put("diagnostic_error", diagnosticError.toString()); }
                catch (org.json.JSONException ignored) { }
            }
        } finally {
            if (bound) context.unbindService(connection); context.stopService(new Intent(context, BoundaryVpnService.class));
            try {
                boolean stopped = BoundaryVpnService.destroyed.await(30, TimeUnit.SECONDS);
                report.put("service_destroyed", stopped).put("cleanup_error", BoundaryVpnService.cleanupError == null ? JSONObject.NULL : BoundaryVpnService.cleanupError);
                if (!stopped || BoundaryVpnService.cleanupError != null) { report.put("status", "failed"); code = Activity.RESULT_CANCELED; }
            } catch (Exception error) { code = Activity.RESULT_CANCELED; }
            result.putString("p00_report", report.toString()); finish(code, result);
        }
    }
}
