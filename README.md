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
- **退出看门狗**：游戏退出时若 CEF 已初始化且清理线程挂死（`CefApp.dispose()` 阻塞），宽限 5 秒后强制结束进程，避免「退出后进程残留」；CEF 从未初始化（没用过浏览器）时看门狗不做任何动作，进程干净退出

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
