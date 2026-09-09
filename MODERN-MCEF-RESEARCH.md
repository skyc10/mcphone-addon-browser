# 现代 MCEF 往 GTNH 1.7.10 移植调研报告

日期：2026-09-09 · 分支：`feat/modern-mcef-port`（测试版，与 master 完全隔离）

## 结论（TL;DR）

**可以移植。** 选择 CCBlueX/mcef 的 java-cef fork（CEF 143）作为新内核，按 montoyo MCEF 的老模式嵌入本 mod：
org.cef 源码进树（JNI 按包名绑定，**不得 relocate**）+ 运行时按 jcef commit 下载 natives tar.gz + `System.load(绝对路径)`。
已排除 jcefmaven（见下）。测试版必须**替换**旧 MCEF 0.6 jar（org.cef 同包名会类冲突），不能共存。

## 一、MC 浏览器 mod 谱系

| 项目 | 仓库 | 内核 | 要点 |
|---|---|---|---|
| WebDisplays 1.7.10 原版 | 无公开源码（作者 montoyo） | montoyo/mcef | GTNH MCEF 0.6 的源流 |
| WebDisplays 1.12.2 | github.com/montoyo/webdisplays | montoyo/mcef（shade 内嵌） | |
| WebDisplays 1.20 | github.com/CinemaMod/webdisplays | com.cinemamod:mcef-forge 2.1.1 | maven: mcef-download.cinemamod.com |
| montoyo/mcef | github.com/montoyo/mcef | JCEF 3.2171，org.cef 整树内嵌 | 运行时从 montoyo.net 下载 natives；本仓库 research/mcef 即此源码 |
| CinemaMod/mcef | github.com/CinemaMod/mcef | **CEF 116.0.27**（自家 java-cef fork） | CPU onPaint→Blaze3D；音频 CefAudioHandler→PCM→MC SoundInstance→OpenAL；**下载站 java-cef-builds 全部 403**（实测），仅作参考 |
| CCBlueX/mcef | github.com/CCBlueX/mcef（26.2 分支，v3.4.0） | **CEF 143.0.14**（CCBlueX/java-cef fork） | LiquidBounce 在用；org.cef fork 的 CefBrowserOsr public+protected 构造器、onPaint 监听器**无 JOGL/AWT 依赖**；键盘 sendKeyPress(keyCode, scanCode, modifiers)；natives 运行时下载 `${host}/mcef-cef/<jcef-commit>/<platform>`，host 可配（api.liquidbounce.net 等），jar 发布于 maven.ccbluex.net `net.ccbluex:mcef` |
| FPSMasterTeam/mcef-nova | github.com/FPSMasterTeam/mcef-nova | CCBlueX fork 衍生 | **唯一把内核与宿主显式分离成 SPI（MCEFHost：schedule/windowHandle/stopGame）**、零 net.minecraft 依赖、裸 LWJGL3 GL 上传——架构参考最佳 |
| DimasKama/mcef-modern | github.com/DimasKama/mcef-modern | jcefmaven（me.friwi，CEF 146） | 标准系方案 |
| 其他 | Cubium / MineChromium / BrowserMCEF / fhyjs mcef2 等 | 均基于上述 MCEF | Modern UI 是自绘 markup，非 CEF |

## 二、为什么选 CCBlueX fork 而不是 jcefmaven

- jcefmaven（上游 java-cef master）：`CefBrowserOsr` 是 **package-private 不可子类化**，且其 onPaint 先做 JOGL `canvas_.getContext().makeCurrent()`，无 GL 上下文直接 return——1.7.10+lwjgl3ify 里塞 JOGL 很痛。→ 排除。
- CCBlueX fork：public CefBrowserOsr + protected 构造器；onPaint 监听器先派发、无 AWT/JOGL 依赖；天然为「MC 自管 GL 纹理」设计。→ **采用**。
- CinemaMod：代码可参考（尤其音频），但分发通道失效。→ 不作基底。

## 三、GTNH 环境约束（均已核实）

1. **lwjgl3ify**：GTNH 2.9 运行时 classpath 上是 LWJGL3 全套；`org.lwjgl.opengl.GL11/GL13/GL33` 直接可用（Angelica 大量如此）。注意 lwjgl3ify 3.x（SDL3 后端）里 `org.lwjgl.opengl.Display` 是空壳，真实现是 `org.lwjglx.opengl.Display`，句柄方法为 `Display.getWindow()`（SDL3 句柄，非 GLFW）——CEF OSR（windowless）不需要窗口句柄，不受影响。
2. **JNI native 加载**：Java 21 模块系统下 `System.load(绝对路径)` 无限制（classpath 类属 unnamed module）。GTNH 官方 org 的 pVnRT 直接 System.load 自己的 Rust .so；Angelica Tracy 是「jar 内打包 natives→解包→绝对路径 load」先例。**不要用** System.loadLibrary/java.library.path/ClassLoader.usr_paths（旧 MCEF 补丁笔记已证明后者在 Java 16+ 需 ASM 绕过）。
3. **jabel 语法限制**：GTNH convention plugin 的 `enableModernJavaSyntax=jabel` = Java 17 语法 + `--release 8` 字节码与 stdlib API。**Java 9+ API 不可用**（List.of、Map.of、Stream.toList、Files.readString 等），var/switch 表达式/record 可用。org.cef fork 源码若有 9+ API 用法需就地修补。
4. **LWJGL2/3 GL 兼容**：GL11 级静态调用签名与常量（glTexImage2D、GL_BGRA 0x80E1 等）在 LWJGL2/3 一致；编译期用项目现成的 LWJGL 2.9，运行期落到 lwjgl3ify 的 LWJGL3。本附属 beta 线已在 GTNH 实证过「CEF 帧→glTexSubImage2D→自绘四边形」全链路。

## 四、移植架构（测试版）

```
src/main/java/org/cef/**            ← CCBlueX/java-cef fork（CEF 143）源码原样进树（BSD 许可），不改包名
                                      + 在 CefBrowserOsr 上补 montoyo 兼容结构（renderer_/view_width_/view_height_/queue/mcefUpdate()）
src/main/java/net/montoyo/mcef/**   ← 兼容层（包名固定供现有反射桥探测）：MCEFApi.getAPI()、api.API/IBrowser、
                                      MCEF(PROXY 静态字段，无 @Mod 注解)、BaseProxy、ClientProxy(下载+初始化+createBrowser)
natives 分发                         ← 运行时下载 CCBlueX host 的 tar.gz（jcef commit 固定，sha256 校验），解压
                                      <游戏目录>/mcefmodern/<commit>/，System.load(绝对路径)，支持 host 覆盖配置
CEF 配置                             ← windowless_rendering_enabled=true、multi_threaded_message_loop、
                                      browser_subprocess_path/resources/locales/cache 指向解压目录、no_sandbox
渲染                                  ← onPaint(CEF 线程)→queue→mcefUpdate()(GL 线程, addon 现有 drawScreen 泵驱动)→
                                      glTexSubImage2D(GL_BGRA)→addon 自绘（复用 beta 线已验证的绕开 CefRenderer.render 的路径）
输入                                  ← 兼容层保持旧签名（char+AWT 掩码），测试版行为与旧内核对齐；fork 的
                                      keyCode+scanCode 直通作为增强（F1-F12/方向键终于可注入）
```

## 五、必须遵守的兼容面（现有 addon 反射桥探测清单）

`MCEFApi.getAPI()`；`API.createBrowser(String[,boolean])`、`isVirtual()`、`getCefApp()`；
`IBrowser.resize/close/draw(double×4)/getTextureID/loadURL/goBack/goForward/getURL/setFocus/runJS`；
`injectMouseMove(int×3,boolean)`、`injectMouseButton(int×4,boolean,int)`（6 参）、`injectMouseWheel(int×5)`、
`injectKeyPressed/Typed/Released(char,int)`；`CefBrowserOsr.renderer_`、`view_width_`/`view_height_`、`queue`、
`mcefUpdate()`；`CefApp.N_DoMessageLoopWork()`；`ClientProxy.VIRTUAL`；`MCEF.PROXY`；`BaseProxy` 无参构造。

## 六、风险与未验证点

1. **org.cef 类冲突**：本测试 jar 与旧 MCEF 0.6 jar 不能共存（同 org.cef 包名）——测试时必须移除旧 MCEF jar。
2. **CCBlueX 下载 host 可用性**：api.liquidbounce.net 目前可达（测试版已配 host 覆盖项；必要时镜像到 skyc10/mcef-resources）。
3. **org.cef fork 在 --release 8 下的编译修补量**：未知，按最小修补处理。
4. **游戏内运行时验证未做**（本机无 GTNH 实例）：CEF 143 在 GTNH Java 21+lwjgl3ify 下的实际拉起、字体/IME、退出清理均待实测；退出看门狗（ExitWatchdog）对 CEF 143 的清理线程行为需观察。
5. **音频未做**（CEF 143 CefAudioHandler→OpenAL 移植留待后续，参考 CinemaMod MCEFClient）。
6. **win32 natives 不存在**（现代 CEF 已放弃 32 位），测试版仅 win64/linux64。
