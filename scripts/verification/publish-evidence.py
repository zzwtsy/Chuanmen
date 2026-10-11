"""预览或发布选定运行；检查脱敏、判据和预算后冻结，禁止覆盖已有运行。"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
from copy import deepcopy
from pathlib import Path

from evidence_io import Tree, digest, json_bytes, relative_name
from privacy import findings, sanitize

_spec = importlib.util.spec_from_file_location(
    "evidence_check", Path(__file__).with_name("check-evidence.py")
)
_checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_checker)


def network_locations(value, mapping):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in ("host_bind", "peer_host", "physical_device_ip") and isinstance(
                item, str
            ):
                host = item.split(":")[0]
                if (
                    host
                    and not host.startswith(("<", "$", "127."))
                    and host not in ("localhost", "0.0.0.0")
                ):
                    mapping[host] = (
                        "<DEVICE_LAN_IP>"
                        if key == "physical_device_ip"
                        else "<HOST_LAN_IP>"
                    )
            network_locations(item, mapping)
    elif isinstance(value, list):
        for item in value:
            network_locations(item, mapping)


def prepare(root, experiment, run, stage):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", experiment):
        raise ValueError("实验名称无效")
    declaration = Tree(root).object("experiments/" + experiment + "/experiment.json")
    bundle = json.loads((run / "bundle.json").read_bytes())
    if bundle.get("declaration_sha256") != digest(json_bytes(declaration)):
        raise ValueError("实验声明与运行前记录的摘要不一致")
    case = next(
        (case for case in declaration["cases"] if case["id"] == bundle.get("case_id")),
        None,
    )
    if not case or bundle.get("criteria") != case["criteria"]:
        raise ValueError("运行判据必须等于事先声明的场景判据")
    run_id = bundle["run_id"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", run_id):
        raise ValueError("运行名称无效")
    if (
        bundle["experiment_id"] != experiment
        or declaration["experiment_id"] != experiment
    ):
        raise ValueError("实验身份不一致")
    if not bundle.get("actor") or not bundle.get("scope") or not bundle.get("criteria"):
        raise ValueError("运行缺少执行者、范围或判据")
    if not bundle["claim_ids"] or not set(bundle["claim_ids"]) <= set(
        declaration["claim_ids"]
    ):
        raise ValueError("运行结论超出实验声明")
    prefix = "docs/verification/" + relative_name(stage)
    tree = Tree(root)
    manifest_bytes = tree.read(prefix + "/manifest.json")
    manifest = json.loads(manifest_bytes)
    if manifest.get("schema_version") != 3 or not set(bundle["claim_ids"]) <= set(
        manifest["claims"]
    ):
        raise ValueError("需要现有版本 3 阶段和已声明结论")
    destination = tree.path(prefix + "/runs/" + run_id)
    if destination.exists():
        raise ValueError("运行已存在，禁止覆盖")
    mapping = {str(root.resolve()): "<REPO_ROOT>", str(run.resolve()): "<RUN_ROOT>"}
    for item in bundle["records"]:
        name = relative_name(item["path"])
        path = run / name
        if path.is_symlink() or not path.resolve().is_relative_to(run.resolve()):
            raise ValueError("运行输入越界")
        if name.endswith(".json"):
            network_locations(json.loads(path.read_bytes()), mapping)
    selected, entries, records = {}, [], {}
    for item in bundle["records"]:
        name = relative_name(item["path"])
        path = run / name
        if (
            path.is_symlink()
            or not path.resolve().is_relative_to(run.resolve())
            or not path.is_file()
        ):
            raise ValueError("运行输入越界或缺失")
        if name in selected or name in (
            "publication.json",
            "manifest.json",
            "bundle.json",
        ):
            raise ValueError("运行输入重复或占用保留名称")
        raw = path.read_bytes()
        if name.endswith(".json"):
            value = sanitize(json.loads(raw), mapping)
            if isinstance(value, dict):
                value["publication"] = {
                    "original_sha256": digest(raw),
                    "experiment_id": experiment,
                    "actor": bundle["actor"],
                    "scope": bundle["scope"],
                    "form": "sanitized_selected_record",
                }
                if item.get("build_record"):
                    value["publication"]["build_ref"] = (
                        prefix
                        + "/runs/"
                        + run_id
                        + "/"
                        + relative_name(item["build_record"])
                    )
            records[name] = value
            data = json_bytes(value)
        else:
            value = sanitize(raw.decode("utf-8"), mapping)
            data = value.encode()
        if findings(value):
            raise ValueError("脱敏后仍有隐私或凭据问题")
        selected[name] = data
        if not item.get("role"):
            raise ValueError("文件缺少用途")
        entries.append(
            {
                "path": prefix + "/runs/" + run_id + "/" + name,
                "sha256": digest(data),
                "role": item["role"],
                "claim_ids": bundle["claim_ids"],
            }
        )
    for criterion in bundle["criteria"]:
        name = criterion["record"]
        if name not in records or not _checker.evaluate(
            _checker.pointer(records[name], criterion["pointer"]),
            criterion["operation"],
            criterion.get("expected"),
        ):
            raise ValueError("运行判据未满足")
    receipt = {
        "schema_version": 1,
        "experiment_id": experiment,
        "run_id": run_id,
        "actor": bundle["actor"],
        "scope": bundle["scope"],
        "claim_ids": bundle["claim_ids"],
        "criteria": bundle["criteria"],
        "original_bytes_retained": False,
        "experiment_reexecuted_by_publisher": False,
    }
    if findings(receipt):
        raise ValueError("发布声明含隐私信息")
    selected["publication.json"] = json_bytes(receipt)
    entries.append(
        {
            "path": prefix + "/runs/" + run_id + "/publication.json",
            "sha256": digest(selected["publication.json"]),
            "role": "publication",
            "claim_ids": bundle["claim_ids"],
        }
    )
    budget = declaration["publication_budget"]
    if (
        len(selected) > budget["files"]
        or sum(map(len, selected.values())) > budget["bytes"]
    ):
        raise ValueError("发布材料超过实验声明预算")
    new_manifest = deepcopy(manifest)
    new_manifest["revision"] = manifest.get("revision", 1) + 1
    new_manifest["entries"] = sorted(
        manifest["entries"] + entries, key=lambda item: item["path"]
    )
    return prefix, destination, manifest_bytes, new_manifest, selected


def _publish_unlocked(root, experiment, run, stage, execute=False):
    prefix, destination, previous, manifest, selected = prepare(
        root, experiment, run, stage
    )
    result = {
        "status": "preview",
        "files": len(selected),
        "bytes": sum(map(len, selected.values())),
        "destination": prefix + "/runs/" + destination.name,
    }
    if not execute:
        return result
    destination.parent.mkdir(parents=True, exist_ok=True)
    manifest_path = root / prefix / "manifest.json"
    temporary = Path(tempfile.mkdtemp(prefix=".publish-", dir=destination.parent))
    moved = False
    try:
        for name, data in selected.items():
            path = temporary / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        if manifest_path.read_bytes() != previous or destination.exists():
            raise ValueError("发布期间输入发生变化")
        temporary.rename(destination)
        moved = True
        replace_manifest(manifest_path, json_bytes(manifest))
        checked = _checker.check(Tree(root), stage)
        if checked["integrity"] != "passed":
            raise ValueError("发布后的阶段完整性检查未通过")
    except BaseException:
        if moved:
            replace_manifest(manifest_path, previous)
            shutil.rmtree(destination)
        raise
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    result["status"] = "published"
    result["handoff_refresh_required"] = True
    return result


def replace_manifest(path, data):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=".manifest-", dir=path.parent, delete=False
        ) as stream:
            temporary = Path(stream.name)
            stream.write(data)
        os.replace(temporary, path)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def publish(root, experiment, run, stage, execute=False):
    if not execute:
        return _publish_unlocked(root, experiment, run, stage, False)
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", stage):
        raise ValueError("阶段名称无效")
    build = Tree(root).path("build")
    build.mkdir(exist_ok=True)
    lock = build / ("verification-" + stage + "-publish.lock")
    try:
        stream = lock.open("x")
    except FileExistsError:
        raise ValueError("该阶段已有发布操作，停止本次发布") from None
    try:
        with stream:
            return _publish_unlocked(root, experiment, run, stage, True)
    finally:
        lock.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    parser.add_argument("--experiment", required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--stage", default="p00")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true")
    mode.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    try:
        result = publish(
            args.repo_root.resolve(),
            args.experiment,
            args.run.resolve(),
            args.stage,
            args.publish,
        )
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = {"status": "failed", "error": type(error).__name__}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
