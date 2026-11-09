# eSetMod 开发者说明（1.1.1）

面向要修改/扩展本模组的人。使用者请看 [README.md](README.md)。

---

## 1. 技术栈与构建

| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.1 |
| 映射 | Yarn `1.20.1+build.10`（`v2`） |
| Fabric Loader | 0.17.2（`fabric.mod.json` 中声明 ≥ 0.15.0） |
| Fabric API | 0.92.6+1.20.1 |
| Loom | 1.11-SNAPSHOT（实际解析为 1.11.8） |
| Gradle Wrapper | 8.14.2 |
| Java | 17（`release = 17`），开发机建议 JDK 21 |

常用命令（在工程根目录）：

```bash
./gradlew build        # 编译 + 打 remap jar → build/libs/esetmod-1.1.1.jar
./gradlew runServer    # 起开发服务器（run/ 目录，需要 run/eula.txt）
./gradlew runClient    # 起开发客户端
./gradlew --offline build   # 依赖已缓存时可离线构建
```

版本号只在 `gradle.properties` 的 `mod_version` 里改，`fabric.mod.json` 的 `${version}` 由 `processResources` 展开。

---

## 2. 目录结构

```
src/main/java/com/electrondz75/setmod/
├── SetMod.java                     # ModInitializer：注册物品/燃料/物品组/命令/tick 回调
├── item/RecordStickItem.java       # 记录木棍：右键写缓存 + tooltip
├── state/RecordedBlock.java        # 单条方块记录（维度 + 坐标 + BlockState）与 NBT 序列化
├── state/Recording.java            # 命名记录 record（不可变方块列表 + 回放时长）
├── state/SetModState.java          # 世界存档数据：每玩家 pending + named
├── command/SetModCommand.java      # /setmod name|start|speed|clear（带执行对象）
└── playback/SetModPlayback.java    # 服务器 tick 调度：延迟 → 逐 tick setblock

src/main/resources/
├── fabric.mod.json                 # id=esetmod, name=eSetMod, main entrypoint
└── assets/esetmod/
    ├── lang/en_us.json, zh_cn.json # 仅两门语言，键集合必须一致
    ├── models/item/record_stick.json  # parent item/handheld + minecraft:item/stick 贴图
    └── icon.png

tools/rcon-test.ps1                 # 开发用：通过 RCON 在开发服务器里跑命令
```

---

## 3. 类职责与调用链

```
右键方块
  → RecordStickItem.useOnBlock
    → SetModState.get(server).addRecord(uuid, RecordedBlock)
      → 写入 pending（缓存），markDirty()

/setmod name <targets> <name>
  → SetModState.savePending(uuid, name)
    → pending → named[name]（Recording，保留原 duration）→ 清空 pending

/setmod start <targets> <name>
  → SetModState.getRecording(uuid, name)
  → SetModPlayback.clearRecordedBlocks(server, blocks)   # 立即清除
  → SetModPlayback.start(server, uuid, name, blocks, resolvedDuration)

每 tick
  → ServerTickEvents.END_SERVER_TICK → SetModPlayback.tick(server)
    → Job: 120 tick 延迟 → blocksToPlace() 计算进度 → world.setBlockState()
```

`SetModState` 是 `PersistentState`，通过 `server.getOverworld().getPersistentStateManager().getOrCreate(...)` 获取，随世界存档保存（`data/esetmod.dat`）。

---

## 4. 数据模型

### 4.1 内存

```java
Map<UUID, List<RecordedBlock>> pending;              // 未保存缓存，按点击顺序
Map<UUID, Map<String, Recording>> named;             // 命名记录，LinkedHashMap 保序
record Recording(List<RecordedBlock> blocks, int durationTicks); // durationTicks=0 表示自动
record RecordedBlock(Identifier dimension, BlockPos pos, BlockState state);
```

上限常量都在 `SetModState`：

| 常量 | 值 | 作用 |
| --- | --- | --- |
| `MAX_RECORDS` | 4096 | 单条记录/缓存的最大方块数 |
| `MAX_RECORDINGS` | 64 | 每个玩家的命名记录条数上限 |
| `DEFAULT_MIN_DURATION_TICKS` | 20 | 自动时长下限 |
| `MAX_DURATION_TICKS` | 72000 | `speed` 上限 |

### 4.2 NBT 结构（`data/esetmod.dat`）

```json5
{
  "players": [
    {
      "uuid": "…",
      "pending": [
        { "dimension": "minecraft:overworld", "x": 10, "y": 64, "z": -3,
          "state": { "Name": "minecraft:oak_stairs", "Properties": { "facing": "east" } } }
      ],
      "named": [
        { "name": "house", "duration": 200, "blocks": [ /* 同上 */ ] }
      ]
    }
  ]
}
```

要点：

- `state` 直接用 `NbtHelper.fromBlockState` / `NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), nbt)`，所以楼梯朝向、半砖、含水等属性都能无损往返。
- 写入顺序是确定的（`TreeSet<UUID>` + `LinkedHashMap`），同一份数据重复 `writeNbt` 结果完全一致，方便写存档往返测试。
- **旧格式兼容**：1.1.0 之前的存档只有 `blocks`（匿名记录）。读取时若没有 `pending` 就回落到 `blocks`，把旧记录读进缓存；旧的 `duration` 字段会被忽略（旧版时长没有名字可挂靠）。
- 增加新字段时，请同时处理「字段缺失」的默认值，保持向后兼容。

---

## 5. 命令树（Brigadier）

```
setmod                                    (requires permission level 2)
├── name   <targets: players> <name: word>
├── start  <targets: players> <name: word>            (name 有 tab 补全)
├── speed  <targets: players> <name: word> [ticks: int 1..72000]
└── clear  <targets: players> [name: word]
```

设计要点：

1. **`<targets>` 必须放在 name 之前**：选择器解析不依赖命令源，因此命令方块、控制台、`/execute as` 都能用；`@s` 只在命令源本身是玩家时有效（命令方块无执行者）。
2. 用 `EntityArgumentType.players()` / `EntityArgumentType.getPlayers(context, "targets")` 得到 `Collection<ServerPlayerEntity>`，循环处理，返回值为「至少一个目标成功」时 1，否则 0（命令方块比较器可用）。
3. **补全**：`RECORDING_NAMES` 会先解析 `targets`，合并所有目标的记录名并排序；解析失败（例如选择器暂时无法解析）时返回空补全，不抛异常。
4. **反馈**：`feedback(source, targets, target, message, error)` 统一出口——
   - 多目标时自动加「(玩家名)」前缀（`setmod.feedback.prefix`）；
   - 当 `source.getEntity() != target`（命令方块、控制台、`/execute as` 其他实体）时，额外把同一条消息发到目标玩家聊天栏。
5. **参数类型**：`name` 用 `StringArgumentType.word()`，因此只允许 `[A-Za-z0-9_-]`。若要支持中文/空格，需要换成 `string()` 并在补全时给名字加引号。

新增子命令的最小改法：

```java
.then(CommandManager.literal("list")
        .then(CommandManager.argument(TARGETS, EntityArgumentType.players())
                .executes(SetModCommand::list)))
```

并在 `SetModCommand` 里加对应 handler（模板参考 `clearCache`：取 targets → 循环 → `feedback()` → 返回成功数）。

---

## 6. 回放引擎

`SetModPlayback.Job` 每条记录一个任务，字段：

```java
UUID playerId; String recordingName; List<RecordedBlock> blocks; int durationTicks;
int remainingDelay = START_DELAY_TICKS;  // 120
int elapsedTicks; int placedBlocks;
```

每个服务器 tick（`END_SERVER_TICK`）：

1. `remainingDelay > 0` → 递减；归零时发「开始回放」动作栏消息。**注意此时还没有放置任何方块**；
2. 否则 `elapsedTicks++`，目标数量
   `target = ceil(elapsedTicks * blockCount / durationTicks)`（`blocksToPlace()`，已 clamp 到 `blockCount`）；
3. `while (placedBlocks < target)` 依次 `world.setBlockState(pos, state, Block.NOTIFY_ALL)`；
4. `elapsedTicks >= durationTicks` → 发完成消息并移除任务。

性质：

- 整段动画**精确**占用 `durationTicks` 个服务器 tick；
- `durationTicks < blockCount` 时每 tick 放多个方块，反之隔几个 tick 放一个；
- 任务只持有区块/维度 key 与方块状态，**不持有实体引用**，玩家下线也不影响播放完成，只是收不到消息；
- `/setmod start` 同名记录会先 `cancel(playerId, name)`，避免同一记录两个动画叠加；
- `SERVER_STOPPED` 时 `clearAll()`，防止跨世界串台。

修改建议：

- 改延迟 → `START_DELAY_TICKS`（注意其它类里的 `@value` Javadoc 不会自动更新）；
- 改清除策略 → `clearRecordedBlocks()`（当前只清「仍等于记录状态」的方块，避免误删玩家改动；同时会连带清空容器内容且不掉落）；
- 想加音效/粒子 → 在 `Job.tick()` 放置方块后调用 `world.playSound(...)` / `serverWorld.spawnParticles(...)`。

---

## 7. 常见修改场景

| 想改什么 | 改哪里 |
| --- | --- |
| 命令权限等级 | `SetModCommand.register` 里的 `.requires(source -> source.hasPermissionLevel(2))` |
| 记录/命名上限 | `SetModState.MAX_RECORDS` / `MAX_RECORDINGS` |
| 默认动画时长 | `SetModState.resolveDuration(Recording)` |
| 时长范围 | `IntegerArgumentType.integer(1, MAX_DURATION_TICKS)` 与 `setRecordingDuration` 的 clamp |
| 回放前延迟 | `SetModPlayback.START_DELAY_TICKS` |
| 记录上限提示文案 | `assets/esetmod/lang/*.json` |
| 物品外观 | `models/item/record_stick.json`（现在直接引用 `minecraft:item/stick`） |
| 物品属性（堆叠/耐久/燃烧） | `SetMod.RECORD_STICK` 的 `FabricItemSettings`、`FuelRegistry.INSTANCE.add(...)` |
| 新语言 | 仅支持 `en_us` / `zh_cn`，新增键时两个文件同步 |
| 允许中文记录名 | `SetModCommand` 中 `StringArgumentType.word()` → `string()`，补全时对非常规字符加引号 |

**加语言键的检查**：代码里 `Text.translatable("key")` 用到的 key 必须同时存在于 `en_us.json` 与 `zh_cn.json`，且两文件键集合完全一致（可用 `ConvertFrom-Json` + 集合比较脚本检查）。

**加存档字段的检查**：`SetModState(NbtCompound)` 要能兜住缺字段；`writeNbt` 要保证同一状态重复写出结果稳定（测试里用「写→读→写」比较 NBT 相等）。

---

## 8. 调试与测试

### 8.1 快速加载验证

```bash
./gradlew runServer     # 看日志里 [esetmod] initialized 和 Done (…s)
```

命令树写错（重复节点、参数顺序问题）通常会在服务器启动构建命令树时直接抛异常。

### 8.2 用 RCON 真正执行命令

`tools/rcon-test.ps1` 会在开发服务器上执行一组命令：

```powershell
# run/server.properties 里先开启
# enable-rcon=true
# rcon.port=25575
# rcon.password=test123
./tools/rcon-test.ps1 -Commands 'setmod','setmod name @a house','setmod start @a house'
```

没有玩家在线时 `@a` 会报 “No player was found”，这正好可以验证选择器解析与错误分支；要测成功分支需要有玩家进入服务器。

### 8.3 临时逻辑自检

改数据层时，可以临时在 `SetMod.onInitialize()` 里加：

```java
if ("1".equals(System.getenv("ESETMOD_SELFTEST"))) {
    SelfTest.run();
}
```

`SelfTest` 放在主源码里、用环境变量开关，跑 `ESETMOD_SELFTEST=1 ./gradlew runServer` 看日志 `SELFTEST OK (n checks)`。**验证完请删掉临时类与开关**，不要带进发布 jar。

当前版本做过的自检覆盖：缓存写入/去重、`name` 保存并清空缓存、同名覆盖保留时长、64 条上限、删除/未知名称、按玩家隔离、存档「写→读→写」稳定、旧格式迁移、回放时序。

### 8.4 存档排查

`run/saves/<world>/data/esetmod.dat` 是 gzip 压缩的 NBT，可用 NBT 查看器（如 NBTExplorer）直接看 `players` 结构。

---

## 9. 已知限制与注意事项

- **仅服务端逻辑**：除 `RecordStickItem.appendTooltip` 外没有客户端代码，不需要 mixin，也没有网络包。
- 所有 `setBlockState` 都必须在服务器主线程执行（当前只在 `END_SERVER_TICK` 回调里做，符合要求）。一次性放大量方块（例如 `speed 1` + 4000 方块）会造成单个 tick 卡顿，用 `speed` 摊开即可。
- `Job` 列表是静态的，`SERVER_STOPPED` 时清空；单人切换世界/多人重启都会走这条路径。
- 清除阶段用 `setBlockState(..., AIR)`，**不会掉落**物品，容器内容也会丢失——这是「回放前清场」的既定行为。
- 记录/回放依赖区块加载：未加载区块会被 `setBlockState` 触发同步加载，超大范围记录会明显变慢。
- 权限等级固定为 2；命令方块天然满足，普通玩家不满足。
- 名称参数是 `word()`，不支持中文和空格；如需支持，见第 7 节。

---

## 10. 版本历史

| 版本 | 变更 |
| --- | --- |
| **1.1.1** | 所有 `/setmod` 子命令新增 `<targets>` 执行对象（`EntityArgumentType.players()`）：支持命令方块、控制台、`/execute as`；支持多目标批量操作；多目标时消息带玩家名前缀，命令方块执行时目标玩家也会收到提示 |
| 1.1.0 | 命名记录体系：`name`、按名称的 `start`/`speed`/`clear`；缓存与命名记录分离；旧存档 `blocks` 自动迁移为缓存 |
| 1.0.0 | 初版：记录木棍 + 匿名记录 + `start`/`speed [ticks]`/`clear` |
