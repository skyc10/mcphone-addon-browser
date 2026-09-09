package com.november.mcphone.addon.browser.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import net.minecraft.client.renderer.Tessellator;

/**
 * net.montoyo.mcef.api.IBrowser 的反射包装（避免编译期依赖 MCEF）。
 *
 * <p>注入方法参数已与 GTNH MCEF 0.6 的 {@code CefBrowserOsr} 实现核对
 * （内部构造 java.awt.event 事件）：</p>
 * <ul>
 * <li>{@code injectMouseMove(x, y, modifiers, focus)}：focus=false → MOUSE_MOVED，
 *     true → MOUSE_EXITED（id 505）；</li>
 * <li>{@code injectMouseButton(posX, posY, modifiers, button, pressed, clickCount)}
 *     ——6 参。旧版误查 5 参签名 {@code (x, y, modifiers, button, pressed)}，
 *     NoSuchMethodException 使 BrowserHandle 构造失败、createBrowser 整体报
 *     「内核创建失败」（GTNH 实测根因）；</li>
 * <li>{@code injectKeyXxx(char, modifiers)}：keyCode 恒为 0，char 才是有效载荷；
 *     modifiers 由调用方从 LWJGL 键状态计算（SHIFT 64 / CTRL 128 / ALT 512），
 *     native 层经 getModifiersEx 读取；</li>
 * <li>{@code injectMouseWheel(x, y, modifiers, scrollAmount, wheelRotation)}：
 *     rotation 正值=向下滚。</li>
 * </ul>
 *
 * <p>核心方法（resize/close/draw/getTextureID/loadURL/goBack/goForward/getURL）
 * 是 IBrowser 接口方法、各版本必有，查找失败直接抛错；注入方法用 {@link #probe}
 * 容错探测——某个签名对不上只禁用对应功能，不再拖垮整个 createBrowser。</p>
 */
@SideOnly(Side.CLIENT)
public final class BrowserHandle {

    private final Object browser;
    private final Method resize, close, draw, getTextureID, loadURL, goBack, goForward, getURL;
    private final Method injectMouseMove, injectMouseButton, injectMouseWheel;
    private final Method injectKeyPressed, injectKeyTyped, injectKeyReleased;
    private final Method setFocus;
    private final Method runJS;

    // ---- MCEF 上游纹理初始化兜底（见 initializeRenderer 注释） ----
    private final Object renderer;          // CefRenderer 实例（探测失败为 null）
    private final Method rendererInit;      // CefRenderer.initialize()（探测失败为 null）
    private boolean rendererInitialized;    // 兜底 initialize() 是否已执行（含失败，只跑一次）

    // ---- 帧泵：GTNH 环境下 MCEF ClientProxy.onTick 未被驱动，onPaint 缓存的帧
    // 永远等不到 mcefUpdate() 上传（CefRenderer.view_width_/view_height_ 只在
    // renderer.onPaint 里赋值，恒 0 → render() 开头守卫直接 return → 画面全透明）。
    // 我们在 drawScreen 里自驱动：N_DoMessageLoopWork() 泵 CEF 消息 + mcefUpdate()
    // 上传排队帧。字段全部容错探测，缺哪个只降级对应功能。
    private final Method mcefUpdate;        // CefBrowserOsr.mcefUpdate()（上传排队帧）
    private final Field viewWidthField;     // CefRenderer.view_width_（>0 = 至少成功上传过一帧）
    private final Field viewHeightField;    // CefRenderer.view_height_
    private final Field queueField;         // CefBrowserOsr.queue（诊断：帧是否到达）

    BrowserHandle(Object browser) throws Exception {
        this.browser = browser;
        Class<?> c = browser.getClass();
        resize = c.getMethod("resize", int.class, int.class);
        close = c.getMethod("close");
        draw = c.getMethod("draw", double.class, double.class, double.class, double.class);
        getTextureID = c.getMethod("getTextureID");
        loadURL = c.getMethod("loadURL", String.class);
        goBack = c.getMethod("goBack");
        goForward = c.getMethod("goForward");
        getURL = c.getMethod("getURL");
        injectMouseMove = probe(c, "injectMouseMove", new Class<?>[]{int.class, int.class, int.class, boolean.class});
        injectMouseButton = probe(c, "injectMouseButton", new Class<?>[]{
            int.class, int.class, int.class, int.class, boolean.class, int.class});
        injectMouseWheel = probe(c, "injectMouseWheel",
            new Class<?>[]{int.class, int.class, int.class, int.class, int.class});
        injectKeyPressed = probe(c, "injectKeyPressed", new Class<?>[]{char.class, int.class});
        injectKeyTyped = probe(c, "injectKeyTyped", new Class<?>[]{char.class, int.class});
        injectKeyReleased = probe(c, "injectKeyReleased", new Class<?>[]{char.class, int.class});
        setFocus = probe(c, "setFocus", new Class<?>[]{boolean.class});
        runJS = probe(c, "runJS", new Class<?>[]{String.class, String.class});

        // 探测 MCEF 0.6/0.7 上游 bug：CefRenderer.initialize()（glGenTextures 的唯一
        // 赋值点）在上游被孤儿化、无任何调用者，导致纹理 id 恒 0、画面永远停在
        // loading。这里只探测字段/方法是否存在并缓存，真正调用延迟到渲染线程
        // （有 GL context 时）由 ensureRendererInitialized() 执行。
        Object r = null;
        Method init = null;
        Field vw = null;
        Field vh = null;
        try {
            Field rf = c.getDeclaredField("renderer_");
            rf.setAccessible(true);
            r = rf.get(browser);
            if (r != null) {
                init = r.getClass().getDeclaredMethod("initialize");
                init.setAccessible(true);
                vw = r.getClass().getDeclaredField("view_width_");
                vw.setAccessible(true);
                vh = r.getClass().getDeclaredField("view_height_");
                vh.setAccessible(true);
            }
        } catch (Throwable t) {
            // 其他 MCEF 版本（无 renderer_ 字段/initialize 方法）可能已自行修复，
            // 打一行日志后不再尝试。
            System.out.println("[mcphone_browser] renderer init shim not applicable: " + t);
            r = null;
            init = null;
            vw = null;
            vh = null;
        }
        renderer = r;
        rendererInit = init;
        viewWidthField = vw;
        viewHeightField = vh;
        if (r != null) {
            System.out.println("[mcphone_browser] OSR renderer texture-init shim armed");
        }

        // 帧泵探测：mcefUpdate()（帧上传入口）+ queue（帧队列，诊断用）。
        Method upd = probe(c, "mcefUpdate", new Class<?>[0]);
        Field q = null;
        try {
            q = c.getDeclaredField("queue");
            q.setAccessible(true);
        } catch (Throwable t) {
            System.out.println("[mcphone_browser] paint queue probe failed (diagnostics disabled): " + t);
        }
        mcefUpdate = upd;
        queueField = q;
        System.out.println("[mcphone_browser] frame pump armed (mcefUpdate=" + (upd != null)
            + ", queue=" + (q != null) + ", viewFields=" + (vw != null) + ")");
    }

    /**
     * 容错方法探测：找到返回 Method，找不到打日志返回 null（对应功能降级禁用），
     * 绝不让注入类方法的不匹配拖垮 BrowserHandle 构造（createBrowser 报
     * 「内核创建失败」的旧根因）。
     */
    private static Method probe(Class<?> c, String name, Class<?>[] paramTypes) {
        try {
            return c.getMethod(name, paramTypes);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] WARN: " + c.getName() + "." + name
                + " signature not found (feature disabled): " + t);
            return null;
        }
    }

    public void resize(int w, int h) {
        try {
            resize.invoke(browser, w, h);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] resize failed: " + t);
        }
    }

    public void close() {
        try {
            close.invoke(browser);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] close failed: " + t);
        }
    }

    /**
     * OSR 浏览器显式获焦。上游 JCEF 在 createBrowserIfRequired 里创建后必调
     * setFocus(true)；不调的话 CEF 收到点击/键盘事件但认为自己无焦点、直接忽略
     * ——「点击/键盘全灭」的头号嫌疑：createBrowser 返回时 native browser 尚在
     * 异步创建，create 后立刻调的 setFocus 会落在 CEF 内部 browser 指针为空的
     * 窗口期被静默丢弃。因此除了创建时，还要在首帧上传后、页面点击时重挂
     * （见 BrowserScreen 三处调用点）。
     */
    public void setFocus(boolean focus) {
        if (setFocus == null) {
            return;
        }
        try {
            setFocus.invoke(browser, focus);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] setFocus failed: " + t);
        }
    }

    /** 首帧上传后应重挂一次焦点（browser 异步创建完毕，此时 setFocus 才真正生效）。 */
    void onFirstFrameUploaded() {
        System.out.println("[mcphone_browser] first frame uploaded — re-focusing browser");
        setFocus(true);
    }

    /** CefRenderer 渲染页面四边形（绑定纹理、处理翻转）。 */
    public void draw(double x1, double y1, double x2, double y2) {
        try {
            draw.invoke(browser, x1, y1, x2, y2);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] draw failed: " + t);
        }
    }

    /**
     * 自绘页面四边形——「透明页面」的绘制侧修复，绕开 MCEF CefRenderer.render()。
     *
     * <p>取证定案（Angelica 字节码级）：帧上传链完好（日志 texture id=204、
     * first frame uploaded 384x216、mcefUpdate→onPaint→glTexSubImage2D 无误），
     * 页面区域却完全透明。差异不在 Tessellator 本身——字号/drawRect 同样经
     * Angelica GLSM 流水线且显示正常——而在两处绘制侧风险点：</p>
     * <ol>
     * <li><b>纹理 alpha 通道</b>：CEF OSR 默认背景色 alpha 可能为 0（透明）。
     * vanilla 管线常驻 GL_ALPHA_TEST（GREATER, 0.1），alpha&lt;0.1 的片段全部
     * 被丢弃 → 四边形整体透明，与症状精确吻合；</li>
     * <li><b>CefRenderer.render 的 UV 缺陷</b>（v1=(1,1) 而非 (1,0)、v4 重复
     * v1 的 UV），在 GLSM 的 FFP shader 变体下行为不确定。</li>
     * </ol>
     *
     * <p>因此自绘：正确 UV(0,0)-(1,1) + 显式关 alpha test/blend（纹理 alpha
     * 无关紧要），不再依赖 MCEF 的 render()。内部覆盖 {@link #draw} 语义，
     * 由 BrowserScreen 调用。</p>
     *
     * <p>附带一次性诊断：首绘时 glGetTexImage 读回纹理 alpha 分布（全 0 = CEF
     * 产的就是全透明帧，问题在 CEF 侧背景色；有内容 = 纹理没问题，坐实绘制侧），
     * 并在纹理同一位置画一个 20x20 参考方块——参考块可见而页面不可见 = 纹理外
     * 因（GLSM 状态/混合），两者都不可见 = 该位置的纹理绘制路径整体失效。</p>
     */
    public void drawSelf(double x1, double y1, double x2, double y2) {
        int tex = textureId();
        if (tex == 0) {
            return;
        }
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            runFirstFrameDiagnostics(x1, y1, tex);

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_BLEND);     // CEF 帧 alpha 不参与混合
            GL11.glDisable(GL11.GL_ALPHA_TEST); // 防 alpha=0 帧被整体丢弃
            GL11.glColor4f(1f, 1f, 1f, 1f);

            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            t.setColorOpaque_F(1f, 1f, 1f);
            // 正确朝向的 UV：CEF onPaint 帧顶行在 buffer 开头 → 纹理 t=0 为页面顶部。
            // 1.7.10 GUI y 向下，故 v = y 对应 (y1→0, y2→1)。
            t.addVertexWithUV(x1, y1, 0, 0d, 0d);
            t.addVertexWithUV(x1, y2, 0, 0d, 1d);
            t.addVertexWithUV(x2, y2, 0, 1d, 1d);
            t.addVertexWithUV(x2, y1, 0, 1d, 0d);
            t.draw();

            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable tr) {
            System.err.println("[mcphone_browser] drawSelf failed: " + tr);
        }
        drawRefSquareIfDue();
    }

    // ---- 一次性诊断（首绘时执行一次） ----
    private boolean diagDone;
    private int diagAttempts;

    private void runFirstFrameDiagnostics(double x1, double y1, int tex) {
        if (diagDone) {
            return;
        }
        int w = cefViewWidth();
        int h = 0;
        try {
            h = (w > 0 && viewHeightField != null && renderer != null) ? viewHeightField.getInt(renderer) : 0;
        } catch (Throwable t) {
            // 视口高度读不到就跳过本轮诊断，下帧重试
        }
        if (w <= 0 || h <= 0) {
            // 帧还没上传，下次再试（最多 600 次 ≈ 10 秒，防每帧刷日志）
            if (++diagAttempts == 600) {
                System.out.println("[mcphone_browser] diag skipped: view still " + w + "x" + h);
                diagDone = true;
            }
            return;
        }
        diagDone = true;
        try {
            // 读回纹理，统计 alpha=0 / alpha=255 占比（GL_BGRA 布局：B,G,R,A）
            java.nio.IntBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            int n = w * h;
            int zeros = 0;
            int opaque = 0;
            for (int i = 0; i < n; i++) {
                int a = (px.get(i) >>> 24) & 0xFF;
                if (a == 0) zeros++;
                if (a == 0xFF) opaque++;
            }
            System.out.println("[mcphone_browser] texture probe " + w + "x" + h
                + " tex=" + tex + " alpha0=" + (zeros * 100 / n) + "% alpha255=" + (opaque * 100 / n) + "%"
                + (zeros == n ? "  << CEF produced a fully transparent frame!" : ""));
        } catch (Throwable t) {
            System.out.println("[mcphone_browser] texture probe failed: " + t);
        }
        // 参考方块：与页面同一绑定纹理状态下画在页面左上角 (bx+2,by+2)，持续 3 秒
        // （refUntil = 系统毫秒）。页面四边形与参考块都不可见 → 该位置纹理绘制整体
        // 失效（GLSM 侧问题）；参考块可见、页面不可见 → CEF 帧 alpha=0 被丢弃或纹理
        // 内容异常（对照 probe 输出的 alpha 分布）。
        refSquareUntil = System.currentTimeMillis() + 3000;
        refX = x1;
        refY = y1;
        System.out.println("[mcphone_browser] reference square armed at (" + (int) x1 + "," + (int) y1 + ") for 3s");
    }

    /** 参考方块：显示截止时刻与位置（诊断期）。 */
    private long refSquareUntil;
    private double refX, refY;

    /** 每帧调用：诊断参考方块仍在其显示窗口内时画出来（不透明黄色）。 */
    private void drawRefSquareIfDue() {
        if (refSquareUntil == 0 || System.currentTimeMillis() > refSquareUntil) {
            return;
        }
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColor4f(1f, 1f, 0f, 1f);
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            t.setColorOpaque_F(1f, 1f, 0f);
            t.addVertex(refX + 2, refY + 2, 0);
            t.addVertex(refX + 2, refY + 22, 0);
            t.addVertex(refX + 22, refY + 22, 0);
            t.addVertex(refX + 22, refY + 2, 0);
            t.draw();
            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] reference square failed: " + t);
        }
    }

    /**
     * 当前页面纹理 ID；未绘制首帧时为 0。
     *
     * <p>MCEF 0.6/0.7 上游 bug 兜底：CefRenderer.initialize()（纹理 id 的唯一
     * 赋值点 glGenTextures）在 MCEF jar 中无任何调用者，getTextureID() 恒 0。
     * 首次在此读到 0 且兜底尚未执行时调用一次 initialize()。本方法只在
     * BrowserScreen.drawScreen（Client thread 渲染路径，有 GL context）中被调用，
     * 因此在这里执行 glGenTextures 是线程安全的。</p>
     */
    public int textureId() {
        try {
            int id = (Integer) getTextureID.invoke(browser);
            if (id == 0) {
                ensureRendererInitialized();
                id = (Integer) getTextureID.invoke(browser);
            }
            return id;
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] getTextureID failed: " + t);
            return 0;
        }
    }

    /**
     * MCEF 上游纹理初始化兜底（反射调用 CefRenderer.initialize() 一次）。
     *
     * <p>调用时机约束：必须在持有 GL context 的线程上执行（glGenTextures 依赖
     * 当前 context）。BrowserScreen.drawScreen 在 Client thread 渲染路径上调用
     * 本方法与 {@link #textureId()}，满足约束。无论成败只执行一次：失败后不再
     * 重试，避免每帧反射调用刷屏。</p>
     */
    void ensureRendererInitialized() {
        if (rendererInitialized || rendererInit == null) {
            return;
        }
        rendererInitialized = true;
        try {
            rendererInit.invoke(renderer);
            int id = (Integer) getTextureID.invoke(browser);
            if (id != 0) {
                System.out.println("[mcphone_browser] CefRenderer.initialize() invoked (texture id=" + id + ")");
            } else {
                System.out.println(
                    "[mcphone_browser] WARN: CefRenderer.initialize() invoked but texture id still 0"
                        + " (GL context unavailable? giving up, no retry)");
            }
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] CefRenderer.initialize() failed: " + t);
        }
    }

    // ===================== 帧泵 =====================

    private static Method messageLoop;      // CefApp.N_DoMessageLoopWork()（静态缓存）
    private static boolean messageLoopProbed;
    private boolean firstFrameLogged;

    /**
     * 帧上传泵——「页面透明」的修复核心。
     *
     * <p>部署的 MCEF 0.7 中，CEF 的 onPaint 只把帧缓存进 {@code queue}
     * （LinkedList&lt;PaintData&gt;），真正 glTexImage2D 上传纹理的是
     * {@code mcefUpdate()}；其唯一调用方是 MCEF ClientProxy.onTick
     * （RenderTickEvent START）。GTNH 环境下该回调未被驱动，帧永远停在队列里，
     * CefRenderer.view_width_/view_height_（只在 renderer.onPaint 里赋值）恒 0，
     * render() 开头 {@code if(view_width_==0||view_height_==0) return;} 静默不画
     * ——textureId 非 0 但像素全空，页面完全透明。</p>
     *
     * <p>在 drawScreen（GL 线程）每帧自驱动：
     * ① {@code CefApp.N_DoMessageLoopWork()} 泵 CEF 消息循环（把 onPaint 送达）；
     * ② {@code mcefUpdate()} 把队列里的帧上传到纹理。两者与 MCEF 自带的 onTick
     * 并存幂等无害（mcefUpdate synchronized、队列空即空转），故不检测/不依赖
     * MCEF 自己的泵是否工作。</p>
     */
    public void pumpFrameUpload() {
        if (mcefUpdate == null) {
            return;
        }
        try {
            Object app = McefBridge.cefAppHandle();
            if (app != null) {
                Method ml = messageLoopMethod(app);
                if (ml != null) {
                    ml.invoke(app);
                }
            }
        } catch (Throwable t) {
            // 消息循环泵失败只降级：CEF 内部线程仍可能继续送帧，不刷屏
        }
        try {
            mcefUpdate.invoke(browser);
            if (!firstFrameLogged && viewWidthField != null && renderer != null) {
                int w = viewWidthField.getInt(renderer);
                int h = viewHeightField.getInt(renderer);
                if (w > 0 && h > 0) {
                    firstFrameLogged = true;
                    System.out.println("[mcphone_browser] first frame uploaded (" + w + "x" + h + ")");
                    onFirstFrameUploaded();
                }
            }
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] mcefUpdate failed: " + t);
        }
    }

    private static Method messageLoopMethod(Object app) {
        if (!messageLoopProbed) {
            messageLoopProbed = true;
            try {
                messageLoop = app.getClass().getMethod("N_DoMessageLoopWork");
            } catch (Throwable t) {
                System.err.println("[mcphone_browser] WARN: CefApp.N_DoMessageLoopWork not found"
                    + " (message-loop pump disabled): " + t);
            }
        }
        return messageLoop;
    }

    // ===================== 诊断 =====================

    /**
     * 等待上传的帧数（queue 深度）。&gt;0 = CEF 在产帧、只是没被上传；
     * 0 = CEF 没有产出新帧；-1 = 探测失败（旧版 MCEF，无诊断能力）。
     */
    public int queuedFrames() {
        if (queueField == null) {
            return -1;
        }
        try {
            return ((java.util.LinkedList<?>) queueField.get(browser)).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 渲染视口宽（直接读 CefRenderer.view_width_ 字段）。&gt;0 = 至少成功上传过
     * 一帧；0 = 从未上传（与「透明」症状对应）；-1 = 探测失败。
     */
    public int cefViewWidth() {
        if (viewWidthField == null || renderer == null) {
            return -1;
        }
        try {
            return viewWidthField.getInt(renderer);
        } catch (Throwable t) {
            return -1;
        }
    }

    public void loadURL(String url) {
        try {
            loadURL.invoke(browser, url);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] loadURL failed: " + t);
            return;
        }
        // 跳转/刷新后 CEF 的焦点态会随新页面重置，重挂一次；地址栏编辑态下
        // 不会走到这里（navigate() 先 setEditMode(false)）。
        setFocus(true);
    }

    public void goBack() {
        try {
            goBack.invoke(browser);
        } catch (Throwable t) {
            logInjectFailure("goBack", t);
        }
    }

    @SuppressWarnings("unused")
    public void goForward() {
        try {
            goForward.invoke(browser);
        } catch (Throwable t) {
            logInjectFailure("goForward", t);
        }
    }

    public String getURL() {
        try {
            return (String) getURL.invoke(browser);
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================== 输入注入 =====================

    // 静默吞异常曾是排查期的坑：注入真失败时无任何日志。现在首次失败打一行
    // WARN（含异常），后续静默——反射调用失败通常是持久性的，刷屏无益。
    private boolean injectFailureLogged;

    private void logInjectFailure(String what, Throwable t) {
        if (!injectFailureLogged) {
            injectFailureLogged = true;
            System.err.println("[mcphone_browser] WARN: " + what
                + " injection failed (further failures silent): " + t);
        }
    }

    /** focus=false → MOUSE_MOVED；true → MOUSE_EXITED。 */
    public void injectMouseMove(int x, int y, int modifiers, boolean focus) {
        if (injectMouseMove == null) return;
        try {
            injectMouseMove.invoke(browser, x, y, modifiers, focus);
        } catch (Throwable t) {
            logInjectFailure("mouse move", t);
        }
    }

    /** button 为 AWT 编号：1=左 2=中 3=右（modifiers, button, pressed, clickCount 顺序）。 */
    public void injectMouseButton(int x, int y, int modifiers, int button, boolean pressed, int clickCount) {
        if (injectMouseButton == null) return;
        try {
            injectMouseButton.invoke(browser, x, y, modifiers, button, pressed, clickCount);
        } catch (Throwable t) {
            logInjectFailure("mouse button", t);
        }
    }

    /** rotation 正值=向下滚。 */
    public void injectMouseWheel(int x, int y, int modifiers, int scrollAmount, int rotation) {
        if (injectMouseWheel == null) return;
        try {
            injectMouseWheel.invoke(browser, x, y, modifiers, scrollAmount, rotation);
        } catch (Throwable t) {
            logInjectFailure("mouse wheel", t);
        }
    }

    /**
     * 键盘注入（MCEF 0.6/0.7 的 keyCode 恒为 0——非字符键无法表达）。
     *
     * <p>modifiers 传 AWT 修饰键掩码（native 层经 {@code KeyEvent.getModifiersEx}
     * 读取，恒 0 会让 CEF 认为修饰键全松开）：LWJGL 的 Keyboard 键位算出
     * SHIFT=64 / CTRL=128 / ALT=512（{@code InputEvent.SHIFT_DOWN_MASK} 等），
     * 两手修饰键都查（LShift 42/RShift 54、LCtrl 29/RCtrl 157、LAlt 56/RAlt 184）。</p>
     */
    public void injectKeyPressed(char c, int modifiers) {
        if (injectKeyPressed == null) return;
        try {
            injectKeyPressed.invoke(browser, c, modifiers);
        } catch (Throwable t) {
            logInjectFailure("key pressed", t);
        }
    }

    public void injectKeyTyped(char c, int modifiers) {
        if (injectKeyTyped == null) return;
        try {
            injectKeyTyped.invoke(browser, c, modifiers);
        } catch (Throwable t) {
            logInjectFailure("key typed", t);
        }
    }

    public void injectKeyReleased(char c, int modifiers) {
        if (injectKeyReleased == null) return;
        try {
            injectKeyReleased.invoke(browser, c, modifiers);
        } catch (Throwable t) {
            logInjectFailure("key released", t);
        }
    }

    @SuppressWarnings("unused")
    public void runJS(String code) {
        if (runJS == null) return;
        try {
            runJS.invoke(browser, code, "");
        } catch (Throwable t) {
            logInjectFailure("runJS", t);
        }
    }
}
