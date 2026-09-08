package com.november.mcphone.addon.browser.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

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
 * <li>{@code injectKeyXxx(char, modifiers)}：keyCode 恒为 0，char 才是有效载荷，
 *     modifiers 传 0；</li>
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
    private final Method runJS;

    // ---- MCEF 上游纹理初始化兜底（见 initializeRenderer 注释） ----
    private final Object renderer;          // CefRenderer 实例（探测失败为 null）
    private final Method rendererInit;      // CefRenderer.initialize()（探测失败为 null）
    private boolean rendererInitialized;    // 兜底 initialize() 是否已执行（含失败，只跑一次）

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
        runJS = probe(c, "runJS", new Class<?>[]{String.class, String.class});

        // 探测 MCEF 0.6/0.7 上游 bug：CefRenderer.initialize()（glGenTextures 的唯一
        // 赋值点）在上游被孤儿化、无任何调用者，导致纹理 id 恒 0、画面永远停在
        // loading。这里只探测字段/方法是否存在并缓存，真正调用延迟到渲染线程
        // （有 GL context 时）由 ensureRendererInitialized() 执行。
        Object r = null;
        Method init = null;
        try {
            Field rf = c.getDeclaredField("renderer_");
            rf.setAccessible(true);
            r = rf.get(browser);
            if (r != null) {
                init = r.getClass().getDeclaredMethod("initialize");
                init.setAccessible(true);
            }
        } catch (Throwable t) {
            // 其他 MCEF 版本（无 renderer_ 字段/initialize 方法）可能已自行修复，
            // 打一行日志后不再尝试。
            System.out.println("[mcphone_browser] renderer init shim not applicable: " + t);
            r = null;
            init = null;
        }
        renderer = r;
        rendererInit = init;
        if (r != null) {
            System.out.println("[mcphone_browser] OSR renderer texture-init shim armed");
        }
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

    /** CefRenderer 渲染页面四边形（绑定纹理、处理翻转）。 */
    public void draw(double x1, double y1, double x2, double y2) {
        try {
            draw.invoke(browser, x1, y1, x2, y2);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] draw failed: " + t);
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

    public void loadURL(String url) {
        try {
            loadURL.invoke(browser, url);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] loadURL failed: " + t);
        }
    }

    public void goBack() {
        try {
            goBack.invoke(browser);
        } catch (Throwable t) {}
    }

    @SuppressWarnings("unused")
    public void goForward() {
        try {
            goForward.invoke(browser);
        } catch (Throwable t) {}
    }

    public String getURL() {
        try {
            return (String) getURL.invoke(browser);
        } catch (Throwable t) {
            return null;
        }
    }

    /** focus=false → MOUSE_MOVED；true → MOUSE_EXITED。 */
    public void injectMouseMove(int x, int y, int modifiers, boolean focus) {
        if (injectMouseMove == null) return;
        try {
            injectMouseMove.invoke(browser, x, y, modifiers, focus);
        } catch (Throwable t) {}
    }

    /** button 为 AWT 编号：1=左 2=中 3=右（modifiers, button, pressed, clickCount 顺序）。 */
    public void injectMouseButton(int x, int y, int modifiers, int button, boolean pressed, int clickCount) {
        if (injectMouseButton == null) return;
        try {
            injectMouseButton.invoke(browser, x, y, modifiers, button, pressed, clickCount);
        } catch (Throwable t) {}
    }

    /** rotation 正值=向下滚。 */
    public void injectMouseWheel(int x, int y, int modifiers, int scrollAmount, int rotation) {
        if (injectMouseWheel == null) return;
        try {
            injectMouseWheel.invoke(browser, x, y, modifiers, scrollAmount, rotation);
        } catch (Throwable t) {}
    }

    /** modifiers 恒传 0（keyCode 在 MCEF 0.7 中无法表达）。 */
    public void injectKeyPressed(char c, int modifiers) {
        if (injectKeyPressed == null) return;
        try {
            injectKeyPressed.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    public void injectKeyTyped(char c, int modifiers) {
        if (injectKeyTyped == null) return;
        try {
            injectKeyTyped.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    public void injectKeyReleased(char c, int modifiers) {
        if (injectKeyReleased == null) return;
        try {
            injectKeyReleased.invoke(browser, c, modifiers);
        } catch (Throwable t) {}
    }

    @SuppressWarnings("unused")
    public void runJS(String code) {
        if (runJS == null) return;
        try {
            runJS.invoke(browser, code, "");
        } catch (Throwable t) {}
    }
}
