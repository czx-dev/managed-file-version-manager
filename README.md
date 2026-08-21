# Managed File Version Manager

> [English](./README_en.md) | [简体中文](./README.md)

一个 Jenkins 插件，基于 [Config File Provider](https://plugins.jenkins.io/config-file-provider/) 插件提供的托管文件（Managed Files），增加了**版本历史**、**差异对比**、**版本回滚**和**分组管理**功能。

> 适用于 Jenkins `2.568.2` 和 JDK 25。

---

## 功能概述

每次对托管文件执行**创建 / 更新 / 回滚 / 删除**操作时，本插件都会自动记录完整的内容快照及元数据（操作用户、时间戳、SHA-256 校验码、备注信息），并保存为不可变的历史版本。通过管理页面，您可以：

- 查看任意托管文件的**完整历史记录**
- 对**任意两个版本进行对比**（左右并排显示）
- **查看**任意历史版本的原始内容
- **回滚**到任意历史版本（实际创建新版本，不会修改历史）
- 按自定义分类**分组管理**托管文件，并按分组筛选列表

本插件采用非破坏性设计：历史记录仅追加（append-only），元数据文件 `metadata.xml` 为人类可读的 XML 格式。

---

## 快速开始

1. 安装插件（从 `mvn package` 构建生成 `.hpi` 文件，或直接放入 `$JENKINS_HOME/plugins` 目录）。
2. 重启 Jenkins。
3. 进入 **Manage Jenkins → Managed File Versions**。
4. 通过现有的 *Config File Provider* 界面管理文件；本插件会在每次保存时静默捕获版本。

---

## 功能详情

### 版本控制（透明、自动）

通过钩取 Jenkins 的 `SaveableListener`，自动捕获 `GlobalConfigFiles` 的每次保存和删除操作：

| 操作       | 触发时机                         | 存储路径                      |
|------------|----------------------------------|-------------------------------|
| `CREATE`   | 新托管文件的首次保存             | `<id>/1/content` + `metadata.xml` |
| `UPDATE`   | 内容发生变化的保存              | `<id>/<n>/content` + `metadata.xml` |
| `ROLLBACK` | 回滚操作完成后                   | `<id>/<n>/content` + `metadata.xml` |
| `DELETE`   | 托管文件被删除时                | `<id>/<n>/content` + `metadata.xml`（保留） |

版本通过 `SHA-256` 去重：如果连续两次保存但内容未变，不会产生冗余版本记录。

### 差异对比（左右并排）

访问 `/manage/managed-file-versions/diff?id=<id>&from=<v1>&to=<v2>` 可查看两个版本的并排差异，采用自主实现的 Myers LCS 算法（无外部依赖）。

### 回滚（两步确认）

访问 `/manage/managed-file-versions/rollback?id=<id>&version=<v>` 首先显示当前版本与目标版本的并排对比确认页面，确认后才执行回滚操作。

回滚会**创建新的版本**（操作为 `ROLLBACK`），而非修改历史，确保审计轨迹完整。

### 分组管理

用户可自定义标签（ID + 显示名 + 描述），每个文件只能属于一个分组。列表视图支持：

- 按名称搜索
- 按分组筛选
- 按名称 / ID / 版本数 / 最新操作时间排序
- 行内分组选择器（选择后自动提交）
- 行内删除（带确认提示）

分组信息存储在独立的 JSON 文件中，不影响文件提供者自身的存储结构。

### 页面原地刷新

所有写操作（创建分组、删除分组、分配文件、删除文件）均采用原地渲染管理页面，而非跳转重定向——滚动位置、筛选条件、排序方式和 URL 均保持不变。删除操作仍会在浏览器端弹出确认框，防止误触 F5 导致重复提交。

---

## 存储结构

```
$JENKINS_HOME/
└── managed-file-version-manager/
    ├── groups.json              ← 分组定义 + 文件 → 分组映射关系
    └── <fileId>/               ← 每个托管文件对应一个文件夹
        └── <version>/          ← 版本号从 1 开始，单调递增
            ├── content          ← 托管文件的原始文本内容
            └── metadata.xml     ← 版本号、文件ID、文件名、操作用户、
                                    用户ID、时间戳、操作类型、回滚来源版本、
                                    SHA-256 校验码、备注
```

存储路径会显示在管理页面顶部（"Storage: ..."）。

### `groups.json` 格式

```json
{
  "groups": [
    { "id": "production", "name": "Production", "description": "生产环境配置" }
  ],
  "assignments": {
    "my-config-id": "production"
  }
}
```

采用手写的 JSON 解析器，保持依赖最小化（无 Jackson 等第三方库）。

---

## 界面布局

```
Manage Jenkins → Managed File Versions
├── 工具栏
│   ├── 搜索名称: [________]  分组: [▼]  [应用]  [重置]
│   └── [+ 新建托管文件]
├── 托管文件列表 (n)
│   └── 表格: 名称 | ID | 分组 | 版本数 | 最新操作 | 最新时间 | 操作
└── 分组管理
    ├── 创建表单（ID、名称、描述）
    └── 表格: ID | 名称 | 描述 | 操作
```

文件表格每一行提供 `历史记录`、`编辑`（委托给 Config File Provider）、`删除` 和行内分组下拉选择器。

分组表格每一行提供 `删除` 操作（带确认提示）。

---

## 架构设计

```
listener/   ManagedFileSaveListener     ← @Extension，钩取 SaveableListener.onChange / onDeleted
service/   ManagedFileVersionService   ← 快照对比，决定 CREATE / UPDATE / DELETE / ROLLBACK
store/     VersionStore                ← 磁盘 I/O，单文件锁，SHA-256 去重
store/     GroupStore                 ← 分组及分配的 JSON 存储
model/     ManagedFileVersion, Operation, Group, ConfigSnapshot, SimpleConfig
util/      Sha256Util, DiffUtil       ← 轻量工具类，无外部依赖
action/    ManagedFileVersionsAction   ← @Extension，ManagementLink，主页面 + 分组 POST 处理
action/    ManagedFileHistoryAction    ← 单文件历史视图
action/    ManagedFileDiffAction       ← 左右并排差异对比
action/    ManagedFileRollbackAction  ← 两步确认回滚
action/    ManagedFileViewVersionAction← 单版本原始内容查看
```

### 编辑操作的数据流

```
用户保存托管文件
        │
        ▼
SaveableListener.onChange
        │
        ▼
ManagedFileVersionService.recordSnapshot
        │  （内存中的快照映射与 GlobalConfigFiles 当前状态对比）
        ▼
VersionStore.saveVersion
        │
        ▼
<fileId>/<n>/{content, metadata.xml}
```

### 回滚操作的数据流

```
POST /rollback（id, version）
        │
        ▼
ManagedFileRollbackAction.doConfirm
        ├── 从 VersionStore 加载目标内容
        ├── 通过反射 / provider.newConfig 修改配置
        ├── ManagedFileVersionService.recordRollback  ── 直接写入 ROLLBACK 版本
        └── GlobalConfigFiles.save                    ── 触发 SaveableListener，
                                                       但 rememberSnapshot() 已预加载
                                                       快照映射，避免重复创建 UPDATE 版本
```

---

## 接口列表

| URL                                              | 方法 | 功能说明                   |
|--------------------------------------------------|------|--------------------------|
| `/manage/managed-file-versions/`                 | GET  | 列表展示 + 筛选           |
| `/manage/managed-file-versions/history?id=<fileId>` | GET  | 完整版本历史              |
| `/manage/managed-file-versions/view?id=<fileId>&version=<n>` | GET  | 查看单版本原始内容         |
| `/manage/managed-file-versions/diff?id=<fileId>&from=<v1>&to=<v2>` | GET  | 左右并排差异对比          |
| `/manage/managed-file-versions/rollback?id=<fileId>&version=<n>` | GET  | 回滚确认页面              |
| `/manage/managed-file-versions/rollback/doConfirm` | POST | 执行回滚操作              |
| `/manage/managed-file-versions/createGroup`       | POST | 创建分组（原地刷新）       |
| `/manage/managed-file-versions/deleteGroup`       | POST | 删除分组（原地刷新）       |
| `/manage/managed-file-versions/assign`            | POST | 分配/取消分配文件到分组    |
| `/manage/managed-file-versions/deleteConfig`     | POST | 删除托管文件（原地刷新）    |

---

## 权限说明

根据项目决策，权限检查已被移除——所有接口均开放。（参见 `ManagedFileDiffAction.checkPermission` 和 `ManagedFileViewVersionAction.checkPermission`）

如环境需要，可重新添加 `Jenkins.ADMINISTER` 权限检查。

---

## 构建

```bash
mvn -B clean package
```

构建产物为 `target/managed-file-version-manager.hpi`。需要 JDK 25。

本地运行带插件的 Jenkins：

```bash
mvn hpi:run
```

---

## 开发说明

- **快照初始化**（`ManagedFileVersionService.initialiseSnapshot`）在插件启动时执行，确保已存在的托管文件在首次保存后不会产生多余的 `CREATE` 记录。
- **回滚快照同步**：`ManagedFileRollbackAction` 在 `GlobalConfigFiles.save` **之后**调用 `recordRollback`，确保快照映射与新内容保持一致。另一种做法（先写版本，然后由 SaveableListener 触发 `UPDATE`）已被明确避免。
- **无外部差异库**：`DiffUtil` 是自主实现的 Myers LCS 算法，适用于小规模托管文件。对于数兆字节的大型配置文件，建议切换到 `java-diff-utils`。
- **手写 JSON**：`GroupStore` 手动解析和序列化 `groups.json`，保持依赖最小化。JSON 结构刻意保持简单，任何超出标准格式的内容都会被拒绝。
- **原地渲染**：`ManagedFileVersionsAction.renderInPlace()` 使用 `HttpResponses.forwardToView` 重新渲染同一视图，避免 302 重定向，从而保持滚动位置和表单状态——但这也意味着 F5 会重复提交表单，因此删除按钮添加了浏览器 `confirm()` 保护。

---

## 项目结构

```
managed-file-version-manager/
├── pom.xml
└── src/main/
    ├── java/com/example/jenkins/managedfile/
    │   ├── action/       ← 5 个 Stapler Action + ManagementLink
    │   ├── listener/     ← SaveableListener @Extension
    │   ├── model/        ← 不可变 POJO + Operation 枚举
    │   ├── service/      ← 快照对比和编排
    │   ├── store/        ← VersionStore + GroupStore
    │   └── util/         ← SHA-256 + 差异算法
    └── resources/
        ├── index.jelly                                ← 插件描述页面
        └── com/example/jenkins/managedfile/action/
            ├── ManagedFileVersionsAction/index.jelly  ← 管理页面
            ├── ManagedFileHistoryAction/index.jelly
            ├── ManagedFileDiffAction/index.jelly
            ├── ManagedFileRollbackAction/index.jelly
            └── ManagedFileViewVersionAction/index.jelly
```

---

## 许可证

内部 / 项目许可证——请参阅项目的 `LICENSE` 文件。
