# 本地首版交付记录

仓库：`/home/kiz/Code/Kizuna-Meridian-Combat-UI-Addon`。
依据交接文件 `combat-ui-v1.md` 和其中指定的战斗 UI 首版范围实现。
只初始化本地 Git；没有提交、推送或配置远端，没有改动相邻仓库源码。

## 实现结果

R 进出架势，架势内沿用原版快捷栏 1–9，独立 0 对应第十槽。
START_CLIENT_TICK 消费按下次数，不拦滚轮；死亡、旁观、断线退出，界面打开暂停输入。
十槽客户端命令和服务端已学补全、本地文件版本保护、按服务器/世界分档、新档复制与 64 档 LRU 均已实现。
正式 PRESS/KNOWN 通道接通公开功法服务，逐次权威校验；方向零值、归一化、1 KiB 与每秒 20 次边界均已处理。
客户端主线程提供架势事件钩子及十槽 HUD；动画由 PA 接线，不包含连招或预输入。

### 十槽 HUD

槽框使用中性半透明深灰纹理，22 像素外框、20 像素格距、16 像素图标，总宽 202 像素。
与原版快捷栏使用同一整数中心；生存模式避开生命、护甲行，创造模式更靠近快捷栏。
GUI 缩放 1–4 使用整数逻辑像素，不再对整条栏进行分数缩放。
槽下方显示实际键位：前九槽沿用原版快捷栏，第十槽读取独立绑定；过长名称按槽宽截断。
空槽只显示暗框，不写“空”。冷却图标去色，遮罩向下收起，秒数四向黑描边；
绑定失败显示去色图标、暗红细边和小叉。按键消费后显示两帧亮框，首帧图标下移一像素。
退出架势清理反馈，资源重载释放灰阶图标缓存并重新读取资源包。

资源包可替换 `assets/meridian_combat/textures/gui/combat/slot_frames.png`（110×22）
与 `failure_badge.png`（7×7）。槽框从左到右为可用、空、失败、按下、释放五格。
相邻槽只绘制一次共享边，不累积半透明颜色。

客户端截图使用隔离 GameTest 世界及完整开发装配：

```bash
./gradlew test build :fabric:runGametest
./gradlew :fabric:runClientTest -PcombatFullAssembly
```

真实客户端截图保存在 `fabric/build/hud-screens/`，包含 GUI 缩放 2、3 的四态、空栏与连续反馈帧。
截图使用测试显示快照及正式渲染器，不代表服务端施放验收；服务端链路由 GameTest 验证。

逻辑分到纯 Java 文件/槽位/状态机/限流类，Fabric 只适配生命周期、网络和输入，保持职责单一。
命令和按键复用槽位语义，服务端复用权威功法规则；没有复制施放规则或为后续功能预建协议。

## 文件清单

以下为全部 52 个新增交付文件，排除 `.git`、Gradle 缓存、依赖快照和构建产物。
Java 文件路径均位于对应模块的 `src/.../java/dev/kizuna/meridian/combat/` 下。

| 目录 | 文件 |
|---|---|
| 根目录 | `.gitattributes`、`.gitignore`、`AGENTS.md`、`LICENSE`、`NOTICE`、`README.md`、`THIRD_PARTY_NOTICES.md`、`settings.gradle`、`build.gradle`、`gradle.properties`、`gradlew`、`gradlew.bat` |
| `gradle/wrapper/` | `gradle-wrapper.jar`、`gradle-wrapper.properties` |
| `scripts/` | `prepare_dependencies.py`、`check_docs.py` |
| `shared/` | `build.gradle`、`src/main/proto/kizuna/meridian/combat/v1/combat.proto` |
| `core/` | `build.gradle` |
| `core/src/main/java/.../core/` | `CombatConfigFile.java`、`CombatDirection.java`、`CombatIds.java`、`CombatInput.java`、`CombatProfiles.java`、`CombatProtocol.java`、`CombatSlots.java`、`PressLimiter.java` |
| `core/src/test/java/.../core/` | `CombatProfilesTest.java`、`CombatInputTest.java`、`CombatProtocolTest.java` |
| `fabric/` | `build.gradle` |
| `fabric/src/main/java/.../fabric/` | `CombatChannels.java`、`CombatCasting.java`、`CombatFabric.java` |
| `fabric/src/main/java/.../fabric/client/` | `CombatClient.java`、`CombatCommandTree.java`、`CombatStanceEvents.java` |
| `fabric/src/main/resources/` | `fabric.mod.json`、`assets/meridian_combat/lang/zh_cn.json` |
| `fabric/src/test/java/.../fabric/client/` | `CombatCommandTreeTest.java` |
| `fabric/src/gametest/java/.../gametest/` | `CombatGameTest.java`、`CombatTestContent.java`、`CombatTestHost.java`、`CombatTestPlayer.java` |
| `fabric/src/gametest/resources/` | `fabric.mod.json` |
| `docs/wiki/` | `Home.md`、`_Sidebar.md`、`Usage.md`、`Contracts.md`、`Integration.md`、`Verification.md`、`Delivery.md` |

构建得到安装用 `fabric/build/libs/kizuna-meridian-combat-ui-addon-fabric-0.1.0-SNAPSHOT.jar`。
测试宿主与测试功法只在 gametest 源码集，不进入发行包。

## 验证结果

| 命令 | 结果 |
|---|---|
| `python3 "scripts/prepare_dependencies.py" --java17 "/usr/lib/jvm/java-17-openjdk-amd64"` | 退出码 0；固定功法快照校验与依赖构建成功，2 秒 |
| `"./gradlew" test build --console=plain` | 退出码 0；BUILD SUCCESSFUL，8 秒；14 项 JUnit，0 失败、0 错误、0 跳过 |
| `"./gradlew" :fabric:runGametest --console=plain` | 退出码 0；Java 17，4 项 required tests 全部通过，正常停服，24 秒 |
| `python3 "scripts/check_docs.py"` | 退出码 0；8 个页面链接通过 |
| `git diff --check` | 退出码 0；本仓没有已跟踪文件，另逐个检查新增文本 |
| 未跟踪文本逐文件 `git diff --no-index --check "/dev/null" 文件` | 51 个文本，0 个空白问题；完整执行脚本见验证文档 |
| `git rev-list --all --count` / `git remote -v` | 提交数 0 / 无远端 |

日志保存在本仓忽略目录 `.local-deps/prepare-final.log`、`build-final.log`、`gametest-final.log`、`checks-final.log`。
全部测试方法名、输入核验证据、实际检查脚本与 XML 位置见 [构建与验证](Verification.md)。
发行 JAR 已核对：内嵌 core、shared、Protobuf 运行库；没有打入 gametest 类或测试模组。

## 屏幕输入与验证边界

核对了实际 1.20.1 Yarn 字节码及本次 Fabric 生命周期源码。
开屏经 `setScreen` 调用 `KeyMapping.releaseAll`，清空按下计数；屏幕存在时键盘/鼠标事件不进入游戏按键计数。
关屏恢复物理按住状态不增加按下计数；原版容器的数字交换键仍由屏幕处理。
START_CLIENT_TICK 在原版 tick 入口，早于 `handleKeybinds`，满足架势内先消费 1–9 的要求。
未发现原版界面内按键积存到关屏后误施放的问题，输入逻辑无需改动。
同一 tick 中数字键与开背包键均已排队时，数字键在开屏前处理；原版也是先处理快捷栏再开背包。

没有运行真实客户端按键、窗口、渲染或 Human-UI CombatJourney；上述结论是代码核验与状态机单测结果。
没有验证 Human 身体、实际基础功法费用/冲量/世界效果、PA 动画、真实远程套接字与网络线程竞争。
没有实测 Windows/macOS、其他模组改写输入流程、文件系统断电恢复或原子移动不支持分支。

## 方案与跨仓接口

没有依赖功法工作区的未提交 6a/6b 接口，也不需要功法库或 Human 新增接口。
方案遗漏的身体身份、精确版本选择、名单上限、通道握手时序、名称字段与列表展示取舍，
以及 Human 宿主要求、PA 22 段动画接入需求，完整记录于 [整合接口与方案问题](Integration.md)。

代码形状自查：第 4 条，`CombatDirection.fromInput`、`CombatProfiles.validateProfileKey`、`CombatCasting.reference`、`CombatFabric.process`、`CombatClient.press/save/notifyStance` 及相关测试缓存同段重复求值；第 5 条，`CombatProfiles.Profile` 改为显式不可变字段赋值；第 7 条，`CombatTestPlayer` 的名称截断长度提为常量；第 8 条，`CombatIds.validateTechnique` 显式命名校验，移除 `CombatSlots.set` 中丢弃返回值的重复转换；第 9 条，`CombatFabric.onInitialize` 的 JOIN 注册拆行至 120 列内。其余规则逐项核对通过。
