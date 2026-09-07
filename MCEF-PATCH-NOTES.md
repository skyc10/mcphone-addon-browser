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

## 补丁 3：下载镜像 URL 路径翻译（GitHub Release 镜像支持）

- 原因：MCEF 0.7 运行时从 `montoyo.net/jcef` 下载 CEF 二进制，大陆访问极慢。
  MCEF 的配置项 `forcedMirror`（`config/MCEF.cfg`，`main` 节）本可覆盖镜像根
  URL，但 MCEF 请求的路径带目录层级（`0.6/win64/cef.pak`），而 GitHub Release
  资产名不能含 `/`（上传时被 GitHub 转成 `.`，且 `.../download/v1/win64/cef.pak`
  这类带斜杠的下载 URL 404），必须把路径压平才能用 GitHub Release 做镜像。
- 镜像仓库：`https://github.com/skyc10/mcef-resources`（release v1，资产命名
  `0.6-<platform>-<file>`，如 `0.6-win64-cef.pak`、`0.6-win64-libcef.dll`；README 内含
  每个文件的原始 URL / 大小 / SHA-1 / SHA-256 清单，另附 `SHA256SUMS.txt` 与
  原版 `config2.json`）。全部 42 个文件下载后逐字节校验过 SHA-1（与 config2.json
  一致），上传后用 GitHub 返回的 SHA-256 资产摘要二次校验全部通过。
- 补丁：ASM 整体替换 `net/montoyo/mcef/remote/Mirror.getResource(String)` 方法体：
  当 `MCEF.FORCE_MIRROR != null`（即配置了 forcedMirror）时先执行
  `name = name.replace('/', '-')` 再拼 URL；未配置 forcedMirror 时字节码行为与
  原版完全一致（仍请求 montoyo.net 原始路径），不影响无镜像用户。
  超时（30000/15000ms）与 User-Agent: MCEF 均保持原样。
- 补丁器：`research/PatchMirror.java`（ASM 9.8，computeMaxs+computeFrames；
  运行时验证：forced 下 `0.6/win64/cef.pak` →
  `https://github.com/skyc10/mcef-resources/releases/download/v1/0.6-win64-cef.pak`，
  `config2.json` 不变，vanilla 下 URL 与原版相同）。
- 产物：`research/MCEF-1.7.10-mirror.jar`（= final jar + 补丁 3）。
  部署到实例 mods 后，在 `config/MCEF.cfg` 的 `main` 节设置：
  `forcedMirror=https://github.com/skyc10/mcef-resources/releases/download/v1`
  （MD5 不涉及；SHA-1 校验用的是 config2.json 里的值，镜像文件逐字节相同，校验照常通过）。

## 验证记录（2026-09-06）

- `JCEF Version = 3.2171.110` + `MCEF loaded successfuly`（真实模式，非虚拟）日志确认。
- 修复前崩溃：`Class bytes are null`（PS5.1 重打包反斜杠条目）→ 改 JDK jar 工具重打包。
- 修复前崩溃：`NoSuchMethodError: createBrowser(String)`（Ticking entity）→ 补丁 2。

## MCEF 运行时下载源清单（补丁 3 前置调研，2026-09-08）

- 反编译源码证据（`research/extract/mcef/`，已部署 0.7 jar）：
  - `net/montoyo/mcef/remote/Mirror.class`：枚举 `MONTOYO`，URL 硬编码
    `http://montoyo.net/jcef`；`getResource()` 直接读 `MCEF.FORCE_MIRROR`。
  - `net/montoyo/mcef/remote/RemoteConfig.class`：下载 `config2.json`，取 JSON
    键 `"0.6"` → `platforms.{win32,win64,linux64}`（平台=OS+`sun.arch.data.model`），
    文件逐个下载到游戏根目录（`ClientProxy.ROOT`），用 config2.json 中的 SHA-1 校验。
  - `net/montoyo/mcef/remote/Resource.class`：URL = `"0.6/" + platform + '/' + name`。
- 实际请求 URL（`<mirror>` = `https://montoyo.net/jcef`）：
  - `<mirror>/config2.json`
  - `<mirror>/0.6/win32/<file>`（15 个文件）、`<mirror>/0.6/win64/<file>`（15 个）、
    `<mirror>/0.6/linux64/<file>`（12 个），文件名与 SHA-1 见镜像仓库 README。
- 上游 1.7.10 最新版指针：config2.json `latestVersions["1.7.10"] = "0.6"`。
