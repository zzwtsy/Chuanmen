"""验证隐私、阶段判据、冻结身份、发布边界和保留周期的真实失败场景。"""

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from evidence_io import Tree, digest, json_bytes, relative_name
from export_reproduction import export_sources
from privacy import findings, sanitize

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


checker = module("checker", "check-evidence.py")
publisher = module("publisher", "publish-evidence.py")
cleanup = module("cleanup", "cleanup-local.py")


class StageTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="chuanmen-evidence-tests-")
        self.root = Path(self.folder.name)
        shutil.copytree(
            ROOT / "docs/verification/p00", self.root / "docs/verification/p00"
        )
        self.manifest_path = self.root / "docs/verification/p00/manifest.json"
        self.manifest = json.loads(self.manifest_path.read_bytes())

    def tearDown(self):
        self.folder.cleanup()

    def check(self):
        self.manifest_path.write_bytes(json_bytes(self.manifest))
        return checker.check(Tree(self.root))

    def name(self, suffix):
        return next(
            row["path"]
            for row in self.manifest["entries"]
            if row["path"].endswith("/" + suffix)
        )

    def edit(self, suffix, change, rehash=True):
        name = self.name(suffix)
        path = self.root / name
        value = json.loads(path.read_bytes())
        change(value)
        path.write_bytes(json_bytes(value))
        if rehash:
            expected = digest(path.read_bytes())
            next(row for row in self.manifest["entries"] if row["path"] == name)[
                "sha256"
            ] = expected
            for row in self.manifest["native_acceptance"]["checks"].values():
                if row["evidence_path"] == name:
                    row["evidence_sha256"] = expected
        return value

    def assert_rejected(self):
        self.assertEqual(self.check()["integrity"], "failed")

    def test_curated_stage_passes(self):
        """条件：完整所选证据；操作：重算全部判据；结果：通过且不声称重跑。"""
        result = self.check()
        self.assertTrue(result["stage_ready"])
        self.assertEqual(result["reproduction"]["physical_reexecution"], "not_run")

    def test_missing_required_input_fails(self):
        """条件：锁文件缺失；操作：校验；结果：不借用开发机缓存补足。"""
        (self.root / "docs/verification/p00/inputs/upstream/Cargo.lock").unlink()
        self.assert_rejected()

    def test_changed_frozen_record_fails(self):
        """条件：冻结记录被改且未更新摘要；操作：校验；结果：完整性失败。"""
        self.edit(
            "xiaomi-native-datapath.json",
            lambda d: d.update(status="failed"),
            rehash=False,
        )
        self.assert_rejected()

    def test_platform_cannot_claim_native(self):
        """条件：重算摘要后的平台记录；操作：用于原生门槛；结果：拒绝。"""
        self.edit(
            "xiaomi-native-datapath.json", lambda d: d.update(layer="android_platform")
        )
        self.assert_rejected()

    def test_emulator_cannot_claim_physical(self):
        """条件：模拟器环境且摘要正确；操作：继承真机结论；结果：拒绝。"""
        self.edit(
            "xiaomi-native-datapath.json",
            lambda d: d.update(environment_kind="emulator"),
        )
        self.assert_rejected()

    def test_missing_observations_fails(self):
        """条件：只剩passed汇总；操作：校验；结果：缺少真实判据失败。"""
        self.edit("xiaomi-native-datapath.json", lambda d: d.pop("observations"))
        self.assert_rejected()

    def test_fewer_than_twenty_roundtrips_fails(self):
        """条件：只有19次真实回包；操作：重算；结果：不能达到20次门槛。"""
        self.edit(
            "xiaomi-native-datapath.json", lambda d: d["observations"]["tcp"].pop()
        )
        self.assert_rejected()

    def test_response_mismatch_fails(self):
        """条件：状态passed但回包摘要不同；操作：重算；结果：拒绝。"""
        self.edit(
            "xiaomi-native-datapath.json",
            lambda d: d["observations"]["udp"][0].update(response_sha256="0" * 64),
        )
        self.assert_rejected()

    def test_peer_records_are_required(self):
        """条件：删除独立对端观察；操作：核对请求；结果：不能只信设备状态。"""
        self.edit(
            "xiaomi-native-datapath.json", lambda d: d.update(host_echo_records=[])
        )
        self.assert_rejected()

    def test_no_actual_writes_fails(self):
        """条件：没有实际TUN写入；操作：重算数据判据；结果：拒绝。"""
        self.edit(
            "xiaomi-native-datapath.json",
            lambda d: d["observations"]["observations"].update(write_packets=0),
        )
        self.assert_rejected()

    def test_fd_growth_fails(self):
        """条件：启停后fd持续超基线；操作：重算；结果：资源释放门槛失败。"""
        self.edit(
            "xiaomi-native-connected-cycles.json",
            lambda d: d["observations"]["rounds"][-1].update(fd_after=999),
        )
        self.assert_rejected()

    def test_owner_not_closed_fails(self):
        """条件：PFD关闭后仍有效；操作：所有权判据；结果：拒绝。"""
        self.edit(
            "xiaomi-native-boundary.json",
            lambda d: d["observations"]["rounds"][0]["closed"].update(
                valid_after_owner_close=True
            ),
        )
        self.assert_rejected()

    def test_main_thread_protect_fails(self):
        """条件：protect回调在主线程；操作：判据检查；结果：拒绝。"""
        self.edit(
            "xiaomi-native-boundary.json",
            lambda d: d["observations"].update(callback_on_main=True),
        )
        self.assert_rejected()

    def test_wrong_library_identity_fails(self):
        """条件：设备库与APK打包库不符；操作：关联身份；结果：拒绝。"""
        self.edit(
            "xiaomi-native-datapath.json",
            lambda d: d.update(native_library_sha256="0" * 64),
        )
        self.assert_rejected()

    def test_new_apk_cannot_inherit_old_acceptance(self):
        """条件：新APK及对应构建都更新；操作：套用旧验收；结果：固定历史身份仍拒绝。"""
        value = self.edit(
            "xiaomi-native-datapath.json",
            lambda d: d.update(apk_sha256="0" * 64, installed_apk_sha256="0" * 64),
        )
        build = Path(value["publication"]["build_ref"]).name
        self.edit(build, lambda d: d.update(apk_sha256="0" * 64))
        self.assert_rejected()

    def test_energy_missing_is_not_zero(self):
        """条件：缺测能量被写成0；操作：重算测量；结果：拒绝。"""
        self.edit(
            "xiaomi-native-performance.json",
            lambda d: d["observations"]["idle"]["samples"][0].update(energy_nwh=0),
        )
        self.assert_rejected()

    def test_goodput_recomputed_from_samples(self):
        """条件：汇总吞吐与字节/时间不符；操作：重算；结果：拒绝。"""
        self.edit(
            "xiaomi-native-performance.json",
            lambda d: d["observations"]["tcp"].update(goodput_mbps=999),
        )
        self.assert_rejected()

    def test_orphan_published_file_fails(self):
        """条件：阶段目录出现未登记日志；操作：集合校验；结果：拒绝。"""
        (self.root / "docs/verification/p00/unneeded.txt").write_text("unused")
        self.assert_rejected()

    def test_no_freezing_development_tree(self):
        """条件：阶段清单冻结当前源码；操作：校验；结果：跨阶段边界失败。"""
        self.manifest["entries"].append(
            {
                "path": "app/Mutable.java",
                "sha256": "0" * 64,
                "role": "source",
                "claim_ids": ["P00-02"],
            }
        )
        self.assert_rejected()

    def test_transport_refresh_cannot_hide_frozen_edit(self):
        """条件：冻结记录改动；操作：刷新运输清单；结果：生成器失败，不掩盖摘要变化。"""
        self.edit(
            "xiaomi-native-datapath.json",
            lambda d: d.update(status="failed"),
            rehash=False,
        )
        result = subprocess.run(
            [
                sys.executable,
                str(HERE / "freeze_handoff.py"),
                "--repo-root",
                str(self.root),
                "--tree",
                "worktree",
            ],
            capture_output=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)

    def publication_fixture(self):
        path = self.root / "experiments/android-vpn-smoke/experiment.json"
        path.parent.mkdir(parents=True)
        path.write_bytes(
            (ROOT / "experiments/android-vpn-smoke/experiment.json").read_bytes()
        )
        declaration = json.loads(path.read_bytes())
        run = self.root / "build/experiments/android-vpn-smoke/config-new-01"
        run.mkdir(parents=True)
        cases = json.loads(
            (
                ROOT / "docs/verification/p00/inputs/config-roundtrip-results.json"
            ).read_bytes()
        )["cases"]
        (run / "result.json").write_bytes(
            json_bytes({"status": "passed", "cases": cases})
        )
        bundle = {
            "experiment_id": "android-vpn-smoke",
            "run_id": "config-new-01",
            "case_id": "config",
            "declaration_sha256": digest(json_bytes(declaration)),
            "actor": {"role": "developer", "id": "executor-01"},
            "scope": "host_config_roundtrip",
            "claim_ids": ["P00-03"],
            "records": [{"path": "result.json", "role": "observation"}],
            "criteria": next(
                c["criteria"] for c in declaration["cases"] if c["id"] == "config"
            ),
        }
        (run / "bundle.json").write_bytes(json_bytes(bundle))
        return run, bundle

    def test_publication_preview_is_readonly(self):
        """条件：有效运行；操作：默认预览；结果：不创建证据或改变清单。"""
        run, _ = self.publication_fixture()
        before = self.manifest_path.read_bytes()
        result = publisher.publish(self.root, "android-vpn-smoke", run, "p00")
        self.assertEqual(result["status"], "preview")
        self.assertEqual(self.manifest_path.read_bytes(), before)
        self.assertFalse((self.root / result["destination"]).exists())

    def test_publication_checks_and_never_overwrites(self):
        """条件：有效运行；操作：发布后再次发布；结果：首次通过、再次拒绝覆盖。"""
        run, _ = self.publication_fixture()
        result = publisher.publish(self.root, "android-vpn-smoke", run, "p00", True)
        self.assertEqual(result["status"], "published")
        self.assertEqual(checker.check(Tree(self.root))["integrity"], "passed")
        with self.assertRaises(ValueError):
            publisher.publish(self.root, "android-vpn-smoke", run, "p00", True)

    def test_publication_rejects_lowered_criteria(self):
        """条件：事后将88案例门槛删去；操作：发布；结果：与预先声明不符。"""
        run, bundle = self.publication_fixture()
        bundle["criteria"] = bundle["criteria"][:1]
        (run / "bundle.json").write_bytes(json_bytes(bundle))
        with self.assertRaises(ValueError):
            publisher.publish(self.root, "android-vpn-smoke", run, "p00", True)

    def test_publication_rejects_changed_declaration(self):
        """条件：运行后更改声明；操作：发布；结果：摘要不符且不创建文件。"""
        run, _ = self.publication_fixture()
        declaration = self.root / "experiments/android-vpn-smoke/experiment.json"
        data = json.loads(declaration.read_bytes())
        data["publication_budget"]["files"] += 1
        declaration.write_bytes(json_bytes(data))
        with self.assertRaises(ValueError):
            publisher.publish(self.root, "android-vpn-smoke", run, "p00", True)

    def test_publication_rejects_private_key(self):
        """条件：选定材料含私钥；操作：脱敏发布；结果：阻止发布，不伪造合成数据。"""
        run, _ = self.publication_fixture()
        record = json.loads((run / "result.json").read_bytes())
        record["debug"] = "-" * 5 + "BEGIN PRIVATE KEY" + "-" * 5
        (run / "result.json").write_bytes(json_bytes(record))
        with self.assertRaises(ValueError):
            publisher.publish(self.root, "android-vpn-smoke", run, "p00", True)

    def test_publication_failure_rolls_back(self):
        """条件：运行判据通过但缺真实构建关联；操作：发布；结果：恢复原清单并移除新记录。"""
        run, _ = self.publication_fixture()
        record = json.loads((run / "result.json").read_bytes())
        record.update(
            layer="android_native_jni_parser",
            environment_kind="physical_device",
            apk_sha256="0" * 64,
        )
        (run / "result.json").write_bytes(json_bytes(record))
        before = self.manifest_path.read_bytes()
        with self.assertRaises(ValueError):
            publisher.publish(self.root, "android-vpn-smoke", run, "p00", True)
        self.assertEqual(self.manifest_path.read_bytes(), before)
        self.assertFalse(
            (self.root / "docs/verification/p00/runs/config-new-01").exists()
        )

    def test_publication_stops_when_stage_is_locked(self):
        """条件：已有发布者占用阶段；操作：再次发布；结果：拒绝并不删除其他发布者的锁。"""
        run, _ = self.publication_fixture()
        lock = self.root / "build/verification-p00-publish.lock"
        lock.write_text("other-publisher")
        with self.assertRaises(ValueError):
            publisher.publish(self.root, "android-vpn-smoke", run, "p00", True)
        self.assertTrue(lock.exists())


class PrivacyAndTreeTests(unittest.TestCase):
    def test_nested_posix_windows_and_argv(self):
        """条件：嵌套POSIX/Windows位置和真实ADB参数；操作：扫描与脱敏；结果：全部检出且不回显值。"""
        # 分段构造合成隐私样本，避免源码出现扫描器会拒绝的完整个人路径。
        personal = "/" + "/".join(("home", "synthetic-user", "sdk", "tool"))  # noqa: FLY002
        windows = "C:" + "\\" + "\\".join(("Users", "synthetic-user", "sdk"))  # noqa: FLY002
        value = {
            "commands": [
                {"argv": ["adb", "-s", "SYNTHETIC-SERIAL"], "stdout": personal}
            ],
            "nested": {"path": windows},
            "serial_sha256": "0" * 64,
        }
        found = findings(value)
        self.assertGreaterEqual(len(found), 4)
        self.assertNotIn("synthetic-user", json.dumps(found))
        self.assertEqual(findings(sanitize(value)), [])

    def test_project_namespace_and_synthetic_hostname_survive(self):
        """条件：公开namespace和合成hostname；操作：脱敏；结果：保留配置语义。"""
        value = {"package": "com.example.project", "hostname": "synthetic-peer"}
        self.assertEqual(sanitize(value), value)
        self.assertEqual(findings(value), [])

    def test_uname_hostname_is_removed(self):
        """条件：uname含真实宿主名；操作：脱敏；结果：保留内核、删除宿主标识。"""
        value = {"system": {"uname": "Linux SYNTHETIC-HOST kernel-version x86_64"}}
        self.assertTrue(findings(value))
        self.assertEqual(findings(sanitize(value)), [])

    def test_relative_path_and_symlink_escape(self):
        """条件：父路径或符号链接越界；操作：读取；结果：拒绝借用包外文件。"""
        with self.assertRaises(ValueError):
            relative_name("../outside")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "link").symlink_to(root.parent)
            with self.assertRaises(ValueError):
                Tree(root).read("link/outside")

    def test_index_does_not_borrow_worktree(self):
        """条件：暂存安全内容后工作树变化；操作：索引读取；结果：仍读取原暂存字节。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", root], check=True)
            (root / "record.txt").write_text("staged")
            subprocess.run(["git", "-C", str(root), "add", "record.txt"], check=True)
            (root / "record.txt").write_text("unstaged")
            tree = Tree(root, "index")
            self.assertEqual(tree.read("record.txt"), b"staged")
            (root / "extra.txt").write_text("not staged")
            with self.assertRaises(ValueError):
                tree.read("extra.txt")

    def test_export_never_overwrites_or_leaves_partial_inputs(self):
        """条件：导出目录已存在或输入不全；操作：导出；结果：拒绝覆盖及半份导出。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "root"
            root.mkdir()
            destination = Path(directory) / "out"
            with self.assertRaises(ValueError):
                export_sources(Tree(root), destination)
            self.assertFalse(destination.exists())
            destination.mkdir()
            with self.assertRaises(ValueError):
                export_sources(Tree(root), destination)

    def test_cleanup_requires_closed_and_inactive(self):
        """条件：过期关闭、过期活跃和近期关闭三运行；操作：预览；结果：仅列出过期关闭项。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            now = time.time()
            for name, closed, age in [
                ("old", True, 10),
                ("active", False, 10),
                ("recent", True, 1),
            ]:
                run = root / "build/experiments/probe" / name
                run.mkdir(parents=True)
                receipt = run / "lifecycle.json"
                receipt.write_bytes(
                    json_bytes(
                        {
                            "experiment_id": "probe",
                            "run_id": name,
                            "state": "closed" if closed else "running",
                        }
                    )
                )
                old = now - age * 86400
                os.utime(receipt, (old, old))
                os.utime(run, (old, old))
            self.assertEqual(
                cleanup.candidates(root, now=now), ["build/experiments/probe/old"]
            )


class ReproductionExportTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="chuanmen-export-tests-")
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name) / "repo"
        self.root.mkdir()
        for identity in ("probe-a", "probe-b"):
            exp = self.root / "experiments" / identity
            exp.mkdir(parents=True)
            (exp / "experiment.json").write_bytes(
                json_bytes(
                    {
                        "experiment_id": identity,
                        "reproduction": {
                            "inputs": {"mapping": "inputs/" + identity + ".json"}
                        },
                    }
                )
            )
            (exp / "run.py").write_text('print("synthetic-probe")\n')
            extra = self.root / "inputs" / (identity + ".json")
            extra.parent.mkdir(exist_ok=True)
            extra.write_bytes(json_bytes({"synthetic": identity}))

    def declaration(self, change):
        path = self.root / "experiments/probe-a/experiment.json"
        value = json.loads(path.read_bytes())
        change(value)
        path.write_bytes(json_bytes(value))

    def export(self, identity="probe-a"):
        return export_sources(Tree(self.root), Path(self.folder.name) / "out", identity)

    def test_selected_experiment_declares_its_own_inputs(self):
        """条件：两个独立实验；操作：分别导出；结果：仅包含所选源码和声明输入。"""
        for identity in ("probe-a", "probe-b"):
            with self.subTest(identity=identity):
                destination = Path(self.folder.name) / identity
                receipt = export_sources(Tree(self.root), destination, identity)
                self.assertEqual(
                    set(receipt["source_files"]),
                    {
                        "experiments/" + identity + "/experiment.json",
                        "experiments/" + identity + "/run.py",
                        "inputs/" + identity + ".json",
                    },
                )
                self.assertEqual(receipt["experiment_id"], identity)
                self.assertFalse(receipt["experiment_executed"])

    def test_missing_extra_input_leaves_no_partial_export(self):
        """条件：源码完整但声明输入缺失；操作：导出；结果：失败且不创建输出。"""
        (self.root / "inputs/probe-a.json").unlink()
        with self.assertRaises((OSError, ValueError)):
            self.export()
        self.assertFalse((Path(self.folder.name) / "out").exists())

    def test_escaping_input_paths_are_rejected(self):
        """条件：输入为父路径、绝对路径或非规范路径；操作：导出；结果：创建前拒绝。"""
        for name in (
            "../outside.json",
            "/tmp/outside.json",
            "inputs/../probe-a.json",
            "inputs\\probe-a.json",
        ):
            with self.subTest(path=name):
                self.declaration(
                    lambda d, name=name: d["reproduction"]["inputs"].update(
                        mapping=name
                    )
                )
                with self.assertRaises(ValueError):
                    self.export()
                self.assertFalse((Path(self.folder.name) / "out").exists())

    def test_invalid_identity_or_input_declaration_is_rejected(self):
        """条件：目录身份不符或缺复现声明；操作：导出；结果：拒绝猜测输入范围。"""
        self.declaration(lambda d: d.update(experiment_id="probe-b"))
        with self.assertRaises(ValueError):
            self.export()
        self.declaration(
            lambda d: (d.update(experiment_id="probe-a"), d.pop("reproduction"))
        )
        with self.assertRaises(ValueError):
            self.export()
        for identity in ("../probe-a", "probe-a/child", "."):
            with self.assertRaises(ValueError):
                self.export(identity)

    def test_symlink_input_is_rejected(self):
        """条件：声明输入改为符号链接；操作：导出；结果：拒绝借用链接目标。"""
        path = self.root / "inputs/probe-a.json"
        path.unlink()
        path.symlink_to(self.root / "inputs/probe-b.json")
        with self.assertRaises(ValueError):
            self.export()

    def test_destination_must_be_new_and_outside_repo(self):
        """条件：输出已存在或位于源码树；操作：导出；结果：拒绝并保留原内容。"""
        output = Path(self.folder.name) / "out"
        output.mkdir()
        (output / "keep.txt").write_text("keep")
        with self.assertRaises(ValueError):
            self.export()
        self.assertEqual((output / "keep.txt").read_text(), "keep")
        with self.assertRaises(ValueError):
            export_sources(Tree(self.root), self.root / "new-output", "probe-a")

    def test_index_export_uses_only_staged_declaration_and_sources(self):
        """条件：暂存后声明、源码、输入变化；操作：导出索引；结果：仅使用暂存字节。"""
        subprocess.run(["git", "init", "-q", self.root], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        source = self.root / "experiments/probe-a/run.py"
        expected = source.read_bytes()
        source.write_text("unstaged")
        (source.parent / "untracked.py").write_text("untracked")
        (self.root / "inputs/probe-a.json").unlink()
        self.declaration(
            lambda d: d["reproduction"]["inputs"].update(mapping="../outside")
        )
        output = Path(self.folder.name) / "out"
        receipt = export_sources(Tree(self.root, "index"), output, "probe-a")
        self.assertEqual((output / "experiments/probe-a/run.py").read_bytes(), expected)
        self.assertNotIn("experiments/probe-a/untracked.py", receipt["source_files"])
        self.assertTrue((output / "inputs/probe-a.json").is_file())

    def test_ignored_local_input_is_not_exported(self):
        """条件：声明引用被忽略的本机配置；操作：导出工作树；结果：创建前拒绝。"""
        subprocess.run(["git", "init", "-q", self.root], check=True)
        (self.root / ".gitignore").write_text("local.properties\n")
        (self.root / "local.properties").write_text("synthetic-local-setting")
        self.declaration(
            lambda d: d["reproduction"]["inputs"].update(mapping="local.properties")
        )
        with self.assertRaises(ValueError):
            self.export()
        self.assertFalse((Path(self.folder.name) / "out").exists())


if __name__ == "__main__":
    unittest.main()
