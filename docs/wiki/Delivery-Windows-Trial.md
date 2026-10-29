# Windows 试玩与无槽入口调整

依据 `~/Code/Kizuna-remote-session/handoff/windows-combat-trial.md` 以及用户试玩时的最新决定。
R 进入架势不再读取槽位或已学功法；玩家状态、屏幕和服务端通道检查仍由 core 状态机处理。
空槽数字键在动作栏提示并立即返回，不构造或发送施放请求，也不退出架势。
删除失去用途的 Slots 枚举和 hasUsableSlot 方法，避免保留第二套入口判断。

## 文件与测试

修改 8 个现有文件，新增本页及 Windows 试玩指南，共 10 个文件；前 7 个文件的修改前副本位于
`.local-deps/empty-stance-baseline/`。Git 仍将首版至今全部源码显示为未跟踪。

- `core/src/main/java/dev/kizuna/meridian/combat/core/CombatInput.java`：入口去掉槽位参数与校验。
- `core/src/main/java/dev/kizuna/meridian/combat/core/CombatSlots.java`：删除仅供入口使用的聚合判断。
- `core/src/test/java/dev/kizuna/meridian/combat/core/CombatInputTest.java`：更新入口测试，新增十空槽提示回归。
- `core/src/test/java/dev/kizuna/meridian/combat/core/CombatProfilesTest.java`：直接断言有效/失效槽的选择结果。
- `fabric/src/main/java/dev/kizuna/meridian/combat/fabric/client/CombatClient.java`：仅传玩家、屏幕和服务端状态。
- `docs/wiki/Usage.md`、`Verification.md`、`Home.md`、本页与 `Windows-Combat.md`：同步入口条件、测试与切换指南。

`entryRequiresSupportedServerAndUsableSlots` 更名为
`entryRequiresSupportedServerButNoUsableSlots`，覆盖未握手拒绝、无槽进入、快捷栏消费及主动退出。
新增 `emptySlotsOnlyReturnPromptAndRetainStance`，逐一检查十槽空 ID、中文提示与架势保留。
客户端 press 的提示分支保留在构造 TechniquePress 之前。
其余全部测试方法名见 [构建与验证](Verification.md#实际通过的测试方法)。

## 本轮实际命令与结果

在本仓根目录运行；两条 Gradle 命令都使用本次试玩的独立 detached worktree，
不读取相邻框架、功法库返工工作区的源码。

```bash
"./gradlew" -PmeridianFrameworkDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Mod-Framework" -PmeridianTechniqueDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Technique-Framework" test build --console=plain > ".local-deps/empty-stance-build.log" 2>&1
"./gradlew" -PmeridianFrameworkDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Mod-Framework" -PmeridianTechniqueDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Technique-Framework" :fabric:runGametest --console=plain > ".local-deps/empty-stance-gametest.log" 2>&1
"./gradlew" -PmeridianFrameworkDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Mod-Framework" -PmeridianTechniqueDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Technique-Framework" test build --console=plain > ".local-deps/empty-stance-build-final.log" 2>&1
"./gradlew" -PmeridianFrameworkDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Mod-Framework" -PmeridianTechniqueDir="/home/kiz/Code/Kizuna-Meridian-Combat-Trial-Catalog/.local-deps/worktrees/Kizuna-Meridian-Technique-Framework" :fabric:runGametest --console=plain > ".local-deps/empty-stance-gametest-final.log" 2>&1
python3 "scripts/check_docs.py"
python3 "scripts/check_artifact.py" "fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar"
git diff --check
```

- `test build` 退出 0，BUILD SUCCESSFUL in 12s；core 28、fabric 2，共 30 项 JUnit，零失败/错误/跳过。
- `runGametest` 退出 0，BUILD SUCCESSFUL in 24s；6 项 required tests 全部通过，正常停服。
- 修正测试重复求值后，`test build` 再次退出 0、3s，`runGametest` 再次退出 0、20s，数量与结果一致。
- 文档检查覆盖 11 页；发行包检查覆盖五个 Mixin/refmap、三个内嵌依赖且没有测试类。
- 发行 JAR SHA-256：`08560fd8a1429f04f29078ccf37503679a0a3fd759623e6071369f295f34a441`。
- diff 检查包含全部未跟踪文本的逐文件 `git diff --no-index --check`，没有暂存源码。

JUnit XML 位于 `core/build/test-results/test/` 与 `fabric/build/test-results/test/`，
GameTest XML 为 `fabric/build/gametest/build/gametest-results.xml`。
本轮构建没有重新运行 Linux 图形客户端；Gradle 仍输出弃用功能提示，未验证 Gradle 9。

## Windows 安装状态

首次装配位于 `D:\Minecraft\Meridian-Animation-Integrated-20261021`，
`Start-combat.ps1` 与 `combat.args` 启动独立 `combat/` 游戏目录。
PID 37604 此前由用户使用；收尾只读查询时已不存在，日志记录 23:36:40 正常停服及世界保存。
本轮没有执行关闭、重启或切换当前装配的操作，当前参数仍保留旧入口规则。
新的三个 named 开发 JAR、完整候选参数、同步 remapClasspath 与 SHA 清单放在
`libraries/combat-trial-no-slot-20261028/`。
候选已移除试玩目录 JAR 的引用，classpath 与映射均为 153 项；已有目录仓与旧 JAR 原地保留。
用户正常退出后，将该目录的 `combat.args` 复制到实例根，再运行原 `Start-combat.ps1` 即可启用新版。
普通模组安装使用发行 JAR；本次 Windows 现成实例启用 fabric.development=true，使用 named dev JAR。

Windows 用户指南源为 [Windows 试玩指南](Windows-Combat.md)，另写入实例根 `COMBAT-TRIAL.md`。
试玩目录仓停止开发、出图和装配，其既有文档仅保留为历史记录。
基础功法轻步、蓄力、调息由后续正式仓提供，本候选不含具体功法。

## 尚未验证

新无槽入口版尚未在 Windows 启动；不影响用户当前使用的旧客户端。
本轮未自动进世界执行 learn → 填槽 → R → 施放，也未实机验证无槽 R、空槽动作栏、V、
WASD/模型转身、人物与服务器朝向、选中射线、墙角拉近或完整视角恢复。
GameTest 继续使用服务端实体与方块，不能替代客户端 Mixin 的真实按键和画面验收。
本候选没有具体功法，因此未验收费用、冷却或冲量；Human 打击和 PA 动画也未接入。
依用户指示已停止截图/OCR和模拟输入，未关闭 PID 37604；切换与启动由用户执行。

## 基线、产物和装配核验

| 仓库 | 已提交基线 | 参与候选的重建模块 |
|---|---|---|
| Mod Framework | `e2859475afe6ef4d948f35052e1e112c051ab737` | server、client |
| Technique Framework | `89e37ec0a35748297d7ffd281231d2eb9537065f` | server、shared、fabric-dev |
| Human Addon | `f0811f30c94f90b1e38bcfd9b21a936abbf371bd` | server、shared、client、fabric-dev |
| Vanilla Damage Addon | `11ca9dcd16b428de1b905bd1b978efa3a8f1538a` | server、fabric-dev |
| Players Animation | `85be47f149ec5844e3c1c066426e72a6ef4de85b` | 根项目 dev JAR |
| Combat UI Addon | 本地未提交源码 | core、shared、fabric-dev，`0.1.1-SNAPSHOT` |

其他重建 JAR 版本均为 `0.1.0-SNAPSHOT`。五个基线在独立 detached worktree 中保持干净，
构建所需模块的命令分别为 `:client:jar :server:jar`、`:fabric:remapJar`、
Human/Damage 的 `-I "../../build-only.gradle" :fabric:remapJar`、
PA 的 `-I "../../build-only.gradle" build`，均带 `--console=plain`。
各自成功日志在冻结试玩目录仓的 `.local-deps/`：`framework-build.log`、`technique-build.log`、
`human-build-retry.log`、`damage-build.log`、`pa-build-retry.log`，耗时为 16s、27s、37s、32s、87s。
PA 的 93 项 JUnit 已通过；其他四个基线仅构建所需模块，没有验收全套测试。

Windows 候选目录包含：

- `kizuna-meridian-combat-ui-addon-core-0.1.1-SNAPSHOT.jar`
- `kizuna-meridian-combat-ui-addon-shared-0.1.1-SNAPSHOT.jar`
- `kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT-dev.jar`
- `combat.args`、`remapClasspath.txt`、`manifest.json`

候选 fabric-dev SHA-256 为 `a6139bf91cc83cf3c269a245d3f519f5efca0606dd5c09f062436bd58d0ab3eb`。
普通安装的发行 JAR 位于 `fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar`。
候选共 153 项：15 个重建模块与 138 个原 classpath 文件，所有文件哈希均与清单一致。
三个 UI 模块更新、试玩目录移除，另 150 项与首次装配一致。
原 104 个受保护文件、当前 20 个战斗装配文件及原 play 文件集合保持不变。
两仓未暂存、无提交、无远端。冻结试玩目录仓与已生成的旧 JAR 均保留原处。

本仓 `.local-deps/prepare-candidate-without-trial.py` 仅改候选三份参数/映射/清单，
`.local-deps/verify-final-candidate.py` 只读检查原实例、候选哈希、无目录 entrypoint、基线和仓库状态。
实际运行：

```bash
python3 ".local-deps/prepare-candidate-without-trial.py" > ".local-deps/candidate-without-trial.log" 2>&1
python3 ".local-deps/verify-final-candidate.py" > ".local-deps/final-native-verification.log" 2>&1
python3 ".local-deps/run-final-checks.py" > ".local-deps/empty-stance-checks.log" 2>&1
```

GameTest 报告方法名：`combatgametest.boundeddecodeandcast`、`combatgametest.chineserejections`、
`combatgametest.networkratelimit`、`combatgametest.knownchangedpush`、
`combatviewgametest.cameraclipsagainstbackandshoulderwalls`、
`combatviewgametest.rayandcastfollowentityfacingdespitecameraorbit`。

## Windows 烟测证据

此前实际启动命令是：

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:/Minecraft/Meridian-Animation-Integrated-20261021/Start-combat.ps1"
```

旧装配日志记录 75 个模组、PA 双体型与 77 个动画、战斗 UI 五个 Mixin 实际应用，
未出现 InjectionError、InvalidMixin 或 Mixin apply failed。未用旧日志代表候选版运行验收。
日志位于实例 `combat/logs/latest.log`、`debug.log` 和根 `combat-stdout.log`、`combat-stderr.log`；
提取的证据另存本仓 `.local-deps/windows-smoke-evidence.json`。
收尾只读解析 Start-combat.ps1 语法通过；查询 PID 37604 返回进程不存在，没有关闭它。
认证 HTTP 401、Realms 不可用和可选类/音效/shader 警告保留，未验收正版多人。
初版复杂脚本曾被火绒隔离，简化脚本后启动成功，未修改安全软件或系统配置。

代码形状自查：第 4 条，`CombatProfilesTest.invalidSlotAndUnknownTechniqueCannotMutateConfiguration`
中的重复 `slots.select(1)` 改为局部变量 `invalidSelection`。
修改保持 core 状态机单一职责、客户端仅接线，并删除不再使用的入口 API。
