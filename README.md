# Warehouse Keeper · 假人仓库管理模组

> **声明**
>
> 这是一个 100% Vibe Coding 项目，所有代码及代码审查均有 AI 负责，人工负责真机测试，由 DeepSeek 完成。
>
> AI 有可能犯错，仅供 vibe coding 学习用途，请勿用于工业生产以及其他重要行业中。若出现任何损失，后果自负！！！
>
> This is a 100% Vibe Coding project: all coding and code review were done by AI, while humans handled real-device testing. Built by DeepSeek.
>
> AI can make mistakes. It is intended for vibe coding and learning purposes only — do not use it in industrial production or any other critical field. Any losses are your own responsibility!!!

## 简介

Warehouse Keeper 是面向 Minecraft 26.2 的 Fabric 模组，用于集中管理基地仓库。模组把若干矩形区域登记为「仓库」，扫描区域内全部容器并建立物品索引，随后在游戏内以可视化面板提供查询、取货、整理与权限管理，并由被称为「搬运工」的假人执行实际的搬运作业。

模组同时提供客户端与服务端内容：联机时服务端负责索引与作业，客户端负责面板与中文物品名；单人游戏下两者同时生效。

## 功能特性

- **仓库登记**：以两个对角点划定矩形区域并保存为仓库，支持扩大、合并与高度调整。
- **索引扫描**：一次扫描区域内所有容器（箱子、陷阱箱、木桶、潜影盒，以及 Iron Chests 等任意 `Container` 实现），记录物品 id、数量、所在仓库、坐标与槽位。雕纹书架与书架默认不计为容器（可在 `config/warehouse-keeper/settings.json` 的 `containerExclude` 中调整）。
- **索引持久化**：索引保存在存档目录，进入存档时自动刷新；扫描会临时加载未加载区块，完成后立即释放。
- **游戏内面板**：默认按 `B` 打开，提供概览、物品、箱子、取货、搬运工、权限与审计页面，支持按名称搜索、按分类与数量排序。
- **搬运工作业**：搬运工可整理仓库、取货并送达指定位置；整理时按箱子标签归类、按容器容量合并堆叠，并在箱内按原版创造栏顺序排序（取不到顺序的物品按名称拼音兜底）。
- **容器标签**：可为箱子设置分类标签、自动分类与暂存标记，容器界面右侧显示统一的标签栏；分类键默认是原版创造栏页签，0.20 及更早的中文类目名仍可作为父类使用（贴着旧名的箱子照旧收下辖物品）。
- **权限与审计**：按玩家授予取货、指挥、整理三种权限，管理员（OP）可查看操作日志。

## 运行环境

| 组件 | 版本要求 |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 或更高 |
| Fabric API | 0.157.0+26.2（或兼容版本） |
| Java | 25 或更高 |
| Carpet（可选） | 26.2，用于生成可见的人形搬运工 |

## 安装

1. 安装 Fabric Loader 0.19.3 或更高版本，并准备 Minecraft 26.2 客户端或服务端。
2. 将 Fabric API 与 `warehouse-keeper-0.21.0.jar` 放入游戏实例的 `mods/` 目录。
3. 联机使用时，服务端与客户端均需安装本模组。
4. 可选：安装 Carpet 以显示人形搬运工。未安装时搬运工不可见，取货与整理功能不受影响。

## 快速开始

1. `/warehouse pos1` 与 `/warehouse pos2` 选择仓库的两个对角点，执行 `/warehouse region save <名称>` 保存仓库。
2. 执行 `/warehouse scan <仓库>` 建立索引，`/warehouse status` 查看扫描进度。
3. 按 `B` 打开面板，在「物品」页查看库存总览。
4. `/warehouse porter add <名称>` 创建搬运工，`/warehouse porter assign <名称> <仓库>` 指定值守仓库。
5. `/warehouse tidy <仓库>` 让搬运工整理仓库；`/warehouse order <物品> [数量]` 下单取货并由搬运工送达。
6. `/warehouse user perm <玩家> take|bot|tidy on|off` 授予其他玩家对应权限。

## 命令参考

全部指令以 `/warehouse` 开头，不带参数的子命令会输出各自的用法提示。

| 分组 | 子命令 |
| --- | --- |
| 仓库 | `pos1`、`pos2`、`region save/list/info/remove`、`region grow/merge/height` |
| 索引 | `scan`、`status`、`list`、`find`、`stats`、`save`、`load`、`clear`、`settings`、`categories` |
| 搬运工 | `porter` / `bot`：`add`、`remove`、`assign`、`here`、`spot`、`spawn`、`kill`、`tidy`、`stop` |
| 取货与入库 | `order`、`give [all]`、`tidy` |
| 标签 | `tag show/list/set/auto/staging/clear/mode/prune` |
| 权限 | `user list/perm/log/migrate` |

修改性命令仅限管理员（OP）执行。普通玩家可用 `status`、`list`、`find`、`stats`、`porter`（只读部分）与 `tag show/list`；`order`、`give` 需要「取货」权限，`tag` 的修改类子命令需要「整理」权限，二者均以管理员（OP）身份执行时不受限制。

## 游戏内面板

按默认按键 `B`（可在「选项 → 控制」中修改「打开仓库管理界面」）打开面板。

- **仓库**页：概览、新建、扩建、物品、箱子、维护；非管理员仅显示概览、物品与箱子。
- **取货**页：输入物品名称（支持中文名与物品 id）或点击清单中的一行下单。
- **搬运工**页：名册值守与整理；可新增、指派、收回、停止与删除搬运工。
- **权限**页：权限与审计；按玩家切换取货、指挥、整理三种权限，并查看操作日志。

## 数据与存档

模组不直接改写存档数据，运行期产生的文件均位于游戏实例的配置目录：

```
config/warehouse-keeper/
├── settings.json          模组设置
├── categories.json        物品分类覆盖表（值为创造栏页签键，或作父类用的旧中文类目名）
├── players.json           玩家权限表
├── audit.log              操作日志（超过上限后轮转为 audit.log.1）
└── worlds/<存档名>/
    ├── index.json         仓库索引
    └── container-tags.json 容器标签
```

整理、取货与投递均由搬运工以正常容器交互完成，效果等同于玩家手动操作。

## 从源码构建

编译目标为 Java 25，请将 `JAVA_HOME` 指向 JDK 25（含 `javac`），或通过 `-Dorg.gradle.java.home=<JDK 25 路径>` 指定。

```bash
# Windows
set JAVA_HOME=<JDK 25 路径>
gradlew build

# Linux / macOS
export JAVA_HOME=<JDK 25 路径>
./gradlew build
```

构建产物：

- `build/libs/warehouse-keeper-<版本>.jar`：可直接放入 `mods/` 的模组文件。
- `build/libs/warehouse-keeper-<版本>-sources.jar`：源码包。

开发期运行：`gradlew runClient` 与 `gradlew runServer` 会在 `run/` 目录下启动开发实例（该目录已被 `.gitignore` 忽略）。

## 目录结构

```
src/main/java/com/ds/warehouse/
├── WarehouseMod.java      模组入口与服务端 tick 调度
├── index/                 区域扫描、容器与物品索引、索引持久化
├── porter/                搬运工：作业调度、寻路移动与动作表现
├── config/                设置、容器标签、分类规则与存档级存储
├── net/                   服务端与客户端之间的索引快照与查询协议
├── client/                游戏内面板、标签栏与客户端状态
├── command/               /warehouse 指令树与权限校验
└── util/                  权限、名称表、审计与通用工具
src/main/resources/        fabric.mod.json 与语言文件
tools/                     开发期辅助脚本（RCON、客户端驱动、分类语料）
```

## 已知限制

- 扫描进行期间，面板数据按较低频率刷新；扫描结束后恢复即时更新。
- 未安装 Carpet 时搬运工不可见，仅能通过指令与面板观察作业结果。
- 中文物品名依赖客户端向服务端推送名称表；专用服务端在没有客户端连入时，指令输出中的物品名为英文。
- 面板布局面向 720P 及以上分辨率设计，极窄窗口下部分区域会按可用空间降级或隐藏。
- 容器界面右侧标签栏的形态会随窗口尺寸与容器尺寸自适应降级。

## 许可证

本项目采用 GNU General Public License v3.0 许可证，详见 [LICENSE](LICENSE)。

版权所有 © 2026 2567922141。
