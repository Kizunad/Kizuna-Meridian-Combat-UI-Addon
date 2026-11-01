"""检查 build/libs 发布 JAR 的文件名、硬依赖、客户端 Mixin 与内嵌运行依赖。"""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ARCHIVE_PREFIX = "kizuna-meridian-combat-ui-addon-fabric"
MIXIN_CONFIG = "meridian_combat.client.mixins.json"
CLIENT_MIXINS = 5
# 内嵌 core、shared 两个子模块与 protobuf-java 运行库。
NESTED_JARS = 3
# 与发布 JAR 并列的带分类后缀产物不能当作安装包，也不能被内嵌。
CLASSIFIERS = ("-sources", "-test-fixtures", "-dev")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="fabric/build/libs 下的发布 JAR")
    arguments = parser.parse_args()
    with zipfile.ZipFile(arguments.jar) as archive:
        entries = set(archive.namelist())
        mod = json.loads(archive.read("fabric.mod.json"))
        # 26.2 不重映射，安装与开发运行共用同一个发布 JAR；文件名必须与包内版本精确对应，不带分类后缀。
        expected_name = f"{ARCHIVE_PREFIX}-{mod['version']}.jar"
        if arguments.jar.name != expected_name:
            raise ValueError(f"不是发布 JAR：期望文件名 {expected_name}，实际 {arguments.jar.name}")
        if "meridian_technique" not in mod["depends"]:
            raise ValueError("发行包缺少功法框架硬依赖")
        if any("gametest" in entry.lower() or "CombatTest" in entry for entry in entries):
            raise ValueError("发行包混入测试内容")
        if MIXIN_CONFIG not in mod["mixins"]:
            raise ValueError("发行包未注册客户端 Mixin 配置")
        config = json.loads(archive.read(MIXIN_CONFIG))
        # 不重映射就不该再有 refmap；出现说明拿到的是 1.20.1 时期的旧产物。
        if "refmap" in config:
            raise ValueError("Mixin 配置仍带 refmap，可能是 1.20.1 旧产物")
        package = config["package"].replace(".", "/")
        mixins = {f"{package}/{name}" for name in config["client"]}
        if len(mixins) != CLIENT_MIXINS:
            raise ValueError("客户端 Mixin 数量不符")
        if any(f"{name}.class" not in entries for name in mixins):
            raise ValueError("发行包缺少客户端 Mixin 类")
        # 声明的内嵌清单与包内实际文件必须一致，且都不是带分类后缀的 JAR。
        declared = sorted(item["file"] for item in mod.get("jars", []))
        nested = sorted(entry for entry in entries if entry.startswith("META-INF/jars/")
                        and entry.endswith(".jar"))
        if declared != nested:
            raise ValueError(f"fabric.mod.json 的 jars 与包内内嵌 JAR 不一致：{declared} / {nested}")
        if len(nested) != NESTED_JARS:
            raise ValueError("内嵌运行依赖数量不符")
        if any(entry.removesuffix(".jar").endswith(CLASSIFIERS) for entry in nested):
            raise ValueError("内嵌了带分类后缀的 JAR")
        print(f"发行 JAR 检查通过：版本 {mod['version']}，{CLIENT_MIXINS} 个客户端 Mixin，"
              f"{NESTED_JARS} 个内嵌依赖，无 refmap，无测试类")
        print("内嵌依赖：" + ", ".join(nested))
    print("SHA-256：" + hashlib.sha256(arguments.jar.read_bytes()).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
