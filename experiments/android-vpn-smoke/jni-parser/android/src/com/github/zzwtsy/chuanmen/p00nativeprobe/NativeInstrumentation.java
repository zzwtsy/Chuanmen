package com.github.zzwtsy.chuanmen.p00nativeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import com.easytier.jni.EasyTierJNI;
import org.json.JSONArray;
import org.json.JSONObject;

/** 直接加载真实上游库；解析通过不代表 TUN、protect 或 VPN 已通过。 */
public final class NativeInstrumentation extends Instrumentation {
    private final JSONArray cases = new JSONArray();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    // 条件：固定核心和已打包的 ABI；操作：调用真实 parseConfig；结果：校验返回值或具体异常。
    private void parse(String name, String input, boolean shouldAccept) throws Exception {
        JSONObject observed = new JSONObject().put("name", name).put("input", input)
            .put("expected", shouldAccept ? "accepted" : "rejected");
        boolean accepted;
        try {
            int code = EasyTierJNI.parseConfig(input);
            observed.put("return_code", code);
            accepted = code == 0;
        } catch (RuntimeException error) {
            accepted = false;
            observed.put("exception", error.getClass().getName()).put("error", error.getMessage());
            String last = EasyTierJNI.getLastError();
            observed.put("last_error", last == null ? JSONObject.NULL : last);
        }
        observed.put("status", accepted == shouldAccept ? "passed" : "failed");
        cases.put(observed);
        if (accepted != shouldAccept) throw new IllegalStateException("解析结果不符：" + name);
    }

    @Override public void onStart() {
        JSONObject report = new JSONObject();
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            report.put("schema_version", 1).put("layer", "android_native_jni_parser")
                .put("status", "failed").put("network_started", false)
                .put("core_commit", "7592951b808c4839cb9d77cc4bc642f124915951")
                .put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE)).put("cases", cases);
            // 初始化类时实际执行 System.loadLibrary，失败会进入 Throwable 分支。
            String initial = EasyTierJNI.listInstances(16);
            report.put("instances_before", new JSONObject(initial));
            if (new JSONObject(initial).length() != 0) throw new IllegalStateException("实验进程实例不为空");
            parse("missing_fields", "", true);
            parse("explicit_empty_lists", "listeners = []\nroutes = []\npeer = []\n", true);
            parse("unicode_hostname", "hostname = \"串门实验节点\"\n", true);
            parse("non_default_flags", "[flags]\nmtu = 1420\nenable_encryption = false\nlatency_first = true\n", true);
            // 官网负向 UI 字段不能原样写入核心 TOML；保留失败输入作为明确拒绝用例。
            parse("assistant_negative_flag_is_not_core_key", "[flags]\ndisable_encryption = true\n", false);
            parse("new_portal_clients", "[vpn_portal_config]\nwireguard_listen = \"127.0.0.1:51820\"\n"
                + "[[vpn_portal_config.clients]]\nname = \"p00-client\"\nvirtual_ip = \"10.222.0.10/24\"\ngroups = []\n", true);
            parse("malformed_toml", "[flags\n", false);
            parse("wrong_field_type", "hostname = 42\n", false);
            parse("legacy_portal_cidr", "[vpn_portal_config]\nclient_cidr = \"10.222.0.0/24\"\n"
                + "wireguard_listen = \"127.0.0.1:51820\"\n", false);
            String remaining = EasyTierJNI.listInstances(16);
            report.put("instances_after", new JSONObject(remaining));
            report.put("network_infos", EasyTierJNI.collectNetworkInfos(16));
            if (new JSONObject(remaining).length() != 0) throw new IllegalStateException("解析创建了网络实例");
            report.put("status", "passed");
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            try { report.put("error", error.getClass().getName() + ": " + error.getMessage()); }
            catch (org.json.JSONException ignored) { }
        } finally {
            result.putString("p00_report", report.toString());
            finish(code, result);
        }
    }
}
