package com.github.zzwtsy.chuanmen.features.configuration.lib

enum class FieldInputType { Text, Secret, Multiline, Toggle, PeerList, ReadOnly }
enum class FieldEnablement { Always, StaticIpv4 }

/** 只列已接入编辑和消费者的字段；尚未接入的参数不进入表单或搜索。 */
data class ConfigurationField(
    val key: String, val label: String, val group: String, val field: DraftField?,
    val inputType: FieldInputType, val emptySemantics: String, val source: String, val consumer: String,
    val basicVisible: Boolean = false, val enablement: FieldEnablement = FieldEnablement.Always,
) {
    fun enabled(input: RawConfigurationInput) = enablement != FieldEnablement.StaticIpv4 || !input.automatic
    fun matches(search: String) = search.trim().let { it.isEmpty() || label.contains(it, true) || key.contains(it, true) }
}

object ConfigurationFields {
    private const val CORE = "easytier-core/src/config/toml.rs"
    val all = listOf(
        ConfigurationField("network_identity.network_name", "网络名称", "网络身份", DraftField.NetworkName, FieldInputType.Text, "不能为空", "$CORE:NetworkIdentity", "CoreConfigCodec → TomlConfigLoader", basicVisible = true),
        ConfigurationField("network_identity.network_secret", "网络密码", "网络身份", DraftField.Secret, FieldInputType.Secret, "显式空密码与缺省分开保留", "$CORE:NetworkIdentity", "CoreConfigCodec → TomlConfigLoader", basicVisible = true),
        ConfigurationField("hostname", "主机名", "网络身份", DraftField.Hostname, FieldInputType.Text, "清空后使用核心默认", "$CORE:ConfigLoader::get_hostname", "CoreConfigCodec → TomlConfigLoader"),
        ConfigurationField("dhcp", "自动分配 IPv4", "地址", DraftField.Automatic, FieldInputType.Toggle, "新建默认开启；已有缺省不覆盖", "$CORE:TomlConfigLoader", "CoreConfigCodec → TomlConfigLoader"),
        ConfigurationField("ipv4", "静态 IPv4", "地址", DraftField.Ipv4, FieldInputType.Text, "缺省不分配静态地址；裸地址按 /24 接管", "$CORE:ConfigLoader::get_ipv4", "CoreConfigCodec → TomlConfigLoader", enablement = FieldEnablement.StaticIpv4),
        ConfigurationField("peer", "连接节点", "节点", DraftField.Peers, FieldInputType.PeerList, "空列表不主动连接；其他成员和协议未接入", "$CORE:PeerConfig::uri", "CoreConfigCodec → TomlConfigLoader", basicVisible = true),
        ConfigurationField("flags.mtu", "MTU", "性能", DraftField.Mtu, FieldInputType.Text, "新建默认 1380", "easytier-proto/proto/common.proto:Flags.mtu", "TunPlatform.kt:interfaceSpec"),
        ConfigurationField("android.internetRouting", "包含互联网流量", "Android", DraftField.Internet, FieldInputType.Toggle, "新建关闭", "AndroidVpnPolicy.includeInternet", "TunPlatform.kt:interfaceSpec"),
        ConfigurationField("android.dnsServers", "DNS IPv4", "Android", DraftField.Dns, FieldInputType.Multiline, "空列表不额外指定 DNS", "AndroidVpnPolicy.dnsServers", "NativeVpnService.kt:establish"),
        ConfigurationField("android.ipv6Policy", "IPv6：阻断", "Android", null, FieldInputType.ReadOnly, "固定阻断，无其他可选能力", "AndroidVpnPolicy.ipv6", "NativeVpnService.kt:establish（不添加 IPv6 地址和路由）"),
    )
}
