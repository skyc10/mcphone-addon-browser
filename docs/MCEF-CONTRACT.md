# MCEF 契约冻结（MCEF-CONTRACT.md）

- 版本：**v1.0（冻结基线）**
- 冻结日期：2026-09-15
- 冻结依据：`mcphone-addon-browser` 分支 `feat/modern-mcef-port` @ `67c371a`
  （modern kernel 内嵌本仓源码 `src/main/java/net/montoyo/mcef/**` + `src/main/java/org/cef/**`；
  legacy 对照物 = `research/MCEF-1.7.10-final.jar`，javap 逐项核实）
- 任务来源：`roadmap-2026-09/22-plan-addon-browser.md` §5.3（X-06）、`23-plan-addon-wiki.md` §4.2-A/§4.3、`30-ROADMAP.md` X-06（**硬串行闸门②：先冻结契约，再抽共用层** AB-17/AW-08；顺序不可颠倒）
- 消费方：`mcphone-addon-wiki`（全程反射、不编译期依赖）与未来任何 CEF 消费附属
- 配套校验脚本：`tools/mcef-contract-check.sh`（javap 签名比对，见 §5）
- 冻结规则：**对本仓 `net/montoyo/mcef/**`、`org/cef/**` 的任何签名改动，必须同步修订本文档 +
  校验脚本，并在提交信息与给 wiki 维护者的通知里写明「是否影响 C-a…C-f」**（无契约已造成过 wiki 静默坏）

---

## 0. 使用本契约的三个前提

1. **运行时唯一 MCEF 提供方 = browser jar**（实例 `mods/` 无独立 MCEF；browser 内嵌
   `net/montoyo/mcef/*` 35 项 + `org/cef/*` 199 项 + natives 镜像引导）。wiki 等消费方以
   `cl.loadClass("net.montoyo.mcef.api.MCEFApi")` 取用。⇒ 本契约是"事实依赖"的显性化。
2. **`mods/` 里不允许同时存在独立 MCEF jar 与内嵌 MCEF 的附属 jar**（`org/cef` /
   `net.montoyo.mcef` 同包名类不可共存，"谁先加载谁生效"不可预测）。
3. 消费方只允许依赖本文列出的面；**未列出即未契约**（包括 `EventData`、`IScheme*` 等
   modern 扩展接口——scheme 管线未启用，见 C-b）。

---

## C-a 方法面 + 字段面（javap 逐项核实）

### C-a.1 `net.montoyo.mcef.api.MCEFApi`（入口类）

```java
public static net.montoyo.mcef.api.API getAPI();
public static boolean isMCEFLoaded();
```

### C-a.2 `net.montoyo.mcef.api.API`（modern 全 10 方法，逐项核实）

```java
net.montoyo.mcef.api.IBrowser createBrowser(java.lang.String, boolean);   // 主入口
default IBrowser createBrowser(java.lang.String);                         // 1 参兼容桥（transp=false；源码 default，jabel 产物打印为 abstract）
void registerDisplayHandler(net.montoyo.mcef.api.IDisplayHandler);
void registerJSQueryHandler(net.montoyo.mcef.api.IJSQueryHandler);
boolean isVirtual();                                                      // true=虚拟模式（createBrowser 返回 VirtualBrowser）
void openExampleBrowser(java.lang.String);                                // modern 未带示例 UI，不得依赖
java.lang.String mimeTypeFromExtension(java.lang.String);                 // modern 新增
void registerScheme(java.lang.String, java.lang.Class<? extends IScheme>, boolean, boolean, boolean, boolean, boolean, boolean, boolean);
boolean isSchemeRegistered(java.lang.String);                             // modern 新增（record-only，见 C-b）
java.lang.String punycode(java.lang.String);                              // modern 新增（IDN）
```

> 与 legacy（MCEF-0.7）差异：modern **新增** `mimeTypeFromExtension` / `punycode` /
> `registerScheme` / `isSchemeRegistered`。**modern 没有 `showBlankPage`**（22-plan C-b
> 选项②提到的 API 现不存在，属"待新增提案"，消费方不得等待该 API）。

### C-a.3 `net.montoyo.mcef.api.IBrowser`（modern 全 17 方法，逐项核实）

```java
void close();
void resize(int, int);
void draw(double, double, double, double);          // = CefRenderer.render；Angelica GLSM 下整面透明 → 消费方自绘（C-d②）
int  getTextureID();                                 // CefRenderer.texture_id_[0]
void injectMouseMove(int, int, int, boolean);
void injectMouseButton(int, int, int, int, boolean, int);
void injectKeyTyped(char, int);
void injectKeyPressedByKeyCode(int, char, int);      // modern 键盘 scancode 主通道
void injectKeyReleasedByKeyCode(int, char, int);
void injectMouseWheel(int, int, int, int, int);
void runJS(java.lang.String, java.lang.String);      // frame="" 表示默认帧；无返回值
void loadURL(java.lang.String);
void goBack();
void goForward();
java.lang.String getURL();
void visitSource(net.montoyo.mcef.api.IStringVisitor);  // 异步；回调在 CEF UI 线程 → 必须回主线程
boolean isPageLoading();                             // modern 新增
```

> **接口层级注意（javap 核实，易错点）**：`injectKeyPressed(char,int)` / `injectKeyReleased(char,int)`
> **不在 modern `IBrowser` 接口上** —— 它们只由 `CefBrowserOsr` 直接实现（char 通道是镜像内方法而
> 非契约接口方法；legacy `IBrowser` 才有这两个方法）。消费方按 `IBrowser` 编程时键盘主通道 =
> `injectKeyTyped` + `injectKeyPressed(By)KeyCode`；确需 char 通道时向下转型/probe
> `CefBrowserOsr` 本身（S5-5 签名容错覆盖这条路径）。
>
> 与 legacy 差异汇总：modern `IBrowser` **新增** `injectKeyPressedByKeyCode` /
> `injectKeyReleasedByKeyCode` / `isPageLoading`，**不再声明** `injectKeyPressed(char,int)` /
> `injectKeyReleased(char,int)`（legacy 16 方法 → modern 17 方法）。

### C-a.4 回调/访问者接口（javap 核实）

```java
interface IDisplayHandler {
    void onAddressChange(IBrowser, java.lang.String);
    void onTitleChange(IBrowser, java.lang.String);
    boolean onTooltip(IBrowser, java.lang.String);
    void onStatusMessage(IBrowser, java.lang.String);
    boolean onConsoleMessage(IBrowser, java.lang.String, java.lang.String, int);
}   // 内核派发链：ClientProxy.displayHandler（队列上限 512）+ 每帧 update() 排空；VIRTUAL 时不派发
interface IJSQueryHandler {
    boolean handleQuery(IBrowser, long, java.lang.String, boolean, IJSQueryCallback);
    void cancelQuery(IBrowser, long);
}   // mcefQuery 通道已接线：CefMessageRouterConfig("mcefQuery","mcefCancel")，页面可回传
interface IJSQueryCallback { void success(java.lang.String); void failure(int, java.lang.String); }
interface IStringVisitor { void visit(java.lang.String); }
interface IScheme { /* scheme 管线未启用，见 C-b；勿依赖 */ }
```

### C-a.5 `org.cef.browser.CefBrowserOsr`（字段面 + 关键方法）

```java
public class CefBrowserOsr extends org.cef.browser.CefBrowser_N
        implements org.cef.handler.CefRenderHandler, net.montoyo.mcef.api.IBrowser

// —— 字段面（消费方反射读取）——
protected org.cef.browser.CefRenderer renderer_;                       // GL 纹理所有者（close → renderer_.cleanup()）
protected final java.util.LinkedList<CefBrowserOsr.PaintData> queue;   // onPaint → mcefUpdate 帧队列（上限 3，见注）
public static boolean CLEANUP;                                          // 默认 true；GL 上下文死亡时 ClientProxy 置 false
protected java.awt.Rectangle browser_rect_;                             // CEF #1437 workaround

// —— 方法面（消费方反射调用）——
public synchronized void close();                    // 源码为 synchronized；jabel 产物 javap 不打印该修饰（不构成契约差异）
public void resize(int, int);                        // 视口重断言入口（C-d③）
public void draw(double, double, double, double);    // 见 C-d②：消费方不要用，自绘
public int getTextureID();
public void mcefUpdate();                            // 帧上传泵：排干 queue（中间帧合并为 fullReRender）→
                                                     //   renderer_.onPaint(false, dirtyRects, buffer, w, h, fullReRender)；
                                                     //   末尾无条件 sendMouseEvent(lastMouseEvent)（YouTube hack）；仅 GL 线程
public void injectKeyPressedByKeyCode(int, char, int);
public void injectKeyReleasedByKeyCode(int, char, int);
public boolean isPageLoading();
public void runJS(java.lang.String, java.lang.String);
public void visitSource(net.montoyo.mcef.api.IStringVisitor);
public static int remapKeycode(int);                 // GLFW 键码映射（F1..F12 → 290..301 属 AB 阶段 P2-8）
```

> 注：帧队列上限 3（`while(queue.size() >= 3) queue.removeFirst()`，`CefBrowserOsr.java:306-307`）；
> 消费方可反射读 `queue.size()` 仅供诊断；深度自适应（22-plan P2-12）会动这一行——改动时同步本契约。

### C-a.6 `org.cef.browser.CefRenderer`（字段面）

```java
class CefRenderer {
    public int[] texture_id_;          // [1]；[0]=纹理 id；0 = 未初始化
    private int view_width_;           // 消费方反射读（首帧探测 / 视口比对，C-d③）
    private int view_height_;
    private boolean initialized_;
    protected void initialize();       // 幂等；glGenTextures 必须 GL 线程
    protected void cleanup();          // 删纹理置 0（与 close 联动）
    public void render(double, double, double, double);
    protected void onPaint(boolean, java.awt.Rectangle[], java.nio.ByteBuffer, int, int, boolean);  // 6 参（modern）；legacy 为 5 参
}
```

### C-a.7 `org.cef.CefApp`（帧泵原语）

```java
public final native void N_DoMessageLoopWork();   // ⚠ 实例方法，不是 static
public static org.cef.CefApp getInstance(...) throws UnsatisfiedLinkError;
```

> 行文里常见「`CefApp.N_DoMessageLoopWork()`」易误读为静态；实际签名为**实例 final native**
> （`CefApp.java:509`）。反射写法：`app.getClass().getMethod("N_DoMessageLoopWork")` → 对
> 实例 invoke（即 `BrowserHandle.java:469` 现状）。

---

## C-b `mod://` 与引导页现状 → 冻结 data:/ 直连 URL 路线

**现状（冻结时点）**：
- modern 内核**没有 `mod://` scheme，也没有 `home.html`**：`ClientProxy.registerScheme`
  （`ClientProxy.java:590-597`）只登记名字，注释 *"the legacy reflection-driven scheme
  pipeline is not ported"*；产品 jar 资源清单无任何 html 页面（对照 legacy
  `research/MCEF-1.7.10-patched.jar` 才有 `assets/mcef/html/home.html` + `ModScheme.class`）。
- 任何 `mod://` URL 都变成 CEF 错误页（`ERR_UNKNOWN_URL_SCHEME`）。

**冻结决策**：
1. **data:/ 直连 URL 路线**：不设引导页，直接 `createBrowser(target_url)` 创建；竞态由
   消费方"首帧后重断言 resize（C-d③）"吸收。需要占位时，初始 URL 用
   `data:text/html;charset=utf-8,<url-encode(占位HTML)>`，首帧拿到后再 `loadURL(目标)`。
   **不改内核恢复 scheme**（22-plan C-b 选项①悬置：仅当仍有第三方 legacy mod 依赖 `mod://` 再议）。
2. **禁止依赖清单**：`mod://mcef/home.html`、`ModScheme`、jar 内嵌 html 引导页、
   `API.showBlankPage()`（不存在）、`file://` 引用外部文件（用户目录不可控）。
3. **历史写入过滤（消费方职责）**：`mod://.*` 与 `data:` 引导/占位 URL 不得进入
   history / lastUrl（23-plan W-02 教训）。

---

## C-c lazy-init 公开入口语义

**现状**：CEF 只能由 browser 拉起。`McefLazyInit`（`com.november.mcphone.addon.browser.client.McefLazyInit`）
是 **browser 内部类**：`defer()` 在 preInit 把 `MCEF.PROXY` 换成 `BaseProxy` 桩
（`isVirtual()=true`、`createBrowser` 返回 `VirtualBrowser`，`ClientProxy.java:531-533`），
首次打开浏览器才 `ensureInitialized()` 换回真 `ClientProxy`（日志：`MCEF CEF init deferred
until the browser app is first opened`）。wiki 没有任何公开入口 ⇒ "先开维基"拿到桩、一旦缓存
住就永久不可恢复（W-03/W-04）。

**冻结语义**：
1. **将暴露（实现在 AB-17，落地前未存在）**：`net.montoyo.mcef.api.MCEFApi.ensureStarted()`
   —— 静态、公开、幂等、**仅客户端主线程调用**；内部转 `McefLazyInit.ensureInitialized()`。
   返回/后果语义：调用返回后 `MCEF.PROXY` 处于三态之一：
   ① established：真实 `ClientProxy` 已就位，可 `createBrowser`；
   ② pending：初始化仍未开始/进行中 —— 本次调用视为"尚未可用"，不抛异常、不缓存；
   ③ failed：已确认失败，原因读 `failReason`（natives 下载失败 / 平台不支持等）。
2. 调用约束：幂等可重入；不得在服务端/非主线程调用；不得依赖 preInit 之前完成态。
3. **消费方三态探测 + 不缓存 pending（AW-06，AB-17 落地前即生效）**：每次打开 UI 重新探测；
   `isMCEFLoaded()`/`getAPI()` 失败、`isVirtual()==true` 都不能"一锤定音"缓存为永久失败；
   pending 态仅当次使用；`failReason` 用于可行动文案。
4. 失败文案模板（禁止误导性"浏览器内核不可用"）：*"需要安装 mcphone-addon-browser（提供
   MCEF 内核）；若已安装，请先打开一次浏览器 App 以启动内核。"*

---

## C-d 帧泵 / 自绘 / 视口 / 容错 4 项内核修复的归属

| # | 修复 | 内容 | 归属（冻结决策 = 22-plan 选项②） |
|---|---|---|---|
| ① | 帧泵 | GTNH+lwjgl3ify 下 MCEF 自带 `RenderTickEvent` 不触发 ⇒ `onPaint` 永不到 GL；嵌入方在 `drawScreen` 每帧自驱动 `CefApp.N_DoMessageLoopWork()` + `browser.mcefUpdate()`（幂等，与内核自带 tick 并存；不得引入独立线程泵——与 CEF UI 线程模型冲突） | **内核/共用门面（browser，AB-17/McphoneMcefHost）** |
| ② | 自绘 | `CefRenderer.render()`（`IBrowser.draw`）在 Angelica GLSM core-profile 下整面透明（UV 布点 + alpha test/blend）；消费方自绘 UV(0,0)-(1,1) 四边形、显式 `glDisable(GL_ALPHA_TEST)`/关 blend、`enable(GL_TEXTURE_2D)`、恢复 `glColor4f(1,1,1,1)` | 同上 |
| ③ | 视口 | 首帧后读 `renderer_.view_width_/view_height_` 与期望视口比对，不一致重断言 `resize(cefW,cefH)`；全部注入坐标乘 `cefW/viewW`（CEF 渲染分辨率 ≠ GUI 缩放尺寸）；分辨率档独立于 GUI（Auto 实测 1536×864） | 同上 |
| ④ | 签名容错 | 反射 probe（`BrowserHandle.probe()` 模式）：单个方法/构造缺失不整体失败，缺失项降级 no-op + 日志 | 同上（通用化） |

**过渡期（方案 A 维持复制，S0–S3）**：wiki 暂持自己的副本（AW-01 S0-2/3/4 + S5-5），
此后**每次 browser 改动 C-a/C-d 相关签名，必须人工同步 wiki 副本并跑双仓回归**
（历史上已滞后 12 提交一次，不允许复发）。

---

## C-e 退出清理：附属不自带看门狗（终局口径）

1. **冻结语义**：附属（browser/wiki 与未来任何 CEF 消费方）**不自带**退出看门狗与
   `Runtime.halt(0)` 调用；halt 唯一权威 = 本体 `ForceExitWatchdog`（HS-25 责任 + HS-31 仲裁：
   本体唯一 halt 权威，附属只登记清理回调并在预算内退出）。
2. **过渡期现状（冻结时点，登记在案、允许暂存）**：

   | 看门狗 | 门控 | 时间线 | 处置 |
   |---|---|---|---|
   | 本体 v3.2 | 心跳 + flag 文件 | 0–25 s 宽限 → 25 s halt 链 / 35 s 无条件 halt + 外部杀手 | 终局权威 |
   | browser v2（282 行） | `McefLazyInit.isCefActive()` | 5 s 宽限 → 90 s 无条件 halt | 过渡保留；AB-16 归口时删 |
   | wiki v2（261 行） | **无门控** | 同上（从未用 CEF 也走全流程） | AW-01 S0-7 先补本地门控；归口时整删 |

3. 新增附属/新消费方**禁止**再复制看门狗；退出清理 = 收到本体清理回调后异步 close 自己的
   浏览器，**不调用 halt**。改任何退出路径后必须回归"关游戏"全流程（含 5 s 内自然退出的
   `armed → force-closing → 无 90s kill` 正常形态）。

---

## C-f 附属间能力声明（待宿主 provides/requires）

1. **现状**：browser 内嵌 MCEF 但不向前声明；wiki 事实依赖 browser jar（G14）；双方
   `mcmod.info` 只声明 `dependencies = ["mcphone"]`。
2. **冻结方向（等本体 C10 = `IPhoneApp.provides()/requires()`，HS-07/HS-26 落地）**：
   - browser：`provides = { "mcef-kernel" }`（modern 内核 + CEF natives 提供方）；
   - wiki：`requires = { "mcef-kernel" }`，缺前置给可行动文案（S5-3），不静默坏。
3. **落地前（本契约接管期）**：双方 README 必须互相链接并指向本文档；安装说明写明
   「必须删除旧 MCEF jar」（同 §0-2）。

---

## 5. 校验脚本（供 CI 与发版前人工核对）

`tools/mcef-contract-check.sh` 对给定 jar 跑 `javap -p`，比对 C-a 全部签名与关键字段，
并做 legacy/modern 判别（发现 `net.montoyo.mcef.client.ModScheme` 或 `CefRenderer.onPaint`
为 5 参 → 判为 legacy 内核 → FAIL，按本契约消费的前提不成立）。

```bash
# 用法（WSL / Linux）：
tools/mcef-contract-check.sh <path-to-jar>
# jar 目标优先级：
#   1) $MCEF_CONTRACT_JAR
#   2) 实例 mods 里的内嵌附属 jar（Prism 290b3test）
#   3) 本仓 build/libs 最新产物
# 输出：逐项 PASS/FAIL + 总结；任一 FAIL 退出码 1（可作 CI 门禁）
```

CI 挂法：browser 发版工作流可在 tag 构建后对**产物 jar**自检（后续任务接上即可；本冻结交付脚本本体）。

---

## 6. 变更记录

| 版本 | 日期 | 内容 | 依据提交 |
|---|---|---|---|
| v1.0 | 2026-09-15 | 首次冻结：C-a…C-f + tools/mcef-contract-check.sh | feat/modern-mcef-port @ `67c371a` |
