# 多版本适配评估：26.3 / 1.21.1 / 1.20.1

本文只写**实测到的事实**，以及由事实推出的工作量判断。凡是没有验证过的都明确标注。

## 一、结论先行

| 目标版本 | 本机是否有实例 | 是否有对方平台补丁原版 | 与 26.3 的关键差异 | 适配判断 |
| --- | --- | --- | --- | --- |
| **26.3** | 有（`26.3-Fabric 0.19.5`，151 模组） | 有（NeoForge `minecraft-client-patched-26.3.0.39-beta`） | — | 正在做，已推进到模组主初始化阶段 |
| **1.21.1** | 有（`1.21.1-Fabric 0.19.5`，2 模组） | 本机有 `1.21.1-NeoForge_21.1.236/249` 实例 | **混淆**，见下 | **需要新增 remap 层**，不是配置问题 |
| **1.20.1** | 有（`1.20.1-Fabric 0.19.3`，115 模组） | 本机未见 1.20.1 NeoForge | 混淆 + Forge 主导（NeoForge 1.20.1 存在但不普遍） | 同上，且要处理 Forge 分支 |

## 二、26.3 与 1.21.1 的根本差异：混淆

这是**唯一真正卡住多版本的事**，其余都是配置与分支。

### 26.3：不混淆

游戏 jar 里的类就是真名：

```
net/minecraft/core/Registry.class
net/minecraft/core/MappedRegistry.class
net/minecraft/resources/ResourceLocation.class
```

我们的 `PatchedBase` 是**纯字节搬运**（把对方平台补丁包里的同名类原样搬进游戏包），
不混淆意味着**搬运即正确** —— 类名、字段名、方法名两边完全一致。

### 1.21.1：混淆

实测（`1.21.1-Fabric 0.19.5.jar`，23519 个条目）：

```
assets/  8448
data/    6796
net/       26      ← 只有入口类用真名
chn.class、cho.class、chp.class、chm.class、chl.class …   ← 其余全是混淆名
```

**顶层就是 `chn.class`、`cho.class` 这种名字**，`net/minecraft/` 下只有 26 个入口类。

### 同一批类，两套名字

1.21.1 的模组用的是 **Yarn/intermediary 名**，26.3 用的是 **Mojang 官方名**：

| 概念 | 26.3（Mojang 名） | 1.21.1（Yarn/intermediary 名） |
| --- | --- | --- |
| 注册表 | `MappedRegistry` | `SimpleRegistry` |
| 注册表加载 | `RegistryDataLoader` | `RegistryLoader` |
| ID 表 | `IdMap` | `IdList` |
| 资源键 | `ResourceLocation` | `Identifier`（intermediary 下是 `class_2960`） |

证据：1.21.1 的 `fabric-registry-sync-v0.mixins.json` 里列的注入类是
`SimpleRegistryMixin`、`RegistryLoaderMixin`、`IdListMixin`；
而 26.3 同一模组用的是 `MappedRegistryMixin`、`RegistryDataLoaderMixin`、`IdMapperMixin`。

### 这对我们意味着什么

`PatchedBase` 现在的做法是"按内部名把补丁类搬进游戏包"。在 1.21.1 上：

1. 范围前缀 `net/minecraft/core/` 在**混淆的游戏 jar 里匹配不到任何类**（那里是 `chn` 之类）；
2. 即使能从 NeoForge 的 `minecraft-client-patched` 里拿到类，那些类是按
   **官方名/NeoForge 内部名**编译的，而游戏运行时要的是 **intermediary 名**，直接搬会：
   - 类名对不上 → 补丁等于没生效；
   - 或者塞进一批名字冲突的类 → `LinkageError`。

所以 1.21.1 需要**先 remap 再合并**：把 NeoForge 补丁产物从它的命名空间映射到
宿主平台运行时的命名空间（或反过来），再做合并。

## 三、工作量判断（基于上面的实测）

**不是"改几个常量"，而是新增一层能力。** 具体要做的：

1. **读映射表**：Fabric 实例里通常带有 Yarn/intermediary 映射（`net.fabricmc:intermediary`），
   NeoForge 侧有自己的映射产物。需要找到并解析它们（本机已有实例可供取证）。
2. **命名空间检测**：合并前先判断"游戏 jar 是混淆还是真名"，不能用固定前缀。
   （26.3 判据：`net/minecraft/core/Registry.class` 是否存在。）
3. **remap 层**：把要合并的类按映射表改名（类名、字段、方法、描述符都要改），
   这是 ASM 级别的活；我们**没有 ASM**（项目要求自研字节码工具），
   所以要么自研 remapper（工作量大、风险高），要么**允许在这一处引入 remap 库**（需你拍板）。
4. **Mixin 目标比对**：`keepVanilla` 依赖"内部名相等"，混淆版下两边命名空间不同，
   必须先把模组的 `@Mixin` 目标 remap 到游戏 jar 的命名空间再比。
5. **Forge 分支**：Forge 的补丁产物路径与 NeoForge 不同
   （现在的 `PatchedBase.locate` 对 forge 复用了 NeoForge 的路径，**这行是错的**），
   1.20.1 主要面向 Forge，必须按 `net/minecraftforge/...` 的规律重写。

## 四、建议的顺序

先**把 26.3 打通到进主界面**（当前已推进到模组主初始化阶段，只剩注册表合并那一块的取舍），
再动多版本 —— 因为 1.21.1 需要的 remap 层会**改变合并的输入形状**，
在 26.3 还没稳定时做，会把两边的变量混在一起，出问题分不清是谁的。

如果你希望**先做 1.21.1**，我需要你先决定一件事：
**remap 是自研，还是允许引入一个现成的 remap 库**（例如 `net.fabricmc:tiny-remapper`）。
这直接决定工作量是"几天"还是"几小时"。

## 五、诚实标注

* **已验证**：1.21.1 混淆（23519 条目里 `net/` 只有 26 个；顶层是 `chn.class` 这类）；
  26.3 不混淆（`net/minecraft/core/Registry.class` 可直接找到）；
  两版 fabric-registry-sync 的注入类名不同（`SimpleRegistryMixin` vs `MappedRegistryMixin`）。
* **未验证**：本机 1.20.1 是否有 NeoForge/Forge 的补丁原版产物（只确认了有 Fabric 0.19.3 实例）；
  1.21.1 的 NeoForge 补丁包具体用什么命名空间（未打开看过）；
  remap 的实际工作量（取决于上面那个决定）。
