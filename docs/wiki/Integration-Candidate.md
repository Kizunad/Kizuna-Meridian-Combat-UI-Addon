# 当前工作区整合候选

此入口把当前工作区构建成 26.2 发布 JAR，装进 Windows 实例的 `mods/`；不改实例的启动参数、启动脚本或存档。
候选包含当前工作区的 Framework、Human、UI、Human-UI、Items、Damage、Healing、Progression、
Qi-Variants、Threatening、Debug、功法库、基础功法、破门斩、战斗 UI、Inventory UI、Players Animation、
Players Animation Mobs-Addon、BS3D 及其 Vanilla、Pocket、Weights 附属、Burden、Humanoid Dummy 与 Humanoid Taunts。
仓库、模块和装入方式只认 Framework 仓的统一装配清单 `scripts/assembly.json`，开发运行的 `full-assembly.gradle` 读同一份；
`candidate` 为 `mod` 或 `data-mod` 的模块各装一个发布 JAR，`library` 与 `named-library` 由所属模组的 JAR 嵌套，
没有 `candidate` 的模块不进客户端，例如 bs3d 附属只在专用服务端加载的 server JAR。
已取消的试玩功法目录不参与构建或安装。

在战斗 UI 仓执行：

```bash
python3 "scripts/package_windows_candidate.py" --dry-run
python3 "scripts/package_windows_candidate.py"
```

默认实例是 `/mnt/d/Minecraft/Meridian-26.2-20261101`：标准 Fabric 目录加 `Start-Meridian.ps1`，
由 remote-session 的 `handoff/s4-work/setup_instance.py` 搭建；可用 `--instance` 指定其他实例。
`--dry-run` 不构建、不写实例，只用各仓现有的 `build/libs` 产物算出安装计划并做下面的全部检查。
正式运行每次在实例的 `candidates/candidate-时间戳/` 下建新目录；`--name` 可指定单层目录名，已有目录会拒绝覆盖。
按清单顺序依次在 25 个相邻仓运行 `bash gradlew build --no-daemon --console=plain`，任一失败即停止并保留候选内的构建日志。
这会使用未提交源文件，不使用隔离提交快照。

安装取各模块的 `build/libs/<项目>[-模块]-<版本>.jar`：26.2 不重映射，开发运行与安装用同一个 JAR；
同目录的 `-sources`、`-test-fixtures` 等带分类后缀的产物不会选中。Fabric API 与 owo 的版本读 Framework 的
`scripts/libs.versions.toml`，JAR 取 Gradle 模块缓存里各仓编译用的那一份。
`mods/` 里以 `kizuna-`、`meridian-`、清单项目名、`fabric-api-`、`owo-lib-` 开头的旧 JAR 先挪进候选的 `replaced/`，
再复制新 JAR 并核对 SHA-256；其他 JAR 保留并参与检查。实例的客户端在运行时拒绝替换。
候选的 `manifest.json`、`build-logs/` 与 `replaced/` 都在新目录内，试玩指南写到实例根 `COMBAT-TRIAL.md`。

清单记录每仓 HEAD、未提交状态、Git 可见源码文件哈希、构建命令、每个 JAR 的来源和 SHA-256。
写入前先检查装好后的整个 `mods/`：顶层模组 ID 不重复，每个 `depends` 都有模组（含嵌套 JAR 与 `provides`）提供，
工作区类引用都能在 `mods/` 里找到，没有 GameTest 入口、带分类后缀的 JAR 或试玩目录。
构建期间源码变化，或实例的启动参数、启动脚本、存档发生变化，都会报错。
静态验证不代表客户端成功启动，也不证明 Mixin 已实际应用。

完整装配 GameTest 在基础功法仓的 `PlayerAssemblyGameTest`、`PlayerCombatAssemblyGameTest` 和
`VindicatorAssemblyGameTest`。复用正式 Human 登录，通过卷轴物品交互学习，通过
`meridian_combat:press` 正式载荷入口施放蓄力、轻步、劈门和叩门，验证位移、力量、实伤与耐久。
真炁卫道士经过原版 AI tick 自选破门斩，对在线玩家造成伤害，并在 `mob_griefing` 开启时劈木门。
基础功法正式数据包与测试入口分开加载；4 项 JUnit、11 项 GameTest 已通过。
日志为相邻 remote-session 的 `handoff/logs/integration-final-gametest.log`。

Windows 烟测用 `powershell.exe` 冷启动实例根 `Start-Meridian.ps1`，到主菜单后核对 `logs/latest.log`
没有 Mixin 失败或模组加载错误，截图后正常退出，不进入存档。
清单中的 `windows_launch_tested: false` 表示打包时尚未启动，后续实机结论以烟测记录为准。

确认用户已正常退出客户端后再打包，之后按 [Windows 试玩指南](Windows-Combat.md) 运行 `Start-Meridian.ps1`。
主菜单烟测不替代进世界后的按键、镜头、HUD、美术与卷轴阅读验收；目前没有破门斩专属动画。
