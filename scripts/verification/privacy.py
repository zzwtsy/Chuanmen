"""证据发布前的隐私检查与确定性脱敏；诊断只返回位置和规则。"""

from __future__ import annotations

import re

HOME = re.compile(r'/(?:home|Users)/[^/\s"\'<>\\]+(?:/|(?=$|[\s"\'<>]))')
WINDOWS_HOME = re.compile(r'(?i)[A-Z]:[\\/]+Users[\\/]+[^\\/\s"\'<>]+')
PATTERNS = {
    "personal_home_path": HOME,
    "windows_home_path": WINDOWS_HOME,
    "private_key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "credential_url": re.compile(r"https?://[^/\s:@]+:[^/\s@]+@"),
    "access_token": re.compile(
        r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|AKIA[A-Z0-9]{16}|sk-(?:proj-)?[A-Za-z0-9_-]{32,})\b"
    ),
}
IDENTIFIER_FIELDS = {
    "serial",
    "serial_sha256",
    "device_serial",
    "machine_hostname",
    "host_username",
}
PLACEHOLDER = re.compile(r"^(?:<[^>]+>|\$\{[A-Z_]+\}|device-\d+|host-\d+)$")


def findings(value, location="$"):
    """只报告字段位置；合成配置 hostname 不等同于宿主身份字段。"""
    issues = []
    if isinstance(value, dict):
        for key, item in value.items():
            child = location + "." + key
            if (
                key in IDENTIFIER_FIELDS
                and item
                and not (isinstance(item, str) and PLACEHOLDER.fullmatch(item))
            ):
                issues.append(
                    {"field": child, "rule": "persistent_device_or_host_identifier"}
                )
            if key == "uname" and isinstance(item, str):
                parts = item.split()
                if (
                    len(parts) > 2
                    and parts[0] in ("Linux", "Darwin")
                    and not PLACEHOLDER.fullmatch(parts[1])
                ):
                    issues.append({"field": child, "rule": "uname_host_identifier"})
            if key in ("host_bind", "peer_host", "physical_device_ip") and isinstance(
                item, str
            ):
                host = item.split(":")[0]
                if host and not (
                    host.startswith(
                        ("<", "$", "127.", "192.0.2.", "198.51.100.", "203.0.113.")
                    )
                    or host in ("localhost", "0.0.0.0")
                ):
                    issues.append({"field": child, "rule": "real_network_endpoint"})
            issues.extend(findings(item, child))
    elif isinstance(value, list):
        for i, item in enumerate(value):
            issues.extend(findings(item, location + "[" + str(i) + "]"))
    elif isinstance(value, str):
        for rule, pattern in PATTERNS.items():
            if pattern.search(value):
                issues.append({"field": location, "rule": rule})
        # 命令内即使 serial 字段缺失，也不能发布真实 ADB 选择器。
        if re.search(r"\badb\s+-s\s+(?!<|\$\{)\S+", value):
            issues.append({"field": location, "rule": "adb_device_identifier"})
    if isinstance(value, list):
        for i, item in enumerate(value[:-1]):
            if (
                item in ("-s", "--serial")
                and isinstance(value[i + 1], str)
                and not PLACEHOLDER.fullmatch(value[i + 1])
            ) and any(
                isinstance(x, str) and (x.endswith("/adb") or x == "adb")
                for x in value[:i]
            ):
                issues.append(
                    {
                        "field": location + "[" + str(i + 1) + "]",
                        "rule": "adb_device_identifier",
                    }
                )
    return issues


def sanitize(value, replacements=None):
    """替换位置和设备标识；不会把秘密自动改成看似可发布的合成数据。"""
    replacements = replacements or {}
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if key in IDENTIFIER_FIELDS:
                if item:
                    result["device_id" if "serial" in key else "host_id"] = (
                        "device-01" if "serial" in key else "host-01"
                    )
            elif key == "uname" and isinstance(item, str) and len(item.split()) > 2:
                parts = item.split()
                parts[1] = "<host-01>"
                result[key] = " ".join(parts)
            elif key not in ("source_cache_path", "source_root"):
                result[key] = sanitize(item, replacements)
        return result
    if isinstance(value, list):
        result = [sanitize(item, replacements) for item in value]
        for i, item in enumerate(result[:-1]):
            if item in ("-s", "--serial") and any(
                isinstance(x, str) and (x.endswith("/adb") or x == "adb")
                for x in result[:i]
            ):
                result[i + 1] = "<controlled-serial>"
        return result
    if isinstance(value, str):
        if value.startswith(("Linux ", "Darwin ")) and len(value.split()) > 2:
            parts = value.split()
            parts[1] = "<host-01>"
            value = " ".join(parts)
        for original, logical in sorted(
            replacements.items(), key=lambda pair: -len(pair[0])
        ):
            value = value.replace(original, logical)
        value = HOME.sub("<USER_HOME>/", value)
        value = WINDOWS_HOME.sub("<USER_HOME>", value)
        return value
    return value
