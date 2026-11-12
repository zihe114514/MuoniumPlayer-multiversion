# muonium-common：七版本共享主体

本目录保存 MuoniumPlayer 七条 Minecraft 车道的版本中立代码、资源和共用测试，由 **14 个加载器子工程直接挂载编译**，不单独构建、不复制回版本树。

版本树：`1.20.1 recode`、`1.21.1`、`1.21.10`、`1.21.11 recode`、`26.1.2`、`26.2`、`26.3`。

## 规范入口

- [仓库总规范](../AGENTS.md)：版本矩阵、工作流程、验证与发布边界。
- [共享层规范](AGENTS.md)：语言/API 基线、compat 契约、文件归属和迁移工具限制。
- [历史迁移清单](SHARE-MANIFEST.md)：生成记录，**不是当前完整文件清单或实时规范**；不手改统计数字。

## 目录

| 路径 | 职责 |
| --- | --- |
| `src/main/java/` | 共享 Java 主体，Java 17 基线 |
| `src/main/kotlin/` | 按需新增 Kotlin，语言/API 1.8 与 stdlib 1.8.22 基线 |
| `src/main/resources/` | 共享资源，二进制按字节处理 |
| `src/test/java/`、`src/test/kotlin/` | 七树 Fabric 子工程执行的共用测试 |

版本差异放各树 compat，加载器注册放各树外壳。共享层零加载器符号；新共享文件直接放这里，不需要先复制七份或运行去重迁移器。

## 验证

在仓库根 PowerShell 执行：

```powershell
.\tools\build-all.ps1 -Task ':fabric:test' -KeepOldJars
# 生产代码/依赖改动还需七树双加载器构建
.\tools\build-all.ps1
```

布局由 `SharedLayoutStaticTest` 守卫。`share-trees.py classify/apply` 属于历史一次性迁移流程，不能当日常体检；`classify` 会写底片，`apply` 会移动/删除文件，具体限制见共享层规范。
