# v1.1 本地交付记录

工作目录：`~/Code/Kizuna-Meridian-Combat-UI-Addon`。依据交接文件文末「追加：架势视角两种模式」。
本次在首版文件上继续实现，版本为 `0.1.1-SNAPSHOT`；首版记录见 [首版交付](Delivery.md)。
仓库仍无提交、无远端；没有暂存、提交、推送或改相邻仓库源码。

## 完成的行为

默认第一人称，架势内 `V` 在第一人称和越肩之间切换，可改绑并在客户端文件记忆。
越肩鼠标只改变客户端镜头角；WASD 立即按镜头水平朝向移动，人物按配置速度沿最短角差平滑转身。
没有移动输入时人物保持朝向。实体真实 yaw/pitch 和原版移动包只包含人物角度。
越肩出招与目标射线沿人物当前朝向，镜头绕看不会把选中射线转向准星。
后墙、肩侧墙和低顶通过完整偏移段的中心与八角探测拉近镜头；右偏、上偏、后退距离和转身速度可配置。
退出、死亡、旁观和断线恢复进入前原版视角。架势内消费 F5，架势外仍使用原版 F5。

模式会话、移动坐标换算、转身插值、镜头几何与碰撞收缩放在纯 Java core。
Fabric 适配器提供方块探测，控制器和五个客户端 Mixin 连接鼠标、输入、实体与相机。
没有第三方视角模组、新网络消息、Human/PA 依赖或配置界面；现有服务端权威校验继续生效。
该划分让 core 独立测试，避免 Mixin 重复持有运动或模式状态，符合 KISS、DRY 与单一职责。

## 本次文件清单

以本轮开始前保存在 `.local-deps/v11-baseline` 的首版文件为比较基线；
Git 因首版未提交而仍将全部交付文件显示为未跟踪。新增 18 个、修改 16 个，共 34 个文件。
以下 `.../core/` 代表 `dev/kizuna/meridian/combat/core/`，`.../fabric/` 代表 `dev/kizuna/meridian/combat/fabric/`。

| 目录 | 新增 | 修改 |
|---|---|---|
| 根目录 | 无 | `AGENTS.md`、`README.md`、`build.gradle` |
| `scripts/` | `check_artifact.py` | 无 |
| `core/src/main/java/.../core/` | `CombatView.java`、`CombatViewMath.java`、`CombatViewSettings.java`、`CombatCamera.java` | `CombatProfiles.java`、`CombatConfigFile.java` |
| `core/src/test/java/.../core/` | `CombatViewTest.java`、`CombatViewConfigTest.java`、`CombatCameraTest.java` | `CombatProfilesTest.java` |
| `fabric/src/main/java/.../fabric/` | `CombatCameraCollision.java` | 无 |
| `fabric/src/main/java/.../fabric/client/` | `CombatViewController.java` | `CombatClient.java` |
| `fabric/src/main/java/.../fabric/mixin/client/` | `MouseMixin.java`、`EntityMovementMixin.java`、`ClientPlayerMixin.java`、`LivingEntityJumpMixin.java`、`CameraMixin.java` | 无 |
| `fabric/src/main/resources/` | `meridian_combat.client.mixins.json` | `fabric.mod.json`、`assets/meridian_combat/lang/zh_cn.json` |
| `fabric/src/gametest/java/dev/kizuna/meridian/combat/gametest/` | `CombatViewGameTest.java` | 无 |
| `fabric/src/gametest/resources/` | 无 | `fabric.mod.json` |
| `docs/wiki/` | `Delivery-v1.1.md` | `Home.md`、`_Sidebar.md`、`Usage.md`、`Contracts.md`、`Integration.md`、`Verification.md` |

本轮忽略目录还保存基线、构建日志、烟测初始化/关闭脚本、截图和反汇编证据，不属于交付源码。
安装产物为 `fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar`。

## 验证与命令结果

以下命令在本仓根目录执行，均退出 0；详细脚本与证据见 [构建与验证](Verification.md)。

| 实际命令 | 结果 |
|---|---|
| `"./gradlew" test build --console=plain` | BUILD SUCCESSFUL，3 秒；29 项 JUnit，0 失败/错误/跳过 |
| `"./gradlew" :fabric:runGametest --console=plain` | BUILD SUCCESSFUL，28 秒；6 项 required tests 全部通过，正常停服 |
| `python3 "scripts/check_docs.py"` | 9 个页面链接通过 |
| `git diff --check` | 通过；另逐文件检查全部未跟踪文本 |
| 未跟踪文本逐文件 `git diff --no-index --check "/dev/null" 文件` | 69 个文本，0 个空白问题 |
| `python3 "scripts/check_artifact.py" "fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.1-SNAPSHOT.jar"` | 发行 JAR 检查通过；refmap 从包内 Mixin 配置读取 |
| `git rev-list --all --count`、`git remote -v` | 提交数 0、无远端 |

新增 15 项 JUnit（视角会话 7、几何与遮挡 5、配置 3），新增 2 项服务端 GameTest。
保留首版 14 项 JUnit 和 4 项 GameTest，全部方法名见验证文档。
JUnit XML 为 core 27 项、fabric 2 项，GameTest XML 为 6 个 testcase 且没有 failure。
日志为 `.local-deps/v11-build-final.log`、`v11-gametest-final.log`、`v11-checks-final.log`。
发行 JAR 已检查版本、五个 Mixin 与 refmap、三个内嵌依赖，以及不含测试类；
SHA-256 为 `ae3eca886558ede5a688e304210e62db23c7c2bbef57ffb721c6db7f0954212a`。

客户端额外实际执行：

```bash
xvfb-run -a "./gradlew" -I ".local-deps/v11-client-smoke.init.gradle" :fabric:runClient --console=plain > ".local-deps/v11-client-smoke.log" 2>&1
```

退出码 0，`BUILD SUCCESSFUL in 3m 24s`。Java 17、LWJGL 初始化和资源加载完成，
五个 Mixin 均实际应用且导出类含预期回调调用；通过窗口关闭事件正常退出。
虚拟显示只验证客户端加载，没有进入世界操作按键或验证越肩画面。

## 方案问题与跨仓需求

功法框架继续固定在已提交 `89e37ec0a35748297d7ffd281231d2eb9537065f`，不依赖未提交 6a/6b。
功法库和 Human 无需新增 API；Human 宿主仍需正确解释 INPUT 零方向为人物朝向，目标选取使用实体眼位/角度。
越肩的非零方向沿人物当前朝向，第一人称保留首版移动方向语义。
模式记忆跨服务器共享；镜头角与进入前视角只存在当前会话。配置格式 1 保槽迁移到 2，非法配置继续原件保护。
PA 仍可通过 `CombatStanceEvents.CHANGED` 接入；动画映射、覆盖优先级和他人可见同步仍由后续整合定义。
完整取舍见 [整合接口与方案问题](Integration.md)。

## 未检查与实机边界

- 真实鼠标、V 改键/冲突、WASD/斜向/疾跑跳跃手感、转身中施放、人物在画面左下的位置及不同 FOV。
- 实际玩家模型与服务器 yaw、左键选中和功法目标、R/F5/死亡/旁观/断线的完整操作旅程。
- 真实墙角、近裁面、半砖、栅栏、低顶、骑乘、游泳/滑翔，以及其他输入/相机模组兼容。
- Human-UI CombatJourney、Human 真实身体、基础功法费用/冲量/世界效果、PA 动画、多人远程网络与平台兼容。

原版自动跳跃保留人物前方判定，转身未完成时可能延后触发；没有对特殊移动姿态宣称验收通过。
服务端 GameTest 不加载客户端 Mixin，core 测试中的独立角度也不代替真实鼠标验收。
其余网络、文件系统与平台边界见 [尚未验证](Verification.md#尚未验证)。

代码形状自查：第 4 条，`CombatCamera.clip`、`CombatCameraTest` 和 `CombatViewGameTest` 缓存同段重复求值；
第 7 条，`CombatViewSettings` 默认参数与 `CombatViewTest` 角度接缝/斜向幅度提为具名常量，配置测试的未来版本由当前版本推导；
第 9 条，`CombatViewGameTest` 提取正式施放断言为独立方法。其余条款逐项核对。
