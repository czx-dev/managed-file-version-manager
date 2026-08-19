# Managed File Version Manager

[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-插件-blue.svg)](https://www.jenkins.io/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个 Jenkins 插件，为 [Config File Provider](https://plugins.jenkins.io/config-file-provider/) 插件管理的 **Managed File** 提供**版本历史**、**并排对比**、**一键回滚**和**审计追踪**能力。

---

## 简介

当你把数据库连接信息、Kubernetes 配置、构建脚本、共享工具脚本等放到 Config File Provider 的 Managed File 里后，一旦改动就再也无法回看"之前是什么样子"——本插件正是为了解决这个痛点。

每次有人**新建 / 修改 / 删除** Managed File，插件都会自动记录一个不可变的版本；每个版本都带作者、时间戳、操作类型和 SHA-256 摘要。出现误改时，可以一键回滚到任意历史版本，回滚本身也会被记录为一个新版本（append-only），形成完整的审计链。

---

## 功能特性

- **自动版本化** — Managed File 的 CREATE / UPDATE / DELETE 都会被捕获为版本，无需手工干预。
- **仅追加的历史** — 版本永不覆盖；回滚生成新版本而不是删除原记录。
- **并排 diff 查看器** — 任意两个版本之间的逐行对比，新增（`+`）、删除（`-`）、修改（`~`）三种标记分别用绿/红/黄高亮。
- **两段式回滚** — 先展示目标版本与当前内容，确认后再写入，避免误操作。
- **完整审计信息** — 每次变更都记录操作人、用户 ID、时间戳、操作类型、内容 SHA-256。
- **崩溃安全** — 每个版本以独立目录存储，包含元数据 XML 与内容快照，单个版本损坏不影响其它版本。
- **并发安全** — 每个文件 ID 一把 `ReentrantLock`，叠加 Jenkins listener 钩子，保证并发保存下的历史一致性。

---

## 工作原理

```
GlobalConfigFiles 保存 ──▶ SaveableListener ──▶ ManagedFileVersionService
                                                       │
                                                       ├── 与内存快照做 diff
                                                       ├── VersionStore.writeVersion
                                                       │     ├── <JENKINS_HOME>/managed-file-version-manager/<fileId>/<version>/content
                                                       │     └── <JENKINS_HOME>/managed-file-version-manager/<fileId>/<version>/metadata.xml
                                                       └── 更新内存快照
```

1. **启动初始化** — Jenkins 启动时，`ManagedFileSaveListener#init` 调用 `initialiseSnapshot()`，从 `GlobalConfigFiles` 当前状态构建内存快照。这样可以避免对磁盘上早已存在的文件产生伪 `CREATE` 记录。
2. **变更捕获** — `GlobalConfigFiles` 每次保存或删除，`ManagedFileSaveListener#onChange`（或 `#onDeleted`）调用 `recordSnapshot()`。Service 对比快照，得出 CREATE/UPDATE/DELETE 增量并落盘。
3. **版本对比** — `ManagedFileDiffAction` 读取任意两个版本，用 Myers LCS 算法（`DiffUtil`）计算行级 diff，并渲染为并排 Jelly 视图。
4. **回滚** — `ManagedFileRollbackAction` 读取目标版本内容，写回 `Config`，先记录一个指向原版本的 `ROLLBACK` 版本，再触发 `GlobalConfigFiles.save()` 让 listener 刷新快照。

---

## 安装

### 方式一：上传 `.hpi`

```bash
mvn clean package -DskipTests
```

产物：`target/managed-file-version-manager.hpi`。

Jenkins 后台：**Manage Jenkins → Plugins → Advanced → Deploy Plugin**，选择该 `.hpi` 上传即可。

### 方式二：从源码构建并运行

```bash
git clone https://github.com/<your-org>/jenkins-extend-config-file-provider.git
cd jenkins-extend-config-file-provider/managed-file-version-manager
mvn clean package
```

开发期启动一个带插件的 Jenkins：

```bash
mvn hpi:run
```

启动后访问 `http://localhost:8080/jenkins`。

---

## 环境要求

| 组件 | 版本 |
| --- | --- |
| Jenkins | 2.568.2 或更新（LTS） |
| JDK | 25 |
| Config File Provider 插件 | 1013.v73c323e52b_1f 或更新 |

---

## 使用指南

### 入口

Jenkins 后台：**Manage Jenkins → Managed File Versions**。索引页列出所有 Managed File，并显示最新版本号（若没有历史记录则会提示）。

### 查看历史

点击某行右侧的 **History**，进入该文件的版本历史页。版本按从新到旧排序，每行展示：

- 版本号
- 操作类型（`CREATE` / `UPDATE` / `DELETE` / `ROLLBACK`）
- 作者（显示名 + 用户 ID）
- 时间戳
- 内容 SHA-256
- 备注（如 `"Rollback to version 5"`）

每行有三个动作按钮：

| 动作 | 作用 |
| --- | --- |
| **View** | 渲染该版本的原始文件内容 |
| **Diff** | 与所选版本并排对比 |
| **Rollback** | 两段式确认 → 写入新 `ROLLBACK` 版本 |

### 对比任意两个版本

在某版本行点击 **Diff**，在下拉框中选择"对比版本"。查看器布局：

- 表头：左侧 `V<n> (旧)`，右侧 `V<m> (新)`
- 绿色行带 `+` —— 仅新版本有
- 红色行带 `-` —— 仅旧版本有
- 黄色行带 `~` —— 成对修改
- 普通行 —— 未变化上下文

底部汇总 `added / removed / unchanged` 行数。

### 回滚到历史版本

1. 在历史页点击目标版本右侧的 **Rollback**
2. 在确认页查看"目标版本内容 vs 当前内容"，确认无误后提交
3. 提交后自动跳回历史页，最顶部出现一个新的 `ROLLBACK` 版本

> 回滚是仅追加的。原本"出错"的版本依然保留——任何时候都可以再次回滚到它。

---

## 存储布局

所有版本数据位于 `$JENKINS_HOME/managed-file-version-manager/`：

```
managed-file-version-manager/
└── <fileId>/                    # 例如 my-pipeline-config
    ├── 1/                       # 版本号
    │   ├── content              # 该版本的完整文件内容
    │   └── metadata.xml         # 版本、作者、时间戳、sha256 等
    ├── 2/
    │   ├── content
    │   └── metadata.xml
    └── 3/
        ├── content
        └── metadata.xml
```

### `metadata.xml` 结构示例

```xml
<version>
    <version>3</version>
    <fileId>my-pipeline-config</fileId>
    <fileName>my-pipeline-config</fileName>
    <user>张三</user>
    <userId>zhangsan</userId>
    <timestamp>2026-08-19T09:12:33Z</timestamp>
    <operation>UPDATE</operation>
    <rollbackFromVersion></rollbackFromVersion>
    <sha256>e3b0c44298fc1c149afbf4c8996fb924...</sha256>
    <comment></comment>
</version>
```

`operation` 取值：`CREATE`、`UPDATE`、`DELETE`、`ROLLBACK`。`rollbackFromVersion` 仅在回滚记录中有值。

---

## 目录结构

```
managed-file-version-manager/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/jenkins/managedfile/
    │   │   ├── action/
    │   │   │   ├── ManagedFileVersionsAction.java   # Manage Jenkins 入口
    │   │   │   ├── ManagedFileHistoryAction.java    # 单文件历史页
    │   │   │   ├── ManagedFileViewVersionAction.java
    │   │   │   ├── ManagedFileDiffAction.java
    │   │   │   └── ManagedFileRollbackAction.java
    │   │   ├── listener/
    │   │   │   └── ManagedFileSaveListener.java     # 监听 GlobalConfigFiles 保存/删除
    │   │   ├── model/                                # ManagedFileVersion、ConfigSnapshot 等
    │   │   ├── service/
    │   │   │   └── ManagedFileVersionService.java   # diff / recordSnapshot 调度
    │   │   ├── store/
    │   │   │   └── VersionStore.java                 # 文件系统 + XML 持久化
    │   │   └── util/                                 # Sha256Util、DiffUtil
    │   └── resources/
    │       ├── index.jelly                           # 插件描述
    │       └── com/example/jenkins/managedfile/action/.../index.jelly
    └── test/
        └── java/com/example/jenkins/managedfile/
            ├── service/                              # NoChangeTest、ConcurrentTest
            ├── store/VersionStoreTest.java
            └── util/Sha256UtilTest.java
```

---

## 开发

### 构建与测试

```bash
mvn clean verify
```

Surefire 报告位于 `target/surefire-reports/`。覆盖范围：

- `VersionStoreTest` —— 持久化往返、fileId 校验、锁语义
- `Sha256UtilTest` —— 哈希稳定性
- `NoChangeTest` / `ConcurrentTest` —— 快照无变化场景以及并发保存下的行为

### 接入真实 Jenkins 调试

```bash
mvn hpi:run -Djenkins.version=2.568.2
```

启动后在 **Manage Jenkins → Managed Config Files** 创建几个 Managed File，反复编辑几次，即可看到每个保存都自动产生新版本。

---

## 安全考量

- fileId 在写入文件系统前会用正则 `[A-Za-z0-9._\-]+` 校验，防止路径穿越。
- 每个 fileId 持有一把 `ReentrantLock`，序列化单文件的并发写；跨文件操作互不影响。
- listener 中所有路径都包裹在 `try/catch`，单个文件的失败不会拖垮 Jenkins。
- 版本数据位于 `JENKINS_HOME`，依赖运维的常规 Jenkins 备份策略；插件本身未实现保留策略，版本会持续累积直到手工清理。

---

## 后续规划

- 版本保留 / 清理策略（每个文件仅保留最近 N 个版本）
- 历史全文 / 正则搜索
- 一键导出历史归档
- 回滚时触发审计 webhook

---

## 许可证

本项目基于 [MIT 许可证](LICENSE) 发布。

---

## 致谢

基于 Jenkins 社区的 [Config File Provider](https://plugins.jenkins.io/config-file-provider/) 插件构建，感谢 Dominik Bartholdi 及所有贡献者。