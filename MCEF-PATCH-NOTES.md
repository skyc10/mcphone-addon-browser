# MCEF-1.7.10 补丁说明（Java 17+ / lwjgl3ify 兼容版）

源 jar：`chill-mods/MCEF-1.7.10.jar`（MCEF 0.7，modid `MCEF`）
产物：`MCEF-1.7.10-final.jar`（已部署到实例 mods，原版备份 `MCEF-1.7.10.jar.bak` / parked_mods）

## 补丁 1：ClientProxy.onInit 的 usr_paths 反射失败不再跳过 CEF 初始化

- 原因：MCEF 用 `ClassLoader.class.getDeclaredField("usr_paths")` 注入库路径，
  该字段 Java 16+ 已删除 → NoSuchFieldException → 原代码直接 `VIRTUAL=true; return`，
  整个 CEF 初始化被跳过（假性虚拟模式）。
- 补丁：onInit 字节码 229..247 的 catch 处理器（19 字节）替换为
  `astore_3; aload_3; printStackTrace; goto 248`——打印异常后继续正常 CEF 初始化。
- 库路径改由实例 JVM 参数 `-Djava.library.path=...` 提供（.minecraft 目录），
  并将 14 个 CEF 原生文件复制到 Prism 运行时 `java-runtime-epsilon\bin`（JVM 内建
  库搜索路径，不会被 Prism 启动时清空 natives 目录影响）。
- 补丁脚本：`research/patch_mcef.ps1`（模式匹配 + 原地替换，唯一命中校验）。

## 补丁 2：API 接口加回 1 参 createBrowser 桥接（WD 0.11 兼容）

- 原因：WD 0.11（1.7.10）调用 `API.createBrowser(String)`（MCEF 0.1 签名），
  MCEF 0.7 只剩 `createBrowser(String, boolean)` → NoSuchMethodError →
  进存档 tick 屏幕实体时崩溃。
- 补丁：ASM 给 `net/montoyo/mcef/api/API` 接口加 default 方法
  `createBrowser(String url)` → `createBrowser(url, false)`。
  boolean=false 与 MCEF 0.1 老实现语义一致（transparent=false；osr 恒为 true）。
  类版本提升到 V1_8（接口默认方法），Java 25 运行时完全支持。
- 补丁器：`research/PatchAPI.java`（ASM 9.8，computeMaxs+computeFrames）。

## 验证记录（2026-09-06）

- `JCEF Version = 3.2171.110` + `MCEF loaded successfuly`（真实模式，非虚拟）日志确认。
- 修复前崩溃：`Class bytes are null`（PS5.1 重打包反斜杠条目）→ 改 JDK jar 工具重打包。
- 修复前崩溃：`NoSuchMethodError: createBrowser(String)`（Ticking entity）→ 补丁 2。
