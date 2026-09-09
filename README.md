# MCphone Addon: Browser（浏览器）

[MCphone (GTNH)](https://github.com/skyc10/mcphone-gtnh) 的附属模组：给手机加一个「览」浏览器 App。**点击图标直接打开一块 16:9 的全屏虚拟大屏**（约占游戏窗口 80%），由 MCEF/CEF 离屏渲染真实网页——不放置任何方块，不修改 MCphone 源码。

**Minecraft 1.7.10** · **GTNH 2.9.0-beta-3** · 客户端模组

## 功能

- **点击「览」图标** → 直接弹出全屏虚拟浏览器，加载上次网址；从未浏览过则加载默认主页 **Bing**
- **Shift+点击** → 手机内管理页：URL 输入、书签、历史记录（最近 20 条）、打开主页
- **自带书签**：首次使用预置「GTNH 中文 Wiki」→ `https://gtnh.huijiwiki.com/wiki/首页`
- **顶部工具栏**：`<` 后退 · `R` 刷新 · 地址栏（点击定位光标，Enter 跳转，支持 Ctrl+A/C/V/X 与 Delete/Home/End）· `H` 主页 · `X` 关闭 · `F` 全屏切换 · `Esc` 关闭
- 鼠标移动/点击/滚轮/键盘全部注入 CEF，网页内可正常交互
- 上次网址、主页设置、书签、历史持久化在 `.minecraft/mcphone/addons/browser/`（跨存档共享）
- **MCEF 惰性初始化**：装 mod 不拉起 CEF——本附属 preInit 时把 `MCEF.PROXY` 临时换成服务端桩（纯反射），CEF 推迟到**玩家第一次打开浏览器 App** 才在主线程初始化（与 MCEF 原装在 FML init 阶段的时序等价）；初始化失败（含下载降级虚拟模式）会在下次打开时重试
- **退出看门狗**：游戏退出时若 CEF 已初始化且清理线程挂死（`CefApp.dispose()` 阻塞），宽限 5 秒后强制结束进程，避免「退出后进程残留」；CEF 从未初始化（没用过浏览器）时看门狗不做任何动作，进程干净退出。**90 秒无条件 kill timer** 兜底：退出后无论卡在哪，90 秒内进程必结束（kill 前会把全部非守护线程堆栈 dump 到 `logs/mcphone_browser_watchdog.log` 留证）
- **方法签名容错探测**：对 MCEF 各版本的注入方法签名（鼠标/键盘/滚轮/runJS）逐个探测，某个签名不匹配只降级禁用该功能并打日志，不再导致「浏览器内核创建失败」整页报错

## 待测项目（beta 反馈重点）

以下项目在 GTNH 2.9.0-beta-3 + MCEF 0.6 环境下**尚未完成实测**，欢迎按此清单反馈：

1. **浏览器打开与渲染**：首次点击「览」图标，CEF 正常拉起、页面渲染出内容（beta.4 根因修复：帧上传链已验证完好，问题定位到**绘制侧**——`CefRenderer.render()` 自带路径在 Angelica GLSM 下整面透明，改为绕开它自绘正确 UV 的四边形并显式关闭 alpha test/blend；首次绘制会做一次纹理像素读回诊断，日志出现 `texture probe ... alpha0=N%`）
2. **网页交互（beta.5 重点）**：鼠标移动/左中右键点击/滚轮/键盘输入是否全部生效。beta.5 修复点击无反应的根因——鼠标事件 modifiers 恒传 0，JCEF 原生层经 `getModifiersEx` 判定「无按钮按下」而整单忽略；现按 AWT `BUTTONx_DOWN_MASK` 传掩码（press/release 一致），并补上创建后 `setFocus(true)`。首次页面点击会打一行诊断日志 `first click: gui=(...) cef=(...) button=N mask=N cefViewport=WxH`
3. **地址栏输入**：beta.3 换用 vanilla `GuiTextField`，应完整支持单击键入/IME 合成整串输入/Ctrl+A 全选/Ctrl+C 复制/Ctrl+V 粘贴/Ctrl+X 剪切/Delete/Home/End/Shift 选区/点击定位光标；若「合成整串输入」仍无效则属 lwjgl3ify/GLFW 层问题，请反馈
4. **渲染分辨率（beta.5 新功能）**：默认「Auto」= 页面区域按物理像素渲染（1 CEF 像素 ↔ 1 屏幕像素，最清晰）；工具栏「分」按钮循环 Auto→720→1080→1440→2160（固定渲染高度，16:9 定宽，数字越大字越小越清晰、越小字越大越模糊），选择持久化到 `settings.json` 的 `resolutionMode`
5. **退出进程清理**：打开过浏览器后退出游戏，进程应在 **约 5–95 秒内** 结束，无残留（对应修复：异步 close + 文件日志 + 90 秒 kill timer）
6. **未用浏览器的退出**：从不打开浏览器 App 直接退出，进程应立即干净结束（看门狗静默收工）
7. **看门狗日志**：若退出仍有异常，检查 `.minecraft/logs/mcphone_browser_watchdog.log`（退出阶段唯一可信日志通道），提交时请附上该文件

## 已知问题与限制

- **依赖 MCEF 版本差异**：针对 GTNH 的 MCEF 0.6（`renderer_` 字段、6 参 `injectMouseButton`）校准；其他 MCEF 分支若签名不同，对应功能会被自动禁用（日志有 `WARN: ... signature not found` 提示），核心浏览功能不受影响
- **帧上传自驱动（GTNH 兼容修复）**：MCEF 的帧上传链是 `onPaint` 缓存 → `mcefUpdate()`（GL 线程 `glTexImage2D`）→ `CefRenderer.render()`，而 `mcefUpdate()` 唯一调用方是 MCEF 自带的 `RenderTickEvent` 监听——GTNH + lwjgl3ify 环境下该事件不触发，帧永远不上传。本附属于 `drawScreen` 内自驱动 `N_DoMessageLoopWork()` + `mcefUpdate()`（与 MCEF 自带泵并存幂等）；页面绘制门槛为「纹理非 0 且 view 尺寸非 0」，若 15 秒后仍未上屏会显示 `[queue=N view=WxH]` 诊断（`queue=0` 为 CEF 未产帧，`queue>0` 为上传链断裂）
- **绘制侧绕开 CefRenderer.render（Angelica 兼容修复）**：字节码级取证确认帧上传成功后页面仍整面透明，且字号/drawRect 等 Tessellator 绘制均正常——问题锁定在 `CefRenderer.render()` 自身（UV 布点有缺陷：v1=(1,1) 非 (1,0)、v4 重复 v1 的 UV）与 Angelica GLSM core-profile 转换的叠加。本附属不再调用 MCEF 的 `render()`，改为自绘正确 UV(0,0)-(1,1) 的四边形并显式关闭 alpha test/blend（CEF OSR 帧的 alpha 通道可能为 0，在常驻 GL_ALPHA_TEST 下全部片段会被丢弃）。首次绘制自动执行纹理像素读回诊断：日志 `texture probe WxH tex=N alpha0=X% alpha255=Y%`（`alpha0=100%` = CEF 产了全透明帧，需查 CEF 背景色；有内容 = 纹理正常），并叠加 3 秒黄色参考方块辅助区分「纹理没内容」与「绘制路径失效」
- **强杀是最后的兜底**：90 秒 kill timer 假设世界保存已在 `running=false` 前完成（1.7.10 退出时序如此）；极端情况下若保存超过 90 秒（巨型存档 + 机械盘）理论上可能被误杀——出现请把 watchdog 日志发到 issue
- **键盘注入无 keyCode**：MCEF 0.6/0.7 的 `injectKeyXxx(char, modifiers)` 无法表达功能键（F1–F12、方向键、组合键），网页内这类按键可能无效
- **鼠标坐标缩放（beta.5）**：CEF 渲染分辨率与 GUI 显示尺寸解耦（默认物理像素，可手动改档），所有鼠标注入坐标按 `cefW/viewW` 缩放到 CEF 视口空间；若高分档（2160p）下点击明显偏移请反馈
- **虚拟模式降级**：CEF 初始化失败时 MCEF 降级为虚拟模式（无真实网页），本附属界面正常但内容为 MCEF 的占位画面
- **剪贴板读取**依赖系统剪贴板实现，在部分 Linux/Wayland 环境下 Ctrl+C/Ctrl+V 可能读不到内容（beta.3 剪贴板路径已随 `GuiTextField` 迁移到 vanilla 实现，行为与原版聊天框一致）

## 安装

| 组件 | 说明 |
| --- | --- |
| [MCphone (GTNH)](https://github.com/skyc10/mcphone-gtnh) | 本体，必需 |
| [MCEF](https://www.curseforge.com/minecraft/mc-mods/mcef) 1.7.10 | 必需，**真实浏览器模式**（非虚拟模式）。Java 17+ 运行时需支持其加载的构建 |
| WebDisplays 1.7.10 | 可选（本附属不再依赖它的方块屏） |

CEF 运行时文件（`libcef.dll` 等）需放在 JVM 能找到的库路径下。

## 构建

```bat
gradlew build
```

产物在 `build/libs/`。要求 JDK 17+（项目用 GTNH 惯例工具链 + Jabel）。

## 许可

MIT —— 见 [LICENSE](LICENSE)。
