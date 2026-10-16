# 1.21.1 跨端合并（remap 层）验证记录

## 结论一句话

**remap 层已实现并验证可用**：1.21.1（混淆宿主）与 NeoForge named 覆盖层的
方法级拼接能正确产出合并包，游戏**能进主界面并跑起渲染**，`test.err` 零异常。

---

## 一、问题是什么

合并的做法是"**按内部名**把对方平台的补丁类搬进游戏包"。这要求两侧命名空间一致。

| 维度 | 26.3 | 1.21.1 |
|---|---|---|
| 宿主游戏包 | **不混淆**（`net/minecraft/core/Registry.class`） | **混淆**（`fyn`、`geo$b`、`fbp$a`…） |
| 宿主 class 数 | 11383 | 8269（其中 `net/minecraft/` 仅 **26**、顶层裸类 **8220**） |
| 对方补丁基座 | `minecraft-client-patched`（同名空间） | `neoforge-21.1.249-client.jar`（**named**，1603 个类） |
| 直接搬运 | ✅ 名字对得上 | ❌ **一个都对不上** |

所以 1.21.1 上原本是**直接放弃合并**的（日志："需要先补上 remap 层"）。

---

## 二、怎么解决的

### 1. 自研 TSRG2 映射表读取器

`src/main/java/com/fusionml/remap/MappingTable.java`（473 行，零第三方库、零正则）

映射表（本地已有，无需联网）：
- 1.21.1：`libraries/net/neoforged/neoform/1.21.1-20240808.144430/…-mappings-merged.txt`（5.38 MB，240346 行）
- 1.20.1：`libraries/de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/…-mappings-merged.txt`（4.35 MB，212139 行）

**实测（独立复核）**：两张表的**类行右列 100% 是完整 named**：
```
1.21.1: 类行 8270 → 右列 named(net|com)=8269 / SRG=0 / 其它=1(表头)
1.20.1: 类行 7437 → 右列 named(net|com)=7436 / SRG=0 / 其它=1(表头)
```
（`f_252495_` 这类 SRG 名出现在**缩进 1 级的成员行**，不是类行 —— 这点排查时一度判断错。）

**1.20.1 的全量核对**（强证据）：`client-1.20.1-…-slim.jar`（7436 个混淆类）与
`client-1.20.1-…-srg.jar`（7436 个 named 类）**类数逐一对应**：
```
在 srg 里命中 : 7436      查不到映射 : 0      映射到但 srg 无 : 0      命中率 : 100.00%
```
排他性：srg 的 7436 个 named 名里有 **25 个**也出现在 slim 中 —— 这 25 个恰好是
Mojang **不混淆**的那批（`Main`、`MinecraftServer`、`ClientBrandRetriever`、
`DontObfuscate` 注解、整个 `com/mojang/*`），属于合理重叠，不是误匹配。

### 2. 接进合并流程（`PatchedBase`）

- 混淆宿主时载入映射表，把对方条目的 **named 内部名反查成混淆名**，用混淆名当 overlay 键
- overlay 的值改为**记住对方原始条目名** —— 因为合并产物要落回原版包（用混淆名），
  但取对方字节要用 named 名，**两者不是同一个字符串**
- `locate()` 增加第二套布局：`libraries/net/neoforged/neoforge/<ver>/neoforge-<ver>-client.jar`
  （1.21.1 用这种；26.x 用 `minecraft-client-patched`）
- 版本号匹配同时接受 `1.21.1` 与 NeoForge 的 `21.1.249` 两种写法

---

## 三、修掉的 4 个真实缺陷

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | 只合并进 5 个类（应为 1554 个候选） | overlay 键是混淆名，却拿它去**对方 jar**里取字节 → 全部落空 | overlay 记住对方原始条目名 |
| 2 | 选了 `21.1.256` 而实例是 `21.1.249` | `locate()` 按目录名取"最大"，跨补丁号借基座 | 从 `fusion.version` 取实例真实补丁号优先 |
| 3 | 映射表"找不到" | 目录名是 `1.21.1-20240808…`（**短横线**），匹配器只接受点号 | 同时接受 `.` 与 `-` |
| 4 | **合并跑 15 分钟仍未结束** | 两处"每次调用都重新打开 jar"进了逐类循环 | 预建索引（见下） |

### 缺陷 4 详述（性能事故，靠线程转储定位）

`jstack` 连抓三次，主线程分别停在：
```
FastZip.parseCentralDirectory → PatchedBase.readFromJars:2911
FastZip.parseCentralDirectory → PatchedBase.readFromJars:2911
  （经 foreignUsesAsNewSuperclass:1559 → prepare:610）
FastZip.readInto → PatchedBase.receiverIndex:2607
```
两处同类问题：
- `foreignNeedsStrictSuperclassType`：每次调用重解压对方 jar + 全部 runtime jar（最多扫 8000 个类），
  只为找一句 `new <本类>`，而它在 8269 个类的循环里被反复调用
- `readFromJars`：每读**一个**条目就重新解析所有 jar 的中央目录

**修法**：分别预建索引 `foreignNewIndex` / `ENTRY_OWNER`（只建一次）。
索引不可用时返回哨兵 `UNUSABLE` → 回退到原实现的保守语义（`return true`），**保持行为不变**。

**实测效果**：
```
修复前：15 分钟未结束（jstack 抓到，CPU 密集非死锁）
修复后：44766 ms
```
且**裁决结果逐项相同**（523 类方法级拼接 / 200 类注入点保护 / 508 条"保留原版+拼接"）
→ 证明**只改了性能、没改行为**。

> **教训**：`"每次调用都重新打开 jar"` 的写法一旦进了逐类循环就是灾难，
> 而且它**表现为"极慢"而不是"报错"**，日志上完全看不出来，只能靠线程转储。

---

## 四、端到端验证（真机）

命令（`--mc-dir` 指向**版本目录本身**，走版本隔离布局）：
```
test.cmd -Mode launch -McVersion 1.21.1-NeoForge_21.1.249 \
         -GameDir "D:\我的世界\服务器\.minecraft\versions\1.21.1-NeoForge_21.1.249" -WaitSeconds 200
```

关键日志：
```
游戏根目录  : D:\我的世界\服务器\.minecraft
游戏运行目录: ...\versions\1.21.1-NeoForge_21.1.249（版本隔离布局）
模组目录    : ...\1.21.1-NeoForge_21.1.249\mods（0 个）

跨端补丁基座：宿主游戏包命名空间 —— **混淆**：类 8269 个，其中 net/minecraft/ 下 26 个、顶层裸类 8220 个
跨端补丁基座：已载入映射表（类 8269 条），将把对方 named 名反查成混淆名后对齐
跨端补丁基座：已合并出游戏包（原版 23192 条 + 覆盖 325 条，其中 721 条是"对方只新增成员、
              保留原版字节"、508 条是"保留原版 + 拼接对方新增方法"）→ 耗时 44766 ms
跨端补丁基座：方法级拼接 523 个类 —— fic（+3 个方法）、dec（+12 个方法）、gki（+10 个方法）
跨端补丁基座：注入点保护 200 个类（保留原版，宿主 Mixin 优先）

[21:01:22] [main/INFO] [Launcher/MODLAUNCHER]: ModLauncher running: --fml.mcVersion 1.21.1 --fml.neoForgeVersion 21.1.249
[21:08:26] [Render thread/INFO] [minecraft/Minecraft]: Setting user: Player
[21:08:26] [Render thread/INFO] [minecraft/Minecraft]: Backend library: LWJGL version 3.3.3+5
[21:08:29] [Render thread/INFO] ReloadableResourceManager: Reloading ResourceManager: vanilla, mod_resources, mod/neoforge
[FusionML/跨端] 挂载点已进入（Minecraft）
[21:08:34] [Render thread/INFO] [minecraft/SoundEngine]: Sound engine started
[21:08:44] [Render thread/INFO] [minecraft/Minecraft]: Stopping!   ← 测试脚本主动终止
```
**`test.err` 完全为空（零异常）。**

### 一次被排除的假故障（值得记）

最初把 `--mc-dir` 指向 `.minecraft` **根**，于是 `gameDir` 解析成根目录，
读到了公共 `mods/`（210 个 jar，其中 **150 个文件名含 1.20.1/1.19/1.18**
—— 而实例是 1.21.1 NeoForge）。启动被两个错误挡住：
```
ServiceConfigurationError: GraphicsBootstrapper: Provider me.decce.asynclogger… not found
  at ModDirTransformerDiscoverer.earlyInitialization     (FML 自己的服务发现)
ModFileLoadingException: Failed to load mod file kotlinforforge-4.11.0-all.jar
  at JarInJarDependencyLocator / JarJarSelector          (FML 嵌套 jar 选择)
```
**对照实验**：临时移走 `asynclogger` 后**换成了另一个错**（`kotlinforforge`），
说明问题在**整合包自身的模组混装**，与合并无关。
两者都发生在 **ModLauncher/FML 阶段、游戏类加载之前**，而合并产物是"游戏包"（minecraft 类），
不可能影响那个阶段。

**改用版本隔离的干净实例 `1.21.1-NeoForge_21.1.249`（0 模组）后一次通过。**

---

## 五、诚实标注

### 已验证
- TSRG2 解析正确：类名 100% 命中、0 查不到、0 多对一、反向表 2000/2000 自洽
- 1.20.1 **全量** 7436 个类换出的 named 名在真实 srg jar 中**全部存在**
- 1.21.1 抽样 1595 / 8269 能在真实覆盖层中找到
- 描述符换算 10 项断言（对象/数组/多维/内部类/基本类型/null/严格版），两版本均通过
- 合并产物 **8269 个类全部能被 `ClassFile` 重新解析**（结构自洽，0 失败）
- **方法级拼接真的进了产物**（直接读产物字节对比原版，不是日志转述）：
  ```
  混淆类   原版方法数   产物方法数   增量
  fic          7           10        +3
  dec         27           39       +12
  gki         14           24       +10
  fil         10           15        +5
  ```
  增量与日志逐字对上（`fic（+3 个方法）、dec（+12）、gki（+10）、fil（+5）`），
  且是**增加而非替换**（原版方法一个没少）—— 这正是"方法级拼接"的定义。
  同时证明映射表把 named 名正确反查成了混淆名（否则按名字根本找不到对应类）。
- 性能：合并 **44766 ms**（修复前 15 分钟+），裁决结果与修复前逐项一致
- **1.21.1 真机端到端：进主界面 + 渲染 + 音效，`test.err` 零异常**
- 安装器注入往返：注入 → 卸载还原，`26.3.json` 精确回到 15 项、
  `PCL/config.v1.yml` 回到 `Info: ''` / `Grass.png`（已实测）
- `-javaagent` 位置：在 `[13]`，`-cp` 在 `[16]` → **在 classpath 之前**（已实测）
- 幂等：重复安装输出"宿主版本定义已注入过（26.3），跳过"（已实测）

### 未验证
- **1.20.1 端到端**：只验证了映射表与名称换算（全量 100%），**没有跑过 1.20.1 的游戏启动**
  （还需要 Forge 侧的补丁产物规律）
- **跨端拼接在真实跨端模组下的效果**：干净实例 **0 个模组**，所以只证明了
  "合并产出正确 + 游戏能起"，**没有**证明"某个具体跨端模组因此可用"
- **1.21.1 余下 6674 个类（8269−1595）的正确性**：本机无完整 named 游戏包作对照，无证据
- 内存占用未测
- `MappingTable` 的 SRG→named 二级映射：1.20.1 的成员右列 93.57% 字段 / 76.52% 方法是
  SRG 中间名，**是否需要二级映射取决于对方补丁基座里成员叫什么名**，未判定
- **PCL 实例概览的实际显示效果**：写入格式已按实测的 PCL 配置结构对齐，
  但**没有在 PCL 界面里肉眼确认**过图标与文字真的显示出来
- **从 PCL 真实点击启动**：注入后的 json 结构已逐项核对（javaagent 位置、继承链、
  jvm 项数），但没有真的在 PCL 里点一次"启动"

## 六、安装器缺陷修复（玩家实测报的）

用户实测报的问题，**根因都不是 remap，而在"版本定义怎么写"**。

### 缺陷 A：`inheritsFrom` 写成了目标版本，而不是宿主版本 id

```
玩家实例的版本目录 id = "1.21.1-NeoForge_21.1.249"（或 "26.3"）
写出的 inheritsFrom  = "1.21.1"        ← 目标版本，启动器根本找不到这个版本
```
同一个 `o.inheritVersion` 被当成两件事用：
- 选哪个 loader（需要**目标版本** `1.21.1`）
- 版本 json 的 `inheritsFrom`（需要**宿主版本目录 id**）

**修法**：拆成两个职责，新增 `hostVersionId(gameDir, target)` 专门解析宿主版本
（优先匹配与目标同版本的目录，取排序后第一个以保证可复现）。

### 缺陷 B：版本 json 自带了 `mainClass` 与空 `libraries`

原实现写：
```json
"mainClass": "com.fusionml.FusionLauncher",
"libraries": [],
"arguments": {"game": ["--gameDir", ..., "--mods", ...]}
```
后果是**顶掉了平台启动器**（Fabric 的 `KnotClient`、NeoForge 的 `BootstrapLauncher`）：
- 从启动器启动**不加载 mod**
- **earlywindow 也没了**（平台的 JVM 参数一个都没继承到）

**修法**（Fabric / Forge 安装器那一套）：`mainClass`、`libraries` **一律不写**，
让它们从父版本继承；只**追加** `arguments.jvm` 里的 `-javaagent` 与我们的属性。

### 缺陷 C：GUI 默认选中的版本不跟随目录

原优先级是 `--inherit > 目录名 > 列表第一项`。第二项用的是 `dir.getFileName()`
—— 那是**玩家选的目录的名字**（多半是 `.minecraft` 或整合包根目录），
既不是 `26.3` 也不是 `1.21.1`，于是**必然落到第一项 `26.3`**，接着被版本闸门判"不符"而拒绝。

**表现**：玩家选完目录、什么都没动，就被红字拒绝；提示"找不到版本 26.3"，
而目录里明明有版本 —— 看起来就是"安装器认不出任何版本"。

**修法**：改成 `--inherit > 游戏目录自己（版本隔离时）> 目录里真实存在的版本 > 目录名 > 第一项`。
中间那条"游戏目录自己"必须有：实测选 `versions\26.3` 时体检会扫出根 `.minecraft`
下的一堆版本（`1.20.1`、`1.21.1`…），若先取"扫描结果里第一个"，会选中 `1.20.1` ——
换了个姿势犯同一个错。

### 缺陷 D：版本闸门不认版本隔离布局

闸门原来只在 `<游戏目录>/versions/` 下找版本。但版本隔离时**游戏目录本身就是版本目录**
（PCL 把实例放在 `versions\<id>`），里面没有 `versions/` 子目录 → 判定为空 →
明明选的就是 `26.3`，却报"目录里没有 26.3"。

**修法**：先判"游戏目录自己是不是版本目录"（有没有 `<目录名>.json`），是就以它自己为准，
再并入 `<目录>/versions/` 下的那些。

---

## 七、"从启动器启动就搞定"是怎么做到的

### 为什么以前必须先装一遍 Minecraft

老安装器是"**往玩家已装好的实例里加东西**"。对新手来说这是最难的一步：
他得先知道"装哪个版本、装在哪、用什么 Java"，才能开始用 FusionML。

Fabric 官方安装器不这样 —— 它**自己把 Minecraft 拉下来**。本轮补上了同样的能力。

### 做法

新增 `FullInstance`：一条命令装出**自包含**的实例。

```
java -jar fusion-installer.jar --install-full 26.3 --target <目录>
```

装出来的结构（实测，`build\fulltest`）：
```
versions/26.3/26.3.jar          ← 游戏本体 39.6 MB
versions/26.3/26.3.json         ← 原版版本定义
libraries/                      ← 114 个依赖库，111 MB
assets/indexes + objects/       ← 资源（--no-assets 可跳过，约 1.4 GB）
lib/fusionml/fusion-ml.jar      ← 加载器本体
versions/fusionml-1.0.0/…json   ← 可启动版本定义（继承 + javaagent）
mods/ config/ start-game.cmd …
```

下载全部走 `McDownloader.ensure` —— 它**先查本地文件是否已通过 sha1 校验**，
通过就复用。所以重复安装廉价、中断可续。

`--no-assets` 用于快速验证启动链路；首次装机必须下 assets，否则没有音效与语言。

### 本轮踩的坑（已记入纪律）

**用 PowerShell 的 `Get-Content … -replace … | Set-Content` 改 Java 源文件，把整个文件的
UTF-8 编码毁成了 GBK** —— 43 处中文注释全部变成乱码，只能整文件重写。

这条其实早就有记录（"绝不要用 PowerShell `Set-Content` 写 .java"），本轮又犯了一次。
**根因是"想省一次写操作"**。正确的做法只有一条：改 Java 源码一律用 write/edit 工具。

---


### 关键认识：启动器启动的是**玩家实例**，不是我们新建的版本

实测 PCL 的机制（读 `PCL/config.v1.yml` 与 `PCL/Setup.ini`）：

- 模组平台**不写进版本 json**，而是记在 PCL 自己的配置里
  （`VersionFabric: ''` / `VersionNeoForge: 21.1.249`），启动时由 PCL 自己拼进 classpath。
- 实测用户那个 26.3 实例：`26.3.json` 的 `mainClass = net.minecraft.client.main.Main`（纯原版）、
  没有任何 fabricloader 库，而 `PCL/config.v1.yml` 的 `VersionFabric` / `VersionNeoForge` **都是空**。

**所以**：任何"新建一个继承版本"的方案都拿不到平台 —— 继承出来的只是个纯原版定义。
玩家在启动器里选的也是**实例本身**（`26.3`），不会去选 `fusionml-1.0.0`。

### 做法：注入宿主实例的版本 json

往 `versions/<hostId>/<hostId>.json` 的 `arguments.jvm` 里**追加**：

```
-javaagent:${game_directory}/lib/fusionml/fusion-ml.jar
-Dfusion.gameDir=${game_directory}
-Dfusion.version=<hostId>
```

三条安全约束：
1. **先备份**：原文件整份存成 `<id>.json.fusionml-backup`，卸载时精确还原（已实测往返）
2. **只追加不覆盖**：不动任何既有 jvm 项
3. **幂等**：已注入则跳过（否则重复安装会堆一串 javaagent）

**插入位置很关键**：必须插在 `-cp` **之前**。实测该版本 `jvm` 末尾是
`["-cp", "${classpath}"]`，追加到最后会让它落在 classpath 之后，部分启动器会出错。
实测结果：`-javaagent` 在 `[13]`、`-cp` 在 `[16]` ✅

### 做法：在 PCL 实例概览里标出加载器

PCL 靠 `PCL/config.v1.yml` 的 `Info`（说明文字）与 `Logo`（实例图标）显示状态。
实测一个 NeoForge 实例：`Logo: .../Blocks/NeoForge.png`、`Setup.ini` 里
`Info:正式版 1.21.1, NeoForge 21.1.249`。

安装时：
- `Info` **追加** `, FusionML`（保留原有内容）
- `Logo` 若还是原版 `Grass.png` 就换成 `Anvil.png`
  （PCL 图标是内嵌 `pack://` 资源，外部加不进去；实测可用的只有
  Grass / Fabric / NeoForge / Anvil / RedstoneBlock。用 Anvil 是因为它不属于任何单一下载器，
  不容易引起误解；**真正说明身份的是 `Info` 里那句 `, FusionML`**）
- **平台字段（`VersionFabric` / `VersionNeoForge`）一律不碰** —— 那是"启动时装哪个平台"
  的开关，写错会让整合包直接起不来。我们只在启动时以 javaagent 挂上去，不需要改它。
- 两个文件都先备份，卸载时还原（已实测往返）

> **一个自己踩的坑**：拼 `"Info:" + ": "` 得到了 `Info:: '', FusionML`（**双冒号**），
> 会破坏 PCL 配置。冒号后只能加一个空格。修复后输出 `Info:FusionML`。

### 为什么不再依赖 `start-game.cmd`

原脚本是 `java -jar fusion-ml.jar --mc-launch`，即**我们自己当启动器**。实测这条路的
根本缺陷：它按 `libraries` 拼 `-cp`，而 NeoForge 是用 `BootstrapLauncher` + **模块路径**
加载的，自己拼**永远拼不全** —— 实测闪退：
```
NoClassDefFoundError: net/neoforged/neoforge/common/extensions/TypedInstanceExtension
  at net.minecraft.client.main.Main.main
```
这正是"用 start-game.cmd 启动慢、还闪退"的原因。改成注入宿主 json 后，
平台启动器与它的全部参数都由启动器自己提供，我们只是被"搭便车"挂上。

---

## 八、一键装出完整实例（对照 Fabric 安装器）

### 为什么以前必须先自己装一遍 Minecraft

老安装器是"**往玩家已装好的实例里加东西**"。对新手这是最难的一步：
他得先知道"装哪个版本、装在哪、用什么 Java"，才能开始用 FusionML。

Fabric 官方安装器不这样 —— 它**自己把 Minecraft 拉下来**。本轮补上了同样的能力。

### 做法

新增 `FullInstance`，一条命令装出**自包含**实例：

```
java -jar fusion-installer.jar --install-full 26.3 --target <目录>
```

实测装出的结构（`build\fulltest`）：
```
versions/26.3/26.3.jar          ← 游戏本体 39.6 MB
versions/26.3/26.3.json         ← 原版版本定义
libraries/                      ← 114 个依赖库，111 MB
assets/indexes + objects/       ← 资源（--no-assets 可跳过，约 1.4 GB）
lib/fusionml/fusion-ml.jar      ← 加载器本体
versions/fusionml-1.0.0/…json   ← 可启动版本定义（继承 + javaagent）
mods/ config/ start-game.cmd …
```

下载全部走 `McDownloader.ensure`：**先查本地文件是否已通过 sha1 校验**，通过就复用。
所以重复安装廉价、中断可续。`--no-assets` 用于快速验证启动链路；
首次装机必须下 assets，否则没有音效与语言。

### 端到端验证（真机，已通过）

在**空目录** `build\fulltest` 上跑 `--install-full 26.3 --no-assets`，然后直接启动：

```
[21:53:29] [Render thread/INFO]: Setting user: Player
[21:53:30] [Render thread/INFO]: Backend library: LWJGL version 3.4.3+4
[21:53:31] [Render thread/INFO]: Using graphics backend OpenGL, using drivers: 3.3.0 NVIDIA 617.42
[21:53:35] [STDOUT]: [FusionML/跨端] 挂载点已进入（Minecraft）
[21:53:38] [Render thread/INFO]: Sound engine started
[21:53:38] [Render thread/INFO]: OpenAL initialized on device OpenAL Soft
```

退出码 0，日志无 `has crashed`。日志里另有两处 ERROR 属正常现象、与安装无关：
`icons/icon_256x256.png` 缺失（没下 assets）、`InvalidCredentialsException`（离线账号）。

**结论：从空目录到"能进主界面并渲染"已是一条命令的事。**

### 本轮踩的坑（写进纪律）

想省一次写操作，用了 PowerShell 的 `Get-Content … -replace … | Set-Content` 改 Java 源文件，
**把整个文件的 UTF-8 编码毁成 GBK**，43 处中文注释全变乱码，只能整文件重写。

这条纪律早就有（"绝不要用 PowerShell `Set-Content` 写 .java"），本轮又犯。
**改 Java 源码一律用 write / edit 工具，没有例外。**

### 两次"编码虚警"的教训（同一个坑踩了两次）

用 `Get-Content -Raw`（默认按 GBK 解码）读 UTF-8 文件，看到中文乱码，
一度以为 `launcher_profiles.json` 与本文档被写坏了 —— 实际两次都是**读取方式**的错，
文件本身完全正常（`[System.IO.File]::ReadAllText(path, UTF8)` 读出来是对的）。

**判据要定死**：怀疑编码问题时先看字节
（`ReadAllBytes` + `Encoding.UTF8.GetString` 数 `U+FFFD` 的个数），
不要用 `Get-Content` 的显示结果下结论。

