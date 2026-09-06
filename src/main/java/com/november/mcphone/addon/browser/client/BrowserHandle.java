package com.november.mcphone.addon.browser.client;

import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * net.montoyo.mcef.api.IBrowser 的反射包装（避免编译期依赖 MCEF）。
 */
@SideOnly(Side.CLIENT)
public final class BrowserHandle {

    private final Object browser;
    private final Method resize, close, draw, getTextureID, loadURL, goBack, goForward, getURL;
    private final Method injectMouseMove, injectMouseButton, injectMouseWheel;
    private final Method injectKeyPressed, injectKeyTyped, injectKeyReleased;
    private final Method runJS;

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
        injectMouseMove = c.getMethod("injectMouseMove", int.class, int.class, int.class, boolean.class);
        injectMouseButton = c.getMethod("injectMouseButton", int.class, int.class, int.class, int.class, boolean.class, int.class);
        injectMouseWheel = c.getMethod("injectMouseWheel", int.class, int.class, int.class, int.class, int.class);
        injectKeyPressed = c.getMethod("injectKeyPressed", char.class, int.class);
        injectKeyTyped = c.getMethod("injectKeyTyped", char.class, int.class);
        injectKeyReleased = c.getMethod("injectKeyReleased", char.class, int.class);
        runJS = c.getMethod("runJS", String.class, String.class);
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

    /** 当前页面纹理 ID；未绘制首帧时为 0。 */
    public int textureId() {
        try {
            return (Integer) getTextureID.invoke(browser);
        } catch (Throwable t) {
            return 0;
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

    public void injectMouseMove(int x, int y, int modifiers, boolean focus) {
        try {
            injectMouseMove.invoke(browser, x, y, modifiers, focus);
        } catch (Throwable t) {}
    }

    public void injectMouseButton(int x, int y, int btn, int count, boolean pressed, int modifiers) {
        try {
            injectMouseButton.invoke(browser, x, y, btn, count, pressed, modifiers);
        } catch (Throwable t) {}
    }

    /** (x, y, modifiers, scrollAmount, wheelRotation)；rotation 正值=向下滚。 */
    public void injectMouseWheel(int x, int y, int modifiers, int scrollAmount, int rotation) {
        try {
            injectMouseWheel.invoke(browser, x, y, modifiers, scrollAmount, rotation);
        } catch (Throwable t) {}
    }

    public void injectKeyPressed(char c, int key) {
        try {
            injectKeyPressed.invoke(browser, c, key);
        } catch (Throwable t) {}
    }

    public void injectKeyTyped(char c, int key) {
        try {
            injectKeyTyped.invoke(browser, c, key);
        } catch (Throwable t) {}
    }

    public void injectKeyReleased(char c, int key) {
        try {
            injectKeyReleased.invoke(browser, c, key);
        } catch (Throwable t) {}
    }

    public void runJS(String code) {
        try {
            runJS.invoke(browser, code, "");
        } catch (Throwable t) {}
    }
}
