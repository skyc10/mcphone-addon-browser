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

1. 启动后首次打开浏览器时（`ClientProxy.onInit()`），检查 `<gamedir>/mcefmodern/<commit>/<platform>/` 是否已有全部必需库：
   - Linux：`libcef.so`、`libjcef.so`（另需 `jcef_helper` 可执行）。
   - Windows：`d3dcompiler_47.dll`、`libGLESv2.dll`、`libEGL.dll`、`chrome_elf.dll`、`libcef.dll`、`jcef.dll`。
2. 已有库则直接初始化（同步，秒级）。**缺失时后台下载，主线程永不阻塞**：`onInit` 立即返回虚拟模式，浏览器界面会提示「CEF natives downloading in background - reopen the browser in a moment」，下载完成后再开一次浏览器即进入真实模式。下载线程是 daemon，进度见日志与 `ClientProxy.NATIVES_STATUS`（downloading / ready / failed: <原因>）。
3. 下载顺序（`<platform>.tar.gz` + `.sha256`，sha256 校验后解压）：
   - **首选镜像（本项目自建）**：`https://github.com/skyc10/mcef-resources/releases/download/mcef-cef-<commit>/<platform>.tar.gz`
   - CCBlueX 官方源（后备）：`https://api.liquidbounce.net/api/v3/resource` 等 3 个 host，URL 为 `<host>/mcef-cef/<commit>/<platform>`（**无 .tar.gz 后缀**，API 307 跳转 S3；v1.0.2-modern.1 误带后缀导致 404，modern.2 已修）
   - 覆盖：JVM 参数 `-Dmcefmodern.host=<host>`（按 CCBlueX 路径布局提供）。
4. 手动预装：把对应平台 tar.gz 解压到 `<gamedir>/mcefmodern/<commit>/`（压缩包内自带 `<platform>/` 前缀目录），启动即免下载。

## 测试步骤

### 1. 干净环境首启（下载路径）

```bash
# 1) 删除旧的 modern 内核目录，强制走下载
rm -rf <gamedir>/mcefmodern
# 2) 放入 jar 后启动客户端，观察日志
```

预期日志顺序（logger 前缀 `[MCEF]`；下载在后台线程，主线程不卡）：

```text
[MCEF] Loading MCEF (modern CEF kernel, jcef b853a9d87fd0a7553001ce0785fee73d55be8d64)
[MCEF] MCEF natives not found; downloading CEF b853a9d... for linux_amd64 in background...
[MCEF] Downloading https://github.com/skyc10/mcef-resources/releases/download/mcef-cef-b853a9d.../linux_amd64.tar.gz ...
[MCEF] SHA-256 ok: c94345c923f163d6605652df9d23ed2c23849c2c2824d40930432ccdef21a00a
[MCEF] MCEF natives download finished; reopen the browser to start CEF.
[MCEF] MCEF initialized successfully.   ← 重新打开浏览器后
```

### 2. 二次启动（缓存路径）

确认不再触发下载，直接 `jcef.path` 指向已有目录并完成 CEF 初始化。

### 3. 浏览器功能冒烟测试

- 打开 addon 浏览器界面（`BrowserScreen`），确认页面渲染、纹理随滚动/刷新变化。
- 输入测试：中文输入、退格、方向键（modern.3 起经 ByKeyCode 管道可用）、Tab 焦点切换（modern.4 起 Windows 下修复）、Ctrl 组合键（如 Ctrl+C/V）。
- **modern.3 重点**：鼠标点击/滚轮、任意键盘输入在打开页面后立即生效（modern.2 及之前
  因缺 GLFW 类全部静默失效——日志无报错但点击/打字无反应）；若输入仍失效，抓
  `ClassNotFoundException: org.lwjgl.glfw.GLFW` 或 `N_SendMouseEvent` 相关日志反馈。
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

## 输入注入与 GLFW stub（modern.3 / modern.4）

**背景**：CEF 143 natives（`jcef.dll`/`libjcef.so`）的键鼠 JNI 桥入口全部以
`ScopedJNIClass(env, "org/lwjgl/glfw/GLFW")` 开头，经 LaunchClassLoader 加载该类来
`GetStaticFieldID` 读取 36 个 GLFW 常量（KEY_\*/MOD_\*/MOUSE_BUTTON_\*/PRESS/RELEASE/REPEAT）。
GTNH 运行时是 LWJGL 3.4.2（3.4 起移除 GLFW 绑定）+ lwjgl3ify（无 GLFW 类）→
`ClassNotFoundException: Class bytes are null for org.lwjgl.glfw.GLFW`，每帧
`N_SendMouseEvent` 都抛异常且被静默吞掉（Java 侧只捕 `UnsatisfiedLinkError`）——
渲染路径不经过这些类，所以「页面能看、输入全灭」。

**修复**（modern.3）：

- mod jar 内嵌 `org/lwjgl/glfw/GLFW` stub：纯常量 + 纯 Java `glfwGetKeyScancode()`
  （IBM PC Set 1 映射），无 `<clinit>`、无 native 依赖，LaunchClassLoader 可直接载入。
- `CefBrowserOsr` 的 `keyEvent()` helper 对齐上游 MCEFBrowser 契约：
  `keyChar` 携带 GLFW 码（natives Win 分支靠 `getKeyChar()==GLFW_KEY_*` 识别特殊键取
  扫描码），可打印字符仍保留原始值走 KEYEVENT_CHAR。
- 非字符键（方向键/DEL/Home/End/PgUp/PgDn）经 `injectKeyXxxByKeyCode` 管道注入
  （`remapKeycode` LWJGL→GLFW），旧内核「keyCode 恒 0 无法表达非字符键」的限制解除。

**修复**（modern.4，并行代码审查跟进）：

- `keyEvent()` 按上游契约填充 `CefKeyEvent.scancode`（`glfwGetKeyScancode`）。Tab/Escape
  不在 natives 的扫描码查找与硬编码表内，此前 scancode=0 → VkCode=0，事件在 Windows 上被
  静默丢弃（Linux 走 XK_Tab 不受影响）；页面内 Tab 焦点切换恢复。
- stub 常量对齐官方 GLFW：`KEY_HOME=268`/`KEY_END=269`（原与官方互换；运行期行为不变，
  `remapKeycode` 已同步引用 stub 常量）；`glfwGetKeyScancode` 扩展 TAB→15、ESCAPE→1。
- `CefBrowserOsr` 内联 GLFW 字面量改为引用随 jar 发布的 stub（单一事实来源）。

## 已知限制

- 仅 linux_amd64 / windows_amd64；macOS 与 ARM 未编译对应 natives，检测到即进 VIRTUAL 模式。
- 首启下载约 100-200MB，依赖网络；镜像不可达且无本地缓存时仅能 VIRTUAL。
- beta 内核：`1.0.2-modern.4`。遇到问题请附日志中 `[MCEF]` 前缀的完整片段反馈。
