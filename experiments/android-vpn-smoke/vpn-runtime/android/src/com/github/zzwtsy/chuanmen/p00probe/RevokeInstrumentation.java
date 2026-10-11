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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** 等待用户在系统设置撤销本实验 VPN，记录框架回调；禁止直接调用 onRevoke 伪造结果。 */
public final class RevokeInstrumentation extends Instrumentation {
    private String host;
    private BoundaryVpnService service;
    private final CountDownLatch connected = new CountDownLatch(1);
    @Override public void onCreate(Bundle args) { super.onCreate(args); host = args.getString("peer_host"); start(); }

    // 条件：同一真机已有授权和受控 peer；操作：等系统实际撤销；结果：停止借用、关闭 PFD、清空实例和服务。
    @Override public void onStart() {
        Context context = getTargetContext(); JSONObject report = new JSONObject(); Bundle result = new Bundle();
        boolean bound = false; int code = Activity.RESULT_CANCELED;
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((BoundaryVpnService.LocalBinder) binder).service(); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        try {
            report.put("layer", "android_native_revoke").put("status", "failed");
            if (host == null || !host.matches("192\\.168\\.[0-9]{1,3}\\.[0-9]{1,3}") || VpnService.prepare(context) != null)
                throw new IllegalStateException("受控对端或授权前置条件不成立");
            Bundle phase = new Bundle(); phase.putString("p00_phase", "awaiting_activity"); sendStatus(1, phase);
            if (!BoundaryActivity.resumed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("页面未恢复");
            runOnMainSync(new Runnable() { @Override public void run() { BoundaryActivity.active.startProbe(); } });
            bound = context.bindService(new Intent(context, BoundaryVpnService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("服务未绑定");
            P00NativeBridge.resetObservations(); service.startCore("p00-datapath", DatapathInstrumentation.configuration(host), host);
            long deadline = SystemClock.elapsedRealtime() + 10000; JSONObject info = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                info = NativeReportInfo.scoped(EasyTierJNI.collectNetworkInfos(16));
                JSONObject instance = info.getJSONObject("map").optJSONObject("p00-datapath");
                if (instance != null && instance.has("peers") && instance.getJSONArray("peers").length() > 0) break;
                SystemClock.sleep(50);
            }
            if (info == null || info.getJSONObject("map").getJSONObject("p00-datapath").optJSONArray("peers") == null
                    || info.getJSONObject("map").getJSONObject("p00-datapath").getJSONArray("peers").length() == 0)
                throw new IllegalStateException("未连接受控 peer，不能开始撤销实验");
            report.put("before_revoke", info);
            phase.putString("p00_phase", "awaiting_system_revoke"); sendStatus(2, phase);
            if (!service.revocationFinished.await(180, TimeUnit.SECONDS)) throw new IllegalStateException("未收到系统撤销回调");
            report.put("revoked", service.revoked).put("closed", service.revocationClosed)
                .put("permission_granted_after", VpnService.prepare(context) == null)
                .put("instances_after", new JSONObject(EasyTierJNI.listInstances(16)))
                .put("observations", new JSONObject(P00NativeBridge.observations()));
            if (!service.revoked || service.revocationClosed == null || BoundaryVpnService.cleanupError != null
                    || new JSONObject(EasyTierJNI.listInstances(16)).length() != 0) throw new IllegalStateException("系统撤销后的资源未释放");
            report.put("status", "passed"); code = Activity.RESULT_OK;
        } catch (Throwable error) {
            try { report.put("error", error.toString()); if (service != null) report.put("failure_closed", service.stopCore()); }
            catch (Exception cleanup) { try { report.put("cleanup_error", cleanup.toString()); } catch (Exception ignored) { } }
        } finally {
            if (bound) context.unbindService(connection); context.stopService(new Intent(context, BoundaryVpnService.class));
            try {
                boolean destroyed = BoundaryVpnService.destroyed.await(30, TimeUnit.SECONDS);
                report.put("service_destroyed", destroyed).put("cleanup_error", BoundaryVpnService.cleanupError == null ? JSONObject.NULL : BoundaryVpnService.cleanupError);
                // 只查询本实验应用自己的系统通知，不读取其他应用或个人通知。
                long deadline = SystemClock.elapsedRealtime() + 2000;
                NotificationManager notifications = context.getSystemService(NotificationManager.class);
                while (notifications.getActiveNotifications().length > 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                int remaining = notifications.getActiveNotifications().length;
                report.put("own_active_notifications_after", remaining);
                if (!destroyed || BoundaryVpnService.cleanupError != null || remaining != 0) { report.put("status", "failed"); code = Activity.RESULT_CANCELED; }
            } catch (Exception error) { code = Activity.RESULT_CANCELED; }
            result.putString("p00_report", report.toString()); finish(code, result);
        }
    }
}
