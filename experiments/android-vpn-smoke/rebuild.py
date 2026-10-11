"""在第二主机的全新目录复建 P00；同一代理执行不等于另一名协作者验收。"""

import argparse
import ctypes
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import zipfile
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath

CORE = "7592951b808c4839cb9d77cc4bc642f124915951"
LOCK = "cd5a7951f897e45f1988b0a4896ab1f336e03e4cb6d70ca1db2a7b9b477d774b"
RUST = "b940084d7eb6a299eb4bfeb8e34901bc051e7ac4"
# URL、大小与 SHA-1 来自 Google 官方 repository2-3.xml；另记录下载后的 SHA-256。
SDK = [
    (
        "android-ndk-r30-linux.zip",
        738633529,
        "5107f898313790e449e87eee2183d9a20602dee9",
        "ndk/30.0.16248370",
    ),
    (
        "platform-37.0_r02.zip",
        67281901,
        "ed8ebf7f8822a4de5686d427f237d2fa30ff7410",
        "platforms/android-37.0",
    ),
    (
        "build-tools_r36_linux.zip",
        63737259,
        "b0b6376977657e8ad9b969bacf4093601da2c6fb",
        "build-tools/36.0.0",
    ),
]


def digest(path, algorithm="sha256"):
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def workflow(exp, root):
    """实际复建与只读预览共用同一组步骤，避免预览入口与执行入口漂移。"""
    core, config = root / "core", root / "config-build"
    native, parser_apk = root / "native-build", root / "parser-apk"
    patched, patch = root / "boundary-core", root / "boundary-core-patch.json"
    boundary, boundary_apk = root / "boundary-build", root / "boundary-apk"
    sdk, py = root / "sdk", sys.executable
    return [
        (
            "config_build",
            [
                py,
                exp / "config-roundtrip/build.py",
                "--rust-toolchain",
                "1.99.0",
                "--core-checkout",
                core,
                "--out",
                config,
            ],
            config / "build-report.json",
        ),
        (
            "config_cases",
            [
                py,
                exp / "config-roundtrip/run.py",
                "--build-report",
                config / "build-report.json",
                "--fixture",
                exp / "config-roundtrip/fixtures/config-cases.json",
                "--output",
                root / "config-cases.json",
            ],
            root / "config-cases.json",
        ),
        (
            "native_build",
            [
                py,
                exp / "support/build_native.py",
                "--rust-toolchain",
                "1.99.0",
                "--checkout",
                core,
                "--sdk",
                sdk,
                "--abi",
                "arm64-v8a",
                "--abi",
                "x86_64",
                "--bindgen-api-target",
                "--out",
                native,
            ],
            native / "native-build-report.json",
        ),
        (
            "parser_apk",
            [
                py,
                exp / "support/build_probe.py",
                "--sdk",
                sdk,
                "--native-build-report",
                native / "native-build-report.json",
                "--out",
                parser_apk,
            ],
            parser_apk / "build-report.json",
        ),
        (
            "prepare_patch",
            [
                py,
                exp / "vpn-runtime/prepare_patch.py",
                "--core-checkout",
                core,
                "--out",
                patched,
            ],
            None,
        ),
        (
            "boundary_build",
            [
                py,
                exp / "support/build_native.py",
                "--rust-toolchain",
                "1.99.0",
                "--checkout",
                patched,
                "--sdk",
                sdk,
                "--abi",
                "arm64-v8a",
                "--bindgen-api-target",
                "--experimental-patch-report",
                patch,
                "--out",
                boundary,
            ],
            boundary / "native-build-report.json",
        ),
        (
            "boundary_apk",
            [
                py,
                exp / "support/build_probe.py",
                "--boundary",
                "--sdk",
                sdk,
                "--native-build-report",
                boundary / "native-build-report.json",
                "--out",
                boundary_apk,
            ],
            boundary_apk / "build-report.json",
        ),
    ]


def required_inputs(exp, steps):
    """从实验声明定位额外输入，并在创建运行目录前核对源码闭包。"""
    repo = exp.parents[1]
    declaration = json.loads((exp / "experiment.json").read_text())
    if declaration["experiment_id"] != exp.name:
        raise ValueError("实验声明身份不符")
    inputs = {}
    for key in ("config_mapping", "native_boundary_patch"):
        name = declaration["reproduction"]["inputs"][key]
        relative = PurePosixPath(name)
        if (
            relative.is_absolute()
            or ".." in relative.parts
            or "\\" in name
            or relative.as_posix() != name
        ):
            raise ValueError("复建输入必须是规范的仓库相对路径")
        path = repo / name
        if not path.resolve().is_relative_to(repo):
            raise ValueError("复建输入越过仓库边界")
        inputs[key] = path
    sources = {
        exp / "experiment.json",
        exp / "rebuild.py",
        exp / "config-roundtrip/main.rs",
        exp / "config-roundtrip/fixtures/config-cases.json",
        exp / "vpn-runtime/native/p00_jni.rs",
        exp / "vpn-runtime/native/p00_observation.rs",
        *(Path(argv[1]) for _, argv, _ in steps),
        *inputs.values(),
    }
    for directory in ("jni-parser/android", "vpn-runtime/android"):
        android = exp / directory
        java = list((android / "src").rglob("*.java"))
        if not java:
            raise ValueError("Android 探针源码缺失")
        sources.update([android / "AndroidManifest.xml", *java])
    if any(not path.is_file() or path.is_symlink() for path in sources):
        raise ValueError("复建输入缺失或不是普通文件")
    return inputs, sorted(sources)


def preview(exp, root, steps, sources):
    """用仓库相对路径与逻辑输出根展示计划，不读取或安装执行工具链。"""
    repo = exp.parents[1]

    def logical(value):
        if str(value) == sys.executable:
            return "PYTHON"
        text = str(value)
        for prefix, label in ((root, "<RUN_ROOT>"), (repo, "<REPO_ROOT>")):
            if text == str(prefix) or text.startswith(str(prefix) + os.sep):
                return label + text[len(str(prefix)) :]
        return text

    return {
        "status": "preview",
        "experiment_id": exp.name,
        "experiment_executed": False,
        "network_started": False,
        "core_commit": CORE,
        "source_files": [p.relative_to(repo).as_posix() for p in sources],
        "steps": [
            {
                "name": name,
                "argv": [logical(x) for x in argv],
                "report": logical(report) if report else None,
            }
            for name, argv, report in steps
        ],
        "environment": {
            "rust_toolchain": "1.99.0",
            "jdk": "21.0.12.1+1",
            "sdk_packages": [destination for _, _, _, destination in SDK],
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        required=True,
        help="全新实验目录；不复用 Cargo 或 target 缓存",
    )
    parser.add_argument(
        "--jdk-package",
        type=Path,
        help="固定 Arch JDK 21.0.12.1.u1-1 包，只解包到实验目录",
    )
    parser.add_argument("--jdk-package-sha256")
    parser.add_argument(
        "--actor",
        help="登记实际执行者；独立验收仍须真实执行和另存复核证据",
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="只读检查源码和声明输入，预览复建步骤"
    )
    args = parser.parse_args()
    root = args.root.resolve()
    if root.exists():
        parser.error("目录已存在，禁止覆盖记录或复用缓存")
    exp = Path(__file__).resolve().parent
    steps = workflow(exp, root)
    try:
        inputs, sources = required_inputs(exp, steps)
    except (OSError, ValueError, KeyError, TypeError):
        parser.error("复建输入缺失、声明无效或路径越界")
    if args.dry_run:
        print(
            json.dumps(preview(exp, root, steps, sources), ensure_ascii=False, indent=2)
        )
        return 0
    if not args.jdk_package or not args.jdk_package_sha256 or not args.actor:
        parser.error("实际复建要求 --jdk-package、--jdk-package-sha256 和 --actor")
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        parser.error("本实验只接受 Linux x86_64")
    if digest(args.jdk_package) != args.jdk_package_sha256:
        parser.error("JDK 输入包哈希不符")
    root.mkdir(parents=True)
    report = {
        "task": "P00-02",
        "layer": "second_host_rebuild",
        "status": "running",
        "started_at_utc": datetime.now(UTC).isoformat(),
        "runner_sha256": digest(Path(__file__)),
        "actor": args.actor,
        "independent_collaborator_status": "unverified",
        "android_runtime_status": "unverified",
        "root": str(root),
        "commit": CORE,
        "cache_reused": False,
        "commands": [],
        "downloads": [],
        "stage_reports": {},
    }
    env = os.environ.copy()
    # 宿主 HOME、默认工具链和用户配置保持原值；任务只覆盖子进程的专用工具/缓存路径。
    for key in (
        "RUSTFLAGS",
        "CARGO_ENCODED_RUSTFLAGS",
        "RUSTC_WRAPPER",
        "RUSTC_WORKSPACE_WRAPPER",
        "RUSTUP_TOOLCHAIN",
    ):
        env.pop(key, None)
    for key in list(env):
        if key.startswith(("CARGO_TARGET_", "BINDGEN_EXTRA_CLANG_ARGS")):
            env.pop(key, None)
    env.update(
        CARGO_HOME=str(root / "cargo"),
        RUSTUP_HOME=str(root / "rustup"),
        TMPDIR=str(root / "tmp"),
        CARGO_BUILD_JOBS="2",
        CARGO_INCREMENTAL="0",
        GIT_CONFIG_GLOBAL="/dev/null",
        GIT_CONFIG_SYSTEM="/dev/null",
        JAVA_HOME=str(root / "jdk/usr/lib/jvm/java-21-openjdk"),
        PROTOC=str(root / "protoc/bin/protoc"),
        PROTOC_INCLUDE=str(root / "protoc/include"),
        LIBCLANG_PATH=str(
            root / "sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/lib"
        ),
    )
    rustup = shutil.which("rustup")
    if not rustup:
        parser.error("需要已安装的 rustup 可执行文件")
    env["PATH"] = os.pathsep.join(
        [
            str(root / "tools/bin"),
            str(root / "jdk/usr/lib/jvm/java-21-openjdk/bin"),
            str(Path(rustup).parent),
            env["PATH"],
        ]
    )
    for directory in ("tmp", "logs", "downloads", "cargo", "rustup", "jdk"):
        (root / directory).mkdir()
    report["environment_overrides"] = {
        key: env[key]
        for key in (
            "CARGO_HOME",
            "RUSTUP_HOME",
            "TMPDIR",
            "JAVA_HOME",
            "PROTOC",
            "PROTOC_INCLUDE",
            "LIBCLANG_PATH",
        )
    }

    def save():
        report["updated_at_utc"] = datetime.now(UTC).isoformat()
        temporary = root / "report.tmp"
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(root / "report.json")

    def run(argv, cwd=root, timeout=1800, bfd=False):
        if shutil.disk_usage(root).free < 2 * 1024**3:
            raise RuntimeError("可用磁盘低于 2 GiB，停止新步骤")
        name = root / "logs" / f"command-{len(report['commands']) + 1:02d}.txt"
        entry = {
            "argv": [str(x) for x in argv],
            "cwd": str(cwd),
            "log_path": str(name),
            "started_at_utc": datetime.now(UTC).isoformat(),
        }
        report["commands"].append(entry)
        save()
        child_env = env.copy()
        if bfd:
            child_env["RUSTFLAGS"] = "-C link-arg=-fuse-ld=bfd"
        with name.open("x") as stream:
            try:
                result = subprocess.run(
                    entry["argv"],
                    check=False,
                    cwd=cwd,
                    env=child_env,
                    stdout=stream,
                    stderr=subprocess.STDOUT,
                    timeout=timeout,
                )
                entry["exit_code"] = result.returncode
            finally:
                stream.flush()
                entry["log_sha256"] = digest(name)
                save()
        if result.returncode:
            raise RuntimeError("步骤失败，保留原件：" + str(name))
        return name.read_text(errors="replace")

    def stage(name, command, path, timeout=7200):
        run(command, timeout=timeout)
        result = json.loads(path.read_text())
        report["stage_reports"][name] = {
            "path": str(path),
            "sha256": digest(path),
            "status": result.get("status"),
        }
        save()
        if result.get("status") != "passed":
            raise RuntimeError("阶段报告未通过：" + name)

    save()
    try:
        report["system"] = {
            "uname": run(["uname", "-srm"]),
            "cc": run(["cc", "--version"]),
            "bfd": run(["ld.bfd", "--version"]),
            "python": sys.version,
        }
        run(
            [
                rustup,
                "toolchain",
                "install",
                "1.99.0",
                "--no-self-update",
                "--profile",
                "minimal",
                "--target",
                "aarch64-linux-android,x86_64-linux-android",
            ],
            timeout=3600,
        )
        report["rustc"] = run([rustup, "run", "1.99.0", "rustc", "-Vv"])
        if "commit-hash: " + RUST not in report["rustc"]:
            raise RuntimeError("精确编译器 SHA 不符")
        run(
            [
                rustup,
                "run",
                "1.99.0",
                "cargo",
                "install",
                "cargo-ndk",
                "--version",
                "4.1.2",
                "--locked",
                "--root",
                root / "tools",
            ],
            timeout=3600,
            bfd=True,
        )
        for name, size, checksum, destination in SDK:
            archive = root / "downloads" / name
            url = "https://dl.google.com/android/repository/" + name
            run(
                [
                    "curl",
                    "--fail",
                    "--location",
                    "--retry",
                    "2",
                    "--max-time",
                    "1800",
                    "--output",
                    archive,
                    url,
                ]
            )
            if archive.stat().st_size != size or digest(archive, "sha1") != checksum:
                raise RuntimeError("SDK 归档身份不符：" + name)
            report["downloads"].append(
                {"url": url, "bytes": size, "sha1": checksum, "sha256": digest(archive)}
            )
            extraction = root / "downloads" / (name + "-extracted")
            extraction.mkdir()
            with zipfile.ZipFile(archive) as zipped:
                if any(
                    PurePosixPath(p.filename).is_absolute()
                    or ".." in PurePosixPath(p.filename).parts
                    for p in zipped.infolist()
                ):
                    raise RuntimeError("SDK 归档包含越界路径")
            run(["unzip", "-q", archive, "-d", extraction])
            children = list(extraction.iterdir())
            if len(children) != 1 or not children[0].is_dir():
                raise RuntimeError("SDK 归档目录不符合预期")
            target = root / "sdk" / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(children[0]), target)
        protoc_url = "https://github.com/protocolbuffers/protobuf/releases/download/v36.1/protoc-36.1-linux-x86_64.zip"
        protoc_sha = "c4bc672d9d49214dc8cafdceadf4df92182d6ca8e3ec65a56b2d7de5602669b4"
        protoc_archive = root / "downloads/protoc-36.1-linux-x86_64.zip"
        run(
            [
                "curl",
                "--fail",
                "--location",
                "--retry",
                "2",
                "--max-time",
                "300",
                "--output",
                protoc_archive,
                protoc_url,
            ]
        )
        if (
            protoc_archive.stat().st_size != 3678715
            or digest(protoc_archive) != protoc_sha
        ):
            raise RuntimeError("protoc 归档不符官方 v36.1 发布 SHA")
        with zipfile.ZipFile(protoc_archive) as zipped:
            if any(
                PurePosixPath(p.filename).is_absolute()
                or ".." in PurePosixPath(p.filename).parts
                for p in zipped.infolist()
            ):
                raise RuntimeError("protoc 归档包含越界路径")
        run(["unzip", "-q", protoc_archive, "-d", root / "protoc"])
        report["downloads"].append(
            {"url": protoc_url, "bytes": 3678715, "sha256": protoc_sha}
        )
        report["protoc"] = {
            "version": run([env["PROTOC"], "--version"]),
            "binary_sha256": digest(Path(env["PROTOC"])),
        }
        if report["protoc"]["version"].strip() != "libprotoc 36.1":
            raise RuntimeError("protoc 精确版本不符")
        libclang = Path(env["LIBCLANG_PATH"]) / "libclang.so"
        ctypes.CDLL(str(libclang))
        report["libclang"] = {
            "path": str(libclang),
            "sha256": digest(libclang),
            "load_status": "passed",
            "source": "frozen NDK libclang; first-host ambient bindgen libclang was not captured",
        }
        report["jdk_input"] = {
            "package": args.jdk_package.name,
            "sha256": digest(args.jdk_package),
            "origin": "user_development_host_Arch_package_cache",
            "compiled_artifact": False,
        }
        run(
            [
                "tar",
                "-xf",
                args.jdk_package.resolve(),
                "-C",
                root / "jdk",
                "usr/lib/jvm/java-21-openjdk",
                "etc/java21-openjdk",
                "usr/share/licenses/java21-openjdk",
            ]
        )
        # Arch 工具包的 conf/legal 是系统绝对链接；只重定位任务内链接，不写宿主 /etc。
        java_home = Path(env["JAVA_HOME"])
        relocated = []
        for name, original, target in (
            ("conf", "/etc/java21-openjdk", root / "jdk/etc/java21-openjdk"),
            (
                "legal",
                "/usr/share/licenses/java21-openjdk",
                root / "jdk/usr/share/licenses/java21-openjdk",
            ),
        ):
            link = java_home / name
            if not link.is_symlink() or os.readlink(link) != original:
                raise RuntimeError("JDK 工具包链接布局不符：" + name)
            relative = os.path.relpath(target, link.parent)
            link.unlink()
            link.symlink_to(relative)
            relocated.append(
                {
                    "path": str(link),
                    "original_target": original,
                    "task_relative_target": relative,
                }
            )
        security = java_home / "conf/security/java.security"
        report["jdk_layout"] = {
            "relocated_links": relocated,
            "java_security_sha256": digest(security),
            "system_configuration_modified": False,
            "cacerts": "package retains host /etc/ssl/certs/java/cacerts reference; no Java TLS experiment",
        }
        report["jdk"] = run(["java", "-version"])
        if "21.0.12.1+1" not in report["jdk"]:
            raise RuntimeError("JDK 精确版本不符")
        health = root / "jdk-health"
        health.mkdir()
        source = health / "JdkHealth.java"
        source.write_text(
            "public final class JdkHealth { public static void main(String[] args) { "
            'System.out.println(java.security.Security.getProperty("keystore.type")); } }\n'
        )
        run(["javac", "--release", "8", "-d", health, source])
        report["jdk_layout"]["security_compile_run"] = run(
            ["java", "-cp", health, "JdkHealth"]
        ).strip()
        if report["jdk_layout"]["security_compile_run"] != "pkcs12":
            raise RuntimeError("JDK 安全配置编译运行不符")
        core = root / "core"
        run(["git", "init", core])
        run(
            [
                "git",
                "-c",
                "credential.helper=",
                "-c",
                "core.hooksPath=/dev/null",
                "fetch",
                "--depth=1",
                "https://github.com/EasyTier/EasyTier",
                CORE,
            ],
            cwd=core,
        )
        run(
            [
                "git",
                "-c",
                "core.hooksPath=/dev/null",
                "checkout",
                "--detach",
                "FETCH_HEAD",
            ],
            cwd=core,
        )
        if (
            run(["git", "rev-parse", "HEAD"], cwd=core).strip() != CORE
            or digest(core / "Cargo.lock") != LOCK
        ):
            raise RuntimeError("核心 commit / lockfile 不符")
        report["cargo_lock_sha256"] = LOCK
        # 首次依赖下载从空 CARGO_HOME 开始；不是复制第一主机的已编译产物或依赖缓存。
        run(
            [rustup, "run", "1.99.0", "cargo", "fetch", "--locked"],
            cwd=core,
            timeout=3600,
        )
        for name, argv, artifact in steps:
            if name == "prepare_patch":
                run(argv)
                patch = json.loads((root / "boundary-core-patch.json").read_text())
                expected = json.loads(inputs["native_boundary_patch"].read_text())
                if (
                    patch["files"] != expected["files"]
                    or patch["lockfile_sha256"] != LOCK
                ):
                    raise RuntimeError("六文件补丁身份不符")
            else:
                stage(name, argv, artifact)
        if (
            run(
                ["git", "status", "--porcelain", "--untracked-files=all"], cwd=core
            ).strip()
            or digest(core / "Cargo.lock") != LOCK
        ):
            raise RuntimeError("复建修改了干净上游")
        report["status"] = "passed"
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report.update(status="failed", error=str(error))
    report["finished_at_utc"] = datetime.now(UTC).isoformat()
    save()
    print(
        json.dumps(
            {"status": report["status"], "report": str(root / "report.json")},
            ensure_ascii=False,
        )
    )
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
