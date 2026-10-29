# 架势十槽图标栏交付

本地追加实现：架势内按客户端十槽顺序显示图标，保留重复绑定、空槽、未学和绑定不可用状态；
冷却遮罩与秒数由服务端身体进度投影驱动，本地只做单调时间插值。缺图显示短名称，动作栏提示继续保留。
图标采用 `presentation.icon_id`，资源映射为 `assets/<ns>/textures/gui/technique/<path>.png`。
界面实现分为纯 Java 模型、服务端只读投影、客户端绘制三部分，没有新增 Mixin 或第三方视角依赖。

## 文件

- 工程与范围：`settings.gradle`、`fabric/build.gradle`、`AGENTS.md`。
- 协议：`shared/src/main/proto/kizuna/meridian/combat/v1/combat.proto`，追加图标、冷却总量/剩余量及不可用原因。
- core：`core/src/main/java/dev/kizuna/meridian/combat/core/CombatHud.java`、`CombatProtocol.java`；
  `core/src/test/java/dev/kizuna/meridian/combat/core/CombatHudTest.java`。
- 服务端：`fabric/src/main/java/dev/kizuna/meridian/combat/fabric/CombatKnownProjection.java`、
  `CombatCasting.java`、`CombatFabric.java`。
- 客户端：同一 fabric 源目录的 `client/CombatHudRenderer.java`、`client/CombatClient.java`。
- GameTest：`fabric/src/gametest/java/dev/kizuna/meridian/combat/gametest/CombatGameTest.java`、
  `CombatTestContent.java`、`CombatTestHost.java`。
- 文档：`README.md`、`docs/wiki/Home.md`、`Usage.md`、`Contracts.md`、`Integration.md`、
  `Verification.md`、`_Sidebar.md`、本页。

## 依赖与接口

默认依赖迁至相邻功法库和 Framework 的当前工作区。功法库 HEAD 仍为 89e37ec，
但本次使用其尚未提交的 `TechniqueHost.Inspection`、`TechniqueState` 与清单接口。
旧版 inspect 的二进制签名与当前 Human 不兼容；不能将本次 JAR 单独替换到旧 Windows 冻结装配。
依赖源码未由本窗口修改，宿主的身体和施放权限始终来自公开接口。

每 5 tick 查询一次，同份投影去重；`KNOWN_CHANGED` 下一 tick 推送，通道重新注册强制补发。
图标取目录中仍存在的最高已学版本。不可用状态使用公开绑定失败；费用、承接、持物尚无统一公开只读预检，
仍以实际施放的动作栏拒绝为准，HUD 不能保证所有显示可用的功法都会施放成功。
未来若要完整预检，需要功法库提供与正式施放共享规则、无提交副作用的查询投影。

## 验证

本仓根目录实际执行的命令与最终日志记录在相邻 remote-session 的 `handoff/logs/`：

```bash
bash "gradlew" test build :fabric:runGametest --console=plain
python3 "scripts/check_docs.py"
python3 "scripts/check_artifact.py" "fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar"
git diff --check
```

最终日志 `combat-hud-final-validation.log`：34 JUnit、7 GameTest 通过，退出 0，
`BUILD SUCCESSFUL in 36s`。交付 JAR 的 SHA-256 见本页末尾。JUnit XML 位于 `core/build/test-results/test` 和
`fabric/build/test-results/test`，GameTest XML 位于 `fabric/build/gametest/build/gametest-results.xml`。
全部测试名见 [构建与验证](Verification.md)。新增测试：

- `CombatHudTest.tenSlotsRetainDuplicatesEmptyAndUnlearnedStates`
- `CombatHudTest.cooldownInterpolatesAndClampsWithoutRetainingOldConnection`
- `CombatHudTest.unavailableAndMissingIconKeepReadableText`
- `CombatHudTest.protocolRejectsTraversalDuplicatesAndInvalidCooldown`
- `CombatGameTest.hudPushesIconsCooldownAndBodyUnavailability`

代码形状自查：第 4 条，`CombatHud.slot`、`CombatProtocol.decodeKnown` 和
`CombatHudTest.protocolRejectsTraversalDuplicatesAndInvalidCooldown` 缓存重复读取；
第 10 条，`CombatHudTest.snapshot` 注释改为说明快照语义。

## 实机边界

没有在本次使用截图、OCR、模拟输入或启动 Windows 客户端。图标绘制、GUI 缩放、十槽布局、
F1、资源包补图/重载以及其他 HUD 重叠须实机验证；R/V、镜头与移动的真实输入验收仍见原 v1.1 记录。
新基础功法与图标栏 JAR 没有装入旧 Windows 候选；最终整合装配由独立任务负责。
旧候选与其切换方法见 [Windows 试玩交付](Delivery-Windows-Trial.md)。

## 最终产物与收尾检查

实际 Gradle 命令包含以下日志重定向：

```bash
bash "gradlew" test build :fabric:runGametest --console=plain > "/home/kiz/Code/Kizuna-remote-session/handoff/logs/combat-hud-final-validation.log" 2>&1
```

交付文件：`fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar`。
SHA-256：`b6333459d0e23ce5abaa78c3a84e7e4d83eb21170b284e81dc95912e8becf2e3`。
发行包检查通过：5 个客户端 Mixin、实际 refmap、3 个内嵌依赖，无测试类。
refmap 为 `kizuna-meridian-combat-ui-addon-fabric-refmap.json`。
文档检查 12 页；`git diff --check` 与逐文件未跟踪文本检查通过，覆盖 76 个文本；
41 个 Java 文件全部不超过 120 列。两仓均为 0 暂存、0 提交、0 远端。

只读收尾脚本及完整结果位于相邻 remote-session 的 `handoff/logs/`：

```bash
python3 "/home/kiz/Code/Kizuna-remote-session/handoff/logs/combat-basic-final-checks.py" > "/home/kiz/Code/Kizuna-remote-session/handoff/logs/combat-basic-final-checks.json"
```

脚本核对了原 Windows 实例 104 个受保护文件和 20 个当前战斗装配文件，哈希全部保持一致；
原 play 文件集合一致。当前装配 154 项 classpath / 73 个 mod ID，旧无槽候选为 153 / 72，
其余 150 项一致，候选不含取消的试玩目录。实例指南与仓内源文档一致。
此核验未启动或关闭客户端，未替换任何实例文件。
