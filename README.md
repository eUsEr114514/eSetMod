# eSetMod-1.0.0

Minecraft **1.20.1** · **Fabric** 模组：一根「记录木棍」+ 三条 `/setmod` 命令，可以把右键点过的方块按顺序录下来，先清除再逐块回放。

- Mod ID：`esetmod`
- 包名：`com.electrondz75.setmod`
- 语言文件：只有 `en_us.json` 与 `zh_cn.json`

## 功能

### 记录木棍 `esetmod:record_stick`

外观与属性和原版木棍完全一致：模型为 `minecraft:item/handheld` + `minecraft:item/stick` 贴图，堆叠上限 64，无耐久，燃烧时间 100 tick，并且在创造模式「材料」页中。

- **右键点击方块** → 把「被点击的那个方块」的坐标、方块状态和所在维度按点击顺序记录进缓存。
- 同一个方块重复右键不会产生重复记录（保留原来的顺序，只刷新方块状态）。
- 每个玩家最多记录 4096 个方块。
- 箱子、门、按钮之类会自己吃掉右键的方块，请**潜行 + 右键**记录。

### `/setmod start`

1. **自动清除**：把所有「仍然保持记录时状态」的已记录方块变成空气（被玩家改动过的方块会跳过，不会被误删）；
2. **等待 120 tick**（6 秒）；
3. **回放**：按记录顺序逐 tick 执行 setblock，整段动画的时长由 `/setmod speed` 决定；
4. **回放结束后不会清除方块**，放置的方块会一直保留。

### `/setmod speed [tick]`

- 不带参数：显示当前动画时长。
- 带参数：设置**整段动画**的总时长（单位 tick，范围 1–72000）。
- 未设置时默认 **1 tick / 方块**，且不少于 20 tick。
- 若已有回放在进行，新时长从下一次 `/setmod start` 开始生效。

### `/setmod clear`

清除该玩家的全部记录，并取消正在进行的回放。

> 命令需要权限等级 2：单人游戏需要在「对局域网开放」里开启作弊，或直接开启作弊；服务器上需要 OP。

## 使用流程

```
/give @s esetmod:record_stick      # 1. 拿棍子（创造模式物品栏「材料」页也能找到）
                                    # 2. 逐个右键要记录的方块
/setmod speed 200                   # 3. 让整段回放持续 200 tick
/setmod start                       # 4. 清除方块 → 120 tick 后开始回放
/setmod clear                       # 5. 清空记录，重新开始
```

## 数据存在哪

每个玩家的记录存放在**世界存档**里的 `data/esetmod.dat`（`SetModState`，`PersistentState` 存档数据），退出游戏再进来依然存在。每条记录都带维度，所以跨维度的记录也能正确回放。

## 构建

需要 JDK 17 或更高（本项目实际以 JDK 21 构建），首次构建需要联网下载 Loom 与依赖：

```bash
./gradlew build
```

产物：`build/libs/esetmod-1.0.0.jar`

安装到游戏需要：

| 依赖 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.15.0 |
| Fabric API | 0.92.x（如 0.92.6+1.20.1） |
| Java | 17+ |

把 `esetmod-1.0.0.jar` 放进 `.minecraft/mods`，同时放入 Fabric API 即可。

## 代码结构

```
src/main/java/com/electrondz75/setmod/
├── SetMod.java                  # 入口：注册物品、燃料、物品组、命令、tick 回调
├── item/RecordStickItem.java    # 右键记录方块 + 物品说明
├── state/RecordedBlock.java     # 单条记录（维度 + 坐标 + 方块状态）与 NBT 序列化
├── state/SetModState.java       # 按玩家保存的记录缓存与动画时长（世界存档）
├── command/SetModCommand.java   # /setmod start | speed [tick] | clear
└── playback/SetModPlayback.java # 服务器 tick 调度：清除 → 延迟 120 tick → 按序回放
```

## 已验证

- `gradlew build` 编译通过（Fabric Loom 1.11.8，Yarn 1.20.1+build.10）。
- 开发服务器启动通过，模组正常初始化、命令注册无异常。
- 逻辑自检通过（440 项）：记录 NBT 往返、方块状态属性往返、重复记录去重、存档保存/读取稳定性、时长上下限、回放时序（单调递增、结束恰好全部放置）。


---

# PATCH

该模组全部代码由 *DeepSeek-V4.1-Flash* 生成，我（人类）只负责将一份初始的 *Fabric-example-mod* 和 *提示词* 扔给AI