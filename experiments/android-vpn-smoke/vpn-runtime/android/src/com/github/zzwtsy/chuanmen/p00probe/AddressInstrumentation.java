package com.github.zzwtsy.chuanmen.p00probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import com.easytier.jni.EasyTierJNI;
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

/** P00-06：系统 TUN 参数、真实 DHCP 地址与同实例替换；不假定上游存在热更新 API。 */
public final class AddressInstrumentation extends Instrumentation {
    private BoundaryVpnService service;
    private String host, runId;
    private final CountDownLatch connected = new CountDownLatch(1);
    @Override public void onCreate(Bundle args) { super.onCreate(args); host = args.getString("peer_host"); runId = args.getString("run_id"); start(); }

    private String config(String ip, int mtu, boolean dhcp) {
        String value = DatapathInstrumentation.configuration(host).replace("p00-datapath", "p00-address")
            .replace("ipv4 = \"10.222.0.2/24\"\n", ip == null ? "" : "ipv4 = \"" + ip + "/24\"\n")
            .replace("dhcp = false", "dhcp = " + dhcp);
        return value.replace("[flags]\n", "[flags]\nmtu = " + mtu + "\n");
    }
    private String digest(byte[] value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(value)) result.append(String.format("%02x", x & 255));
        return result.toString();
    }
    private JSONObject echo(String name) throws Exception {
        byte[] request = (runId + ":address:" + name + "\n").getBytes(StandardCharsets.UTF_8);
        long start = SystemClock.elapsedRealtimeNanos();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("10.222.0.1", 31271), 5000); socket.setSoTimeout(5000);
            socket.getOutputStream().write(request); byte[] response = new byte[request.length]; int received = 0;
            while (received < response.length) { int n = socket.getInputStream().read(response, received, response.length - received); if (n < 0) break; received += n; }
            if (!Arrays.equals(request, response)) throw new IllegalStateException("地址场景回包不匹配");
            return new JSONObject().put("name", name).put("request_sha256", digest(request)).put("response_sha256", digest(response))
                .put("source_address", socket.getLocalAddress().getHostAddress()).put("bytes", received)
                .put("reply_elapsed_ns", SystemClock.elapsedRealtimeNanos()).put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - start) / 1000000.0);
        }
    }
    private JSONObject platform(String expectedIp, int mtu, String dns, boolean extra) throws Exception {
        ConnectivityManager manager = getTargetContext().getSystemService(ConnectivityManager.class);
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            Network network = manager.getActiveNetwork(); NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            LinkProperties properties = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? manager.getLinkProperties(network) : null;
            if (properties != null) {
                boolean address = false, route = false, extraFound = false, dnsFound = dns == null;
                JSONArray addresses = new JSONArray(), routes = new JSONArray(), servers = new JSONArray();
                for (LinkAddress value : properties.getLinkAddresses()) { addresses.put(value.toString()); if (value.getAddress().getHostAddress().equals(expectedIp) && value.getPrefixLength() == 24) address = true; }
                for (RouteInfo value : properties.getRoutes()) {
                    String prefix = value.getDestination().toString(); routes.put(prefix);
                    if (prefix.equals("10.222.0.0/24")) route = true;
                    if (prefix.equals("10.223.0.0/24")) extraFound = true;
                }
                for (InetAddress value : properties.getDnsServers()) { servers.put(value.getHostAddress()); if (value.getHostAddress().equals(dns)) dnsFound = true; }
                if (address && route && extra == extraFound && dnsFound && properties.getMtu() == mtu)
                    return new JSONObject().put("vpn_network", network.toString()).put("addresses", addresses).put("routes", routes)
                        .put("dns_servers", servers).put("mtu", properties.getMtu()).put("interface_name", properties.getInterfaceName());
            }
            SystemClock.sleep(50);
        }
        throw new IllegalStateException("系统未实际应用所声明的 TUN 参数");
    }
    private JSONObject step(String name, String ip, int mtu, String dns, boolean extra) throws Exception {
        JSONObject observed = new JSONObject().put("name", name).put("status", "failed");
        observed.put("platform", platform(ip, mtu, dns, extra)).put("network_infos", NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16)))
            .put("echo", echo(name));
        if (!observed.getJSONObject("echo").getString("source_address").equals(ip)) throw new IllegalStateException("业务实际源地址与 TUN 不符");
        return observed.put("status", "passed");
    }
    // 条件：真机与独立受控对端；操作：逐类变更并发真实请求；结果：系统/核心/业务地址可交叉核对。
    @Override public void onStart() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle(); Context context = getTargetContext();
        boolean bound = false; int code = Activity.RESULT_CANCELED;
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { service = ((BoundaryVpnService.LocalBinder) binder).service(); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        try {
            report.put("layer", "android_native_address").put("status", "failed").put("run_id", runId)
                .put("core_commit", "7592951b808c4839cb9d77cc4bc642f124915951");
            if (host == null || !host.matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}") || runId == null || !runId.matches("p00-[0-9a-f]{12}")
                    || VpnService.prepare(context) != null) throw new IllegalStateException("地址实验输入或授权无效");
            Bundle phase = new Bundle(); phase.putString("p00_phase", "awaiting_activity"); sendStatus(1, phase);
            if (!BoundaryActivity.resumed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("实验页面未恢复");
            runOnMainSync(new Runnable() { @Override public void run() { BoundaryActivity.active.startProbe(); } });
            bound = context.bindService(new Intent(context, BoundaryVpnService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("实验服务未绑定");
            P00NativeBridge.resetObservations(); JSONArray steps = new JSONArray(), closed = new JSONArray(); report.put("steps", steps).put("closed_sessions", closed);
            JSONObject start = service.startAddressCore(config("10.222.0.2", 1380, false), host, "10.222.0.2", 1380, null, false);
            report.put("static_start", start); JSONObject before = step("static", "10.222.0.2", 1380, null, false); steps.put(before);
            String[] names = {"platform_mtu", "platform_dns", "platform_route"};
            for (int i = 0; i < names.length; i++) {
                long lastReply = before.getJSONObject("echo").getLong("reply_elapsed_ns");
                JSONObject replaced = service.replaceAddressTun(host, 1280, i >= 1 ? "10.222.0.1" : null, i == 2);
                JSONObject current = step(names[i], "10.222.0.2", 1280, i >= 1 ? "10.222.0.1" : null, i == 2);
                current.put("replacement", replaced).put("last_success_to_first_success_ms", (current.getJSONObject("echo").getLong("reply_elapsed_ns") - lastReply) / 1000000.0)
                    .put("window_meaning", "顺序采样的成功间隔上界，不是连续发包测得的丢包窗口");
                steps.put(current); before = current;
            }
            JSONObject oldSnapshot = before.getJSONObject("network_infos"); long oldGeneration = start.getLong("generation");
            long oldIp = oldSnapshot.getJSONObject("map").getJSONObject("p00-address").getJSONObject("my_node_info")
                .getJSONObject("virtual_ipv4").getJSONObject("address").getLong("addr");
            final String oldActualAddress = ((oldIp >> 24) & 255) + "." + ((oldIp >> 16) & 255) + "." + ((oldIp >> 8) & 255) + "." + (oldIp & 255);
            closed.put(service.stopCore());
            JSONObject next = service.startAddressCore(config("10.222.0.3", 1280, false), host, "10.222.0.3", 1280, "10.222.0.9", false);
            report.put("changed_address_start", next); steps.put(step("address_reconnect", "10.222.0.3", 1280, "10.222.0.9", false));
            final boolean[] staleAccepted = {true}; CountDownLatch delivered = new CountDownLatch(1);
            new Thread(new Runnable() { @Override public void run() {
                SystemClock.sleep(100); staleAccepted[0] = service.applyAddressSnapshot(oldGeneration, oldActualAddress); delivered.countDown();
            } }, "p00-delayed-real-snapshot").start();
            if (!delivered.await(5, TimeUnit.SECONDS) || staleAccepted[0] || !service.currentAppliedAddress().equals("10.222.0.3"))
                throw new IllegalStateException("旧真实快照覆盖了新实例地址");
            report.put("late_snapshot", new JSONObject().put("old_generation", oldGeneration).put("current_generation", next.getLong("generation"))
                .put("original_native_snapshot", oldSnapshot).put("delivery_origin", "experiment_scheduled_real_prior_jni_snapshot")
                .put("accepted", staleAccepted[0]).put("current_address", service.currentAppliedAddress())
                .put("scope", "仅实验宿主代次过滤，不是上游异步回调或生产状态机"));
            closed.put(service.stopCore());
            JSONObject automatic = service.startAddressCore(config(null, 1380, true), host, null, 1380, null, false);
            report.put("dhcp_start", automatic); steps.put(step("dhcp", automatic.getString("actual_address"), 1380, null, false));
            closed.put(service.stopCore());
            report.put("instances_after", new JSONObject(EasyTierJNI.listInstances(16))).put("observations", new JSONObject(P00NativeBridge.observations()))
                .put("dns_scope", "系统 TUN 参数读回；未验证 DNS 解析服务")
                .put("mtu_scope", "同核心替换仅改平台 MTU；核心 flags.mtu 变更经完整重连")
                .put("status", "passed"); code = Activity.RESULT_OK;
        } catch (Throwable error) {
            try { report.put("error", error.toString()); if (service != null) report.put("failure_closed", service.stopCore()); }
            catch (Exception cleanup) { try { report.put("cleanup_error", cleanup.toString()); } catch (Exception ignored) { } }
        } finally {
            if (bound) context.unbindService(connection); context.stopService(new Intent(context, BoundaryVpnService.class));
            try {
                boolean destroyed = BoundaryVpnService.destroyed.await(30, TimeUnit.SECONDS);
                int notifications = context.getSystemService(NotificationManager.class).getActiveNotifications().length;
                report.put("service_destroyed", destroyed).put("own_active_notifications_after", notifications)
                    .put("cleanup_error", BoundaryVpnService.cleanupError == null ? JSONObject.NULL : BoundaryVpnService.cleanupError);
                if (!destroyed || notifications != 0 || BoundaryVpnService.cleanupError != null) { report.put("status", "failed"); code = Activity.RESULT_CANCELED; }
            } catch (Exception error) { code = Activity.RESULT_CANCELED; }
            result.putString("p00_report", report.toString()); finish(code, result);
        }
    }
}
