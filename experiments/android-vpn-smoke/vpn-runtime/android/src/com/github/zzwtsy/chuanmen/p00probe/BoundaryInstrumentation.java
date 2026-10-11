package com.github.zzwtsy.chuanmen.p00probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import com.easytier.jni.EasyTierJNI;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** P00-04 真实原生边界实验：无 peer、无监听、禁用外部发现，不作连通性声明。 */
public final class BoundaryInstrumentation extends Instrumentation {
    private BoundaryVpnService service;
    private final CountDownLatch connected = new CountDownLatch(1);
    private boolean abort;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); abort = args != null && "true".equals(args.getString("panic")); start();
    }
    private int count(String path) {
        String[] entries = new File(path).list();
        if (entries == null) throw new IllegalStateException("资源计数不可读");
        return entries.length;
    }
    private void protectorCase(String name, boolean reject, boolean throwing, JSONArray tests) throws Exception {
        P00NativeBridge.resetObservations();
        service.rejectProtect = reject; service.throwProtect = throwing;
        if (P00NativeBridge.installProtector(service) != 0) throw new IllegalStateException("protector 注册失败");
        JSONObject sockets = new JSONObject(P00NativeBridge.probeProtectedSockets());
        JSONObject observations = new JSONObject(P00NativeBridge.observations());
        JSONArray callbacks = observations.getJSONArray("protections");
        boolean expected = !reject && !throwing;
        if (sockets.getBoolean("tcp_created") != expected || sockets.getBoolean("udp_created") != expected
                || callbacks.length() != 2 || service.callbackOnMain) throw new IllegalStateException("保护回执不符：" + name);
        for (int i = 0; i < 2; i++) {
            if (callbacks.getJSONObject(i).getInt("before_port") != 0
                    || callbacks.getJSONObject(i).getBoolean("success") != expected) throw new IllegalStateException("保护顺序不符");
        }
        tests.put(new JSONObject().put("name", name).put("status", "passed").put("sockets", sockets)
            .put("observations", observations));
        P00NativeBridge.clearProtector();
    }
    private String configuration() {
        return "instance_name = \"p00-boundary\"\nhostname = \"p00-android\"\nipv4 = \"10.222.0.2/24\"\n"
            + "dhcp = false\nlisteners = []\npeer = []\nstun_servers = []\ntcp_stun_servers = []\nstun_servers_v6 = []\n"
            + "[network_identity]\nnetwork_name = \"chuanmen-p00-isolated\"\nnetwork_secret = \"p00-synthetic-public-test-only\"\n"
            + "[flags]\nenable_ipv6 = false\ndisable_upnp = true\ndisable_udp_hole_punching = true\ndisable_tcp_hole_punching = true\n";
    }

    // 条件：受控真机已有 VPN 授权；操作：真实核心安装/停止 20 轮；结果：确认释放后才关闭 PFD。
    @Override public void onStart() {
        if (abort) { P00NativeBridge.abortForExperiment(); return; }
        JSONObject report = new JSONObject(); Bundle result = new Bundle();
        Context context = getTargetContext(); boolean bound = false; int code = Activity.RESULT_CANCELED;
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((BoundaryVpnService.LocalBinder) binder).service(); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { service = null; }
        };
        try {
            report.put("schema_version", 1).put("layer", "android_native_boundary").put("status", "failed")
                .put("core_commit", "7592951b808c4839cb9d77cc4bc642f124915951").put("connectivity", "unverified");
            if (VpnService.prepare(context) != null) throw new IllegalStateException("缺少人工 VPN 授权");
            Bundle phase = new Bundle(); phase.putString("p00_phase", "awaiting_activity"); sendStatus(1, phase);
            if (!BoundaryActivity.resumed.await(10, TimeUnit.SECONDS) || BoundaryActivity.active == null) throw new IllegalStateException("页面恢复超时");
            runOnMainSync(new Runnable() { @Override public void run() { BoundaryActivity.active.startProbe(); } });
            bound = context.bindService(new Intent(context, BoundaryVpnService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("服务绑定超时");
            JSONArray protect = new JSONArray(); report.put("protector_tests", protect);
            protectorCase("real_platform_ack", false, false, protect);
            protectorCase("false_blocks_creation", true, false, protect);
            protectorCase("exception_blocks_creation", false, true, protect);
            service.rejectProtect = false; service.throwProtect = false;
            String config = configuration(); report.put("configuration", config);
            JSONArray rounds = new JSONArray(); report.put("rounds", rounds);
            // 首次真实会话用于初始化核心全局 runtime；原始观察保留，后续再核对 20 轮趋势。
            P00NativeBridge.resetObservations(); service.startCore("p00-boundary", config, null);
            report.put("warmup", service.stopCore()); SystemClock.sleep(1000);
            int baselineFd = count("/proc/self/fd"), baselineThreads = count("/proc/self/task");
            report.put("baseline_fd", baselineFd).put("baseline_threads", baselineThreads);
            for (int index = 1; index <= 20; index++) {
                P00NativeBridge.resetObservations(); service.startCore("p00-boundary", config, null);
                JSONObject running = new JSONObject(P00NativeBridge.observations());
                JSONObject infos = NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16));
                JSONObject closed = service.stopCore(); SystemClock.sleep(300);
                int fd = count("/proc/self/fd"), threads = count("/proc/self/task");
                JSONObject round = new JSONObject().put("round", index).put("running", running).put("network_infos", infos)
                    .put("closed", closed).put("fd", fd).put("threads", threads).put("status", "passed");
                rounds.put(round);
                if (fd > baselineFd || threads > baselineThreads) {
                    round.put("status", "failed"); throw new IllegalStateException("停止后资源超过预热基线");
                }
            }
            report.put("callback_on_main", service.callbackOnMain).put("status", "passed"); code = Activity.RESULT_OK;
        } catch (Throwable error) {
            try { report.put("error", error.getClass().getName() + ": " + error.getMessage()); }
            catch (org.json.JSONException ignored) { }
        } finally {
            if (bound) context.unbindService(connection);
            context.stopService(new Intent(context, BoundaryVpnService.class));
            try {
                boolean stopped = BoundaryVpnService.destroyed.await(10, TimeUnit.SECONDS);
                report.put("service_destroyed", stopped).put("cleanup_error", BoundaryVpnService.cleanupError == null ? JSONObject.NULL : BoundaryVpnService.cleanupError);
                if (!stopped || BoundaryVpnService.cleanupError != null) { report.put("status", "failed"); code = Activity.RESULT_CANCELED; }
            } catch (Exception error) { code = Activity.RESULT_CANCELED; }
            result.putString("p00_report", report.toString()); finish(code, result);
        }
    }
}
