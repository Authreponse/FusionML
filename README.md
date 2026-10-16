# FusionML

**一个从零手写的 Minecraft 模组加载器：让 Fabric、Forge、NeoForge 三端模组在同一个进程里同时加载。**

不 fork、不套壳、不做兼容层。连 ZIP 读写、JSON/TOML 解析、class 文件的解析与改写
都是自己写的那一套 —— 整个项目只依赖 JDK 自身。

```
✅ 实测：149 个模组包、三端混装，进到游戏主界面并正常渲染
✅ 实测：NeoForge 版 DistantHorizons 的 Mixin 真的注入到了原版 Minecraft 类里
✅ 实测：yumi_mc_core 走通 NeoForge 的 DeferredRegister 注册链
✅ 实测：安装器给出"目录 + 版本"双重验证，装之前就知道能不能用
✅ 实测：服务端一条命令拉取 + 装好 + 起服，`Done (6.229s)!` 并持续 tick
```

## 服务端：对照 Fabric 安装器的做法

不需要先用官方启动器装一遍，安装器自己拉取服务端 jar：

```
java -jar fusion-installer.jar --install-server --target ./server --fetch 26.3
```

它会向 `piston-meta` 查询版本清单、下载 `server.jar`、校验 SHA1、铺好标准目录，
并生成 Fabric 形态的启动脚本。启动脚本**仍由官方 `server.jar` 启动**
（它是一个 bundler），加载器只以 javaagent 形式挂上去，不替代官方入口：

```cmd
set JAVA_TOOL_OPTIONS=-javaagent:"%~dp0lib\fusionml\fusion-ml.jar"=server
"%JAVA%" -Xmx4096m -jar "%~dp0server.jar" nogui
```

实测结果（26.3 原版服务端）：

```
[FusionML] 已改写 net.minecraft.server.MinecraftServer：注入 1 个方法，111872 字节
[FusionML/跨端] 挂载点已进入（DedicatedServer）
[Server thread/INFO]: Starting Minecraft server on *:25565
[Server thread/INFO]: Done (5.612s)! For help, type "help"
[Server thread/INFO]: Server empty for 60 seconds, pausing
```

> ⚠️ **"服务端起得来" ≠ "钩子生效了"。** 这两件事必须分开看：早期版本里钩子因
> 常量池索引溢出被静默跳过，服务端照样打出 `Done`。判断钩子是否真的生效只能看
> `挂载点已进入`。安装器刻意**不自动同意 EULA**（写出 `eula=false`，由你自己改）。

改动清单、踩坑记录与"已验证 / 未验证"逐条对照见
[docs/server-support-delivery.md](docs/server-support-delivery.md)。

---

## 为什么值得一看

**模组生态被三套互不兼容的加载器切成了三块。** 想同时用 Fabric 的性能模组和
Forge 的内容模组？传统答案是"兼容层"—— 把一端的 API 假装成另一端的。
那种做法要么牺牲性能，要么在边界上不断漏气。

FusionML 换了个思路：**不翻译，直接让三端的官方 API 都真实存在**。

这带来几个不那么常见的工程点：

| 做法 | 常见方案 | FusionML |
| --- | --- | --- |
| 字节码改写 | 引入 ASM | **自己解析与改写 class 文件** |
| 构建 | Gradle / Maven | **`javac` + 一个自研构建脚本** |
| 基础工具 | 第三方库 | **ZIP、JSON、TOML 全部自研** |
| 合并冲突 | 二选一 | **方法级拼接：两个签名都留下** |

**最后一行是整个项目最有意思的地方。** 当一端要"改"某个原版类、而宿主模组的
Mixin 又必须看到那个类的原版字节时，两边都要、Java 又不给多继承。FusionML 的答案是
**把对方新增的方法拼进原版字节**，于是：

* 原版的方法一个不少 → 宿主模组的注入点还在
* 对方新增的方法也在 → 跨端模组调得到

代价是这套拼接要自己处理常量池移植、`StackMapTable` 重写、`BootstrapMethods` 合并
等等——README 里逐条记着踩过的坑（那些"JVM 认、ASM 不认"的坏类是真实发生过的）。

---

## 三端同时加载是怎么做到的

```
             ┌──────────────────────────────────────┐
             │  你的整合包（Fabric + Forge + NeoForge 模组混装）│
             └──────────────────┬───────────────────┘
                                │
                    ┌───────────▼───────────┐
                    │   FusionML 接管启动     │   ← javaagent premain 前置介入
                    └───────────┬───────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
   ┌────▼────┐            ┌─────▼─────┐          ┌──────▼──────┐
   │ Fabric  │            │  NeoForge  │          │    Forge    │
   │ 原生 API │            │  原生 API   │          │   原生 API   │
   └────┬────┘            └─────┬─────┘          └──────┬──────┘
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                │
                    ┌───────────▼───────────┐
                    │   原版游戏包（合并后的基座）│
                    │  原版字节 + 对方补丁的拼接产物│
                    └───────────────────────┘
```

**关键点：不替换平台的启动器。** 宿主平台的类加载语义（Mixin、入口点注入、类路径分组）
和它自己的启动器不可分割，所以 FusionML 用 `javaagent` 的 `premain` **前置介入**，
而不是顶替它。

---

## 现在能用吗（诚实版）

**能。但只有一部分版本。** 下面这张表是当前真实状态，不含任何"应该可以"：

| 游戏版本 | 状态 | 说明 |
| --- | --- | --- |
| **26.3** | ✅ **实测可玩** | 149 个模组包三端混装，进游戏主界面并渲染 |
| **26.2** | ⚠️ 未实测 | 与 26.3 同族（26.x 不混淆、同一套合并规则），可以构建，但**本机没有实例，一次都没跑过** |
| **26.1.2** | ⚠️ 部分验证 | 有实例、命名空间检查通过；未做完整真机回归 |
| **1.21.1** | ❌ 暂不可用 | 游戏包是**混淆**的，而合并按内部名搬运 → 需要 remap 层 |
| **1.20.1** | ❌ 暂不可用 | 同上，另外还要处理 Forge 侧的补丁产物规律 |

**为什么 1.21.1/1.20.1 不行**：实测它们的游戏 jar 里，8269 个类中
`net/minecraft/` 下只有 **26** 个，其余 8220 个是顶层裸类（`chn.class` 这种混淆名）。
而 26.x 从 Mojang 起就不混淆了（11383 个类里 10737 个在 `net/minecraft/` 下）——
所以"按内部名把补丁类搬进游戏包"这条路线在 26.x 成立，在 1.21.1/1.20.1 上一个都匹配不上。

**安装器会在安装前就告诉你**，不会让你装完才发现：

```
第一道：目录校验：✔ 通过          ← 这是不是一个游戏目录
第二道：版本校验：✘ 不通过        ← 这个版本能不能用
   ✘ 命名空间 —— **混淆** —— 合并是按内部名搬运的…需要先补 remap 层
```

---

## 下载与安装

**只有一个安装器**：`fusion-installer.jar`。它内嵌了全部 5 个版本的加载器，
你在界面上选哪一版，它就部署哪一版的 loader（各版本的 Java 主版本与平台适配不同，
所以不能只嵌一个通用版）。

打开界面后：选游戏目录 → 点「重新体检」→ 在「安装到版本」下拉框里选版本 → 安装。

```powershell
java -jar fusion-installer.jar --gui
```

它会在安装前做**两道验证**，并且**版本不符时直接拒绝安装**：

1. **目录体检** —— 这个目录是不是标准的 `.minecraft` 布局、有没有写入权限；
2. **版本一致性** —— 下拉框里选的版本，必须与目录 `versions/` 下**实际存在的版本**相符。
   不符就禁用「一键安装」，并在状态条与日志里写明是哪两者不符。

> 为什么要有第二道：版本目录是玩家自己起的（`26.3-Fabric 0.19.5`、`1.21.1-NeoForge_21.1.249` …），
> 而下拉框里的版本是"目标版本"。两者对不上时，装进去的 loader 与游戏实例不是一套，
> 起来之后会以各种奇怪的方式失败。与其装完再排错，不如**装之前就拒绝**。
>
> 判据基于**目录里真实存在的版本**，不是你在下拉框里选了什么 —— 因为下拉框的内容本身
> 就可能与目录不符，那正是要抓的情形。

只想要加载器本体（自己已有启动流程）的话，单独的 loader jar 也照常提供：

| 产物 | Java | 平台 |
| --- | --- | --- |
| `fusion-ml-26.3.jar` | 25 | Fabric / NeoForge |
| `fusion-ml-26.2.jar` | 25 | Fabric / NeoForge |
| `fusion-ml-26.1.2.jar` | 25 | Fabric |
| `fusion-ml-1.21.1.jar` | 21 | Fabric / NeoForge |
| `fusion-ml-1.20.1.jar` | 17 | Fabric / Forge |
| `fusion-ml.jar` | 按实例 | 不绑定版本的通用版 |

安装只做新增、不覆盖已有文件；卸载按安装清单精确还原，整合包自带的模组/配置/存档不会被碰。

**怎么确认你下的是哪一份**：

```powershell
java -cp fusion-ml-26.3.jar com.fusionml.FusionVersions
# → 26.3
#   Java 25 / Fabric / NeoForge / 不混淆
```

---

## 目录

* [现在能用吗](#现在能用吗诚实版)
* [三端同时加载是怎么做到的](#三端同时加载是怎么做到的)
* [快速开始](#快速开始)
* [实测数据](#二实测数据本机i7-37704-物理核--8-逻辑核l3-8mbwindows)
* [性能设计](#四性能设计每一项都说明为什么更快)
* [自研字节码注入](#五自研字节码注入)
* [跨端模组的入口点驱动](#十一跨端模组的入口点驱动最新一轮状态如实)
* [超类桥接](#十二超类桥接让原版类长出对方超类的能力)
* [多版本适配评估](#十四多版本适配评估1211--1201)
* [服务端支持](#十五服务端支持)
* [分版本打包与双重验证](#十七分版本打包与安装器的双重验证2026-10-16)

---

## 一、快速开始

```powershell
# 编译（只需要 JDK 21+，不需要联网、不需要 Gradle）
.\build.ps1

# 查看完整加载报告
java -cp build\classes com.fusionml.FusionLauncher --mods <模组目录>

# 用表格查看模组列表（支持搜索 / 过滤 / 排序）
java -cp build\classes com.fusionml.FusionLauncher --mods <模组目录> --list --search 优化 --filter fabric

# 无界面环境也能出图（用于核对界面与做文档）
java -cp build\classes com.fusionml.FusionLauncher --mods <模组目录> --shot-ew build\early-window.png

# 生成测试夹具并跑加载基准（200 个包 / 1.1 万个类）
java -cp "build\classes;build\test-classes" com.fusionml.bench.LoadBenchmark --mods build\fixtures --fresh --count 200 --classes 45

# 字节码层自检（含"注入后由 JVM 校验"的端到端验证）
java -cp "build\classes;build\test-classes" com.fusionml.bytecode.BytecodeSelfTest

# 安装到游戏目录（双击 fusion-installer.jar 也可以，会打开图形安装器）
java -jar build\fusion-installer.jar --target "%APPDATA%\.minecraft" --inherit 1.21
```

产物两个：

* `build/fusion-ml.jar` —— 加载器本体，可直接 `java -jar` 运行；
* `build/fusion-installer.jar` —— 安装器（内嵌加载器本体），负责释放标准目录结构、部署与还原。

---

## 一点五、安装器：一键释放标准目录结构

```powershell
# 图形界面（双击也行）
java -jar build\fusion-installer.jar

# 命令行
java -jar build\fusion-installer.jar --target <游戏目录> --mods-from <模组来源目录> --inherit 1.21
java -jar build\fusion-installer.jar --target <游戏目录> --dry-run   # 预演
java -jar build\fusion-installer.jar --target <游戏目录> --uninstall # 卸载
```

安装器会做这些事：

1. 确定游戏目录（默认探测 `%APPDATA%\.minecraft`）；
2. 释放标准目录：`mods/ config/ lib/ libraries/ versions/ resourcepacks/ shaderpacks/ saves/ logs/ crash-reports/ .fusionml/`；
3. 把加载器本体释放到 `lib/fusionml/fusion-ml.jar`；
4. 写出早期窗口配置模板到 `config/fusion-earlywindow.properties`（已存在则保留，绝不覆盖玩家设置）；
5. **合并**写入启动器配置 `launcher_profiles.json`，并写出版本定义 `versions/fusionml-<版本>/`，
   这样官方启动器的版本列表里会多出一项，选中即可启动；
6. 可选把指定目录里的模组包拷进 `mods/`（同名已存在则跳过）；
7. 写安装清单到 `.fusionml/install-manifest.txt`。

安全边界（这一条是刻意设计的）：

* 安装过程**只新增**，不删除、不覆盖任何已有文件；
* 卸载只删除清单里记录过的、由安装器创建的文件，以及因此变空的目录；
* `mods/`、`saves/`、`config/` 等玩家数据目录**永不删除**（实测卸载后 3 个模组 jar 原封不动）；
* `launcher_profiles.json` 解析失败时宁可不改，也不会把玩家的启动器配置写坏。

另外，**加载器自己启动时也会自动补齐缺失的标准目录**（仅当模组目录的父目录确实像一个游戏目录时，
且只创建缺失项、不动已有文件）。所以哪怕不用安装器，直接把加载器丢进游戏目录跑，也能得到标准结构。

---

## 二、实测数据（本机：i7-3770，4 物理核 / 8 逻辑核，L3 8MB，Windows）

同一套代码、同一份夹具，唯一变量是工作线程数（每种配置取多轮最佳值，冷热交替以抵消页缓存影响）：

| 并行度 | 总耗时 | 相对 1 线程 |
|---|---|---|
| 1 线程 | 294.6 ms | 1.00× |
| 2 线程 | 233.6 ms | 1.26× |
| 5 线程（自动） | 190.2 ms | 1.55× |
| 8 线程 | **135.8 ms** | **2.17×** |

单线程基线在多轮之间会在 295~414 ms 之间浮动（取决于页缓存状态），而 8 线程的结果非常稳定
（134.9~135.8 ms）。所以这里给出区间而不是一个精确数：

> **8 线程下的加载耗时稳定在约 135 ms；相对单线程的加速比在 2.2× ~ 3.1× 之间。**

这种"基线不稳、并行结果稳定"的现象本身也说明了一件事：
单线程时耗时被 IO 与页缓存主导，而并行化之后 CPU 侧成为主导，于是结果变得可预测。

规模：**200 个模组包 / 211 个模组条目 / 11083 个类文件**，其中
Fabric 113、Forge 66、NeoForge 32。

各阶段耗时（自动并行度）：

| 阶段 | 耗时 | 占比 |
|---|---|---|
| 扫描模组目录 | 12.6 ms | 6.9% |
| 解析三端元数据 | 46.0 ms | 25.1% |
| 依赖解析与分层 | 2.9 ms | 1.6% |
| 建立全量类索引 | 81.7 ms | 44.7% |
| 收集注入规则 | 3.6 ms | 2.0% |
| 注入自检 | 24.6 ms | 13.4% |

注入链路端到端结果：**56 条注入规则 → 改写 56 个类 → 56 个类全部通过 JVM 字节码校验**。

> 这些数字是"同一份代码改并行度"的对比，不是"跟别的加载器比快"。
> 后者需要控制大量无关变量才可信，本项目宁愿给出可复现的、说明白了变量是什么的数字。

---

## 三、为什么这是"原生三端"，而不是兼容层

三端的模组契约在本质上是不同的，不是"配置差异"：

| | Fabric | Forge | NeoForge |
|---|---|---|---|
| 元数据 | `fabric.mod.json` | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` |
| 主类如何被找到 | 元数据里声明的入口点，**类上不需要注解** | 全包扫描 `@Mod` 注解 | 全包扫描 `net.neoforged.fml.common.Mod` |
| 如何被调用 | 实例化后调用 `onInitialize` 等接口方法 | 构造器注入 `IEventBus` / `ModContainer` | 构造器注入 `net.neoforged.bus.api.IEventBus` |
| 依赖版本写法 | `>=1.0.0 <2.0.0`、`^1.2`、`~1.2` | `[1.0,2.0)` 这类 Maven 区间 | 同 Forge |
| 侧（client/server） | `environment` + 入口点分类 | `side=BOTH/CLIENT/SERVER` | 同 Forge |

所以"原生"在本项目里指的是：**为每一端各写一套真实的判定与调用代码**
（`src/main/java/com/fusionml/platform/`），而不是把 A 端的调用翻译成 B 端的调用。

* `FabricPlatformAdapter`：只看入口点列表，按 `main` / `client` / `server` 分类调用；
  接口不存在时明确说明"环境未提供该接口"，而不是抛一个看不懂的堆栈。
* `ForgePlatformAdapter`：靠 `@Mod` 注解扫描结果定位主类，按 `(IEventBus)` / `(IEventBus, ModContainer)`
  的顺序尝试构造器注入。
* `NeoForgePlatformAdapter`：注解包名与总线类型整套换成 NeoForge 的，不复用 Forge 的类型表 ——
  否则 NeoForge 模组会因为"找不到 Forge 的总线类型"而静默降级，这正是兼容层做法最容易造成的隐蔽故障。

还额外处理了现实里一定会遇到的两件事：

* **一个 jar 同时是 Fabric + Forge 模组**（多端同包）：会为每一端各产出一条模组条目；
* **旧版 NeoForge 也用 `mods.toml`**：按内容特征（`neoforge` 关键字等）判定，并给出提示。

---

## 四、性能设计（每一项都说明"为什么更快"）

### 1) 自研 ZIP 读取：让同一个大包也能被多线程同时解压

`java.util.zip.ZipFile` 对本机句柄有共享同步，多个线程读同一个 jar 会互相排队 ——
"并行解压一个 300MB 的模组包"实际上并行不起来。
`FastZip` 自己解析中央目录，读取时用 `FileChannel.read(buf, position)` 做定位读，
不持有任何共享锁，因此任意多线程可以同时解压同一个 jar 的不同条目。

此外：识别元数据时只解出前 N 字节（`readPrefix`）；`Inflater` 按线程复用；
同名条目会显式报告（真实 jar 里那几乎一定是打包事故，而不是让读取结果变成"随机取一个"）。

### 2) 并行度按 CPU 拓扑算，并且启动路径上绝不调用慢命令

`CpuTopology` 会取物理核数、逻辑核数、L2/L3 大小与主频，用它算出：
CPU 密集阶段的线程数、IO 阶段的线程数、以及"按缓存行大小决定的分块粒度"。

这里有一条用血换来的经验：**不要在启动路径上调用 PowerShell**。
`Get-CimInstance` 拿到的核心数最准，但 PowerShell 冷启动在这类机器上要 4 秒 ——
那会让"加载 200 个模组只用 120ms"变成一个笑话。现在改为：
注册表（几十毫秒、必然存在）→ `wmic`（可用时约 50ms，400ms 上限）→ 启发式；
整条探测还有总时间预算兜底（实测约 80ms）。要精确值可以显式开 `-Dfusion.probe=deep`。

### 3) 自研工作窃取线程池，按任务形状而不是按"线程数"设计

`FusionPool`：

* **本地 LIFO + 窃取 FIFO**：自己的工作从队尾取（热数据留在 L1），窃取者从队首偷（粒度最大、冲突最少）；
* **调用方参与**：主线程调 `parallelFor` 时自己也去抢块执行，把本来会浪费掉的等待时间直接变成吞吐；
* **伪共享隔离**：所有计数器按 64 字节（8 个 long）步长铺开，避免多个 worker 写相邻字段导致缓存行来回弹跳；
* **分级退避**：自旋 → 让出 → 定时 park，空闲时几乎不吃 CPU，有活干时纳秒级唤醒；
* **故障隔离**：单个模组抛异常不会带崩整池，错误收集后在阶段边界统一抛出；
* **嵌套调用退化为本地执行**：从 worker 内部再调 `parallelFor` 时不会出现"所有 worker 互相等对方"的死锁。

### 4) 类索引：把"一个 jar 一个任务"改成"一个类一个任务"

`ModClassIndex` 把**所有 jar 的所有 `.class` 条目拉平成一张一维任务表**再并行处理。
模组包体积分布极不均（300MB 的大包和 20KB 的小包），按 jar 分配会让大包成为长尾；
按条目分配能让每个核都均匀吃满，而这正是上一条"无锁定位读"才能支撑的做法。

第二重优化：只有确实会声明 `@Mod` 的端（Forge / NeoForge）才需要扫注解，
纯 Fabric 包直接走"只取类名"的快路径，省掉整个类结构的解析。

### 5) 分层并行加载：让"顺序正确"和"能并行"不再矛盾

依赖解析产出**分层拓扑序**（`ModGraph`）：同一层内的模组之间没有任何依赖或被依赖关系，
可以完全并行；层与层之间严格有序。这样既有确定性的加载顺序，又能吃满多核。
循环依赖会被检测出来并给出可读的环路文本，而不是含糊地卡住。

### 6) 细节上的几处"少做一次重复劳动"

* 目录扫描先并行把文件大小缓存住再排序 —— 反面教材是 `sort(comparingLong(Files::size))`，
  那会在比较器里反复做系统调用，文件一多就变成 O(n log n) 次 IO；
* 大包优先处理（LPT 调度思想），缩短整体长尾；
* 未命中的类在做字节码变换时**直接返回原数组**（引用相等），一条字节都不重新生成；
* 重复提示会被合并计数，避免 200 个模组把同一条提示刷 200 遍、把真正的问题淹掉；
* 类加载器注册为 parallel-capable，避免类加载器级全局锁把启动串行化。

---

## 五、自研字节码注入

`HookInjector` 在目标方法体最前面插入一段**栈中立**的代码：`[aload_0] [加载各参数] invokestatic 钩子`。
钩子返回 void，因此这段代码对操作数栈零净效果，也不碰局部变量表。

由此得到一个很漂亮的结论：**原有 StackMapTable 的每一帧内容依旧成立**，
只需平移偏移量，而不需要重算栈图 —— 这就是"不写验证器也能保证注入后的类通过 JVM 校验"的原因。

实现上踩过、也已经修掉的坑（都写进了注释，避免以后重犯）：

* StackMapTable 的**帧标签只占 1 字节**，且 `same_frame`(0~63) 与
  `same_locals_1_stack_item_frame`(64~127) 把 `offset_delta` 直接编码在标签里 ——
  偏移变大时必须升级成扩展形式（251 / 247）。现在的做法是完整解析后重新编码整张表；
* 偏移是增量编码，所以只有第一帧需要加偏移，后面自动跟随；
* 方法内的类型注解属性带绝对偏移，注入后必然失效：选择移除并明确告知，而不是留一份错误的调试信息；
* 钩子签名必须与"实际压栈序列"完全一致（实例方法要把 `this` 一起传），
  不匹配就跳过并说明，而不是生成一个运行期必然 VerifyError 的类。

**边界（明确写出来，而不是假装没有）**：

* 目前只实现 HEAD 注入点。RETURN 注入点需要在每条 return 前插入并逐条修正分支偏移，属于后续扩展；
* 构造器与静态初始化块会被跳过（那时的 `this` 还是"未初始化"状态，传给静态钩子会直接触发 VerifyError）；
* 没有实现整段数据流分析，因此不支持需要重算栈图的注入类型（`@Redirect` / `@ModifyArg` 那一类）。

原生注入格式：

```java
@FusionMixin(target = "com.example.SomeClass")
public class MyMixin {
    @FusionHead(method = "doThing", desc = "(I)V")
    public static void before(SomeClass self, int arg) { ... }
}
```

配 `fusion.mixins.json`（`{"package":"...","mixins":["MyMixin"]}`）声明入口。
用"配置文件列出类名"而不是"扫全包找注解"，是为了只解压并解析少数几个类。

---

## 七、早期加载窗口（earlywindow）与自定义

模组加载发生在游戏主窗口出现之前，这段时间玩家原本只能看黑屏。
早期窗口负责展示阶段、进度、用时 / 预计剩余与日志尾行。

### 换成你自己的早期窗口（三条路任选）

**1. SPI 自动发现（推荐，第三方模组用这种方式）**

实现 `EarlyWindowProvider`，在自己的 jar 里放：

```
META-INF/services/com.fusionml.earlywindow.EarlyWindowProvider
```

文件内容写上你的类全名即可。加载器会用 `ServiceLoader` 发现它，
并按 `priority()` 排序（数值越小越优先）。内置实现是 `swing`(100) 与 `console`(1000)，
所以把优先级设成小于 100 就能压过内置实现。

**2. 直接指定类全名**

```powershell
java -Dfusion.earlywindow=com.my.SplashWindow ...
```

该类实现 `EarlyWindow`（支持 `(EarlyWindowConfig)` 或空参构造器）或 `EarlyWindowProvider` 都行。

**3. 编程方式**

```java
new FusionRuntime.Options().earlyWindow(new MyEarlyWindow());
```

### 只改外观，不写代码

首次运行后写出配置模板：

```powershell
java -cp build\classes com.fusionml.FusionLauncher --write-ew-config
```

会在 `config/fusion-earlywindow.properties` 生成带注释的模板，可改标题、字标、窗口尺寸、
背景 / 面板 / 强调 / 文字颜色、进度样式（`BAR` / `RING` / `PERCENT`）、是否显示日志与用时、
是否置顶、完成后是否自动关闭等。命令行 `-Dfusion.ew.<键>=值` 可临时覆盖。

任何 `custom.<键>=值` 都会被原样透传给自定义实现，
于是"自定义窗口想加什么选项"完全不需要改动加载器本身。

查看当前可用实现：

```powershell
java -cp build\classes com.fusionml.FusionLauncher --list-early-windows
```

早期窗口起不来**绝不会**中断加载：任何异常都会退化为控制台实现，再退化为空实现。

---

## 八、目录结构

```
src/main/java/com/fusionml/
  FusionLauncher.java            命令行入口（报告 / 列表 / 截图 / 预览）
  runtime/
    CpuTopology.java             CPU 拓扑探测（快路径 + 时间预算）
    FusionPool.java              自研工作窃取线程池
    StageProfiler.java           分阶段计时
    FusionRuntime.java           流水线编排 + 可读报告
  util/
    FastZip.java                 自研 ZIP 读取（无锁定位读）
    FastZipWriter.java           自研 ZIP 写入
    Json.java                    自研 JSON 解析（字节直解）
    Toml.java                    自研 TOML 解析
  bytecode/
    ClassFile.java               自研 class 文件读写
    ClassScanner.java            注册用轻量扫描
    ClassEmitter.java            自研类生成器
    MethodCode.java              Code 属性与偏移平移
    HookInjector.java            栈中立字节码注入
    Descriptors.java             描述符解析
  meta/                          三端元数据读取（各自原生）
  version/                       版本号与区间（Maven 区间 + semver 比较符/通配）
  resolve/ModGraph.java          依赖解析 + 分层拓扑 + 环路检测
  loader/                        类索引、类加载器、变换管线
  platform/                      三端原生适配器
  mixin/                         自研注入格式与扫描器
  hud/                           模组列表模型 + 离线列表渲染后端（AWT）
  earlywindow/                   早期窗口接口 + 实现 + SPI + 配置
```

---

## 九、目前已验证 / 尚未验证

**先说清楚这一节的性质**：下面每一条都对应一次真实运行或一次离线自检，
"已验证"意味着有日志/输出可以复现，"未验证"意味着**一次都没跑过**。
不把"没测过"写成"已完成"。

### 已验证（有日志或自检输出为证）

* 自研 ZIP 读写往返一致（STORED / DEFLATE / UTF-8 条目名）；
* 自研 JSON / TOML 解析三端真实元数据结构；
* 三端元数据并行读取、版本区间判定、分层拓扑、环路与冲突检测；
* 11083 个类的索引与并行加速（8 线程 2.17×）；
* 字节码注入：改写后的类**由 JVM 完成校验**并正常执行，钩子收到正确参数（含 long/double 槽位）；
* 早期窗口的离屏渲染产物（`build/` 下的截图是真实运行结果）；
* **真机（26.3-Fabric 0.19.5，151 个模组）跑到了模组主初始化阶段**：
  `DistantHorizons` 的 NeoForge 版 Mixin 真的在往原版类上注入
  （日志：`Apply DH mixin [...] -> [net.minecraft.client.Minecraft]`），
  证明两端 Mixin 在同一个进程里共用同一套服务；
* **跨端驱动报告真的生成了**（`<gameDir>/.fusionml/crossload-report.txt`）：
  6 个跨端包里 2 个被驱动起来，另外 4 个的失败原因逐条写明；
* **方法级拼接真的生效**：离线自检里 220 个类"保留原版字节 + 拼进对方新增方法"，
  产物通过 JVM 校验与常量池索引自检；
* `/Level` 的超类桥接**已完成大部分**：`AttachmentHolder` 的实例字段与
  `getData`/`setData`/`validateAttachmentType` 等方法已能搬进原版 `Level`
  （见第十二节）。

### 未验证 / 未完成（诚实列出）

* **还没有一次"进到主界面且不崩"的真机运行。** 目前最好的一次是
  `still-running-after-150s = True`，但那是**误导性指标** ——
  进程没退是因为早期窗口的看门线程还活着，主线程其实已经崩了
  （`Minecraft has crashed!`）。判据应该看日志里有没有 `Minecraft has crashed`，
  而不是看进程是否存活。
* **超类桥接差最后一步**（`ldc` 升格 `ldc_w` 与偏移重算），因此 `Level` 这类
  "超类被换 + 又是宿主 Mixin 目标"的冲突还没有真正解决。
* **`-7` 退出码**：有一次游戏只跑了 8 秒就退出、没有崩溃报告。
  原因**已查明**（崩溃栈写进了 `build/test.err`，而当时只看了 `build/test.out`）：
  `c2me` 的 Mixin 注入失败 → `SharedConstants.<clinit>` 崩。
  教训记在第十三节。
* **`ModList` / `LoadingModList` 目前是空表**：对方模组读
  `ModList.get().isLoaded("x")` 会得到 `false`。要"原生"就得用对方官方 API
  （`ModFile` / `ModInfo`）把我们驱动的跨端模组登记进去。
* **Forge 端从未实测**：本机没有 26.3 的 Forge；
  `PatchedBase.locate` 的 forge 分支目前复用了 NeoForge 的路径（**这行是错的**），
  需要按 Forge 自己的产物规律重写。
* **1.21.1 / 1.20.1 一次都没跑过**（见第十四节的适配评估）。
* **服务端一次都没回归过**（见第十五节）。
* **安装器**本轮未回归。

---

## 十、设计立场小结

1. **不假装**。跑不起来的路径会明确说明"当前环境缺什么"，而不是抛一个看不懂的异常或静默跳过。
2. **用数据说话**。CPU 并行度、分块大小、缓存相关的取舍都基于实测拓扑与阶段耗时，而不是拍脑袋。
3. **正确性优先于聪明**。例如注入器的栈中立约束，牺牲了一部分注入能力，换来"改完的类一定合法"。
4. **可验证**。早期窗口这类"看得见"的东西，专门做了离屏渲染路径，让它在没有游戏的环境里也能被截图核对。
5. **取舍要写出来**。跨端合并里有几处是"两边都要、Java 只允许一个"，我们选了一边并把
   牺牲记进日志（`结构性冲突` / `注入点保护`），而不是假装解决了。

---

## 十一、跨端模组的入口点驱动（最新一轮，状态如实）

目标：一个整合包里同时放 Fabric 与 NeoForge 模组时，**同一个进程**里两端的模组都要真的跑起来。
这一轮的实现与实测结论分开写，不混在一起。

### 已验证（有日志为证）

* **借对方平台的运行时**：从本机同版本的另一端实例里解析出 `neoforge` / `fml(loader)` /
  `bus` / `typetools` / `maven-artifact` 这几个包（`ForeignRuntime`），
  在驱动那一刻登记进宿主类路径（**不能更早**，见下）。
* **`@Mod` 入口点真的被构造**：真实 NeoForge 格式包（探针模组）在 Fabric 宿主里
  `[FusionML/跨端探针] @Mod 构造器被调用：fusionbridgeprobe / 1.0.0`；
  同一次运行里 6 个跨端包中有 2 个被驱动起来（另一个是真实的第三方模组）。
* **注册表事件按内置注册表逐个发出**：96 个内置注册表各发一次 `RegisterEvent`（用对方平台的
  `GameData.unfreezeData/freezeData` 打开与关闭写入窗口），失败会逐个报出原因。
* **生命周期事件投递**：`FMLCommonSetupEvent` / `FMLClientSetupEvent` / `FMLLoadCompleteEvent`
  都按模组逐个投递；探针的 `@EventBusSubscriber` 静态订阅类确实收到了事件。
* **跨端 Mixin 与入口点在同一次运行里同时生效**（前者是本轮之前就有的能力）。
* **原版密封类型的必要修补**：对方平台的 API 类里有"靠对方给原版打补丁加 `permits` 才能存在"
  的实现者（例如 `DeferredHolder implements Holder`）。宿主上没有那份补丁，
  加载会以 `IncompatibleClassChangeError` 失败。我们按"本机实际装了哪些跨端包"算出需要解除
  密封的原版类型（当前是 `Holder` 一个），在类加载时解除（自研字节码改写，无 ASM）。

### 未完成（卡点已定位到具体原因）

* **Forge 端未实测**：本机没有 26.3 的 Forge 实例，符号表已经写好但没跑过。
* **对方平台的 `ModList` / `FMLLoader` 全局状态已经建立**（`FMLLoader` 实例、`FMLLoader.current`、
  `LoadingModList`、`ModList`），但它们目前是**空表** —— 所以对方模组读
  `ModList.get().isLoaded("xxx")` 会得到 `false`。要"原生"就得用对方官方 API
  （`ModFile` / `ModInfo` / `LoadingModList.of`）把被驱动的跨端模组登记进去。这是后续项。

### 补丁原版基座：合并规则（2026-10-14 下午那一轮，全部有实测证据）

把对方平台那份 `minecraft-client-patched` 合并进游戏包时，**"整类替换"是行不通的**，
但也**不能一刀切"保留原版"** —— 判据是逐类算出来的：

| 情况 | 处理 | 实测依据 |
| --- | --- | --- |
| 对方**改过原版已有的成员**（删/改方法或字段签名） | 用对方的字节 | 对方模组硬依赖新形状 |
| 对方**只新增成员**，且**接口、超类都没变** | 保留原版字节 | 见下 |
| 对方新增了**带抽象方法**的接口（如 `Registry` → `IRegistryExtension`，11 个抽象方法） | **必须**用对方的字节 | 不给就抛 `LambdaConversionException: Invalid receiver type interface Registry; not a subtype of ... IRegistryExtension` |
| 对方新增了**纯 default** 的接口（如 `DataComponentMap$Builder` → `IDataComponentMapBuilderExtensions`，0 抽象方法） | **必须**保留原版字节 | 否则宿主平台的 `@Overwrite` Mixin 崩：`InvalidMixinException: ... cannot overwrite method ... because @Overwrite is required`（`fabric-item-api-v1.mixins.json` 的 `overwrites.requireAnnotations=true`） |

最后两条是**互相冲突的需求**，靠"新增的接口里有没有抽象方法"这一个判据同时满足。
产物实测（`MergedCheck` 工具，按字节比对合并包到底等于哪一份）：

```
== net/minecraft/core/component/DataComponentMap$Builder：原版（保留了原版字节）
== net/minecraft/core/Registry：对方平台补丁（整类替换）
== net/minecraft/core/MappedRegistry：对方平台补丁（整类替换）
```

273 个候选类里最终**整类替换 11 个、保留原版 262 个**，离线自检全部通过。

### 方法级拼接：两个签名都留下

对方平台有时不是"改原版的方法体"，而是**改签名**。实测：

```
原版 RegistryDataLoader.load(LoaderFactory, List, List, Executor)        ← 宿主模组的 Mixin 按这个找注入点
补丁 RegistryDataLoader.load(LoaderFactory, List, List, Executor, Z)     ← NeoForge 加了第 5 个 boolean
```

这时"整类换"与"整类不换"**都不行**：

* 整类换 → 宿主模组的 `@ModifyArg` 按 4 参数签名找，永远找不到，崩在
  `Critical injection failure: ... could not find any targets matching 'load(...)'`；
* 整类不换 → 对方模组调不到那个 5 参数的重载。

唯一两边都成立的做法是**两个签名都留**：以原版字节为基，把对方新增的重载拼进去。
实现是自己写的（**没有引入 ASM**）：

* `bytecode/ConstantPoolTransplant` —— 常量池移植。把方法的字节码改写成"在目标类的常量池下成立"：
  逐项导入它用到的常量（类/方法/字段/字符串/数值）、重写索引、
  处理 `Code` 属性（含异常表与 `tableswitch`/`lookupswitch`/`wide`/`invokedynamic` 等变长指令）；
* `ClassFile` —— 常量池增长、原始项搬运、方法追加、跨类方法导入。

**安全立场写进了代码**：字节码里"哪些位置是常量池索引"取决于当前指令，
所以扫描是保守的 —— 遇到任何解释不了的指令就**整体放弃**这次搬运，返回 `null`，
而不是猜。因为"少拼一个方法"可控、可记录、可诊断，而"拼出校验不过的类"会让整局起不来，
且报错位置（`VerifyError`/`ClassFormatError`）离真正的原因极远。

**验证方式（不启动游戏）**：`SpliceProbe` 把拼出来的类交给 JVM 真的去 `defineClass`，
跑字节码校验器。实测结果：

```
== net/minecraft/resources/RegistryDataLoader
   原版方法 23 个，补丁方法 26 个
   尝试拼接 1 个方法
   拼接后字节 26302（原版 25659）
   拼接后方法 24 个
   JVM 校验：通过（已定义 net.minecraft.resources.RegistryDataLoader）
```

**这一步踩到的坑**：拼接一开始一直失败，诊断信息只有"属性 Signature 处理失败" ——
`Signature`（泛型签名）属性里有一个指向 UTF8 的索引，结构简单到不会读错，
支持它之后立刻就通了。**把"放弃的原因"记下来**（而不是只报"失败"），
是这次能从"拼接失败"快速走到"JVM 校验通过"的关键。

### 常量池移植踩到的三个坑（都会产出"JVM 认、ASM 不认"的坏类）

这三处的共同点是：**症状离原因极远**。JVM 的 `defineClass` 与 `javap` 都能通过，
只有宿主平台的 ASM 变换器会在启动期崩：

```
ArrayIndexOutOfBoundsException: Index 940 out of bounds for length 841
  at org.objectweb.asm.ClassReader.readCode
  at net.fabricmc.loader.impl.transformer.FabricTransformer.transform
```

1. **`long`/`double` 占两个常量池槽位**。索引算术写成 `cpSlotCount + extraCp.size()`
   （项数）而不是槽位数，只要导入的常量大里有 `long`/`double`，后面所有索引就整体错位。
   修法：显式维护"下一个可用槽位"，`long`/`double` 记 2。
2. **带常量池索引的指令被当成"定长指令"跳过了**。
   `getstatic`/`invokevirtual`/`invokespecial`/`invokestatic`/`new`/`checkcast`/`instanceof`
   长度虽然是固定的 3 字节，但操作数是**常量池索引**，必须在扫描时改写。
   一开始把它们登记成"定长 3"，于是扫描直接跳过、索引原封不动 ——
   拼出来的类里索引还是源类的值（实测越界 1033/1036）。
3. **常量池项要按层级解码**。`Methodref` 的结构是
   `(class_index, name_and_type_index)`，而 `class_index` 指向的是 **Class 项**、
   Class 项再指向 UTF8。写成 `utf8(class_index)` 拿到的是 null（因为 Class 项的
   `utf8` 字段为空），整项导入失败。报错看起来像"索引 1033 越界"，其实是解码层级错了。

**检查手段**：`SpliceProbe` 会逐条扫拼接后每个方法的字节码，
把**所有落在常量池范围外的索引**报出来（`索引检查：全部在范围内`）。
这条检查比"会不会抛异常"有效得多 —— 上面三个 bug 都是靠它定位的。



这是本轮代价最大的一个 bug，写下来免得重踩。保护名单（宿主模组的 Mixin 目标，
本整合包 783 个）原本写成了：

```java
if (all && keepVanilla.contains(internal)) { continue; }   // ✗ 错
```

`all` 表示"整份替换模式"。而默认走的是**收窄范围**
（`net/minecraft/core/` + `net/minecraft/resources/`），`all == false` ——
于是这道保护**一次都没生效过**。

它偏偏又最难发现，因为默认范围里的 `net/minecraft/resources/` 正好覆盖了出事的那批类：

```
Critical injection failure: @ModifyArg annotation on supplyAsync could not find any targets
  matching 'load(Lnet/minecraft/resources/RegistryDataLoader$LoaderFactory;...)' in
  net/minecraft/resources/RegistryDataLoader. No refMap loaded.
  （fabric-registry-sync-v0.mixins.json:RegistryDataLoaderMixin）
```

`KeepProbe` 工具直接把这个矛盾摆了出来：`RegistryDataLoader 在保留名单里=true`，
而合并产物里它却是对方的字节。

正确语义：**无论整份还是收窄，模组的注入目标都必须保留原版字节** ——
Mixin 的注入点按指令序列匹配，对方那份补丁是反编译再编译的，指令序列并不逐条一致。

### 黑名单：任何"提前加载"的代码都必须先查它

模组平台的 Mixin 有一条硬规则：**目标类一旦被加载，就不能再注入**。
而 `Class.forName(name, false, cl)` 里的 `false` **只关掉初始化，不阻止加载** ——
所以哪怕只是"打印一行诊断"，也可能替模组抢跑，报出离原因极远的：

```
MixinTargetAlreadyLoadedException: Critical problem:
  yumi_mc_core.neoforge.mixins.json:DeferredRegisterMixin from mod yumi_mc_core
  target net.neoforged.neoforge.registries.DeferredRegister was loaded too early.
  → MixinApplyError ... FAILED during PREPARE
  → 连带炸掉 axiom 的 preLaunch 入口点 → A mod crashed on startup!
```

做法：启动侧扫出**全部模组的 Mixin 目标**（本整合包 846 个），写成黑名单，
诊断与预热一律先查它。三个必须记住的点：

1. **要扫模组全集，不能只扫 `mods` 目录**。本整合包 `mods` 里只有 37 个 jar，
   而游戏日志写着 `Loading 151 mods` —— 差额在宿主平台的嵌套包缓存
   `<gameDir>/.fabric/processedMods`（112 个）。肇事模组 `yumi_mc_core` 就住在那里。
   只看 `mods` 会把最大的一批模组排除在保护之外。
2. **黑名单要给所有平台格式的包扫描，不只看 `fabric.mod.json`**。`yumi_mc_core` 是双格式包
   （同时有 `fabric.mod.json` 和 `META-INF/neoforge.mods.toml`），它注入的是 NeoForge 的类。
3. **名单要走文件，不能塞进命令行**。846 个类名拼成一个 `-D` 参数约 45KB，
   超过 Windows 的命令行上限，进程会以一句毫不相干的
   `CreateProcess error=206, 文件名或扩展名太长` 起不来 ——
   而且日志里既没崩也没报错，看起来像"问题自己好了"。


### 时机上的三个坑（都是实测踩出来的，写下来免得重踩）

1. **不能把对方的运行时放在类路径最早处**。宿主要准备 Mixin 配置时，跨端模组里的
   Mixin 插件/Mixin 类会"找得到 FML 的类、于是真的去用"，抛
   `There is no current FML Loader`，结果是**启动崩溃**（找不到类时反而只记一笔警告）。
   → 运行时包推迟到驱动那一刻才登记。
2. **不能在 `Bootstrap#bootStrap` 头部构造模组**：`bootstrapped` 还没置位，`DeferredRegister`
   直接撞 `Not bootstrapped (called from registry ...)`。
3. **也不能在 `BuiltInRegistries#bootStrap` 头部**：注册表对象建好了但 Holder 还没绑定，
   `DeferredItem` 构造时会撞 `Trying to access unbound value`。
   → 最终挂在 `Minecraft#run` / `MinecraftServer#runServer` 的入口（原版引导已走完）。

### 离线自检（不需要启动游戏）

```powershell
# 驱动链路的秒级验证：借用真实运行时 + 真实探针模组包
java -cp "build\classes;build\test-classes" com.fusionml.crossload.CrossLoadSelfTest
```

它把游戏类（版本 jar）与对方平台的运行时放进同一个类加载器，按游戏里的同样顺序跑一遍，
所以"能不能构造、能不能发事件、条目有没有进注册表"都能在几秒内得到结论。

---

## 十二、超类桥接：让原版类"长出"对方超类的能力

### 问题：两个都致命的选项

对方平台有些补丁是靠**换超类**实现的，而宿主模组的 Mixin 又打在同一批类上。
实测各撞过一次，两个方向都是硬崩：

| 选择 | 后果 | 实测证据 |
| --- | --- | --- |
| 用对方的字节 | 宿主 Mixin 注入点匹配 0 个目标 | `c2me` 的 `MixinWorld` 打在 `Level` 上 → `Scanned 0 target(s)` → `SharedConstants.<clinit>` 崩 → 进程退出 |
| 保留原版字节 | 对方按新超类使用它，校验期失败 | `RegistryBuilder.create()` 把 `MappedRegistry` 当 `BaseMappedRegistry` 用 → `VerifyError: ... not assignable to ...` |

### 第三条路：组合代替继承

让**原版类自己长出**对方超类的能力。以 `Level` 为例，实测对方的超类结构非常简单：

```
AttachmentHolder（超类 java/lang/Object）
  实例字段：1 个（attachments : Map）
  方法：13 个（getData / setData / hasAttachments / serializeAttachments …）
  接口：IAttachmentHolder
  构造器：只多做一件事 —— 把 attachments 置 null
```

所以桥接要做的是：

1. 把 `AttachmentHolder` 的**实例字段**搬进原版 `Level`；
2. 把它的**方法**搬进去（`NameAndType` 与常量池一起移植）；
3. 加上它实现的**接口**；
4. 把 `Level.<init>` 里那条 `invokespecial AttachmentHolder.<init>()V`
   换成 `Object.<init>()V`，并补上"把 `attachments` 置 null"的指令。

**关键点**：除了 `<init>`，其他方法**一个字节都不动** ——
这正是它能与宿主模组共存的原因（Mixin 的注入点按原版方法匹配，那些方法原封不动）。

### 已完成 / 未完成（如实）

**已经打通的**（每一步都靠诊断输出确认）：

| 环节 | 状态 |
| --- | --- |
| 读对方超类（它在 NeoForge 运行时包里，不在补丁包里） | 已通 |
| 搬实例字段（跳过 static —— 它们的初始化在 `<clinit>` 里） | 已通 |
| 丢掉类型注解 / 普通注解属性（对运行无影响） | 已通 |
| 搬方法：`MethodHandle` + **`BootstrapMethods` 合并**（字符串拼接用） | 已通 |
| `StackMapTable` 改写（帧里的类型要**主动导入**，不能"没映射就放弃"） | 已通 |
| 改 `<init>` 的超类构造器调用 + 补字段初始化 | 已写 |
| **`ldc` 升格 `ldc_w`** | **未完成** |

**最后这一项为什么必须做**：`ldc` 是**单字节**索引（上限 255），
而原版 `Level` 的常量池已有 **1595** 项 —— 任何新导入的常量下标都必然 > 255，
`ldc` 装不下。升格成 `ldc_w`（3 字节）只多一个字节，但会**移动后面所有指令的偏移**，
于是跳转偏移、异常表、`StackMapTable` 都要跟着重算。

**这是"小型汇编器 + 偏移重算"，不难但琐碎**，而且算错一处就是 `VerifyError`。
好在 `BridgeProbe` 能在离线几秒内让 JVM 真的校验一遍产物，不用每次跑 150 秒真机。

### 这个冲突不止一个类

判据输出显示还有 9 个类属于同一形态（超类被换 + 是宿主 Mixin 目标）：

```
EntityRenderState、MapRenderState、MapRenderState$MapDecorationRenderState、
LevelRenderState、PlayerRenderState …
```

`bridgeSuperclass` 是**通用函数**（不是给 `Level` 写死的），所以桥接一旦做完，
这些类可以走同一套逻辑。但每个类的超类结构不同，可能需要逐个调优；
"对方超类不是直接继承 `Object`"的情况目前**明确拒绝**（条件写在函数里）。

---

## 十三、调试方法上的两个教训（都花掉了一整轮）

### 1. 崩溃栈在 `build\test.err`，不在 `build\test.out`

`test.ps1` 把 stdout 与 stderr **分开重定向**：

```
build\test.out   ← 游戏日志
build\test.err   ← JVM 的 stderr（崩溃栈在这里）
```

有一次游戏只跑了 8 秒、退出码 `-7`、没有崩溃报告，我一直在 `test.out` 里找不到原因。
**实际原因写在 `test.err` 里**：`c2me` 的 Mixin 注入失败 → `SharedConstants.<clinit>` 崩。

**教训**：排查启动失败时，`test.err` **必须一起看**。

### 2. `still-running-after-Ns = True` 是个误导性指标

有一次它报 `True`，看起来"游戏起来了"。但那只是**进程没退** ——
主线程其实已经崩了（`Minecraft has crashed!`），活下来的是早期窗口的看门线程。

**正确判据**：看日志里有没有 `Minecraft has crashed` / `Game crashed` / `VerifyError`，
而不是看进程是否存活。

### 3. 分支追踪日志：一次定位"判据两处语义不一致"

合并里有多个判据，曾经出现"离线判据说该换、产物却是原版"的矛盾。
加了一行分支追踪：

```
· 分支追踪 net/minecraft/core/MappedRegistry：
    Mixin目标=true，超类变=true，预处理保留=false，结构性冲突=true
```

一眼看出：预扫判定"不保留"，但写入循环开头有个**上一轮遗留**的
`if (STRUCTURAL_CONFLICTS.contains(...)) 保留原版` 早退出，抢先保留了原版。
**两个阶段各有一套语义** —— 这类 bug 靠读代码很难发现，把它打出来就立刻清楚。

---

## 十四、多版本适配评估：1.21.1 / 1.20.1

### 结论先行

| 目标版本 | 本机实例 | 与 26.3 的关键差异 | 适配判断 |
| --- | --- | --- | --- |
| **26.3** | 有（151 模组） | — | 正在做 |
| **1.21.1** | 有（Fabric 0.19.5 + NeoForge 21.1.x） | **混淆** | **需要新增 remap 层** |
| **1.20.1** | 有（Fabric 0.19.3，115 模组） | 混淆 + Forge 主导 | 同上，且要处理 Forge 分支 |

### 根本差异：26.3 不混淆，1.21.1/1.20.1 混淆

**实测数据**（`NamespaceProbe`，本机全部相关版本）：

| 版本 | 判定 | 类总数 | `net/minecraft/` 下 | 顶层裸类 |
| --- | --- | --- | --- | --- |
| **26.3-Fabric 0.19.5** | ✅ 不混淆 | 11383 | **10737** | 0 |
| **26.3-NeoForge 26.3.0.39-beta** | ✅ 不混淆 | 11383 | 10737 | 0 |
| **26.1.2-Fabric** | ✅ 不混淆 | 10682 | 10208 | 0 |
| **1.21.1-Fabric 0.19.5** | ❌ 混淆 | 8269 | 26 | **8220** |
| **1.21.1-NeoForge 21.1.236** | ❌ 混淆 | 8269 | 26 | 8220 |
| **1.20.1-Fabric 0.19.3** | ❌ 混淆 | 7436 | 25 | **7388** |

这张表就是多版本适配的**路线图**：`26.x` 一路都是真名（因为 Mojang 从 26.x 起不再混淆），
`1.21.1`/`1.20.1` 是混淆的（顶层就是 `chn.class`、`cho.class` 这种名字）。

### 已实现的安全闸（`PatchedBase.isDeobfuscated`）

合并前先探测宿主游戏包的命名空间；**混淆就明确拒绝合并**并在日志里说明原因：

```
跨端补丁基座：宿主游戏包命名空间 —— **混淆**：类 8269 个，其中 net/minecraft/ 下 26 个、顶层裸类 8220 个
跨端补丁基座：这个版本的游戏包是**混淆**的，而合并是按内部名搬运的（26.3 不混淆所以成立）。
             本次**不做合并**，跨端注册将不可用 ——
             要在该版本上工作，需要先补上 remap 层（见 README 第十四节）。
```

**为什么要有这道闸**：合并是"按内部名把对方补丁类搬进游戏包"，混淆时按名字
**一个都匹配不上**。与其搬出一份坏包、让游戏崩在启动期（症状离原因极远），
不如在这里就说清楚"做不到、缺什么"。

### 同一批类，两套名字

| 概念 | 26.3（Mojang 名） | 1.21.1（Yarn/intermediary 名） |
| --- | --- | --- |
| 注册表 | `MappedRegistry` | `SimpleRegistry` |
| 注册表加载 | `RegistryDataLoader` | `RegistryLoader` |
| ID 表 | `IdMap` | `IdList` |
| 资源键 | `ResourceLocation` | `Identifier` |

证据：同一款 `fabric-registry-sync-v0`，26.3 的配置里写的是
`MappedRegistryMixin` / `RegistryDataLoaderMixin`，1.21.1 里写的是
`SimpleRegistryMixin` / `RegistryLoaderMixin`。

### 这对我们意味着什么

`PatchedBase` 现在的做法是"**按内部名把补丁类搬进游戏包**"。在 1.21.1 上：

1. 范围前缀 `net/minecraft/` 在**混淆的游戏 jar 里匹配不到任何类**；
2. 即使从 NeoForge 的补丁产物里拿到类，那些类是按**另一套命名空间**编译的，
   而游戏运行时要的是 **intermediary 名** —— 直接搬会类名对不上，
   或者塞进一批名字冲突的类导致 `LinkageError`。

**所以 1.21.1/1.20.1 需要"先 remap 再合并"**，这是一层新能力，不是配置问题。

### 工作量（基于实测）

1. **读映射表**：Fabric 实例自带 intermediary 映射，NeoForge 侧有自己的映射产物，
   需要找到并解析；
2. **命名空间检测**：合并前先判断"游戏 jar 是混淆还是真名"，不能写死前缀
   （26.3 的判据：`net/minecraft/core/Registry.class` 是否存在）；
3. **remap 层**：按映射表改类名/字段/方法/描述符。这是 ASM 级别的活，
   而本项目**没有 ASM**（自研字节码工具）——
   要么自研 remapper，要么**允许在这一处引入现成的 remap 库**（需要拍板）；
4. **Mixin 目标比对**：`keepVanilla` 依赖"内部名相等"，混淆版下两边命名空间不同，
   必须先把模组的 `@Mixin` 目标 remap 到游戏 jar 的命名空间再比；
5. **Forge 分支**：1.20.1 主要面向 Forge，`PatchedBase.locate` 的 forge 分支
   目前是错的（复用了 NeoForge 路径），必须按 `net/minecraftforge/...` 重写。

### 建议的顺序

**先把 26.3 打通到进主界面，再动多版本** —— 因为 remap 层会**改变合并的输入形状**，
在 26.3 还没稳定时做，会把两个变量混在一起，出问题分不清是谁的。

**需要决定的一件事**：remap 是自研，还是允许引入一个现成库（如 `tiny-remapper`）？
这直接决定工作量是"几天"还是"几小时"。

---

## 十五、服务端支持

### 已验证（本轮实测）

**启动计划（`--server --mc-check`，退出码 0）**：

```
版本解析    : 26.3-Fabric 0.19.5（继承链 26.3-Fabric 0.19.5，Java 25，库 121 条）
依赖库      : 本地库齐全（81 个）
环境提供    : minecraft 26.3、java 25.0.4.1、fabricloader 0.19.5、fabric 0.19.5、
              forge 1.20.1-47.4.6、neoforge 26.3.0.39-beta
真实主类    : net.fabricmc.loader.impl.launch.knot.KnotClient
实际主类    : net.fabricmc.loader.impl.launch.knot.KnotServer（已按侧别换成平台自己的另一个启动器）
```

**这一轮修掉的一个真问题**：以前无论客户端还是服务端，主类都取版本定义里写的那一个
（`KnotClient`）。而版本定义里永远是客户端启动器 —— 服务端要用 **同平台自己的服务端启动器**。
这不算"顶替平台"（`KnotServer` 同样是 Fabric 官方的启动类），只是**把平台自己的两个入口选对**。

`KnotServer` 的存在性已在 `fabric-loader-0.19.5.jar` 里确认（不是猜的类名）。

### 三处差异（代码里已经处理好）

1. **主类**：客户端 `KnotClient` / 服务端 `KnotServer`（同一平台的官方入口）；
2. **挂载点**：客户端挂 `Minecraft#run`，服务端挂 `MinecraftServer#runServer`
   （两者都在原版引导完成之后，见第十一节的"时机三坑"）；
3. **运行侧开关**：`-Dfusion.client=false` 时服务端不做任何客户端相关的事。

日志写在 `build/srv*.out`。

### 诚实标注

* **已验证**：服务端启动计划能完整生成（`--server --mc-check` 退出码 0），
  且主类确实切换成了 `KnotServer`；
* **未验证**：**没有真正启动过服务端进程**（没有独立的 26.3 服务端 jar，
  只用合并 jar 验证到"计划正确"这一步）。真正要验证的是：
  三端模组在无图形环境下的注册与生命周期事件是否都发出。

---

## 十七、分版本打包与安装器的双重验证（2026-10-16）

### 17.1 五个版本，各自一个 jar（已验证）

支持的版本之间有几项设定**不可能一样** —— 需要的 Java 主版本、以及游戏包是不是混淆的。
把这些写死在代码里就是"要么全对、要么全错"，所以改成**每个版本打一份 jar**，
jar 清单里带上这份"出厂设定"：

| 产物 | Java | 平台 | 命名空间 |
| --- | --- | --- | --- |
| `fusion-ml-26.3.jar` | 25 | Fabric / NeoForge | 不混淆 |
| `fusion-ml-26.2.jar` | 25 | Fabric / NeoForge | 不混淆 |
| `fusion-ml-26.1.2.jar` | 25 | Fabric | 不混淆 |
| `fusion-ml-1.21.1.jar` | 21 | Fabric / NeoForge | **混淆** |
| `fusion-ml-1.20.1.jar` | 17 | Fabric / Forge | **混淆** |

每个版本还各有一个**自带对应加载器的安装器**
（`fusion-installer-<版本>.jar`，里面嵌的是那一版的 loader，不是通用版）。

**怎么验证它是真的**：让 jar 自己报出自己的出厂设定 ——

```powershell
java -cp "build\fusion-ml-1.20.1.jar" com.fusionml.FusionVersions
# → 1.20.1
#   Java 17 / Fabric / Forge / 混淆
```

实测五个 jar **各自报出的目标都与文件一致**。
这一条值得单独测，是因为清单是构建脚本拼的字符串，拼错只会表现为
"读不到、悄悄回退到通用默认值"，而不会报错 —— 加载器照样能跑，只是用了错的 Java 要求。

通用 `fusion-ml.jar` 仍然保留（不绑定版本，行为与以前一致）。

**出厂设定只是"默认值"**，任何一条都能被显式 `-D` 覆盖；
判断"能不能装"的仍然是那些实测过的判据，本机制不改变任何行为逻辑。

### 17.2 安装器：先选目录，再选版本（已验证）

原来"继承原版版本"是个自由文本框，用户得把版本目录名**一字不差**敲进去 ——
敲错一个空格就找不到版本，而错误很晚才出现。

现在改成两步：

1. **选游戏目录** → 自动体检，把探测到的版本填进下拉框；
2. **从下拉框里选目标版本**（默认选中"用户当前所在的那个版本"）。

默认值这一条是踩出来的：用户常常直接把**某一个版本目录**选进来（整合包就是这一层），
此时版本列表取自它的同级目录，同级里可能有几十个版本。
默认取列表第一个，本机恰好有个叫 `1` 的目录，于是给出
`第二道：版本校验 ✘ 在 …\26.3-Fabric 0.19.5 里找不到版本 1` ——
**结论正确但默认值很蠢**（用户明明就在那个版本目录里）。现在优先选中目录自己。

### 17.3 双重验证（已验证）

两道验证各自给出**看得见的结论**，而不是"写下去试试、失败很晚才知道"：

**第一道：目录校验** —— 这到底是不是一个 Minecraft 游戏目录

```
第一道：目录校验：✔ 通过
   ✔ 游戏目录 —— D:\我的世界\服务器\.minecraft\versions\26.3-Fabric 0.19.5
   ✔ 目录形态 —— 标准的 .minecraft 布局（有 versions/）
   ✔ 模组目录 —— mods/ 里有 37 个模组包
   ✔ 写入权限 —— 可写
```

**第二道：版本校验** —— 这个版本能不能被本加载器接管

```
第二道：版本校验：✔ 通过          （26.3）
   ✔ 目标版本 / ✔ 版本目录 / ✔ 版本完整性 / ✔ 继承链
   ✔ 命名空间 —— 不混淆（真名）—— 合并可以按内部名搬运，这个版本能装
```

**反例（这正是它存在的意义）**：

```
第二道：版本校验：✘ 不通过        （1.20.1，115 个模组）
   ✔ 目标版本 —— 1.20.1-Fabric 0.19.3
   ✔ 版本目录 / ✔ 版本完整性
   ✘ 命名空间 —— **混淆** —— 合并是按内部名搬运的，混淆版本一个都匹配不上。
                 这个版本需要先补 remap 层（见第十四节），本次安装会装上但跨端功能不可用
```

**目录对了不代表版本能装** —— 这两件事必须分开报，用户才知道问题出在哪一层。

双重验证的结论也能在**不打开界面**的情况下看：

```powershell
java -cp "build\classes;build\test-classes" com.fusionml.crossload.VerifyProbe
```

实测本机全部实例：`26.x` 一族**通过**，`1.21.1`/`1.20.1` **不通过**（混淆）——
与已知事实一致。

### 17.4 本轮未做的

* **remap 层还没实现**。按对照项目 Forbric 的做法，它用 `net.fabricmc:mapping-io` +
  `tiny-remapper`，而本项目要求"不联网、不用 Gradle、零第三方依赖"，
  已确定**自研 remapper（只读映射表，不引库）**，属于下一轮。
* **26.2 未实测**（本机没有实例）。它与 26.3 同族（26.x 不混淆、同一套合并规则），
  jar 能构建、安装器能识别，但**没有任何一次真机运行**。

---

## 十六、离线验证工具一览

这些工具是本项目"快速定位字节码问题"的关键，都在 `src/test/java/com/fusionml/crossload/`：

| 工具 | 用途 |
| --- | --- |
| `CrossLoadSelfTest` | 秒级跑完整驱动链（不启动游戏） |
| `SpliceProbe` | 验证"方法级拼接"产物：**让 JVM 真的 `defineClass`**，并检查常量池索引越界与类型错用 |
| `BridgeProbe` | 验证"超类桥接"产物（同上） |
| `WhyReplaced` | 打印某个类走了哪条合并分支、每个判据的取值 |
| `MergedCheck` | 按**字节比对**合并产物里某个类到底用了谁的字节（原版 / 对方 / 改写） |
| `KeepProbe` | 查某个类是否在"宿主 Mixin 目标保护名单"里 |
| `ClassDiff` / `MethodCompare` | 比较两份 jar 里同一个类/方法的差异 |
| `ClassShape` | 打印一个类的完整形状（字段、方法、接口） |
| `InterfaceShape` | 判断一个接口是不是纯 default（决定"要不要整类换"） |
| `TargetProbe` | 扫真实模组目录，报告黑名单规模与命令行容量 |
| `NamespaceProbe` | 探测本机各版本的游戏包是"真名"还是"混淆"（多版本适配的第一道门） |
| `LibraryPeek` | 在整份 libraries 里搜字符串（"哪句话是谁说的"） |

用法示例：

```powershell
java -cp "build\classes;build\test-classes" com.fusionml.crossload.SpliceProbe net/minecraft/resources/RegistryDataLoader
java -cp "build\classes;build\test-classes" com.fusionml.crossload.MergedCheck latest net/minecraft/core/Registry
java -cp "build\classes;build\test-classes" com.fusionml.crossload.WhyReplaced net/minecraft/world/level/Level
java -cp "build\classes;build\test-classes" com.fusionml.crossload.BridgeProbe net/minecraft/world/level/Level
java -cp "build\classes;build\test-classes" com.fusionml.crossload.NamespaceProbe
```

### 一个通用规律：让"放弃的原因"可读

上面这些工具里，凡是会"放弃某个操作"的地方，都会把**原因**记下来并打出来。
这一条不是洁癖，是几次踩坑换来的：

* 拼接失败时只说"失败"，分不清是"本来没得拼"还是"扫描能力不足" ——
  补上原因之后，`属性 Signature 处理失败` 一行就定位了；
* 桥接失败时只说"字段搬不进来"，看不出是角色反了、属性不支持、还是索引算错 ——
  补上原因之后，一步步从"读不到超类"推进到"ldc 需要升格"。

**"失败"是一个结论，"为什么失败"才是可执行的下一步。**



