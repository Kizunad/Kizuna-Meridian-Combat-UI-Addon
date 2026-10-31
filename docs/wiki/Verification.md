# 构建与验证

## 当前图标栏构建

需要 Python 3.12 以上与 JDK 25：Gradle 9.8.0 守护进程按 `gradle/gradle-daemon-jvm.properties` 选用本机 JDK 25（Loom 1.19.3），产物以 Java 25 编译。
相邻仓库位置、产物路径与第三方版本由 Framework 的 `scripts/workspace.gradle`、装配清单与版本目录提供，默认读取相邻本地 `Kizuna-Meridian-Technique-Framework` 与 `Kizuna-Meridian-Mod-Framework`。
图标栏通过宿主公开 `inspect().body()` 查询身体，并使用功法库当前工作区的 `TechniqueState`、
`TechniqueBinder` 和目录接口；这些接口尚未提交，不能改用首版的 89e37ec 冻结快照。
功法 fabric 与 server 以源码组合，编译与运行直接使用功法仓当前源码构建的普通 JAR（26.2 不混淆，无映射），随本仓先行构建。
本仓不修改依赖源码，依赖的构建输出位于各仓忽略目录。
可用 Gradle `kizuna.dir.technique`、`kizuna.dir.framework` 属性按清单 id 指定依赖路径（`kizunaCodeDir` 整体改指代码根目录）；功法库自身组合的 Framework 同样按清单定位，两处指向同一构建。
编译、单测与游戏运行任务均指定 Java 25 工具链。在仓库根目录执行：

```bash
bash "gradlew" test build --console=plain
bash "gradlew" :fabric:runGametest --console=plain
python3 "scripts/check_docs.py"
git diff --check
```

根 `build` 包含文档链接检查；GameTest 独立启动专用服务端。
发行文件为 `fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar`。
`-sources.jar` 是源码，不用于安装；26.2 不混淆，不再另出 `fabric/build/devlibs` 开发 JAR。
原 `scripts/prepare_dependencies.py` 固定 89e37ec 功法与 e285947 Framework，仅用于首版历史复现，已在构建收拢时删除；
当前构建按组合构建直接使用功法库与 Framework 源码。历史验证见下文及原交付记录。

## 自动验证覆盖

| 测试 | 数量 | 覆盖 |
|---|---:|---|
| CombatProfilesTest | 6 | 文件往返、重复槽、损坏/未来版本保护、读上限、跨档复制、64 档 LRU、越界与失效槽 |
| CombatInputTest | 5 | 无槽进入、十个空槽提示与保持架势、界面暂停、主动与自动退出、视角方向及零输入 |
| CombatProtocolTest | 3 | 非法/超长/非有限数解码、槽位指令操作与槽号校验、滑动窗口边界、玩家隔离和清理 |
| CombatCommandTreeTest | 3 | 正式 Brigadier 解析 `meridian combat slot`、槽边界与槽号补全、拒绝未学、已学补全实时更新 |
| CombatViewTest | 7 | 默认与记忆、全部原视角恢复、独立镜头、相对移动、斜向、最短弧限速转身、人物施放方向 |
| CombatCameraTest | 5 | 右上后几何、无墙、后墙、肩侧墙/低顶、眼位遮挡归零 |
| CombatViewConfigTest | 3 | 模式与参数往返、格式 1 保槽迁移、无效/非有限/未来配置原件保护 |
| CombatHudTest | 4 | 十槽与重复绑定、缺图文字、冷却插值与钳位、断线清理、资源路径和时间边界 |
| CombatGameTest | 7 | 正式通道有界解码与施放、精确旧版权限、中文拒绝、入口限流、通道注册与 KNOWN_CHANGED 实际 S2C 推送、暂停与重连、`combat slot` 节点补全与只发执行者 |
| CombatViewGameTest | 2 | 真实方块后墙/侧墙整段收缩、人物射线与正式施放方向不随纯镜头角改变 |

GameTest 玩家进入真实玩家管理器，调用正式 `ServerGamePacketListenerImpl.handleCustomPayload`，仅以记录连接替换真实套接字。
测试宿主的身体、时间结算、活动提交使用真实 Framework 存储与命令处理器，测试内容不会进入发行 JAR。

JUnit XML 位于 `core/build/test-results/test` 与 `fabric/build/test-results/test`。
GameTest XML 位于 `fabric/build/gametest/build/gametest-results.xml`。
真实客户端按键、渲染和 Human 世界效果的验收边界见 [整合接口](Integration.md)。

## 实际通过的测试方法

`CombatProfilesTest`（6 项）：

- `fileRoundTripRetainsTenSlotsAndIndependentProfiles`
- `corruptAndFutureFilesRemainByteForByteUnchanged`
- `oversizedFileIsProtected`
- `newProfileCopiesPreviousWithoutSharingMutableSlots`
- `capacityEvictsLeastRecentlyUsedAndRetainsCurrentAtTies`
- `invalidSlotAndUnknownTechniqueCannotMutateConfiguration`

`CombatInputTest`（5 项）：

- `entryRequiresSupportedServerButNoUsableSlots`
- `emptySlotsOnlyReturnPromptAndRetainStance`
- `openScreenSuspendsConsumptionAndRetainsStance`
- `deathSpectatorAndDisconnectAlwaysExit`
- `movementUsesWorldYawAndNormalizesDiagonals`

`CombatProtocolTest`（2 项）：

- `protocolRejectsMalformedOversizedAndNonFiniteDirection`
- `slidingWindowRejectsBurstAndReleasesAtBoundary`

`CombatCommandTreeTest`（2 项）：

- `parsesSetClearListAndRejectsBadArguments`
- `completionContainsOnlyCurrentKnownTechniques`

`CombatViewTest`（7 项）：

- `defaultsToFirstPersonAndRetainsSelectionAcrossSessions`
- `restoresEveryOriginalPerspectiveDespiteRepeatedReconciliation`
- `cameraLookIsIndependentAndSwitchingNeverTurnsTheCharacter`
- `cameraRelativeAxesPreserveMovementAndInputMagnitudeDuringTurning`
- `wasdAndDiagonalsFaceTheirCameraRelativeDirections`
- `turnsAtConfiguredRateAlongShortestArcWithoutOvershooting`
- `shoulderCastFollowsCurrentCharacterNotCameraOrMovementTarget`

`CombatCameraTest`（5 项）：

- `shoulderIsRightAboveAndBehindForDifferentCameraAngles`
- `unobstructedCameraKeepsFullConfiguredOffset`
- `backWallShrinksEntireOffsetAndReleasesImmediatelyWhenClear`
- `shoulderSideWallAndCeilingAlsoPullCameraTowardEyes`
- `obstructionAtEyeClampsToZeroWithoutReversingCamera`

`CombatViewConfigTest`（3 项）：

- `modeAndGeometryRoundTripWithoutLeakingAcrossProfileOperations`
- `legacyConfigurationKeepsSlotsAndMigratesToFirstPerson`
- `invalidOrFutureViewConfigurationPreservesOriginalBytes`

GameTest 报告中的实际名称（7 项）：

- `combatgametest.boundeddecodeandcast`
- `combatgametest.chineserejections`
- `combatgametest.networkratelimit`
- `combatgametest.knownchangedpush`
- `combatgametest.hudpushesiconscooldownandbodyunavailability`
- `combatviewgametest.cameraclipsagainstbackandshoulderwalls`
- `combatviewgametest.rayandcastfollowentityfacingdespitecameraorbit`

无槽入口调整后的 JUnit XML 汇总为 30 项（core 28、fabric 2），失败、错误、跳过均为 0。
GameTest XML 为 6 个 testcase、无 failure；日志确认 `All 6 required tests passed :)` 后正常停服。
新增测试曾修正等价角度 180/-180 的断言，以及负零偏移、侧墙摆放和测试玩家 yHeadRot 的夹具问题。
真实世界的射线读取 LivingEntity 头部角度；客户端 Mixin 将 yHeadRot/yBodyRot 同步到人物 yaw。

## 屏幕输入调用顺序核验

检查对象为本次构建的 Minecraft 1.20.1 / Yarn 1.20.1+build.10，以及 Fabric 生命周期 2.2.22+1802ada577。
字节码输出保存在 `.local-deps/client-input.javap.txt` 与 `.local-deps/Mouse.javap.txt`，生命周期源码摘录保存在 `.local-deps/FabricMinecraftClientMixin.java`。
下表按 Mojang 官方名书写；字节码与映射无关，当时按 Yarn 名执行的反汇编命令和证据文件名原样保留。

| 入口 | 核验结论 |
|---|---|
| Fabric `MinecraftClientMixin.onStartTick` | `@Inject(at = @At("HEAD"), method = "tick")`，确实早于原版输入处理 |
| `Minecraft.setScreen` | 屏幕非空分支在字节码偏移 149 调用 `KeyMapping.releaseAll()` |
| `KeyMapping.releaseAll/release/consumeClick` | release 清零 clickCount，consumeClick 每次减少一次计数 |
| `KeyboardHandler.keyPress` | 屏幕先处理事件；若已消费立即返回；之后偏移 580 检查 screen，非空不增加游戏按键计数 |
| `MouseHandler.onPress` | 屏幕已消费、screen 非空或 overlay 非空时，均不增加游戏按键计数 |
| `MouseHandler.grabMouse` / `KeyMapping.setAll` | 关屏只恢复 isDown 状态，不增加 clickCount |
| `AbstractContainerScreen.keyPressed` | 数字交换与背包关闭由原版屏幕处理，不依赖战斗按键消费 |
| `Minecraft.tick/handleKeybinds` | 无屏幕时才处理原版按键；快捷栏处理位于开背包之前 |
| `MouseHandler.onScroll` | 原版直接调用 `Inventory.swapPaint`；本附属没有拦截 |

此版本 `Screen` 没有 `passEvents` 字段，不能套用其他版本的透传条件。
普通原版界面内输入不会积存为关屏后的施放，架势状态保持；独立 0 的无效计数也会被丢弃。
若数字键与打开背包键都已在同一 tick 前排队，本附属在 START tick 按仍无界面的状态处理数字键，之后原版才开背包。
这不是界面内预输入；真实窗口事件、长按重复、改键冲突和其他模组界面仍待 CombatJourney 验收。

执行的反汇编命令：

```bash
javap -classpath "/home/kiz/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2/minecraft-merged-1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2.jar" -p -c net.minecraft.client.MinecraftClient net.minecraft.client.Keyboard net.minecraft.client.option.KeyBinding net.minecraft.client.gui.screen.Screen net.minecraft.client.gui.screen.ingame.HandledScreen > ".local-deps/client-input.javap.txt"
javap -classpath "/home/kiz/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2/minecraft-merged-1.20.1-net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2.jar" -p -c net.minecraft.client.Mouse > ".local-deps/Mouse.javap.txt"
```

无槽入口调整、隔离 worktree 构建及 Windows 候选装配的最新命令见
[Windows 试玩交付](Delivery-Windows-Trial.md)。

## 两模式首轮执行命令（历史记录）

工作目录为本仓根目录。重定向仅将详细日志保存在本仓忽略目录。
沿用首版已校验依赖快照，本轮没有重新调用依赖准备脚本。

```bash
"./gradlew" test build --console=plain > ".local-deps/v11-build-final.log" 2>&1
"./gradlew" :fabric:runGametest --console=plain > ".local-deps/v11-gametest-final.log" 2>&1
python3 "scripts/check_docs.py"
git diff --check
git rev-list --all --count
git remote -v
python3 "scripts/check_artifact.py" "fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar"
```

test build 退出 0，BUILD SUCCESSFUL（3 秒）；GameTest 退出 0，BUILD SUCCESSFUL（28 秒）。
文档 9 页通过，diff 检查通过，提交数 0，远端为空。完整检查输出为 `.local-deps/v11-checks-final.log`。
Gradle 输出包含弃用提示；本次使用 Gradle 8.8，没有验证 Gradle 9。

因为本仓没有已跟踪文件，单独的 `git diff --check` 不覆盖全部交付。
另实际执行以下逐文件检查，结果为「未跟踪文本检查：69 个文本，0 个问题」：

```bash
python3 - <<'PY'
from pathlib import Path
import subprocess

paths = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"])
checked = 0
failures = []
for raw in paths.split(b"\0"):
    if not raw:
        continue
    path = Path(raw.decode())
    if b"\0" in path.read_bytes():
        continue
    checked += 1
    result = subprocess.run(
        ["git", "diff", "--no-index", "--check", "/dev/null", str(path)],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    if result.stdout or result.returncode not in (0, 1):
        failures.append(f"{path}: {result.stdout}")
print(f"未跟踪文本检查：{checked} 个文本，{len(failures)} 个问题")
for failure in failures:
    print(failure)
raise SystemExit(bool(failures))
PY
```

`--no-index --check` 在存在文件差异而无空白错误时也可能返回 1，因此同时检查输出与返回码。
安装 JAR 使用 `scripts/check_artifact.py` 检查 Mixin 类、内嵌模块、硬依赖声明及没有测试内容。
检查脚本从包内 Mixin 配置的 `refmap` 字段读取实际名称：
`kizuna-meridian-combat-ui-addon-fabric-refmap.json`，不再硬编码错误的 `meridian_combat-refmap.json`。

## 客户端 Mixin 加载烟测

实际执行：

```bash
xvfb-run -a "./gradlew" -I ".local-deps/v11-client-smoke.init.gradle" :fabric:runClient --console=plain > ".local-deps/v11-client-smoke.log" 2>&1
```

本仓忽略目录中的初始化脚本只在根项目 `kizuna-meridian-combat-ui-addon` 的 runClient 添加
`-Dmixin.debug.export=true` 和 `-Dmixin.debug.verbose=true`，未改交付构建脚本。
虚拟显示客户端使用 Java 17，LWJGL、纹理与资源加载完成后，通过 WM_DELETE_WINDOW 正常关闭。
命令退出 0，`BUILD SUCCESSFUL in 3m 24s`。虚拟声卡和 Fabric API 的 Mixin 类版本警告未阻止运行。

日志明确显示 EntityMovementMixin、LivingEntityJumpMixin、ClientPlayerMixin、MouseMixin、CameraMixin
分别应用到 Entity、LivingEntity、LocalPlayer、MouseHandler、Camera。
导出到 `fabric/build/playtest/.mixin.out/class` 的实际类经过 `javap -p -c` 检查，
包含 relativeInput、jumpYaw、turn/alignModel、look、shoulder 回调调用，均无注入失败。
反汇编保存在 `.local-deps/*.v11-mixed.javap.txt`，窗口截图为 `.local-deps/v11-client-smoke.png`。
没有进入世界，未以这次烟测宣称真实按键或架势渲染通过。

## 视角调用顺序核验

反汇编证据为 `.local-deps/{Camera,GameRenderer,Mouse,ClientPlayerEntity,Entity,LivingEntity,KeyboardInput}.v11.javap.txt`（文件名沿用当时的 Yarn 类名）。
已核对 MouseHandler 的灵敏度/反转预处理后才调用 turn；越肩仅替换其接收者。
Input.tick 后才转身，之后由原版移动和发包；moveRelative 在实体局部输入变成世界速度前换算坐标，
不通过临时改实体 yaw 达成移动。疾跑跳跃的额外冲量单独换算到移动目标朝向。
GameRenderer 仍从人物 pick/getViewVector 取目标，LivingEntity 的插值 getViewYRot 读取 yHeadRot，
因此本地人物 tick 结束同步 yHeadRot/yBodyRot 到真实 yaw。
原版相机只沿后方裁剪，本实现改为完整右上后段探测；形状与流体策略仍沿用 VISUAL/NONE。
切换原版视角时同步调用着色器更新和地形刷新，避免只改枚举留下渲染状态。

## 尚未验证

- 真实客户端 R、1–0、改键、长按、滚轮、界面开关、死亡/旁观/断线的手动或自动按键验收。
- V 改键与记忆、独立绕看时模型和服务器 yaw、WASD/斜向/疾跑跳跃、转身手感、人物射线与目标、完整视角恢复。
- 实际越肩画面左下构图、不同 FOV/近裁面、真实墙角/半砖/栅栏/低顶、骑乘/游泳/滑翔及相机模组兼容。
- Human-UI CombatJourney、Human 真实身体与基础功法的费用、冷却、冲量和世界效果。
- PA 22 段架势动画及他人可见同步；首版没有接入。
- 真实远程套接字、网络延迟/丢包、网络线程竞争和大规模负载；GameTest 使用正式接收器和记录连接。
- Windows/macOS 实机、其他模组改写输入流程、文件系统断电恢复及不支持原子移动时的替换分支。


## 图标栏验证

新增 `CombatHudTest` 方法：

- `tenSlotsRetainDuplicatesEmptyAndUnlearnedStates`
- `cooldownInterpolatesAndClampsWithoutRetainingOldConnection`
- `unavailableAndMissingIconKeepReadableText`
- `protocolRejectsTraversalDuplicatesAndInvalidCooldown`

实际命令、日志与图形验收边界见 [图标栏交付](Delivery-HUD.md)。
历史 30 JUnit / 6 GameTest 结果不包含图标栏。本次在更新后的功法库公开 API 上验证 34 JUnit / 7 GameTest。
图标实际绘制、资源包补图/重载、GUI 缩放、F1 与其他 HUD 重叠仍须真实客户端验证；没有截图或 OCR。

## 槽位指令改挂 /meridian（S2）

旧客户端根 `/meridian_combat` 删除，改为服务端节点 `/meridian combat slot` 加 `meridian_combat:slot` 下发，原因与消息形状见
[网络与配置契约](Contracts.md#槽位指令)。命令树 `CombatCommandTree` 移到 `fabric` 包，`attach` 只填 `slot` 之下的子树。

- `CombatProtocolTest.slotCommandRejectsUnknownOperationAndOutOfRangeSlot`：三种操作通过；空包、超长、未指定操作、清空 0 号与设置 11 号被拒。
- `CombatCommandTreeTest` 三项改走 `meridian combat slot …` 路径，断言不变。
- `combatgametest.slotcommandforwardstoexecutoronly`：旧根已删；权限 0 玩家补全到 `slot`、`set/clear/list`、十个槽号与已推送的已学功法；
  `set 10`、`clear 3`、`list` 各发一条 `SlotCommand` 给执行者，旁观玩家收不到；控制台执行报错；未声明通道的玩家执行返回 0。
- clientTest（`-PcombatFullAssembly`，Xvfb）在截图前先学 `meridian_door_breaker:knock`，经正式聊天指令入口发
  `/meridian combat slot clear 1` 与 `set 1 …`，读回 `config/kizuna-meridian-combat.json` 当前分档第一槽；
  再确认 Fabric 客户端分发器没有 `meridian` 根，并发送 `/meridian body status`，回复不能是 `command.*` 错误。
  报告多一行 `commands=true`。

```bash
./gradlew test build :fabric:runGametest
xvfb-run -a -s "-screen 0 1280x960x24" ./gradlew -PcombatFullAssembly :fabric:runClientTest
```

结果：JUnit 41/41（core 38、fabric 3）；GameTest 9/9，49 个模组；clientTest `success=true commands=true captures=10`，87 个模组。

## 迁到 Minecraft 26.2（S3）

2026-11-01 在 26.2（不混淆，Loom 1.19.3、Java 25）上重新执行：

```bash
./gradlew test build :fabric:runGametest
```

结果：JUnit 41/41（core 38、fabric 3），零失败、错误、跳过；GameTest 9/9，另有 26.2 运行器自带的 `minecraft:always_pass`，51 个模组；文档检查 13 页通过。用例数与 S2 相同。
GameTest 玩家改用功法库 testFixtures 的 `TestPlayers.logIn` 与 `RecordingConnection`，仍经正式登录、通道注册与 `handleCustomPayload` 收发，记录连接保存未编码的载荷对象。
未单独设时限的用例显式写 `maxTicks = 100`，沿用 1.20.1 注解默认值；26.2 默认只有 20 tick。

clientTest 依赖 `-PcombatFullAssembly` 完整装配，Human 等上游尚未全部迁到 26.2，本次只编译 `clientTest` 源集，没有运行。
26.2 每帧先提取 HUD、后渲染，提取回调时主画面仍是上一帧，所以截图改为在下一次 HUD 回调时截取上一帧，异步写盘完成后才计数；这一流程同样未实际运行。
真实客户端的越肩相机、按键、HUD 图标与两处客户端 Mixin（`CameraMixin` 改注入 `alignWithEntity` 里的 `setPosition(DDD)`，`ClientPlayerMixin` 改注入 `ClientInput.tick`）都未在 26.2 客户端运行验证。
开启实验性新矿车行为时，原版乘坐矿车的相机走 `setPosition(Vec3)`，越肩偏移在这种情况下不生效。本页其余 1.20.1 时期的结果不代表 26.2 已验证。
