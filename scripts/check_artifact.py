"""检查发行 JAR 中的客户端 Mixin、映射文件与运行依赖。"""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main() -> int:
    # 从打包后的配置读取 refmap 名称，不猜测 Loom 生成的文件名。
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="安装用 Fabric JAR")
    arguments = parser.parse_args()
    with zipfile.ZipFile(arguments.jar) as archive:
        entries = set(archive.namelist())
        mod = json.loads(archive.read("fabric.mod.json"))
        if "meridian_technique" not in mod["depends"]:
            raise ValueError("发行包缺少功法框架硬依赖")
        if any("gametest" in entry.lower() or "CombatTest" in entry for entry in entries):
            raise ValueError("发行包混入测试内容")
        config_name = "meridian_combat.client.mixins.json"
        if config_name not in mod["mixins"]:
            raise ValueError("发行包未注册客户端 Mixin 配置")
        config = json.loads(archive.read(config_name))
        refmap_name = config["refmap"]
        refmap = json.loads(archive.read(refmap_name))
        package = config["package"].replace(".", "/")
        expected = {f"{package}/{name}" for name in config["client"]}
        if len(expected) != 5:
            raise ValueError("客户端 Mixin 数量不符")
        if set(refmap["mappings"]) != expected:
            raise ValueError("refmap 未包含全部客户端 Mixin")
        if any(f"{name}.class" not in entries for name in expected):
            raise ValueError("发行包缺少客户端 Mixin 类")
        nested = sorted(entry for entry in entries if entry.startswith("META-INF/jars/")
                        and entry.endswith(".jar"))
        if len(nested) != 3:
            raise ValueError("内嵌运行依赖数量不符")
        print(f"发行 JAR 检查通过：版本 {mod['version']}，5 个 Mixin/refmap，3 个内嵌依赖，无测试类")
        print(f"refmap：{refmap_name}")
        print("内嵌依赖：" + ", ".join(nested))
    print("SHA-256：" + hashlib.sha256(arguments.jar.read_bytes()).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
