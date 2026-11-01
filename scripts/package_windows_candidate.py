"""从相邻当前工作区构建 26.2 发布 JAR，装进 Windows 实例的 mods 目录。"""

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CODE = ROOT.parent
# 实例由 remote-session 的 handoff/s4-work/setup_instance.py 搭建：标准 Fabric 目录与 Start-Meridian.ps1。
INSTANCE = Path("/mnt/d/Minecraft/Meridian-26.2-20261101")
START_SCRIPT = "Start-Meridian.ps1"
# 仓库、模块与产物名只认 Framework 的统一装配清单，开发运行的 full-assembly.gradle 读同一份。
MANIFEST = json.loads((CODE / "Kizuna-Meridian-Mod-Framework/scripts/assembly.json").read_text(encoding="utf-8"))
REPOSITORIES = {repository["directory"]: repository for repository in MANIFEST["repositories"]}
# 第三方模组版本只认 Framework 的版本目录，与各仓编译用的是同一份。
CATALOG = tomllib.loads((CODE / "Kizuna-Meridian-Mod-Framework/scripts/libs.versions.toml").read_text(encoding="utf-8"))
THIRD_PARTY = ("fabric-api", "owo")
GRADLE_FILES = Path.home() / ".gradle/caches/modules-2/files-2.1"
# 模组与数据模组各自一个安装 JAR；库由所属模组的安装 JAR 嵌套，没有 candidate 的模块不进客户端。
INSTALLED_KINDS = ("mod", "data-mod")
PROJECTS = {
    repository["directory"]: [module["path"] for module in repository["modules"]
                              if module.get("candidate") in INSTALLED_KINDS]
    for repository in MANIFEST["repositories"]
}
WORKSPACE_MARKERS = ("kizuna-", "meridian-") + tuple(repository["project"] + "-"
                                                    for repository in MANIFEST["repositories"])
FORBIDDEN = "combat_trial"
# build/libs 里与发布 JAR 并列的带分类后缀产物：源码、下游测试夹具与旧开发 JAR 都不能进候选。
EXCLUDED_CLASSIFIERS = ("-sources.jar", "-test-fixtures.jar", "-dev.jar")
# Loader 自己提供的依赖 ID；其余依赖必须由 mods 目录里的模组或其嵌套 JAR 提供。
BUILTIN_IDS = {"java", "minecraft", "fabricloader"}
CLASS_REFERENCE = re.compile(rb"dev/kizuna/[A-Za-z0-9_/$]+")


def digest(path):
    # 全字节哈希同时用于源产物、复制结果和保护文件。
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


def artifact(repository, module):
    # 26.2 不重映射，安装与开发运行用同一个 build/libs 发布 JAR；按清单的前缀与版本拼出精确文件名，
    # 同目录的 -sources、-test-fixtures 等带分类后缀的 JAR 不会被选中。
    entry = REPOSITORIES[repository]
    basename = entry["project"]
    if module:
        basename += "-" + module
    version = entry.get("version", MANIFEST["version"])
    return CODE / repository / module / "build" / "libs" / f"{basename}-{version}.jar"


def third_party(alias):
    # 版本目录给出坐标与版本，JAR 取 Gradle 模块缓存里各仓编译用的那一份；缓存目录名就是文件的 SHA-1。
    library = CATALOG["libraries"][alias]
    group, name = library["module"].split(":")
    version = CATALOG["versions"][library["version"]["ref"]]
    matches = sorted((GRADLE_FILES / group / name / version).glob(f"*/{name}-{version}.jar"))
    if len(matches) != 1:
        raise RuntimeError(f"Gradle 缓存里 {name} {version} 的 JAR 不是恰好一个：{matches}")
    path = matches[0]
    if hashlib.sha1(path.read_bytes()).hexdigest() != path.parent.name:
        raise RuntimeError(f"Gradle 缓存 JAR 的 SHA-1 与目录名不符：{path}")
    return {"name": name, "version": version, "source": path}


def windows(path):
    # 指南写给 Windows 用户，路径交给 wslpath 换成 Windows 写法；演练用的 Linux 目录也能换算。
    result = subprocess.run(["wslpath", "-w", str(path)], check=True, capture_output=True, text=True)
    return result.stdout.strip()


def running(instance):
    # 换 mods 前确认本实例没有客户端在跑：Start-Meridian.ps1 以实例根的 meridian.args 启动 Java。
    pattern = re.escape(instance.name) + r"[\\/]meridian[.]args"
    command = ("@(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^javaw?[.]exe$' -and "
               f"$_.CommandLine -match '{pattern}' }}).Count")
    result = subprocess.run(["powershell.exe", "-NoProfile", "-Command", command],
                            check=True, capture_output=True, text=True, timeout=120)
    return int(result.stdout.strip()) > 0


def replaceable(path):
    # 工作区与两个第三方模组的旧 JAR 都会被本次安装替换；其他 JAR 留在 mods 目录并参与检查。
    name = path.name.lower()
    markers = WORKSPACE_MARKERS + tuple(CATALOG["libraries"][alias]["module"].split(":")[1] + "-"
                                        for alias in THIRD_PARTY)
    return name.startswith(markers)


def preserved_files(instance):
    # 启动参数、启动脚本与存档按字节保护：本脚本只动 mods、候选记录目录与实例根指南。
    paths = set(instance.glob("*.args")) | set(instance.glob("*.ps1"))
    paths.update(path for path in (instance / "saves").rglob("*") if path.is_file())
    return {str(path.relative_to(instance)): digest(path) for path in sorted(paths) if path.is_file()}


def read_archive(name, archive, found):
    # 递归读取 JAR 与其嵌套 JAR：登记模组 ID、依赖、类文件与类文件里对工作区类的引用。
    entries = archive.namelist()
    found["classes"].update(entry[:-len(".class")] for entry in entries if entry.endswith(".class"))
    for entry in entries:
        if entry.endswith(".class"):
            found["references"].update(match.decode() for match in CLASS_REFERENCE.findall(archive.read(entry)))
    if "fabric.mod.json" not in entries:
        return None
    mod = json.loads(archive.read("fabric.mod.json"))
    for identity in [mod["id"], *mod.get("provides", [])]:
        found["ids"].setdefault(identity, name)
    for dependency in mod.get("depends", {}):
        found["depends"].append((name, mod["id"], dependency))
    if "gametest" in " ".join(mod.get("entrypoints", {})):
        raise RuntimeError(f"安装 JAR 带 GameTest 入口：{name} 的 {mod['id']}")
    for nested in mod.get("jars", []):
        with zipfile.ZipFile(io.BytesIO(archive.read(nested["file"]))) as inner:
            read_archive(name + "!" + nested["file"], inner, found)
    return mod["id"]


def verify_mods(paths):
    # 静态检查只证明 mods 目录装配完整，不代替 Windows 启动与 Mixin 应用烟测。
    found = {"ids": {}, "depends": [], "classes": set(), "references": set()}
    top_level = {}
    for path in paths:
        if path.name.endswith(EXCLUDED_CLASSIFIERS):
            raise RuntimeError(f"mods 目录包含源码、测试夹具或开发 JAR：{path.name}")
        with zipfile.ZipFile(path) as archive:
            identity = read_archive(path.name, archive, found)
        if identity is None:
            raise RuntimeError(f"mods 目录里的 JAR 不是 Fabric 模组：{path.name}")
        if FORBIDDEN in identity or "combat-trial-catalog" in path.name.lower():
            raise RuntimeError(f"候选包含已取消的试玩目录：{path.name}")
        if identity in top_level:
            raise RuntimeError(f"重复顶层模组 ID：{identity}（{top_level[identity]}、{path.name}）")
        top_level[identity] = path.name
    missing = sorted({f"{owner} 的 {mod} 依赖 {dependency}" for owner, mod, dependency in found["depends"]
                      if dependency not in found["ids"] and dependency not in BUILTIN_IDS})
    if missing:
        raise RuntimeError("依赖没有任何模组提供：\n" + "\n".join(missing))
    packages = {name.rsplit("/", 1)[0] for name in found["classes"]}
    unresolved = sorted(reference for reference in found["references"]
                        if reference not in found["classes"]
                        and not any(package == reference or package.startswith(reference + "/")
                                    for package in packages))
    if unresolved:
        raise RuntimeError("工作区类引用在 mods 目录里找不到：\n" + "\n".join(unresolved))
    return top_level


def plan(instance, states):
    # 先算出安装后的完整 mods 目录并检查，任何一项不通过都不改实例。
    rows = []
    for repository, modules in PROJECTS.items():
        for module in modules:
            source = artifact(repository, module)
            rows.append({"file": source.name, "repository": repository, "module": module,
                         "head": states[repository]["head"], "source": str(source), "sha256": digest(source)})
    for alias in THIRD_PARTY:
        library = third_party(alias)
        rows.append({"file": library["source"].name, "catalog": alias, "version": library["version"],
                     "source": str(library["source"]), "sha256": digest(library["source"])})
    names = [row["file"] for row in rows]
    if len(set(names)) != len(names):
        raise RuntimeError("安装 JAR 文件名重复")
    current = sorted((instance / "mods").glob("*.jar"))
    replaced = [path.name for path in current if replaceable(path)]
    kept = [path for path in current if not replaceable(path)]
    top_level = verify_mods([Path(row["source"]) for row in rows] + kept)
    return rows, replaced, [path.name for path in kept], top_level


def install(instance, record, rows, replaced):
    # 旧 JAR 挪进本次记录目录可回滚，再复制新 JAR 并逐个核对哈希。
    mods = instance / "mods"
    (record / "replaced").mkdir()
    for name in replaced:
        shutil.move(mods / name, record / "replaced" / name)
    for row in rows:
        target = mods / row["file"]
        shutil.copy2(row["source"], target)
        if digest(target) != row["sha256"]:
            raise RuntimeError(f"复制后的 JAR 校验和不符：{target}")


def write_guide(instance, record):
    # 试玩命令与已知问题只维护 Wiki 一份，实例根指南填入本实例的启动脚本与记录目录。
    guide = (ROOT / "docs/wiki/Windows-Combat.md").read_text(encoding="utf-8")
    guide = guide.replace("__START_SCRIPT__", windows(instance / START_SCRIPT))
    guide = guide.replace("__CANDIDATE_DIRECTORY__", windows(record))
    (instance / "COMBAT-TRIAL.md").write_text(guide, encoding="utf-8")


def build(record, states):
    # 按清单顺序逐仓构建（下游按文件依赖上游产物），任一失败即停止并保留日志；构建期间源码不得变化。
    commands = []
    (record / "build-logs").mkdir()
    for repository in states:
        command = ["bash", "gradlew", "build", "--no-daemon", "--console=plain"]
        commands.append({"repository": repository, "command": command})
        print("构建：" + repository, flush=True)
        with (record / "build-logs" / (repository + ".log")).open("w", encoding="utf-8") as output:
            subprocess.run(command, cwd=CODE / repository, stdout=output, stderr=subprocess.STDOUT, check=True)
    for repository, before in states.items():
        if workspace_state(CODE / repository) != before:
            raise RuntimeError(f"构建期间源码发生变化，需重新打包：{repository}")
    return commands


def main():
    # 每次创建新的记录目录；演练只用现有产物算安装计划并检查，不构建也不写实例。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, default=INSTANCE)
    parser.add_argument("--name", default="candidate-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--dry-run", action="store_true", help="不构建、不写实例，只用现有产物检查并打印安装计划")
    args = parser.parse_args()
    if Path(args.name).name != args.name or args.name in (".", ".."):
        parser.error("候选名必须为单层目录名")
    instance = args.instance.resolve()
    if not (instance / START_SCRIPT).is_file() or not (instance / "mods").is_dir():
        parser.error(f"实例缺少 {START_SCRIPT} 或 mods 目录：{instance}")
    if running(instance):
        raise RuntimeError("实例的客户端正在运行，请先正常退出游戏")
    record = instance / "candidates" / args.name
    protected = preserved_files(instance)
    states = {repository: workspace_state(CODE / repository) for repository in REPOSITORIES}
    commands = []
    if not args.dry_run:
        record.mkdir(parents=True, exist_ok=False)
        commands = build(record, states)
    rows, replaced, kept, top_level = plan(instance, states)
    summary = {"instance": str(instance), "dry_run": args.dry_run, "workspaces": len(states),
               "installed": [row["file"] for row in rows], "replaced": replaced, "kept": kept,
               "top_level_mods": len(top_level)}
    if args.dry_run:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return
    if running(instance):
        raise RuntimeError("构建期间实例的客户端启动了，拒绝替换 mods")
    install(instance, record, rows, replaced)
    if preserved_files(instance) != protected:
        raise RuntimeError("实例保护文件发生变化，拒绝标记候选就绪")
    manifest = {"workspaces": states, "commands": commands, "installed": rows, "replaced": replaced,
                "kept": kept, "top_level_mods": top_level, "protected": protected,
                "start_script": windows(instance / START_SCRIPT), "windows_launch_tested": False}
    (record / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_guide(instance, record)
    summary["candidate"] = str(record)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
