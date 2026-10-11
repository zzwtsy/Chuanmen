package com.github.zzwtsy.chuanmen.p00nativeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import com.easytier.jni.EasyTierJNI;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** 全字段合成配置只调用无补丁上游解析器；不启动核心、服务或 VPN。 */
public final class MappingInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    // 条件：37 核心编辑单元和 8 隐藏项；操作：逐条真实 JNI 解析；结果：原始返回或错误，不推导运行支持。
    @Override public void onStart() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle(); int code = Activity.RESULT_CANCELED;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = getTargetContext().getAssets().open("config-cases.json")) {
                byte[] block = new byte[4096]; int count;
                while ((count = input.read(block)) >= 0) bytes.write(block, 0, count);
            }
            byte[] data = bytes.toByteArray(); StringBuilder digest = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) digest.append(String.format("%02x", value & 255));
            JSONObject fixture = new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            JSONArray cases = fixture.getJSONArray("cases"), observations = new JSONArray(); boolean passed = true;
            report.put("layer", "android_native_config_parser").put("status", "failed").put("network_started", false)
                .put("core_commit", fixture.getString("candidate_commit")).put("fixture_sha256", digest.toString())
                .put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE)).put("cases", observations)
                .put("instances_before", new JSONObject(EasyTierJNI.listInstances(16)));
            for (int index = 0; index < cases.length(); index++) {
                JSONObject input = cases.getJSONObject(index), row = new JSONObject().put("name", input.getString("name"));
                boolean accepted;
                try { int rc = EasyTierJNI.parseConfig(input.getString("toml")); accepted = rc == 0; row.put("return_code", rc); }
                catch (RuntimeException error) { accepted = false; row.put("exception", error.getClass().getName()).put("error", error.getMessage()); }
                boolean matches = accepted == input.getBoolean("expected_acceptance");
                row.put("accepted", accepted).put("status", matches ? "passed" : "failed"); observations.put(row); passed &= matches;
            }
            JSONObject remaining = new JSONObject(EasyTierJNI.listInstances(16));
            report.put("instances_after", remaining);
            if (report.getJSONObject("instances_before").length() != 0 || remaining.length() != 0) passed = false;
            if (passed) { report.put("status", "passed"); code = Activity.RESULT_OK; }
        } catch (Throwable error) {
            try { report.put("error", error.toString()); } catch (Exception ignored) { }
        } finally { result.putString("p00_report", report.toString()); finish(code, result); }
    }
}
