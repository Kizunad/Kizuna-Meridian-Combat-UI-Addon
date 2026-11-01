# Windows 战斗整套试玩指南

实例：`D:\Minecraft\Meridian-26.2-20261101`（Minecraft 26.2、Fabric Loader 0.20.1，标准 `mods/` 目录）。
`mods/` 装的是 25 个当前工作区构建的 26 个发布 JAR 与 Fabric API、owo，包含未提交修改、PA Mobs-Addon、
bs3d 三个附属、Burden、Humanoid Dummy、Humanoid Taunts、正式基础功法、破门斩与调试库 `meridian_debug`，
已移除试玩功法目录。
来源提交、源码摘要、JAR 哈希及各仓构建日志见本次候选的 `manifest.json`、`build-logs/`。
本页由打包脚本写到实例根 `COMBAT-TRIAL.md`，路径占位符换成本实例的实际路径。

## 启动与回滚

先正常退出当前游戏；不要强行结束已有客户端，也不要同时使用同一游戏目录。
在 PowerShell 执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "__START_SCRIPT__"
```

启动脚本检查重复实例，以实例根 `meridian.args` 启动，PID 写 `meridian.pid`。
Java 25 用实例内的 Temurin 25 JRE（`runtime\temurin-25\`，Adoptium 官方发布，版本与 SHA-256 记在实例根 `instance-manifest.json`）。
游戏目录就是实例根，世界在 `saves/`，模组在 `mods/`。
本次替换下来的旧 JAR 在 `__CANDIDATE_DIRECTORY__\replaced\`。回滚时正常退出，
移走 `mods/` 里本次装入的 JAR（`manifest.json` 的 `installed` 列出），再把 `replaced/` 里的旧 JAR 移回。

## 创建试玩世界、读卷与资源准备

创建单人创造世界并打开「允许作弊」。已有世界可 ESC → 对局域网开放 → 允许作弊：开 → 创建局域网世界。
使用原版世界，先确保所在位置安全；以下调试命令需要权限等级 2：

```text
/gamemode creative
/difficulty normal
/meridian opening complete all
/meridian qi fill
/gamerule meridian_human:technique_player_break_blocks true
/gamerule mob_griefing true
```

创造物品栏找「功法」页（也可搜索「基础功法」「破门斩」），取得：

- 基础功法·调息卷、基础功法·轻步卷、基础功法·蓄力卷。
- 破门斩·上卷·木门：学会叩门、破门、撞门三式。

手持卷轴右键学习，成功后打开只读阅读界面：书名·卷名、式名称、说明、前置卷。
长文用滚轮；合上按钮、纸外点击、ESC、背包键、右键或使用键均可关闭，键位跟随改绑。
界面显示已学会提示、各式说明与目录数值，卷末列出架势与填槽命令；合上按钮使用生成纹理。
创造模式不消耗卷轴，生存模式消耗一份。再次使用已学卷不重复学习。
缺失专属背景时使用通用纸面并为该资源记录一次警告。

## 填槽、架势与视角

学会后输入命令（权限 0；槽位只存本机）：

```text
/meridian combat slot set 1 meridian_basic_techniques:light_step
/meridian combat slot set 2 meridian_basic_techniques:charge
/meridian combat slot set 3 meridian_basic_techniques:breath
/meridian combat slot set 4 meridian_door_breaker:cleave
/meridian combat slot set 5 meridian_door_breaker:knock
/meridian combat slot set 6 meridian_door_breaker:ram
/meridian combat slot list
```

清槽用 `/meridian combat slot clear 1`。旧 trial 槽会显示失效，应清空或改成已学的正式 ID。
`set`、`clear` 的槽号补全 1–10，功法 ID 补全当前服务器已学列表。
调试授予可用 `/meridian technique learn <id>`（省略目标即学给自己）；ID 补全当前目录的书、卷、式，整书一次原子学会。

- R 进入/退出架势，即使没有学会或填槽也能进入。
- 架势内 1–9 与 0 对应十槽；1–9 跟随原版快捷栏键位，滚轮仍可换持物。
- V 在第一人称/越肩之间切换；R、V、0 可在「控制 → 经脉战斗」改键。
- 架势内 F5 被消费，使用 V 切换；退出架势恢复原来的原版视角。
- 越肩时鼠标绕看，WASD 按镜头移动并带动人物转向；攻击沿人物朝向。
- 越肩准星不是攻击射线；需调整俯仰时先切回第一人称。
- 聊天和背包打开时暂停施放；死亡、旁观、断线退出架势。
- 下肢损毁只会拒绝行走、疾跑的身体占用，不会暂停整个身体；原版移速暂不受疲劳或黑腿调整。若聊天明确提示身体已暂停，退出世界后重新加入即可重新认领原身体，伤势和真元保留；仍失败时保留日志检查必装模组，不要删除身体存档。暂停期间功法同步清空已学投影并停止轮询，治疗也不会退回原版回血。

架势中快捷栏上方出现中性深灰十槽，实际键名位于槽下；空槽不写字。
默认槽 1 显示 `1`，槽 10 显示 `0`；验收截图的 `Q`/`Z` 是测试专门改绑，用于验证实际键名显示，
不是正式默认键位。已有 `options.txt` 的个人改键会继续生效。
可用、冷却去色与秒数、失败暗红边与小叉、施放高亮各有区分，按键有两帧反馈；F1 隐藏 HUD。
空槽或施放被拒绝会在动作栏提示；槽位按世界/服务器保存，视角模式跨档记忆。

## 玩家效果与卫道士

轻步：按槽 1 施放后获得 20 秒“轻身”；地面按跳跃键向准星处跃进，潜行加跳跃仍为普通跳。
每跳起点到落点的直线最远 7 格：平地最远 7 格，上两三格高的墙头时水平距离相应缩短，往下跳不占距离。
看向这个范围内的地面或墙头会出现淡淡的落点描边；准星太近允许短跳，看得更远时落在 7 格处附近。
弧线贴低、飞行约半秒（平地 6 格约 10 tick），落地稍停 0.3 秒即可按住跳跃键继续连跳。
每次跃步只耗腿部体力，重疲劳时提示“腿脚乏力”并退回普通跳。状态没有粒子，物品栏和 HUD 显示羽毛图标。
可直接测试：`/effect give @s meridian_basic_techniques:light_body 20 0`。
用 `/meridian watch damage on` 观察：正常跃步落地不摔伤，跌落超过预定落点的部分仍受伤。
卫道士也可用 `/effect give @e[type=minecraft:vindicator,limit=1,sort=nearest] meridian_basic_techniques:light_body 20 0`
给予轻身；生存模式被其锁定后，它会跃到玩家身前一两格再出招。起跳暂借 PA `technique_dash`，专属动画待后续制作。
蓄力：2 启动力量曲线，先增强、回落，再进入虚脱，最终恢复；不是永久属性提升。
调息：3 帮助恢复疲劳。费用不足可再次执行 `/meridian qi fill`，但补元不会消除疲劳。

手持铁斧，对准两格内的木门按 4，等待约 3.2 秒起手后劈开；铁门保持不动。
创造不扣耐久；切生存后铁斧末击扣 3 耐久。生存测试前先备好安全场地。
对准三格内生物按 5，在 0.90、1.25、1.70 秒连敲三下；钝击倍率依次为 0.3、0.3、0.9，合计 1.5。
主手铁斧的每击护甲前剂量为 `9 × 本击倍率 × 当时肌肉系数`；目标离开范围后，后续打击会落空。
开启 `/meridian watch damage on` 后，命中三次应出现三条日志，黑肢体命中也逐击记录蔓延。
轻步、撞门、掩门、连门和万门一斩均由六块腿肌平分做功，不设绝对腿力门槛。
正常走动后仍可使用；重疲劳或重伤导致做功预算不足时，撞门起手拒绝，轻身跃步退回普通跳。
疲劳、伤势、护甲等会影响最终伤害；两手可用时部分招式改为双手判定。
破门斩八式从起手到收势均为 0.4 倍移速，玩家仍可移动，类人仍会寻路；冲量保持原值。
收势、承接、打断、死亡与断线会移除对应施放的临时限速。

在创造栏「类人」页取「卫道士刷怪蛋（真炁）」并右键方块，或使用以下命令直接生成真炁卫道士。
物种与档位都有补全，位置沿用原版坐标；省略位置则在命令源位置生成。命令需要权限 2：

```text
/meridian humanoid spawn vindicator true_qi ~ ~ ~5
/meridian body status @e[type=minecraft:vindicator,sort=nearest,limit=1]
/give @s meridian_humanoid:vindicator_true_qi_spawn_egg
```

生成反馈包含实体名称、档位与 UUID；`body status` 应输出 `档位=TRUE_QI`。
它会带原版铁斧，自动学会调息、轻步、蓄力三卷和破门上卷、中卷，另有 UUID 分桶决定的下卷机会。
要测试游炁可将 `true_qi` 改为 `wandering_qi`；七种生人均有两档，共 14 个蛋。
普通原版 `/summon minecraft:vindicator` 仍随机抽档；不支持虚构的 `Realm` NBT。
测试清理使用生成反馈中的实际 UUID（替换下面占位符），避免误删其他卫道士：

```text
/kill <生成反馈中的UUID>
```

确认真炁后保持普通难度、`mob_griefing true`，切换 `/gamemode survival` 让它以玩家为目标。
它会举斧起手并自行选择破门斩；让它在视线通畅时起手劈门式，再在前方两格内放木门，可观察木门被劈开。
关闭木门挡住视线时 AI 可能不决策；并非每次攻击都选择劈门。
测试后切回创造并按 UUID 清理测试实体即可。

## 透视镜、伤害观察与恢复

调试库需要权限等级 2（单人开启作弊或服务器 OP）。创造栏「调试」页可取肢体透视镜，也可输入：

```text
/give @s meridian_debug:limb_lens
```

手持透视镜对准已有 Meridian 身体的玩家或类人生物，每 10 tick 更新部位 HP 条、总 HP、体力、真元和
已有经脉状态。对实体右键打印完整快照；潜行右键查看自己。

伤害观察在聊天中显示本人参与的双向伤害：攻击方、受击方、伤害种类、命中部位、原始与结算剂量、
来源 ID 和部位剩余 HP。

命中已毁部位或一次伤害超过其剩余 HP 时，日志额外显示“溢出量 × 来源系数 → 各接收部位剂量”。
手臂（含前臂、手）系数 0.7，腿（含小腿、足）1.0，腹部 1.5，其余部位 1.0；
按未毁部位的实际 HP 上限分配，接收方护甲不重复减伤。玩家及七类生人默认开启，
可在对应游戏目录的 `config/meridian-human-damage.toml` 分别修改实体开关与模板系数，重启后生效。

可限制到当前选中的目标，结束后关闭：

```text
/meridian watch damage on
/meridian watch damage on @e[type=minecraft:vindicator,sort=nearest,limit=1]
/meridian watch damage off
```

恢复命令只操作已有权威身体，支持玩家和类人生物。省略模式等同 `all`，同时补满真元；其余模式为 `health`、`stamina`，
单独补充真元用 `qi fill`（完整写法见 [Framework 指令页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki/Commands#body)）。例如恢复自己、最近卫道士或单独补充真元：

```text
/meridian body heal @s all
/meridian body heal @e[type=minecraft:vindicator,sort=nearest,limit=1] all
/meridian qi fill
```

单部位恢复用 `/meridian body heal @s limb <部位> [数值]`，部位 ID 按 Tab 从目标实际身体补全；
省略数值时补满该部位，提供数值时按指定剂量恢复。

## 死亡与重生

先在安全场地准备好已学功法和填槽，再用以下命令测试真实死亡：

```text
/gamemode survival
/kill @s
```

点击原版「重生」。原来的身体、境界、经脉开合、功法和已读卷进度继续保留；
各部位、组织、器官 HP 至少恢复到上限的 10%，原本更高的 HP 保持。红心同步为约 10%，
重生后可以正常受伤、治疗。全身做功肌肉疲劳置满，须按原有曲线自然恢复，
立刻使用腿部功法可能提示疲惫；补元不会清除疲劳。动作栏会提示一次：
「大难不死，元气大伤，折损一年寿元」。

身体检视信息区首行显示「年龄 / 寿元」，两者均以年为单位，保留一位小数。
新生样例约为 `18.0 年 / 62.0 年`，第一次重生后约为 `18.0 年 / 61.0 年`。
每次复活扣一年（31,557,600 秒），数据来源为 Human 的 `meridian-human-revival.toml`。
真元保留复活前的当前余额；死亡本身按既有规则清账，复活不补元。
换维度不扣寿元，类人生物死亡后不会自动复活。

调试复活支持默认自己和实体选择器，Tab 可补全；同样扣除 Human 配置的寿元费用：

```text
/meridian body revive
/meridian body revive @s
/meridian body revive @e[type=minecraft:vindicator,sort=nearest,limit=1]
```

该命令只接受已有死态身体，活体会拒绝；它只复活身体，不替代玩家的原版重生按钮，
也不重新生成已被原版移除的生物。寿元不足时仍复活，余额扣至零，反馈明确提示耗尽；
下一次推进生理时间会再次寿尽判死，这是预期规则。

## 三式新动画试玩

准备步骤中的开脉与补元后，右键阅读上卷，或直接学卷、填槽：

```text
/meridian technique learn meridian_door_breaker:upper
/meridian combat slot set 4 meridian_door_breaker:cleave
/meridian combat slot set 5 meridian_door_breaker:knock
/meridian combat slot set 6 meridian_door_breaker:ram
/give @s minecraft:iron_axe
```

按 R 进入架势、V 切越肩，手持斧头朝向目标。5 为单手短叩，1.70 秒命中；
4 为双手重劈，3.20 秒命中；6 为冲步接横撩，2.40 秒命中。
撞门在 1.55–2.25 秒原地迈步配合服务端冲量；施放仍为 0.4 倍移速。
将斧头按 F 换至副手并让主手留空，三式均播放副手版；皮肤设置中的主手选「左手」可观察运行时镜像。
架势左护手位已外移，副手斧头不再贴住脸部。木门可手动放置，也可站定后执行：

```text
/setblock ~ ~ ~2 minecraft:oak_door[facing=north,half=lower]
/setblock ~ ~1 ~2 minecraft:oak_door[facing=north,half=upper]
/meridian humanoid spawn vindicator true_qi ~ ~ ~5
/gamerule mob_griefing true
```

木门命令沿世界 +Z 方向放置，转向门再施放 4。卫道士的出招由距离、资源、受击等情境评分选择，
不会按三式固定轮播；在生存模式让它取得目标，可观察同样的专属片段与劈门。
需要副手观察时可对刚生成的最近卫道士执行：

```text
/item replace entity @e[type=minecraft:vindicator,sort=nearest,limit=1] weapon.mainhand with minecraft:air
/item replace entity @e[type=minecraft:vindicator,sort=nearest,limit=1] weapon.offhand with minecraft:iron_axe
```

## 类人对敌

七种生人都由类人大脑指挥：有威胁时先评估打得过还是该撤，再决定出招、躲、硬抗或拉开；没有威胁时照原版日常。
以下在普通难度、生存模式试玩，命令需要权限 2。观察施放可用 `/meridian technique status <目标>`，
观察跃步可用 `/meridian watch leap on <目标>`。

**村民撤退。** 生成一个游炁村民，空手打它一拳：

```text
/meridian humanoid spawn villager wandering_qi ~ ~ ~3
```

它不会像原版那样乱跑恐慌，而是先放轻步，再背对你跃步拉开到约 12 格外；你走远或停手一阵后它回去干活。
附近有三个以上村民时，它撤退期间照原版恐慌的节奏召唤铁傀儡。

**村民还手。** 换成真炁村民，给它铁斧并教会叩门，再打它一拳：

```text
/meridian humanoid spawn villager true_qi ~ ~ ~3
/item replace entity @e[type=minecraft:villager,sort=nearest,limit=1] weapon.mainhand with minecraft:iron_axe
/meridian technique learn @e[type=minecraft:villager,sort=nearest,limit=1] meridian_door_breaker:knock
```

自认打得过时它会对你出叩门；血量、最弱部位或双臂力量掉到门槛以下就转为撤退，恢复后再回来。

**调息。** 无威胁、四肢疲劳时它会自己调息：

```text
/meridian debug fatigue set @e[type=minecraft:villager,sort=nearest,limit=1] all 0.5
```

**卫道士突击。** 生成一只真炁卫道士，站在约 6 格外不动：

```text
/meridian humanoid spawn vindicator true_qi ~ ~ ~6
```

它先蓄力、放轻步，再跃到你身前一两格起手破门式；双臂虚脱后会背向跃步拉开，恢复后再回来。

**两只卫道士互打。** 卫道士、唤魔者、幻术师、掠夺者同属原版 `#minecraft:illager_friends`，
双方都不在队伍里时互为同伴，互相不受功法伤害：只牵引仇恨会看到双方出招却打不中。
要互打，先用 `/team` 把两只分进不同队伍，再让它们互相仇恨：

```text
/meridian humanoid spawn vindicator true_qi ~ ~ ~4
/meridian humanoid spawn vindicator true_qi ~ ~ ~10
/team add vindicator_a
/team add vindicator_b
/team join vindicator_a @e[type=minecraft:vindicator,sort=nearest,limit=1]
/team join vindicator_b @e[type=minecraft:vindicator,sort=furthest,limit=1]
/meridian humanoid aggro @e[type=minecraft:vindicator,sort=nearest,limit=1] @e[type=minecraft:vindicator,sort=furthest,limit=1] mutual
```

改用仇恨牵引器（`meridian_debug:aggro_link`）牵引时，同样要先分队。
能看到抢在对方起手结束前打断、来袭时侧闪或硬抗、开打前蓄力，以及虚脱后的拉扯。
试完执行 `/team remove vindicator_a` 和 `/team remove vindicator_b`。

学卷和行为门槛在 `config/meridian-humanoid-brain.toml`；`log_decisions = true` 时每次决策写一行日志
（动作与理由）。退出游戏后再编辑。测试后按生成反馈中的 UUID 清理实体。

## 验证、日志与已知问题

基础功法仓完整装配覆盖真实卷轴、战斗协议、位移、力量曲线、
木门/耐久、叩门实伤，真炁卫道士由类人大脑对在线玩家施放和劈门，以及村民被玩家打后撤退或还手。
详细伤害公式、权限和时序边界仍由各仓 GameTest 覆盖。

Windows 烟测仅到主菜单：冷启动 `Start-Meridian.ps1`，到主菜单后核对 `logs/latest.log` 没有 Mixin 失败
或模组加载错误，截图后正常退出；不进入世界。打包时清单中的 `windows_launch_tested: false` 不代表后续烟测结论。
按键、镜头、阅读排版和 HUD 的实际游玩体验仍需用户进世界观察。

- 叩门、破门、撞门主副手六段专属动画已接入玩家与卫道士，打击时刻已与目录对齐。
  破盾、横扫、掩门、连门、万门一斩尚无专属动画，仍借用对应主副手挥砍。
- 类人待机、走跑、举斧、拉弩、施法、喝药与交易已使用 PA；举斧借用三叉戟预备段，施法借用蓄力，
  交易借用使用物品，受击借用单手格挡受击。未新制动作；主副手、盔甲、头饰与头戴旗帜继续可见。
- 离线启动参数可能出现认证 HTTP 401、Realms 不可用及可选类/音效/shader 警告；不代表正版多人登录已验收。
- 只验证本整套工作区装配，未验收任意第三方模组组合。
- 正式运行日志：`logs/latest.log`、`debug.log`；启动输出：实例根 `meridian-stdout.log`、`meridian-stderr.log`。
- 本次装入的 JAR、来源提交与哈希见 `__CANDIDATE_DIRECTORY__\manifest.json`。
- 配置：`config/kizuna-meridian-combat.json`；退出游戏后再编辑镜头参数。
