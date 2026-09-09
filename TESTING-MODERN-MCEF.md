# TESTING-MODERN-MCEF

本 MOD 新增了基于 CCBlueX 现代 MCEF 内核（java-cef / CEF 143）的 **beta 测试内核**，用于替代旧的 MCEF 0.6/0.7 内核。本文说明其架构、下载机制、与旧内核的兼容性，以及测试步骤。

## 概述

- 旧内核（MCEF 0.6/0.7，CEF 3.x）已年久失修，新内核将 CCBlueX 的 java-cef fork 直接嵌入本 MOD jar（`org.cef` 包，未重定位，JNI 按包名绑定）。
- 嵌入的内核版本：`b853a9d87fd0a7553001ce0785fee73d55be8d64`（见 jar 根资源 `jcef.commit`）。
- 对外暴露的 API 与旧 MCEF 完全兼容：`net.montoyo.mcef.api.MCEFApi.getAPI()`、`IBrowser` 全套签名不变，依赖旧 MCEF 的第三方 MOD（含本 MOD 的浏览器 addon 桥）无需改动。
- 运行前提：Java 21 + lwjgl3ify（GTNH 环境即满足）； natives 通过绝对路径 `System.load` 加载，不污染 `java.library.path`。

## 与旧内核的差异

| 项目 | 旧内核（MCEF 0.7） | 新内核（modern.1） |
| --- | --- | --- |
| CEF 版本 | CEF 3.x（2016） | CEF 143（chromium 143） |
| natives 获取 | MCEF 官方镜像 + usr_paths 反射加载 | `${host}/mcef-cef/<jcef-commit>/<platform>.tar.gz` + `System.load(绝对路径)` |
| 存放目录 | `<gamedir>/mcef/` | `<gamedir>/mcefmodern/<jcef-commit>/<platform>/` |
| 帧上传 | `render()` 内部自绘 | `onPaint` → 队列 → `mcefUpdate()`（GL 线程）→ `glTexImage2D/glTexSubImage2D(GL_BGRA)` |
| 消息泵 | 依赖 ClientProxy.onTick | addon 桥在 `drawScreen` 里自驱动 `N_DoMessageLoopWork()` + `mcefUpdate()` |
| 平台 | linux/windows | linux_amd64 / windows_amd64（arm 暂不支持） |

## natives 下载与校验

1. 启动后首次打开浏览器时（`ClientProxy.onInit()`，同步执行），检查 `<gamedir>/mcefmodern/<commit>/<platform>/` 是否已有全部必需库：
   - Linux：`libcef.so`、`libjcef.so`（另需 `jcef_helper` 可执行）。
   - Windows：`d3dcompiler_47.dll`、`libGLESv2.dll`、`libEGL.dll`、`chrome_elf.dll`、`libcef.dll`、`jcef.dll`。
2. 缺失时按顺序尝试以下 host 下载 `<platform>.tar.gz` 与同名 `checksum`（sha256）：
   - `https://api.liquidbounce.net/api/v3/resource`
   - `https://api.ccbluex.net/api/v3/resource`
   - `http://nossl.api.liquidbounce.net/api/v3/resource`
   - 覆盖：JVM 参数 `-Dmcefmodern.host=https://your-mirror/...`（需按同样路径布局提供）。
3. 下载后校验 sha256，用内置 ustar 解析器（`net.montoyo.mcef.compat.TarExtractor`，纯 Java，无新依赖）解压到 commit 目录，Linux 下自动 `chmod +x jcef_helper`。

## 测试步骤

### 1. 干净环境首启（下载路径）

```bash
# 1) 删除旧的 modern 内核目录，强制走下载
rm -rf <gamedir>/mcefmodern
# 2) 放入 jar 后启动客户端，观察日志
```

预期日志顺序（logger 前缀 `[MCEF]`）：

```text
[MCEF] Loading MCEF (modern CEF kernel, jcef b853a9d87fd0a7553001ce0785fee73d55be8d64)
[MCEF] MCEF natives not found; downloading CEF b853a9d... for linux_amd64...
[MCEF] Downloading https://api.liquidbounce.net/api/v3/resource/mcef-cef/b853a9d87fd0a7553001ce0785fee73d55be8d64/linux_amd64.tar.gz ...
[MCEF] SHA-256 ok: c94345c923f163d6605652df9d23ed2c23849c2c2824d40930432ccdef21a00a
[MCEF] MCEF initialized successfully.
```

### 2. 二次启动（缓存路径）

确认不再触发下载，直接 `jcef.path` 指向已有目录并完成 CEF 初始化。

### 3. 浏览器功能冒烟测试

- 打开 addon 浏览器界面（`BrowserScreen`），确认页面渲染、纹理随滚动/刷新变化。
- 输入测试：中文输入、退格、方向键、Ctrl 组合键（如 Ctrl+C/V）。
- 鼠标测试：移动、左/中/右键、双击选中、滚轮平滑滚动。
- 视频测试：`https://www.youtube.com` 播放（`--autoplay-policy=no-user-gesture-required` 已默认注入）。
- JS 桥测试：依赖 `mcefQuery` 的查询回调（addon 的 JS 桥走 `mcefQuery`/`mcefCancel` 路由）。

### 4. 失败回退（VIRTUAL 模式）

```bash
# 模拟下载失败：断网或指向不存在的 host
-Dmcefmodern.host=https://127.0.0.1:1/
```

预期：初始化失败 → `ClientProxy.VIRTUAL=true`，浏览器进入 Virtual（占位）模式，游戏不崩溃；下次打开浏览器时自动重试真实初始化。

### 5. 退出清理

退出世界/客户端时观察：浏览器逐一 `close(true)` → `runMessageLoopFor(100ms)` 排空消息 → `cefClient.dispose()`。反复开关浏览器界面 20 次，无 `MallocStackLogging`/`CHECK failed` 崩溃即为通过。

## 已知限制

- 仅 linux_amd64 / windows_amd64；macOS 与 ARM 未编译对应 natives，检测到即进 VIRTUAL 模式。
- 首启下载约 100-200MB，依赖网络；镜像不可达且无本地缓存时仅能 VIRTUAL。
- beta 内核：`1.0.2-modern.1`。遇到问题请附日志中 `[MCEF]` 前缀的完整片段反馈。
