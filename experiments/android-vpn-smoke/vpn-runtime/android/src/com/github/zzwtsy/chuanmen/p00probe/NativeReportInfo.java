package com.github.zzwtsy.chuanmen.p00probe;

import java.util.Iterator;
import org.json.JSONObject;

/** 保留实验实例和虚拟地址证据；不导出无关物理网卡地址列表。 */
final class NativeReportInfo {
    static JSONObject scoped(String raw) throws Exception {
        JSONObject infos = new JSONObject(raw);
        JSONObject map = infos.optJSONObject("map");
        if (map != null) {
            Iterator<String> names = map.keys();
            while (names.hasNext()) {
                JSONObject node = map.getJSONObject(names.next()).optJSONObject("my_node_info");
                if (node != null) node.remove("ips");
            }
        }
        return infos;
    }
}
