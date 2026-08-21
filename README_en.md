# Managed File Version Manager

> [English](./README_en.md) | [简体中文](./README.md)

A Jenkins plugin that adds **version history**, **diff**, **rollback**, and **grouping** on top of
Managed Files provided by the [Config File Provider](https://plugins.jenkins.io/config-file-provider/)
plugin.

> Built for Jenkins `2.568.2` and JDK 25.

---

## What it does

Every time a Managed File is **created / updated / rolled back / deleted**, this plugin captures the
full content plus metadata (user, timestamp, sha-256, comment) and stores it as an immutable
version. From the management page you can then:

- View the **full history** of any managed file
- **Diff** any two versions (side-by-side)
- **View** the raw content of any historical version
- **Rollback** to any prior version (creates a new version, never mutates history)
- **Group** managed files under user-defined categories and filter the list by group

The plugin is intentionally non-destructive: history is append-only, and `metadata.xml` is
human-readable XML.

---

## Quick start

1. Install the plugin (`.hpi` from your `mvn package` build, or drop it into `$JENKINS_HOME/plugins`).
2. Restart Jenkins.
3. Open **Manage Jenkins → Managed File Versions**.
4. Manage Files through the existing *Config File Provider* UI; this plugin will silently capture
   every save.

---

## Features

### Versioning (transparent, automatic)

Hooks into Jenkins' `SaveableListener` so every save/delete of `GlobalConfigFiles` is captured:

| Operation | When recorded | Storage path |
|-----------|---------------|--------------|
| `CREATE`  | First save of a new managed file | `<id>/1/content` + `metadata.xml` |
| `UPDATE`  | Subsequent saves with content change | `<id>/<n>/content` + `metadata.xml` |
| `ROLLBACK`| After a rollback completes | `<id>/<n>/content` + `metadata.xml` |
| `DELETE`  | When a managed file is removed | `<id>/<n>/content` + `metadata.xml` (kept) |

Versions are deduplicated by `sha-256`: if you click Save twice without changing anything, no
spurious new version is recorded.

### Diff (side-by-side)

`/manage/managed-file-versions/diff?id=<id>&from=<v1>&to=<v2>` renders a paired old/new diff
using a hand-rolled Myers LCS implementation (no external diff library).

### Rollback (two-stage confirmation)

`/manage/managed-file-versions/rollback?id=<id>&version=<v>` first renders a confirmation page
with current vs. target content side-by-side, then POSTs to actually perform the rollback. The
rollback creates a **new** version (operation = `ROLLBACK`) instead of mutating history, so the
audit trail stays intact.

### Groups

User-defined labels (id + display name + description). Files can be assigned to one group. The
list view supports:

- Search by name
- Filter by group
- Sort by name / id / version count / latest operation time
- Inline group selector (auto-submits on change)
- Inline delete with confirmation

Group membership is stored in a sidecar JSON file; it is purely metadata and never touches the
file provider's own storage.

### In-place refresh

All write actions (create group, delete group, assign file, delete file) render the management
page **in place** rather than redirecting — scroll position, filters, sort and URL all survive.
Delete actions still prompt for confirmation in the browser to prevent accidental F5 re-submits.

---

## Storage layout

```
$JENKINS_HOME/
└── managed-file-version-manager/
    ├── groups.json              ← group definitions + file → group assignments
    └── <fileId>/               ← one folder per managed file
        └── <version>/          ← versions start at 1, monotonically increasing
            ├── content         ← raw text of the managed file
            └── metadata.xml    ← version, fileId, fileName, user, userId, timestamp,
                                  operation, rollbackFromVersion, sha256, comment
```

The exact storage path is shown at the top of the management page ("Storage: ...").

### `groups.json` format

```json
{
  "groups": [
    { "id": "production", "name": "Production", "description": "live configs" }
  ],
  "assignments": {
    "my-config-id": "production"
  }
}
```

Hand-rolled JSON parser keeps the dependency footprint flat (no Jackson).

---

## UI tour

```
Manage Jenkins → Managed File Versions
├── Toolbar
│   ├── Search name: [________]  Group: [▼]  [Apply]  [Reset]
│   └── [+ New Managed File]
├── Managed Files (n)
│   └── table: Name | ID | Group | Versions | Latest Op | Latest Time | Actions
└── Groups
    ├── Create form (ID, Name, Description)
    └── table: ID | Name | Description | Actions
```

Each row in the file table exposes `History`, `Edit` (delegates to Config File Provider),
`Delete`, and an inline group `<select>`.

Each row in the groups table exposes `Delete` (with confirm).

---

## Architecture

```
listener/   ManagedFileSaveListener     ← @Extension, hooks SaveableListener.onChange / onDeleted
service/    ManagedFileVersionService   ← snapshot diff, decides CREATE / UPDATE / DELETE / ROLLBACK
store/      VersionStore                ← disk I/O, per-file locks, sha-256 dedup
store/      GroupStore                  ← sidecar JSON for groups + assignments
model/      ManagedFileVersion, Operation, Group, ConfigSnapshot, SimpleConfig
util/       Sha256Util, DiffUtil        ← tiny helpers, no external deps
action/     ManagedFileVersionsAction   ← @Extension, ManagementLink, index page + group POSTs
action/     ManagedFileHistoryAction    ← per-file history view
action/     ManagedFileDiffAction       ← side-by-side diff
action/     ManagedFileRollbackAction   ← two-stage rollback
action/     ManagedFileViewVersionAction← raw content of a single version
```

### Data flow for an edit

```
User saves a Managed File
        │
        ▼
SaveableListener.onChange
        │
        ▼
ManagedFileVersionService.recordSnapshot
        │  (in-memory snapshot map compared to current GlobalConfigFiles state)
        ▼
VersionStore.saveVersion
        │
        ▼
<fileId>/<n>/{content, metadata.xml}
```

### Data flow for a rollback

```
POST /rollback (id, version)
        │
        ▼
ManagedFileRollbackAction.doConfirm
        ├── load target content from VersionStore
        ├── mutate Config via reflection / provider.newConfig
        ├── ManagedFileVersionService.recordRollback  ── writes the ROLLBACK version directly
        └── GlobalConfigFiles.save                    ── triggers SaveableListener,
                                                       but rememberSnapshot() pre-loads
                                                       the snapshot map so no duplicate
                                                       UPDATE version is created
```

---

## Endpoints

| URL                                                                                | Method | Purpose                           |
|------------------------------------------------------------------------------------|--------|-----------------------------------|
| `/manage/managed-file-versions/`                                                   | GET    | List + filter                     |
| `/manage/managed-file-versions/history?id=<fileId>`                                | GET    | Full version history              |
| `/manage/managed-file-versions/view?id=<fileId>&version=<n>`                       | GET    | Raw content of one version        |
| `/manage/managed-file-versions/diff?id=<fileId>&from=<v1>&to=<v2>`                 | GET    | Side-by-side diff                 |
| `/manage/managed-file-versions/rollback?id=<fileId>&version=<n>`                   | GET    | Confirmation page                 |
| `/manage/managed-file-versions/rollback/doConfirm`                                 | POST   | Perform rollback                  |
| `/manage/managed-file-versions/createGroup`                                        | POST   | Create a group (in-place)         |
| `/manage/managed-file-versions/deleteGroup`                                        | POST   | Delete a group (in-place)         |
| `/manage/managed-file-versions/assign`                                             | POST   | Assign / unassign file → group    |
| `/manage/managed-file-versions/deleteConfig`                                       | POST   | Delete managed file (in-place)    |

---

## Permissions

Permission checks were intentionally removed per project decision — endpoints are open. (See
`ManagedFileDiffAction.checkPermission` and `ManagedFileViewVersionAction.checkPermission`.)
You can re-add `Jenkins.ADMINISTER` checks if your environment requires them.

---

## Build

```bash
mvn -B clean package
```

Produces `target/managed-file-version-manager.hpi`. Requires JDK 25.

To run a local Jenkins with the plugin installed:

```bash
mvn hpi:run
```

---

## Development notes

- **Snapshot initialization** (`ManagedFileVersionService.initialiseSnapshot`) is run at plugin
  start-up so that an existing Managed File does not get a spurious `CREATE` entry on the first
  save after a restart.
- **Rollback snapshot sync**: `ManagedFileRollbackAction` calls `recordRollback` *after*
  `GlobalConfigFiles.save`, so the snapshot map stays consistent with the new content. The
  alternative (writing the version first and then having the SaveableListener trigger another
  `UPDATE`) is explicitly avoided.
- **No external diff library**: `DiffUtil` is a Myers LCS implementation sized for small
  Managed Files. For multi-megabyte configs you may want to switch to `java-diff-utils`.
- **Hand-rolled JSON**: `GroupStore` parses and serialises `groups.json` by hand to keep the
  dependency surface flat. The schema is intentionally trivial; anything beyond the canonical
  shape is rejected.
- **In-place rendering**: `ManagedFileVersionsAction.renderInPlace()` uses
  `HttpResponses.forwardToView` to re-render the same view without a 302. This preserves scroll
  position and form state — but it does mean F5 re-submits the form, so delete buttons have a
  browser `confirm()` guard.

---

## Project layout

```
managed-file-version-manager/
├── pom.xml
└── src/main/
    ├── java/com/example/jenkins/managedfile/
    │   ├── action/       ← 5 Stapler actions + ManagementLink
    │   ├── listener/     ← SaveableListener @Extension
    │   ├── model/        ← immutable POJOs + Operation enum
    │   ├── service/      ← snapshot diff & orchestration
    │   ├── store/        ← VersionStore + GroupStore
    │   └── util/         ← Sha256 + Diff
    └── resources/
        ├── index.jelly                                ← plugin descriptor body
        └── com/example/jenkins/managedfile/action/
            ├── ManagedFileVersionsAction/index.jelly  ← management page
            ├── ManagedFileHistoryAction/index.jelly
            ├── ManagedFileDiffAction/index.jelly
            ├── ManagedFileRollbackAction/index.jelly
            └── ManagedFileViewVersionAction/index.jelly
```

---

## License

Internal / project license — see your project's `LICENSE`.
