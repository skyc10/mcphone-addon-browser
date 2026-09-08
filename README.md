# MCphone Addon: Browser（浏览器）

[MCphone (GTNH)](https://github.com/skyc10/mcphone-gtnh) 的附属模组：给手机加一个「览」浏览器 App。**点击图标直接打开一块 16:9 的全屏虚拟大屏**（约占游戏窗口 80%），由 MCEF/CEF 离屏渲染真实网页——不放置任何方块，不修改 MCphone 源码。

**Minecraft 1.7.10** · **GTNH 2.9.0-beta-3** · 客户端模组

## 功能

- **点击「览」图标** → 直接弹出全屏虚拟浏览器，加载上次网址；从未浏览过则加载默认主页 **Bing**
- **Shift+点击** → 手机内管理页：URL 输入、书签、历史记录（最近 20 条）、打开主页
- **自带书签**：首次使用预置「GTNH 中文 Wiki」→ `https://gtnh.huijiwiki.com/wiki/首页`
- **顶部工具栏**：`<` 后退 · `R` 刷新 · 地址栏（点击输入，Enter 跳转，Ctrl+V 粘贴）· `H` 主页 · `X` 关闭 · `F` 全屏切换 · `Esc` 关闭
- 鼠标移动/点击/滚轮/键盘全部注入 CEF，网页内可正常交互
- 上次网址、主页设置、书签、历史持久化在 `.minecraft/mcphone/addons/browser/`（跨存档共享）
- **MCEF 惰性初始化**：装 mod 不拉起 CEF——本附属 preInit 时把 `MCEF.PROXY` 临时换成服务端桩（纯反射），CEF 推迟到**玩家第一次打开浏览器 App** 才在主线程初始化（与 MCEF 原装在 FML init 阶段的时序等价）；初始化失败（含下载降级虚拟模式）会在下次打开时重试
- **退出看门狗**：游戏退出时若 CEF 已初始化且清理线程挂死（`CefApp.dispose()` 阻塞），宽限 5 秒后强制结束进程，避免「退出后进程残留」；CEF 从未初始化（没用过浏览器）时看门狗不做任何动作，进程干净退出。**90 秒无条件 kill timer** 兜底：退出后无论卡在哪，90 秒内进程必结束（kill 前会把全部非守护线程堆栈 dump 到 `logs/mcphone_browser_watchdog.log` 留证）
- **方法签名容错探测**：对 MCEF 各版本的注入方法签名（鼠标/键盘/滚轮/runJS）逐个探测，某个签名不匹配只降级禁用该功能并打日志，不再导致「浏览器内核创建失败」整页报错

## 待测项目（beta 反馈重点）

以下项目在 GTNH 2.9.0-beta-3 + MCEF 0.6 环境下**尚未完成实测**，欢迎按此清单反馈：

1. **浏览器打开与渲染**：首次点击「览」图标，CEF 正常拉起、页面渲染出内容（对应修复：6 参 `injectMouseButton` 签名查找 + 容错探测）
2. **网页交互**：鼠标移动/左中右键点击/滚轮/键盘输入是否全部生效（重点验证 `injectMouseButton` 6 参顺序是否与 GTNH MCEF 0.6 实现一致）
3. **退出进程清理**：打开过浏览器后退出游戏，进程应在 **约 5–95 秒内** 结束，无残留（对应修复：异步 close + 文件日志 + 90 秒 kill timer）
4. **未用浏览器的退出**：从不打开浏览器 App 直接退出，进程应立即干净结束（看门狗静默收工）
5. **看门狗日志**：若退出仍有异常，检查 `.minecraft/logs/mcphone_browser_watchdog.log`（退出阶段唯一可信日志通道），提交时请附上该文件

## 已知问题与限制

- **依赖 MCEF 版本差异**：针对 GTNH 的 MCEF 0.6（`renderer_` 字段、6 参 `injectMouseButton`）校准；其他 MCEF 分支若签名不同，对应功能会被自动禁用（日志有 `WARN: ... signature not found` 提示），核心浏览功能不受影响
- **MCEF 上游纹理初始化 bug**：`CefRenderer.initialize()` 在上游 jar 中无调用者，本附属反射兜底（需在渲染线程有 GL context 时执行一次）；若首次打开时纹理 ID 仍为 0 会放弃重试并打日志，画面可能停留在 loading——重开浏览器 App 可恢复
- **强杀是最后的兜底**：90 秒 kill timer 假设世界保存已在 `running=false` 前完成（1.7.10 退出时序如此）；极端情况下若保存超过 90 秒（巨型存档 + 机械盘）理论上可能被误杀——出现请把 watchdog 日志发到 issue
- **键盘注入无 keyCode**：MCEF 0.6/0.7 的 `injectKeyXxx(char, modifiers)` 无法表达功能键（F1–F12、方向键、组合键），网页内这类按键可能无效
- **虚拟模式降级**：CEF 初始化失败时 MCEF 降级为虚拟模式（无真实网页），本附属界面正常但内容为 MCEF 的占位画面
- **Ctrl+V 粘贴**依赖系统剪贴板，在部分 Linux/Wayland 环境下可能读不到内容

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
