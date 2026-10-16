# FusionML 服务端支持 —— 交付说明（server-dev / task-1）

> **状态：已完成并经真机验证。** 服务端已能完整启动（`Done (6.229s)!`）、监听端口
> （`Starting Minecraft server on *:25565`）、持续 tick，且跨端入口点钩子已确认生效
> （`挂载点已进入（DedicatedServer）`）。未验证项见第 6 节，如实保留、未美化。

> 本文档只覆盖 **server-dev 负责的那条线**：命令行拉取服务端 jar、服务端启动脚本生成、
> 相关 CLI 与 agent 参数通道。跨端注入器（`HookInjector`）与 `Preflight` 的派发改造由 Lead 负责，
> 不在本文档的改动清单内（但其验证结果会如实记录在"验证状态"一节）。

> ⚠️ **本文档冻结**：Lead 即将执行全项目删注释，`src/` 下不再改动，本文档内容即最终版。

---

## 0. 一句话结论

**对照 Fabric 安装器的做法，"命令行自己拉取服务端并跑起来"这条链路已打通并实测通过。**

- 拉取：两条来源（本地版本 json 优先 / 官方清单兜底），SHA1 校验，幂等复用
- 启动：生成 Fabric 形态脚本 —— **仍由官方 `server.jar`（bundler）启动，加载器以 javaagent 挂载**，
  不自研 main
- 验证：**原版服务端**路径完整跑通（启动 + 端口 + tick + 钩子生效）

---

## 1. 代码改动清单

四个文件，全部在授权写域内（`com/fusionml/mc/`、`FusionLauncher.java`、`launch/FusionAgent.java`）。

### 1.1 `src/main/java/com/fusionml/mc/ServerFetch.java`（**新增**，约 470 行）

命令行拉取服务端 jar + 生成 Fabric 形态启动脚本。公开 API：

| 成员 | 作用 |
|---|---|
| `MANIFEST_URL` | `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` |
| `SERVER_JAR_NAME` | `server.jar`（沿用官方习惯，便于复用已下过的文件） |
| `enum Source` | 地址来源：`LOCAL_VERSION_JSON` / `OFFICIAL_MANIFEST`，用于如实上报 |
| `record ServerJar` | 来源 + URL + SHA1 + 大小 + Java 要求 |
| `record Result` | 拉取结果（文件、是否新下载、耗时） |
| `resolve(...)` | 解析出下载信息（**先本地后清单**） |
| `fetch(...)` | 下载 + SHA1 校验 + 进度 |
| `writeServerLaunchers(...)` | 生成 `start-server.cmd` / `.sh`（含 4 参兼容重载） |
| `listVersions(...)` | 列出官方清单版本（供 `--list-mc-versions`） |
| `mcVersionFromId(...)` | `"26.3-Fabric 0.19.5"` → `26.3` |

**两条解析来源，优先本地**：

1. **`LOCAL_VERSION_JSON`（优先）** —— 读游戏目录里已有版本的 json，取
   `downloads.server.{url,sha1,size}`。**不发网络请求、离线可用**。
   实测本机 `26.3-Fabric 0.19.5.json` 里本来就写着：
   ```
   downloads.server = {"sha1":"33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c",
                       "size":62294556,
                       "url":"https://piston-data.mojang.com/v1/objects/33680f5f.../server.jar"}
   ```
2. **`OFFICIAL_MANIFEST`（兜底）** —— 两级解析：清单 → 版本条目 → 该版本 json → `downloads.server`。
   本地给不出答案时才发网络请求。

**下载语义**复用 `McDownloader.ensure` 的三条既有保证：已存在且 SHA1 通过就跳过、
先落 `.part` 再改名、校验不过就删掉重下。

**进度**：每 10% 报一次；总长度未知（分块传输）时每 8 MB 报一次。

**失败**：明确报错并给出下一步动作，绝不假装成功。

### 1.2 `src/main/java/com/fusionml/mc/McVersion.java`

原来只解析 `downloads.client`，现补上服务端侧字段：

- 新增 `serverUrl` / `serverSha1` / `serverSize`
- 新增 `serverMappingsUrl` / `serverMappingsSha1`（`downloads.server_mappings`，服务端插桩会用）
- 新增 `hasServerJar()` / `serverSizeText()`

### 1.3 `src/main/java/com/fusionml/launch/FusionAgent.java`

**(a) 解析 agent 参数**（`applyAgentArgs`）——`-javaagent:fusion-ml.jar=server` 的 `=server` 会
原样传给 `premain`，这是走官方 `server.jar` 启动时**唯一**能告知运行侧的通道（命令行里没有地方能写
`-Dfusion.client=false`）。逗号/分号分隔都容错；`server` → `fusion.client=false`。
**`args` 为空时什么都不做**，保证客户端既有路径不受影响。

**(b) 把加载器 jar 追加到 system classpath**（`exposeLoaderJarToSystemClassLoader`）——
用 `Instrumentation.appendToSystemClassLoaderSearch`。整段 `try/catch(Throwable)`，
失败只打一行日志（**绝不能因为这一步让 agent 挂载失败**）；用 `AtomicBoolean` 保证只调一次
（重复调用会抛 `IllegalArgumentException`）；位置是目录（开发期 `build\classes`）时跳过。

> ⚠️ 这一步**对服务端 bundler 实际无效**，原因见第 5 节。保留它是因为它对其它加载器布局仍有意义，
> 且成本极低；但**不要误以为它解决了服务端问题**。

### 1.4 `src/main/java/com/fusionml/FusionLauncher.java`

新增 6 个 CLI 参数（含帮助文本）：

| 参数 | 作用 |
|---|---|
| `--list-mc-versions` | 列出官方清单里的可安装版本 |
| `--snapshots` | 列表/安装时包含快照 |
| `--list-limit <n>` | 列表条数，默认 25（0=全部） |
| `--fetch-server [版本]` | 拉取服务端 jar（默认 `latest-release`） |
| `--server-version <版本>` | 指定要拉取的服务端版本号 |
| `--server-dir <目录>` | 服务端 jar 落地目录 |
| `--official-version <版本>` | 官方清单版本号（默认从版本名前缀推断） |
| `--write-server-script` | 同时生成 Fabric 形态 `start-server.cmd/.sh` |

（`--official-version` 与 `--write-server-script` 是第 7、8 项，实际共 8 个；其中
`--server-version` 与 `--fetch-server <版本>` 两种写法都支持。）

---

## 2. 服务端启动脚本：Fabric 形态

### 2.1 为什么不是"我们自己写一个 main"

26.3 的官方 `server.jar` 是一个 **bundler**（实测解包确认，非推测）：

```
META-INF/MANIFEST.MF  : Main-Class: net.minecraft.bundler.Main, Bundler-Format: 1.0
META-INF/main-class   : net.minecraft.server.Main
META-INF/versions.list: a362163e...  26.3  26.3/server-26.3.jar
META-INF/libraries.list: 完整依赖清单（lz4-java, azure-json, oshi-core, gson, ...）
version.json          : {"id":"26.3","java_version":25,"protocol_version":777,...}
```

它自己负责解包 versions/libraries、拼类路径、调真实主类。用 `--mc-launch` 自己搭一套类路径
等于**绕开官方启动器并重新实现它**——依赖清单解析、原生库摆放、参数传递任何一处不一致
都会变成难查的启动失败。**既然官方已把这套做成自洽的 jar，正确做法是包着它启动。**

### 2.2 agent 怎么传进去：`JAVA_TOOL_OPTIONS`

```cmd
cd /d "%~dp0"
set "JAVA=C:\Program Files\Zulu\zulu-25\bin\java.exe"
set JAVA_TOOL_OPTIONS=-javaagent:"%~dp0lib\fusionml\fusion-ml.jar"=server
"%JAVA%" -Xmx4096m -jar "%~dp0server.jar" nogui
```

实测确认：`JAVA_TOOL_OPTIONS` 被 JVM 自动读取并应用，`premain` 收到 `args=[server]`。

**附实测的"bundler 不 fork 子 JVM"**：`Picked up JAVA_TOOL_OPTIONS` 全程只出现 **1 次**，
且栈为 `net.minecraft.server.Main.main` → `net.minecraft.bundler.Main.lambda$run$0` → `Thread.run`
（同线程同层栈），`sun.java.command=server.jar nogui`。即解包后在**原 JVM 内**换 classloader
调真实主类。所以该变量不依赖"子进程继承环境"这一环，更稳。

---

## 3. 实测修掉的 6 个真实 bug

全部来自**实跑**，不是代码审查。前 3 个由 Lead 在真实安装产物中发现，后 3 个由我在实跑中抓到。

| # | Bug | 症状 | 修法 |
|---|---|---|---|
| 1 | `%HERE%lib\...` | cmd 变量名允许字母，贪婪解析成 `HERElib` → 展开为空 → **agent 路径为空** | 直接写 `%~dp0lib\...`（`%~dp0` 自带尾反斜杠） |
| 2 | `%HERE%server.jar` | 同上 → `-jar " nogui"`，jar 路径为空 | 同上 |
| 3 | `-XmxMEMm` | 替换键 `%MEM%` 匹配不到模板里裸的 `MEM` → 字面 `-XmxMEMm`，JVM 拒绝启动 | 改为 `StringBuilder` 拼接，不再用占位符替换 |
| 4 | `JAVA=java` | 本机 PATH 上是 **Java 21**，26.3 要 **Java 25** → `UnsupportedClassVersionError: class file version 69.0 ... up to 65.0` | 用 `JavaFinder.find(javaMajor)` 把**解析出的 java.exe 绝对路径**写进 `.cmd` |
| 5 | 未设工作目录 | 加载器无从知道服务端目录 → **模组目录指向调用者所在目录**（实测 `D:\xiangmu\加载器\mods`），服务端有 mods/ 却一个都不加载；`版本 ?` | 脚本加 `cd /d "%~dp0"` |
| 6 | `.sh` 写了 Windows Java 路径 | 安装机（Windows）路径被写进 `.sh` → Linux 上必然 "not found" | `.sh` 一律用 `JAVA="${JAVA:-java}"` |

**第 1、2、3 条的共性**：都是"占位符/变量拼接静默失效"，**脚本不报错**，但功能悄悄没了。
所以改用字符串拼接（不再与脚本里 `%~dp0`/`%JAVA%`/`%ERRORLEVEL%` 这些真 `%` 打架）。

---

## 4. 生成后自检（两层，拒绝写出坏脚本）

延续"宁可抛异常也不写出坏脚本"的原则：

```java
// 第一层：缺必需片段 / 残留占位符 → 抛异常
checkGenerated(cmd, "start-server.cmd", new String[] {
        "%~dp0" + SERVER_LOADER_REL, "%~dp0" + jar,
        "-Xmx" + memMb + "m", "-javaagent:\"", "cd /d \"%~dp0\"",
});
// 残留 %LOADER% / %JAR% / %MEM% / MEMm 也抛异常

// 第二层：.sh 不得含 Windows 路径
if (sh.contains(":\\")) {
    throw new IllegalStateException("生成的 start-server.sh 里出现了 Windows 路径…");
}
```

另有一个内存下限兜底：`-Xmx4m` 这种值 JVM 是**接受**的（4 MB 堆），服务端会在半路
`OutOfMemoryError`，报错点离原因极远 —— 所以明显不合理的值顶到 512 MB。

---

## 5. 关于服务端类加载的一个关键结论（重要，避免后人重复踩坑）

**现象**：服务端走官方 `server.jar` 时，注入的钩子抛
`NoClassDefFoundError: com/fusionml/crossload/CrossLoad`（`Caused by: ClassNotFoundException`
于 `URLClassLoader.findClass`）。

**根因（反编译 bundler 字节码证实，非推测）**：
```
104: aload_0
105: invokevirtual  // Object.getClass()
108: invokevirtual  // Class.getClassLoader()
111: invokevirtual  // ClassLoader.getParent()      ← ★ 关键
116: new            // java/net/URLClassLoader
136: invokespecial  // URLClassLoader."<init>"([Ljava/net/URL;Ljava/lang/ClassLoader;)V
```
即 bundler 取的父加载器是 **`this.getClass().getClassLoader().getParent()`** ——
按双亲委派，加载 bundler 的是 system CL（`server.jar` 在 `-jar` 的 system classpath 上），
**它的 parent 就是 `PlatformClassLoader`**。bundler **故意跳过 App CL**。

**实测打印的加载器拓扑**（`-javaagent:...=clprobe`）：
```
system CL               = AppClassLoader
bundlerParent (real)    = PlatformClassLoader
bundlerParent==platform = true
bundlerParent==system   = false
parent chain: PlatformClassLoader   resource(CrossLoad)=null   ← jar 在这一层不可见
bundlerParent load CrossLoad FAIL = ClassNotFoundException
```

**因此**：
- `Instrumentation.appendToSystemClassLoaderSearch`（只作用于 App CL）**在原理上必然无效** ——
  bundler 的加载器根本不把 App CL 当父，父子链是断开的。
- 任何"让 bundler 的加载器去解析**我们某个类**"的方案都会失败（包括"加一个转发桥类"）。
- **唯一可行方向**：让注入的字节码**只引用 bootstrap 定义的 JDK 类型**
  （`java/lang/System`、`java/util/Properties`、`java/util/function/Consumer` 等），
  把真正的调用推迟到运行时经对象引用转发。该方向由 Lead 在 `HookInjector`/`Preflight` 实现。

**验证工具**（保留在仓库中，仅诊断用）：
- `launch/ClProbe.java` —— 仅当 agent 参数含 `clprobe` 时执行，正常路径零开销
- `build/bundler-disasm.txt` —— bundler 反汇编（上述结论的原始证据）

---

## 6. 验证状态

### ✅ 已验证（有实跑日志）

| 项 | 证据 |
|---|---|
| 拉取：官方清单可用 | `--list-mc-versions` 拉通 piston-meta，列出 26.3 / 26.2 / 26.1.2 … |
| 拉取：**本地版本 json 来源**（离线） | 日志 `地址来源：本地版本定义 D:\我的世界\服务器\.minecraft\versions\26.3-Fabric 0.19.5\26.3-Fabric 0.19.5.json` |
| 拉取：**官方清单来源** | 日志 `地址来源：官方版本清单 https://piston-meta.mojang.com/v1/packages/11f1aefe.../26.3.json` |
| 下载 + SHA1 | 62,294,556 字节；SHA1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`，与官方 `downloads.server.sha1` 一致 |
| 重跑幂等 | 第二次运行 `本地已有且 SHA1 校验通过，直接复用（755 ms）` |
| 写入范围（硬约束） | 只写**空格版**目录；**下划线版 `26.3-Fabric_0.19.5` 全程未被改动** |
| `JAVA_TOOL_OPTIONS` + `=server` 通道 | `premain` 收到 `args=[server]`，日志 `代理参数指定：服务端模式（fusion.client=false）` |
| 含空格路径必须加引号 | 实测对比：不写引号 → `Error: Could not create the Java Virtual Machine.`；写 `-javaagent:"<路径>"=server` → 正常 |
| bundler 结构与"不 fork 子 JVM" | 见 2.1 与 2.2 |
| 服务端不注册客户端钩子 | 日志 `服务端模式：跳过游戏内 HUD 注册`；全仓 grep 确认 `net/minecraft/client/**` 只在 client 分支注册 |
| 客户端回归（`premain` 改动后） | 151 模组、`Setting user: Player`、`Sound engine started`、`still-running-after-90s = True`；`test.err` 零异常；`挂载点已进入（Minecraft）` 命中 |
| **服务端完整启动 + 持续 tick** | `Starting Minecraft server on *:25565`；`Done (6.229s)! For help, type "help"`；随后 `Server empty for 60 seconds, pausing`（tick 循环跑满 60 秒的产物） |
| **服务端跨端入口点钩子生效** | `已改写 net.minecraft.server.MinecraftServer：注入 1 个方法，111872 字节`；`[FusionML/跨端] 挂载点已进入（DedicatedServer）`（派发真的被调到，`self` 为被调用实例）；全日志 grep `已跳过/变换.*失败/NoClassDefFound/ClassNotFound/Exception` **零命中** |
| `build.cmd` | exit 0，12 个产物 jar 齐全 |

### ⚠️ 一条必须分开看的边界说明（防止误读）

**"服务端跑起来了" ≠ "钩子生效了"**，这两件事必须分开判断：

- 服务端能**完整启动、持续 tick、监听端口**，这部分**与钩子无关** ——
  修复前钩子被跳过（`变换 MinecraftServer 失败，已跳过`）时，服务端**照样** `Done (8.730s)`。
- 因此判断"钩子是否真的生效"**只能看 `挂载点已进入` 这类由钩子体打印的行**，
  不能看"服务端是否启动成功"。
- 本次最终复验中，`挂载点已进入（DedicatedServer）` 命中，才证明跨端入口点驱动真的生效。

这条经验来自本项目的真实教训：`ldc` 常量池索引超过 255 时注入被**静默跳过**（只留一行日志），
服务端表现完全正常，**不翻日志根本发现不了**。

### ❌ 未验证 / 未达成（如实保留，不美化）

1. **未验证到"带 Fabric 平台的服务端"** —— 当前测试目录是原版服务端，日志为
   `平台 原版（无模组平台）`（`libraries/` 里没有 fabric-loader）。
   即：**原版服务端路径已完整跑通；带模组平台的服务端路径未实跑。**
2. **`.sh` 未在 Linux 实跑** —— 本机只有 Windows；`.cmd` 已实跑。
3. **本沙箱的网络限制**：此前（2026-10-13）曾在此环境遇到 netty 建不出 selector
   （`UnixDomainSockets.connect0` → `Invalid argument: connect`）。
   **本次两次实跑均未复现**（`Starting Minecraft server on *:25565` 成功）。
   因此"监听端口可用"在本机本次是**已验证**的，但该限制在该环境下仍可能出现，
   **故不宣称在所有环境可用**。

### 修复前 → 修复后对照（`ldc` 溢出缺陷）

| | 修复前 | 修复后 |
|---|---|---|
| `MinecraftServer` 注入 | `变换 … 失败，已跳过：常量池索引超过 255：4001，需要 ldc_w` | `已改写 …：注入 1 个方法，111872 字节` |
| `挂载点已进入` | **零命中**（钩子从未被调用） | **命中** `挂载点已进入（DedicatedServer）` |
| 服务端启动 | `Done (8.730s)!`（但钩子失效） | `Done (6.229s)!`（钩子生效） |
| 其它跳过/异常 | 无 | 无 |

> 该缺陷由 Lead 在 `HookInjector` 修复（`ldc` → `ldc_w`：索引 >255 时用 `0x13`+u2，否则 `0x12`+u1；
> 栈效果相同、不引入分支，故"前缀栈中立且无分支"的不变量不受影响），并新增自检第 6 项
> （把常量池撑到 8024 项逼出 `ldc_w` 路径）。**该文件不在本文档的改动清单内。**
>
> 值得记录的对比：**同一注入规则在客户端上注入成功**（`已改写 net.minecraft.server.MinecraftServer：
> 注入 1 个方法`），在服务端被跳过 —— 差异在**类规模/常量池布局**，不在平台。
> 所以 `ldc` 的 255 上限是"随版本/随类规模随机触发"的雷，不是服务端专属问题。

---

## 7. 复现命令

```cmd
rem 构建
cmd /c "build.cmd"

rem 列出可安装版本
cmd /c "java -jar build\fusion-ml.jar --list-mc-versions --list-limit 8"

rem 拉取服务端 jar + 生成启动脚本（离线路径：--server-dir 指向已有版本目录）
cmd /c "java -jar build\fusion-ml.jar --fetch-server \"26.3-Fabric 0.19.5\" --server-dir <版本目录> --write-server-script"

rem 拉取（在线路径：走官方清单）
cmd /c "java -jar build\fusion-ml.jar --fetch-server 26.3 --server-dir build\sdfetch --write-server-script"

rem 启动服务端
cmd /c "build\srvtest\start-server.cmd"

rem 客户端回归（premain 公共路径）
cmd /c "test.cmd -Mode launch -WaitSeconds 90"
```

> 注：命令行**不要传中文路径或带空格的引号参数**（会被 Windows 裁切）。
> 需要传路径时写到 `build\gamedir.txt` 之类文件里让程序自己读。
