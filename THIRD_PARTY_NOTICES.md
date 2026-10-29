# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

| 组件 | 版本 / 来源 | 许可与使用 |
|---|---|---|
| Meridian Technique Framework | `89e37ec`，相邻仓库的 Git 归档 | PolyForm Noncommercial 1.0.0；硬依赖，发行包不内嵌 |
| Meridian Mod Framework | `e285947`，相邻干净仓库 | PolyForm Noncommercial 1.0.0；编译与测试依赖，发行包不内嵌 |
| Gradle Wrapper | 8.8，复制自 Technique Framework | Apache License 2.0；保留启动脚本版权头、Wrapper JAR 内许可及分发校验和 |
| Protocol Buffers | 4.36.2 | BSD 3-Clause；使用 protoc 生成消息，发行包内嵌 protobuf-java 并保留原 JAR |
| Gson | 2.10.1 | Apache License 2.0；由 Minecraft 运行环境提供，发行包不内嵌 |
| Fabric API / Loader / Loom | 0.92.3+1.20.1 / 0.16.10 / 1.6.12 | Apache License 2.0；运行环境与构建工具，不内嵌 |
| Yarn | 1.20.1+build.10 | CC0 1.0；仅构建映射 |
| JUnit / Hamcrest Core | 4.13.2 / 1.3 | Eclipse Public License 1.0 / BSD 3-Clause；仅测试 |
| Minecraft | 1.20.1 | Mojang 专有许可；由使用者独立取得，不分发游戏文件 |

宿主分发须保留各依赖的原始许可。发行 JAR 内的 `META-INF/jars` 只包含本项目 core、shared 和 protobuf-java。
