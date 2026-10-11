package com.github.zzwtsy.chuanmen.p00probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.VpnService;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import com.easytier.jni.EasyTierJNI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** 通过 UiAutomation 操作实际系统授权对话框；不注入 Activity 结果或模拟授权状态。 */
public final class PermissionDeniedInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    // 条件：真实授权已撤销；操作：仅点击系统 VPN 对话框的取消；结果：实际 RESULT_CANCELED、无实例和服务。
    @Override public void onStart() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            report.put("layer", "android_native_permission_denial").put("status", "failed");
            if (VpnService.prepare(getTargetContext()) == null) throw new IllegalStateException("授权仍有效，拒绝实验前置条件不成立");
            Bundle phase = new Bundle(); phase.putString("p00_phase", "awaiting_activity"); sendStatus(1, phase);
            if (!BoundaryActivity.resumed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("页面未恢复");
            runOnMainSync(new Runnable() { @Override public void run() { BoundaryActivity.active.requestAuthorization(); } });
            long deadline = SystemClock.elapsedRealtime() + 10000; boolean clicked = false;
            while (SystemClock.elapsedRealtime() < deadline && !clicked) {
                AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (root != null && "com.android.vpndialogs".contentEquals(root.getPackageName())) {
                    List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button2");
                    for (AccessibilityNodeInfo button : buttons) {
                        CharSequence text = button.getText();
                        if ("取消".contentEquals(text == null ? "" : text) || "Cancel".contentEquals(text == null ? "" : text)) {
                            clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                    }
                }
                if (!clicked) SystemClock.sleep(100);
            }
            if (!clicked || !BoundaryActivity.permissionFinished.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("未识别系统取消按钮或未收到真实回执");
            JSONObject instances = new JSONObject(EasyTierJNI.listInstances(16));
            boolean granted = VpnService.prepare(getTargetContext()) == null;
            report.put("system_dialog_package", "com.android.vpndialogs").put("action", "actual_cancel_button")
                .put("permission_result", BoundaryActivity.permissionResult).put("permission_granted", granted)
                .put("instances_after", instances).put("service_destroyed", BoundaryVpnService.destroyed.getCount() == 0);
            if (BoundaryActivity.permissionResult != Activity.RESULT_CANCELED || granted || instances.length() != 0
                    || BoundaryVpnService.destroyed.getCount() != 0) throw new IllegalStateException("拒绝后启动了会话或授权有效");
            report.put("status", "passed"); code = Activity.RESULT_OK;
        } catch (Throwable error) { try { report.put("error", error.toString()); } catch (Exception ignored) { } }
        result.putString("p00_report", report.toString()); finish(code, result);
    }
}
