package com.github.zzwtsy.chuanmen.p00probe;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.View;
import com.easytier.jni.EasyTierJNI;
import java.nio.charset.StandardCharsets;
import java.io.OutputStream;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;

/** 实验页面只启动已授权的服务；真实核心操作由有界 instrumentation 串行执行。 */
public final class BoundaryActivity extends Activity {
    static final CountDownLatch resumed = new CountDownLatch(1);
    static volatile BoundaryActivity active;
    static volatile int permissionResult = Integer.MIN_VALUE;
    static volatile CountDownLatch permissionFinished = new CountDownLatch(1);
    private boolean offlineStarted;
    private boolean performanceStarted;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView message = new TextView(this);
        message.setText("Chuanmen P00 原生边界实验\n使用真实 EasyTier 和本项目观察补丁。\n"
            + "VPN 仅覆盖本实验包及测试路由；结果见设备实验报告。");
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(message);
        Button authorize = new Button(this); authorize.setText("授权实验 VPN");
        authorize.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestAuthorization(); }
        });
        content.addView(authorize); setContentView(content);
    }
    /** 显式打开系统授权；取消只记录结果，不启动核心或前台服务。 */
    void requestAuthorization() {
        Intent intent = VpnService.prepare(this);
        permissionResult = Integer.MIN_VALUE; permissionFinished = new CountDownLatch(1);
        if (intent != null) startActivityForResult(intent, 42);
        else { permissionResult = RESULT_OK; permissionFinished.countDown(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 42) {
            permissionResult = result; permissionFinished.countDown();
            // 保留真实系统 Activity 回执；该页面不自动启动核心或服务，供拒绝授权实验独立取证。
            try (OutputStream stream = openFileOutput("p00-permission.json", MODE_PRIVATE)) {
                JSONObject report = new JSONObject().put("layer", "android_native_permission")
                    .put("permission_result", result).put("permission_granted", VpnService.prepare(this) == null)
                    .put("core_instances", new JSONObject(EasyTierJNI.listInstances(16)))
                    .put("elapsed_realtime_ns", android.os.SystemClock.elapsedRealtimeNanos());
                stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { throw new IllegalStateException("授权回执记录失败", error); }
        }
    }
    @Override protected void onResume() {
        super.onResume(); active = this; resumed.countDown();
        String host = getIntent().getStringExtra("p00_offline_host");
        String performanceHost = getIntent().getStringExtra("p00_performance_host");
        if (host != null && performanceHost != null) throw new IllegalStateException("只允许一个实验所有者");
        if (performanceHost != null && !performanceStarted) {
            performanceStarted = true;
            PerformanceExperiment.start(this, performanceHost, getIntent().getStringExtra("p00_run_id"),
                getIntent().getIntExtra("p00_idle_seconds", 1800));
        }
        if (host != null && !offlineStarted) {
            offlineStarted = true;
            OfflineExperiment.start(this, host, getIntent().getStringExtra("p00_run_id"));
        }
    }
    @Override protected void onDestroy() {
        if (active == this) active = null;
        super.onDestroy();
    }
    void startProbe() {
        if (VpnService.prepare(this) != null) throw new IllegalStateException("实验包尚未授权 VPN");
        Intent intent = new Intent(this, BoundaryVpnService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }
}
