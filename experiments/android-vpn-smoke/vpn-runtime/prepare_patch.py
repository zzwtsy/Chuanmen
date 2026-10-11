"""在独立临时 checkout 应用自有 P00 观察补丁；原始固定 checkout 保持只读。"""

import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CORE = "7592951b808c4839cb9d77cc4bc642f124915951"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-checkout", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    original, out = args.core_checkout.resolve(), args.out.resolve()
    if out.exists():
        parser.error("禁止覆盖已有实验 checkout")

    def git(cwd, *cmd):
        return subprocess.check_output(
            ["git", "-c", "core.hooksPath=/dev/null", *cmd], cwd=cwd, text=True
        )

    if (
        git(original, "rev-parse", "HEAD").strip() != CORE
        or git(original, "status", "--porcelain").strip()
    ):
        parser.error("要求干净固定核心 checkout")
    subprocess.run(
        [
            "git",
            "-c",
            "core.hooksPath=/dev/null",
            "clone",
            "--shared",
            "--no-checkout",
            str(original),
            str(out),
        ],
        check=True,
    )
    git(out, "checkout", "--detach", CORE)
    touched = {}

    def change(name, old, new):
        path = out / name
        text = path.read_text()
        if text.count(old) != 1:
            raise ValueError(f"补丁锚点不唯一：{name}")
        touched.setdefault(
            name, {"original_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        )
        path.write_text(text.replace(old, new))

    for name, source in (
        ("easytier/src/p00_observation.rs", "p00_observation.rs"),
        ("easytier-contrib/easytier-android-jni/src/p00_jni.rs", "p00_jni.rs"),
    ):
        shutil.copyfile(ROOT / "native" / source, out / name)
        touched[name] = {"original_sha256": None}
    change(
        "easytier/src/lib.rs",
        "pub mod socket_protector;",
        "pub mod socket_protector;\npub mod p00_observation;",
    )
    change(
        "easytier-contrib/easytier-android-jni/src/lib.rs",
        "mod strings;",
        "mod strings;\nmod p00_jni;",
    )
    name = "easytier/src/instance/virtual_nic.rs"
    change(
        name,
        "        let len = buf.filled().len();",
        "        let len = buf.filled().len();\n        if ret.is_ok() && len > 0 { crate::p00_observation::tun_read(buf.filled()); }",
    )
    change(
        name,
        "        g.as_pin_mut().poll_write(cx, buf)",
        "        let result = g.as_pin_mut().poll_write(cx, buf);\n        if matches!(&result, Poll::Ready(Ok(n)) if *n > 0) { crate::p00_observation::tun_write(); }\n        result",
    )
    # FramedWriter 实际采用 vectored 路径；只有底层成功写入才计数，不能以收到回包反填计数。
    change(
        name,
        "        g.as_pin_mut().poll_write_vectored(cx, bufs)",
        "        let result = g.as_pin_mut().poll_write_vectored(cx, bufs);\n        if matches!(&result, Poll::Ready(Ok(n)) if *n > 0) { crate::p00_observation::tun_write(); }\n        result",
    )
    change(
        name,
        "    tasks: JoinSet<()>,",
        "    tasks: JoinSet<()>,\n    p00_borrowed_fd: Option<i32>,",
    )
    change(
        name,
        "            tasks: JoinSet::new(),",
        "            tasks: JoinSet::new(),\n            p00_borrowed_fd: None,",
    )
    change(
        name,
        "    pub async fn ifname(&self)",
        '    /// 等待所有 TUN I/O 任务实际退出，再允许平台关闭借用 fd。\n    pub(super) async fn p00_stop_io(&mut self) {\n        self.tasks.shutdown().await;\n        if let Some(fd) = self.p00_borrowed_fd.take() { crate::p00_observation::tun_state(fd, "released"); }\n    }\n\n    pub async fn ifname(&self)',
    )
    change(
        name,
        "        let (stream, sink) = tunnel.split();\n\n        self.do_forward_nic_to_peers_task(stream)?;\n        self.do_forward_peers_to_nic(sink);\n\n        Ok(())\n    }\n}",
        '        self.p00_borrowed_fd = Some(tun_fd);\n        let (stream, sink) = tunnel.split();\n        self.do_forward_nic_to_peers_task(stream)?;\n        self.do_forward_peers_to_nic(sink);\n        crate::p00_observation::tun_state(tun_fd, "installed");\n        Ok(())\n    }\n}',
    )
    name = "easytier/src/instance/runtime_host/tun_common.rs"
    change(name, "    any::Any,\n", "")
    change(
        name,
        "    _nic_ctx: Option<Box<dyn Any + Send>>,",
        "    nic_ctx: Option<NicCtx>,\n    drain_tasks: Option<JoinSet<()>>,",
    )
    change(
        name,
        "            _nic_ctx: Some(Box::new(nic_ctx)),",
        "            nic_ctx: Some(nic_ctx),\n            drain_tasks: None,",
    )
    change(
        name,
        "            _nic_ctx: Some(Box::new(tasks)),",
        "            nic_ctx: None,\n            drain_tasks: Some(tasks),",
    )
    change(
        name,
        "            nic.magic_dns.stop().await;",
        "            nic.magic_dns.stop().await;\n            if let Some(ctx) = nic.nic_ctx.as_mut() { ctx.p00_stop_io().await; }\n            if let Some(tasks) = nic.drain_tasks.as_mut() { tasks.shutdown().await; }",
    )
    for name, identity in touched.items():
        identity["patched_sha256"] = hashlib.sha256(
            (out / name).read_bytes()
        ).hexdigest()
    report = {
        "schema_version": 1,
        "core_commit": CORE,
        "layer": "experimental_project_patch",
        "status": "prepared",
        "production_approved": False,
        "checkout": str(out),
        "files": touched,
        "prepare_script_sha256": hashlib.sha256(
            Path(__file__).read_bytes()
        ).hexdigest(),
        "lockfile_sha256": hashlib.sha256(
            (out / "Cargo.lock").read_bytes()
        ).hexdigest(),
    }
    (out.parent / (out.name + "-patch.json")).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    (out.parent / (out.name + "-patch.diff")).write_text(
        git(out, "diff", "--no-ext-diff")
    )
    print(
        json.dumps({"status": "prepared", "checkout": str(out), "files": len(touched)})
    )


if __name__ == "__main__":
    main()
