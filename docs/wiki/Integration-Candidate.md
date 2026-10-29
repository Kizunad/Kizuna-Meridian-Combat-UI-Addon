# 当前工作区整合候选

此入口只准备 Windows Native 候选，不替换实例根的 `combat.args`、`play.args`、启动脚本或存档。
候选包含当前工作区的 Framework、Human、UI、Human-UI、Items、Damage、Healing、Progression、
Qi-Variants、Threatening、Debug、功法库、基础功法、破门斩、战斗 UI、Inventory UI、Players Animation、
Players Animation Mobs-Addon 与 BS3D。
已取消的试玩功法目录不参与构建或类路径。

在战斗 UI 仓执行：

```bash
python3 "scripts/package_windows_candidate.py"
```

默认实例是 `/mnt/d/Minecraft/Meridian-Animation-Integrated-20261021`，可用 `--instance` 指定其他 WSL 盘符目录。
每次在 `libraries/combat-current-时间戳/` 下创建新目录；`--name` 可指定单层目录名，已有目录会拒绝覆盖。
依次在 19 个相邻仓运行 `bash gradlew build --console=plain`，任一失败即停止并保留候选内的构建日志。
这会使用未提交源文件，不使用隔离提交快照。

脚本从原 `play.args` 继承外部 Minecraft、Fabric、Java 与 Windows 原生库，替换所有工作区 JAR，
增加 Qi-Variants、Threatening、Debug、PA Mobs-Addon 和四个功法相关模块，
移除旧 `-Dkiui.smoke` 标记以正常加载用户偏好。
Fabric 模块使用 named 的 `build/devlibs/*-dev.jar`，
纯 Java 库与基础功法、破门斩的无类数据包使用 `build/libs`；Items 的宿主库含 Minecraft 类型，也使用 named dev JAR。
候选的 `combat.args`、`remapClasspath.txt`、`manifest.json`、`COMBAT-TRIAL.md` 与 `build-logs/` 均在新目录内。

清单记录每仓 HEAD、未提交状态、Git 可见源码文件哈希、构建命令、每个 JAR 的来源和 SHA-256。
构建期间源码变化、顶层模组 ID 重复、混入试玩目录或原实例保护文件变化都会报错。
静态验证不代表客户端成功启动，也不证明 Mixin 已实际应用。

完整装配 GameTest 在基础功法仓的 `PlayerAssemblyGameTest`、`PlayerCombatAssemblyGameTest` 和
`VindicatorAssemblyGameTest`。复用正式 Human 登录，通过卷轴物品交互学习，通过
`meridian_combat:press` 正式载荷入口施放蓄力、轻步、劈门和叩门，验证位移、力量、实伤与耐久。
真炁卫道士经过原版 AI tick 自选破门斩，对在线玩家造成伤害，并在 `mobGriefing` 开启时劈木门。
基础功法正式数据包与测试入口分开加载；4 项 JUnit、11 项 GameTest 已通过。
日志为相邻 remote-session 的 `handoff/logs/integration-final-gametest.log`。

Windows 烟测从候选参数生成独立 `smoke.args` 和游戏目录，临时客户端探针仅在真实 `TitleScreen`
且资源加载遮罩已消失时记录主菜单标记，不截图、OCR、模拟按键或进入存档。
检查完整模组清单和 Mixin 日志后，仅按本次启动的 PID 关闭；证据写入候选 `windows-smoke.json`。
探针不进入正式 `combat.args`。静态清单中的 `windows_launch_tested: false` 表示打包时尚未启动，
后续实机结论以该独立证据为准。

确认用户已正常退出旧客户端后，按 [Windows 试玩指南](Windows-Combat.md) 备份和替换 `combat.args`，
再使用现有 `Start-combat.ps1`。指南由 Wiki 模板渲染具体候选路径，烟测后同步至实例根 `COMBAT-TRIAL.md`。
主菜单烟测不替代进世界后的按键、镜头、HUD、美术与卷轴阅读验收；目前没有破门斩专属动画。
