"""从冻结映射生成 P00 合成解析输入；只覆盖探针，不实现产品导入器。"""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
BASE = 'instance_id = "00000000-0000-4000-8000-000000000003"\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    declaration = json.loads((ROOT / "experiment.json").read_text())
    name = declaration["reproduction"]["inputs"]["config_mapping"]
    relative = PurePosixPath(name)
    if (
        relative.is_absolute()
        or ".." in relative.parts
        or "\\" in name
        or relative.as_posix() != name
    ):
        parser.error("配置映射必须是规范的仓库相对路径")
    source = REPO / name
    if not source.resolve().is_relative_to(REPO):
        parser.error("配置映射越过仓库边界")
    mapping = json.loads(source.read_text())
    cases = [
        {
            "name": "missing_fields",
            "editors": [
                f["editor_key"] for f in mapping["fields"] if f["scope"] == "core"
            ],
            "kind": "missing",
            "toml": BASE,
            "expected_acceptance": True,
        }
    ]

    def add(name, key, kind, fragment, expected=True):
        cases.append(
            {
                "name": name,
                "editors": [key],
                "kind": kind,
                "toml": BASE + fragment,
                "expected_acceptance": expected,
            }
        )

    # 参数均为合成数据；不含真实网络凭据、客户端私钥或个人应用名单。
    samples = {
        "network_identity.network_name": (
            '[network_identity]\nnetwork_name="p00-config"\n',
            '[network_identity]\nnetwork_name=""\n',
        ),
        "network_identity.network_secret": (
            '[network_identity]\nnetwork_name="p00-config"\nnetwork_secret="p00-synthetic-public-test-only"\n',
            '[network_identity]\nnetwork_name="p00-config"\nnetwork_secret=""\n',
        ),
        "hostname": ('hostname="串门\\n配置实验"\n', 'hostname=""\n'),
        "dhcp": ("dhcp=true\n", "dhcp=false\n"),
        "ipv4": ('ipv4="10.222.0.2/24"\n', 'ipv4=""\n'),
        "peer": ('[[peer]]\nuri="tcp://[fd00::1]:31270/p00?q=test"\n', "peer=[]\n"),
        "listeners": ('listeners=["tcp://127.0.0.1:31375"]\n', "listeners=[]\n"),
        "mapped_listeners": (
            'mapped_listeners=["tcp://127.0.0.1:31375"]\n',
            "mapped_listeners=[]\n",
        ),
        "routes": ('routes=["10.223.0.0/24"]\n', "routes=[]\n"),
        "exit_nodes": ('exit_nodes=["10.222.0.1","fd00::1"]\n', "exit_nodes=[]\n"),
        "proxy_network": (
            '[[proxy_network]]\ncidr="127.0.0.1/32"\nmapped_cidr="10.223.0.1/32"\nallow=["p00-group"]\n',
            "proxy_network=[]\n",
        ),
        "socks5_proxy": (
            'socks5_proxy="socks5://127.0.0.1:31373"\n',
            'socks5_proxy=""\n',
        ),
        "port_forward": (
            '[[port_forward]]\nproto="tcp"\nbind_addr="127.0.0.1:31371"\ndst_addr="10.222.0.1:31271"\n[[port_forward]]\nproto="udp"\nbind_addr="127.0.0.1:31372"\ndst_addr="10.222.0.1:31272"\n',
            "port_forward=[]\n",
        ),
        "vpn_portal_config": (
            '[vpn_portal_config]\nenabled=true\nwireguard_listen="127.0.0.1:31374"\n[[vpn_portal_config.clients]]\nname="p00-client"\nvirtual_ip="10.222.0.10/24"\ngroups=[]\n',
            '[vpn_portal_config]\nenabled=false\nwireguard_listen="127.0.0.1:31374"\nclients=[]\n',
        ),
        "acl": (
            '[acl.acl_v1]\n[[acl.acl_v1.chains]]\nname="p00-inbound"\nchain_type="Inbound"\nenabled=true\ndefault_action="Drop"\n[[acl.acl_v1.chains.rules]]\nname="p00-allow-tcp"\nenabled=true\nprotocol="TCP"\nports=["31271"]\nsource_ips=["10.222.0.0/24"]\naction="Allow"\n',
            "[acl.acl_v1]\nchains=[]\n",
        ),
    }
    for field in mapping["fields"]:
        if field["scope"] != "core":
            continue
        key = field["editor_key"]
        if key.startswith("flags."):
            name = key[6:]
            dtype = field["candidate_type"]
            values = (
                [True, False]
                if dtype == "bool"
                else [1280, 1380]
                if dtype == "uint32"
                else [1000000, 18446744073709551615]
                if dtype == "uint64"
                else ["p00-group", ""]
            )
            for index, value in enumerate(values):
                add(
                    key + "_" + str(index),
                    key,
                    "explicit",
                    "[flags]\n"
                    + name
                    + "="
                    + json.dumps(value, ensure_ascii=False)
                    + "\n",
                )
        else:
            first, empty = samples[key]
            add(key + "_explicit", key, "explicit", first)
            add(key + "_empty", key, "empty_or_disabled", empty, key != "socks5_proxy")
    hidden = [x["key"] for x in mapping["hidden_review"]]
    for key in hidden:
        fragment = (
            "ipv6_public_addr_auto=true\n"
            if key == "ipv6_public_addr_auto"
            else "[flags]\n"
            + key[6:]
            + "="
            + ('"p00-ignored-tun"' if key == "flags.dev_name" else "true")
            + "\n"
        )
        add(key + "_hidden_explicit", key, "hidden_explicit", fragment)
    for name, key, fragment in [
        (
            "legacy_disable_encryption",
            "flags.enable_encryption",
            "[flags]\ndisable_encryption=true\n",
        ),
        ("legacy_disable_ipv6", "flags.enable_ipv6", "[flags]\ndisable_ipv6=true\n"),
        (
            "legacy_portal_client_cidr",
            "vpn_portal_config",
            '[vpn_portal_config]\nwireguard_listen="127.0.0.1:31374"\nclient_cidr="10.222.0.0/24"\n',
        ),
        ("wrong_hostname_type", "hostname", "hostname=42\n"),
        ("wrong_mtu_type", "flags.mtu", '[flags]\nmtu="large"\n'),
    ]:
        add(name, key, "invalid", fragment, False)
    output = args.out
    if output.resolve().is_relative_to(REPO) or output.exists():
        parser.error("输出必须是仓库外的新文件；固定 fixture 不允许覆盖")
    output.parent.mkdir(parents=True, exist_ok=True)
    data = (
        json.dumps(
            {
                "schema_version": 1,
                "task": "P00-03",
                "candidate_commit": mapping["candidate_commit"],
                "catalog_sha256": mapping["catalog_sha256"],
                "core_editor_count": 37,
                "hidden_count": 8,
                "network_started": False,
                "cases": cases,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    output.write_text(data)
    print(
        json.dumps(
            {
                "cases": len(cases),
                "path": str(output),
                "sha256": hashlib.sha256(data.encode()).hexdigest(),
            }
        )
    )


if __name__ == "__main__":
    main()
