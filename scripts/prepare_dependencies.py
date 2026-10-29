"""从指定提交准备隔离功法快照，不读取功法工作区的未提交改动。"""

import argparse
import io
import os
from pathlib import Path
import subprocess
import tarfile


TECHNIQUE_COMMIT = "89e37ec0a35748297d7ffd281231d2eb9537065f"
FRAMEWORK_COMMIT = "e2859475afe6ef4d948f35052e1e112c051ab737"


def prepare_snapshot(source: Path, destination: Path) -> None:
    # Git 归档只包含已提交文件；已有快照只校验，不覆盖可能存在的本地编辑。
    archived = subprocess.run(
        ["git", "-C", str(source), "archive", TECHNIQUE_COMMIT],
        check=True, stdout=subprocess.PIPE,
    ).stdout
    with tarfile.open(fileobj=io.BytesIO(archived)) as archive:
        if not destination.exists():
            destination.mkdir(parents=True)
            archive.extractall(destination, filter="data")
            return
        for member in archive.getmembers():
            target = destination / member.name
            if member.isfile():
                expected = archive.extractfile(member).read()
                if not target.is_file() or target.read_bytes() != expected:
                    raise RuntimeError(f"快照与指定提交不同，请使用新的依赖目录：{target}")
            elif member.issym() and (not target.is_symlink() or os.readlink(target) != member.linkname):
                raise RuntimeError(f"快照符号链接不同：{target}")


def prepare_framework(source: Path, link: Path) -> None:
    # Framework 使用已验证的干净提交，符号链接供两个 composite build 统一识别。
    commit = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True,
    ).strip()
    if commit != FRAMEWORK_COMMIT:
        raise RuntimeError(f"Framework 需要提交 {FRAMEWORK_COMMIT}，当前为 {commit}")
    subprocess.run(["git", "-C", str(source), "diff", "--quiet", "HEAD"], check=True)
    if link.exists() or link.is_symlink():
        if not link.is_symlink() or link.resolve() != source:
            raise RuntimeError(f"依赖路径已被其他内容占用：{link}")
    else:
        link.symlink_to(os.path.relpath(source, link.parent), target_is_directory=True)


def main() -> None:
    # 默认只访问相邻本地仓库，构建产物全部位于忽略目录或 Gradle 输出目录。
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--technique", type=Path, default=root.parent / "Kizuna-Meridian-Technique-Framework")
    parser.add_argument("--framework", type=Path, default=root.parent / "Kizuna-Meridian-Mod-Framework")
    parser.add_argument("--java17", type=Path, help="Gradle 未自动发现工具链时指定 Java 17 安装目录")
    args = parser.parse_args()
    dependencies = root / ".local-deps"
    snapshot = dependencies / "technique-89e37ec"
    prepare_snapshot(args.technique.resolve(), snapshot)
    prepare_framework(args.framework.resolve(), dependencies / "Kizuna-Meridian-Mod-Framework")
    wrapper = root / "gradlew"
    if os.name == "nt":
        wrapper = root / "gradlew.bat"
    command = [str(wrapper), "-p", str(snapshot), ":fabric:build", "--console=plain"]
    if args.java17 is not None:
        command.append("-Dorg.gradle.java.installations.paths=" + str(args.java17.resolve()))
    subprocess.run(command, cwd=root, check=True)
    print(f"依赖已准备：Technique {TECHNIQUE_COMMIT}；Framework {FRAMEWORK_COMMIT}")


if __name__ == "__main__":
    main()
