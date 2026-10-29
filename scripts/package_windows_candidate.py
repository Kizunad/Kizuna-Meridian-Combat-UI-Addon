"""从相邻当前工作区构建 Windows Native 候选；只在新目录内写入文件。"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CODE = ROOT.parent
INSTANCE = Path("/mnt/d/Minecraft/Meridian-Animation-Integrated-20261021")
VERSION = "0.1.0-SNAPSHOT"
COMBAT_VERSION = "0.1.1-SNAPSHOT"
# 顺序满足文件形式的跨仓编译依赖；构建直接读取工作区，包含未提交源文件。
PROJECTS = {
    "Kizuna-Meridian-Mod-Framework": [("server", "library"), ("client", "library")],
    "Kizuna-Meridian-Qi-Variants-Addon": [("server", "library"), ("shared", "library")],
    "Kizuna-Meridian-Technique-Framework": [("server", "library"), ("shared", "library"), ("fabric", "mod")],
    "Kizuna-Inventory-UI-Framework": [("", "mod"), ("server", "mod")],
    "Kizuna-Players-Animation": [("", "mod")],
    "Kizuna-Players-Animation-Mobs-Addon": [("", "mod")],
    "Kizuna-Meridian-Items-Addon": [("", "named-library")],
    "Kizuna-Meridian-Progression-Addon": [
        ("server", "library"), ("shared", "library"), ("fabric", "mod"), ("client", "mod")],
    "Kizuna-Meridian-UI-Addon": [("server", "library"), ("client", "mod")],
    "Kizuna-Meridian-Vanilla-Damage-Addon": [("server", "library"), ("fabric", "mod")],
    "Kizuna-Meridian-Vanilla-Healing-Addon": [("server", "library"), ("fabric", "mod")],
    "Kizuna-Meridian-Human-Addon": [
        ("server", "library"), ("shared", "library"), ("client", "library"), ("fabric", "mod")],
    "Kizuna-Meridian-Human-UI-Addon": [("client", "mod")],
    "backpack-and-storage-3D": [("server", "mod"), ("client", "mod")],
    "Kizuna-Meridian-Vanilla-Humanoid-Threatening": [("server", "library"), ("fabric", "mod")],
    "Kizuna-Meridian-Debug-Addon": [("server", "library"), ("fabric", "mod")],
    "Kizuna-Meridian-Basic-Techniques-Addon": [("fabric", "data-mod")],
    "Kizuna-Meridian-Door-Breaker-Addon": [("fabric", "data-mod")],
    "Kizuna-Meridian-Combat-UI-Addon": [("core", "library"), ("shared", "library"), ("fabric", "mod")],
}
FORBIDDEN = "combat_trial"


def digest(path):
    # 全字节哈希同时用于源产物、复制结果和保留的外部运行库。
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(repository, *arguments):
    # 新仓允许尚无 HEAD；其他 Git 错误必须保留给调用方处理。
    return subprocess.run(["git", "-C", str(repository), *arguments],
                          check=False, capture_output=True)


def workspace_state(repository):
    # 只纳入 Git 可见源码，忽略构建缓存；摘要绑定未提交和未跟踪文件。
    head = git(repository, "rev-parse", "--verify", "HEAD")
    paths = git(repository, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    paths.check_returncode()
    hashes = {}
    for raw in sorted(set(paths.stdout.split(b"\0")) - {b""}):
        relative = raw.decode("utf-8")
        path = repository / relative
        hashes[relative] = digest(path) if path.is_file() else "missing"
    encoded = json.dumps(hashes, sort_keys=True).encode("utf-8")
    return {
        "head": head.stdout.decode().strip() if head.returncode == 0 else "unborn",
        "status": git(repository, "status", "--porcelain=v1").stdout.decode(),
        "source_sha256": hashlib.sha256(encoded).hexdigest(),
        "files": hashes,
    }


def artifact(repository, module, kind):
    # 运行于 named 开发命名空间，所有 Fabric 类使用 remap 前的 dev JAR。
    basename = repository.lower()
    if repository == "backpack-and-storage-3D":
        basename = "backpack-storage-3d"
    if module:
        basename += "-" + module
    version = COMBAT_VERSION if repository == ROOT.name else VERSION
    development = kind in ("mod", "named-library")
    directory = "devlibs" if development else "libs"
    suffix = "-dev" if development else ""
    return CODE / repository / module / "build" / directory / f"{basename}-{version}{suffix}.jar"


def mod_id(path):
    # 只读取顶层身份，嵌套第三方库由 Fabric 的依赖解析处理。
    with zipfile.ZipFile(path) as archive:
        if "fabric.mod.json" not in archive.namelist():
            return None
        return json.loads(archive.read("fabric.mod.json"))["id"]


def windows(path):
    # WSL 盘符挂载映射到 Windows Java 可读取的正斜线绝对路径。
    parts = path.resolve().parts
    if len(parts) < 4 or parts[1] != "mnt" or len(parts[2]) != 1:
        raise ValueError(f"候选需要 Windows 盘符路径：{path}")
    return parts[2].upper() + ":/" + "/".join(parts[3:])


def local(path):
    # 只接受实例中已存在的 Windows 绝对路径，不猜测相对类路径。
    if len(path) < 3 or path[1:3] != ":/":
        raise ValueError(f"不支持的类路径：{path}")
    return Path("/mnt") / path[0].lower() / path[3:]


def preserved_files(instance, live_instance=False):
    # 启动文件始终按字节保护；运行中存档由游戏持锁并持续保存，不能读取或要求其冻结。
    paths = set(instance.glob("*.args")) | set(instance.glob("*.ps1"))
    paths.add(instance / "COMBAT-TRIAL.md")
    if not live_instance:
        for game in ("play", "combat"):
            paths.update(path for path in (instance / game / "saves").rglob("*") if path.is_file())
    return {str(path): digest(path) for path in sorted(paths) if path.is_file()}


def inherited_classpath(arguments, sources):
    # 按身份和库文件名移除旧工作区模块，外部 Minecraft/Fabric/Windows 运行库逐字保留。
    index = arguments.index("-classpath") + 1
    identities = {identity for source in sources if (identity := mod_id(source)) is not None}
    retained = []
    removed = []
    for entry in arguments[index].split(";"):
        path = local(entry)
        identity = mod_id(path)
        workspace_library = any(marker in path.name.lower() for marker in
                                ("kizuna-", "meridian-", "backpack-storage-3d"))
        if identity in identities or workspace_library or FORBIDDEN in (identity or ""):
            removed.append(entry)
        else:
            if identity and (identity.startswith("meridian_") or identity.startswith("kizuna_")):
                raise RuntimeError(f"未识别的工作区模组，拒绝继承：{entry}")
            retained.append({"path": entry, "source": "inherited-external-play.args", "sha256": digest(path)})
    return index, retained, removed


def verify_classpath(rows):
    # 候选静态检查只证明装配完整，不代替 Windows 启动与 Mixin 应用烟测。
    identities = {}
    for row in rows:
        path = local(row["path"])
        if digest(path) != row["sha256"]:
            raise RuntimeError(f"候选校验和不符：{path}")
        identity = mod_id(path)
        if FORBIDDEN in (identity or "") or "combat-trial-catalog" in path.name.lower():
            raise RuntimeError(f"候选包含已取消的试玩目录：{path}")
        if identity:
            if identity in identities:
                raise RuntimeError(f"重复顶层模组 ID：{identity}")
            identities[identity] = row["path"]
    return identities


def write_candidate(destination, arguments, index, rows, removed, states, commands, protected, live_instance=False):
    # 所有写入仅落在刚创建的候选目录；现用参数和启动脚本保持原字节。
    classpath = ";".join(row["path"] for row in rows)
    arguments[index] = classpath
    remap = "-Dfabric.remapClasspathFile=" + windows(destination / "remapClasspath.txt")
    arguments = [remap if item.startswith("-Dfabric.remapClasspathFile=") else item for item in arguments]
    if not any(item.startswith("-Dfabric.remapClasspathFile=") for item in arguments):
        raise RuntimeError("原实例没有开发重映射类路径参数")
    if "-Dfabric.development=true" not in arguments:
        raise RuntimeError("原实例不是 named 开发装配")
    # 旧开发入口的 smoke 标记会跳过库存 UI 偏好读取，正式候选使用正常用户配置。
    arguments = [value for value in arguments if not value.startswith("-Dkiui.smoke=")]
    arguments[arguments.index("--gameDir") + 1] = windows(destination.parent.parent / "combat")
    arguments[arguments.index("--version") + 1] = "Meridian-Combat-Current-Candidate"
    (destination / "remapClasspath.txt").write_text(classpath, encoding="utf-8")
    (destination / "combat.args").write_text(
        "\n".join('"' + value + '"' for value in arguments) + "\n", encoding="utf-8")
    identities = verify_classpath(rows)
    manifest = {"workspaces": states, "commands": commands, "classpath": rows,
                "removed_workspace_entries": removed, "top_level_mods": identities,
                "protected": protected, "windows_launch_tested": False, "live_instance": live_instance}
    (destination / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # 试玩命令与已知问题只维护 Wiki 一份，候选填入自己的确定路径。
    guide = (ROOT / "docs/wiki/Windows-Combat.md").read_text(encoding="utf-8")
    guide = guide.replace("__CANDIDATE_ARGS__", windows(destination / "combat.args"))
    guide = guide.replace("__CANDIDATE_DIRECTORY__", windows(destination))
    (destination / "COMBAT-TRIAL.md").write_text(guide, encoding="utf-8")
    return identities


def main():
    # 每次创建新目录，失败也保留日志，不覆盖任何旧候选或现用文件。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, default=INSTANCE)
    parser.add_argument("--name", default="combat-current-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--live-instance", action="store_true",
                        help="保留运行中的客户端；只校验启动文件，不读取或写入其正在使用的存档")
    args = parser.parse_args()
    if Path(args.name).name != args.name or args.name in (".", ".."):
        parser.error("候选名必须为单层目录名")
    instance = args.instance.resolve()
    destination = instance / "libraries" / args.name
    protected = preserved_files(instance, args.live_instance)
    states = {name: workspace_state(CODE / name) for name in PROJECTS}
    arguments = shlex.split((instance / "play.args").read_text(encoding="utf-8"))
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "build-logs").mkdir()
    commands = []
    for repository in PROJECTS:
        command = ["bash", "gradlew", "build", "--console=plain"]
        commands.append({"repository": repository, "command": command})
        print("构建：" + repository, flush=True)
        with (destination / "build-logs" / (repository + ".log")).open("w", encoding="utf-8") as output:
            subprocess.run(command, cwd=CODE / repository, stdout=output, stderr=subprocess.STDOUT, check=True)
    sources = [artifact(repository, module, kind)
               for repository, modules in PROJECTS.items() for module, kind in modules]
    index, rows, removed = inherited_classpath(arguments, sources)
    for repository, modules in PROJECTS.items():
        for module, kind in modules:
            source = artifact(repository, module, kind)
            target = destination / source.name
            shutil.copy2(source, target)
            rows.append({"path": windows(target), "source": str(source), "repository": repository,
                         "module": module, "sha256": digest(source)})
    for repository, before in states.items():
        if workspace_state(CODE / repository) != before:
            raise RuntimeError(f"构建期间源码发生变化，需重新打包：{repository}")
    if preserved_files(instance, args.live_instance) != protected:
        raise RuntimeError("原实例保护文件发生变化，拒绝标记候选就绪")
    identities = write_candidate(destination, arguments, index, rows, removed, states, commands, protected,
                                 args.live_instance)
    print(json.dumps({"candidate": str(destination), "workspaces": len(states),
                      "workspace_jars": len(sources), "classpath_entries": len(rows),
                      "top_level_mods": len(identities), "protected_files": len(protected),
                      "windows_launch_tested": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
