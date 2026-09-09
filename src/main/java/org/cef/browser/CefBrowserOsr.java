// Copyright (c) 2014 The Chromium Embedded Framework Authors. All rights
// reserved. Use of this source code is governed by a BSD-style license that
// can be found in the LICENSE file.

// GTNH 1.7.10 modern-MCEF port: this file is the CCBlueX java-cef fork's
// CefBrowserOsr (upstream commit b853a9d87fd0a7553001ce0785fee73d55be8d64)
// extended in-tree with the montoyo/MCEF compatibility face:
//  - protected CefRenderer renderer_ + PaintData queue drained by mcefUpdate()
//    on the GL thread (CEF onPaint only buffers frames here);
//  - net.montoyo.mcef.api.IBrowser implementation with the historical
//    inject* signatures used by 1.7.10 mods (AWT-style modifier masks and
//    button numbering are converted to the GLFW-style values the fork's JNI
//    bridge expects, see GetCefModifiersGlfw in CefBrowser_N.cpp);
//  - char-first injectKeyPressed/injectKeyReleased/injectKeyTyped entry
//    points used by the mcphone_browser addon bridge.
// The upstream onPaint listener API (CefPaintEvent consumers) is preserved.

package org.cef.browser;

import net.montoyo.mcef.MCEF;
import net.montoyo.mcef.api.IBrowser;
import net.montoyo.mcef.api.IStringVisitor;
import net.montoyo.mcef.client.ClientProxy;
import net.montoyo.mcef.client.StringVisitor;
import net.montoyo.mcef.utilities.Log;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;
import org.cef.handler.CefAcceleratedPaintInfo;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * This class represents an off-screen rendered browser.
 * The visibility of this class is "package". To create a new
 * CefBrowser instance, please use CefBrowserFactory.
 */
public class CefBrowserOsr extends CefBrowser_N implements CefRenderHandler, IBrowser {
    private boolean justCreated_ = false;
    protected Rectangle browser_rect_ = new Rectangle(0, 0, 1, 1); // Work around CEF issue #1437.
    private Point screenPoint_ = new Point(0, 0);
    private double scaleFactor_ = 1.0;
    private int depth = 32;
    private int depth_per_component = 8;
    private boolean isTransparent_;

    private CopyOnWriteArrayList<Consumer<CefPaintEvent>> onPaintListeners = new CopyOnWriteArrayList<>();

    private CopyOnWriteArrayList<Consumer<CefAcceleratedPaintEvent>> onAcceleratedPaintListeners =
            new CopyOnWriteArrayList<>();

    // ===================== montoyo/MCEF compatibility face =====================

    public static boolean CLEANUP = true;

    // GL thread-side renderer (texture owner). Named renderer_ on purpose:
    // embedding mods reflect on this field to arm their texture shims.
    protected CefRenderer renderer_;

    // A frame produced by CEF's UI thread, waiting to be uploaded to GL.
    private static class PaintData {
        ByteBuffer buffer;
        int width;
        int height;
        Rectangle[] dirtyRects;
        boolean fullReRender;
    }

    // Frames buffered by onPaint() (CEF thread) and drained by mcefUpdate()
    // (GL thread). Named queue on purpose: embedding mods read its depth for
    // diagnostics.
    protected final LinkedList<PaintData> queue = new LinkedList<>();

    // Last mouse position, re-sent on every mcefUpdate(): works around pages
    // (YouTube) that stop rendering video when CEF believes the mouse is
    // idle. Same trick as upstream MCEF.
    private CefMouseEvent lastMouseEvent = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, 0, 0, 0, 0, 0);

    // Addon key presses that arrive as chars; the release path needs the
    // matching char again (historical MCEF "WORST_HACK").
    private static final HashMap<Integer, Character> WORST_HACK = new HashMap<>();

    // GLFW action codes used by the fork's JNI bridge (org.lwjgl.glfw.GLFW
    // is not on the 1.7.10 compile classpath, so the values are inlined).
    private static final int GLFW_PRESS = 1;
    private static final int GLFW_RELEASE = 0;

    // AWT InputEvent _DOWN_MASK values sent by 1.7.10 embedders.
    private static final int AWT_SHIFT = 64;
    private static final int AWT_CTRL = 128;
    private static final int AWT_ALT = 512;
    private static final int AWT_BTN_LEFT = 1024;
    private static final int AWT_BTN_MIDDLE = 2048;
    private static final int AWT_BTN_RIGHT = 4096;

    /**
     * Converts historical MCEF AWT modifier masks into the GLFW-style mask
     * expected by GetCefModifiersGlfw() in the fork's JNI bridge:
     * SHIFT=1, CTRL=2, ALT=4, buttons 0x10/0x20/0x40 (identical in both).
     */
    private static int awtToGlfwMods(int mods) {
        int out = 0;
        if((mods & AWT_SHIFT) != 0)
            out |= 1;
        if((mods & AWT_CTRL) != 0)
            out |= 2;
        if((mods & AWT_ALT) != 0)
            out |= 4;
        if((mods & AWT_BTN_LEFT) != 0)
            out |= CefMouseEvent.BUTTON1_MASK;
        if((mods & AWT_BTN_MIDDLE) != 0)
            out |= CefMouseEvent.BUTTON2_MASK;
        if((mods & AWT_BTN_RIGHT) != 0)
            out |= CefMouseEvent.BUTTON3_MASK;
        return out;
    }

    /**
     * Converts historical MCEF button numbering (1=left, 2=middle, 3=right)
     * to GLFW button indices (0/1/2). Returns -1 for unknown buttons.
     */
    private static int awtToGlfwButton(int btn) {
        if(btn == 1)
            return 0;
        if(btn == 2)
            return 1;
        if(btn == 3)
            return 2;
        return -1;
    }

    /**
     * Maps control characters to the GLFW key codes the Linux JNI bridge
     * recognizes (GLFW_KEY_ENTER/BACKSPACE/TAB/ESCAPE). Returns 0 for
     * printable characters (the bridge then uses key_char directly).
     */
    private static int controlCharToGlfwKey(char c) {
        switch(c) {
        case '\n':
        case '\r':
            return 257; // GLFW_KEY_ENTER
        case '\b':
            return 259; // GLFW_KEY_BACKSPACE
        case '\t':
            return 258; // GLFW_KEY_TAB
        case 27:
            return 256; // GLFW_KEY_ESCAPE
        default:
            return 0;
        }
    }

    /**
     * Maps 1.7.10 LWJGL Keyboard codes to GLFW key codes for the keys the
     * Linux JNI bridge maps to X keysyms (navigational keys); everything
     * else falls back to key_char on the native side.
     */
    public static int remapKeycode(int kc) {
        switch(kc) {
        case 28:  return 257; // KEY_RETURN  -> GLFW_KEY_ENTER
        case 14:  return 259; // KEY_BACK    -> GLFW_KEY_BACKSPACE
        case 211: return 261; // KEY_DELETE  -> GLFW_KEY_DELETE
        case 208: return 264; // KEY_DOWN    -> GLFW_KEY_DOWN
        case 200: return 265; // KEY_UP      -> GLFW_KEY_UP
        case 203: return 263; // KEY_LEFT    -> GLFW_KEY_LEFT
        case 205: return 262; // KEY_RIGHT   -> GLFW_KEY_RIGHT
        case 15:  return 258; // KEY_TAB     -> GLFW_KEY_TAB
        case 1:   return 256; // KEY_ESCAPE  -> GLFW_KEY_ESCAPE
        case 201: return 266; // KEY_PRIOR   -> GLFW_KEY_PAGE_UP
        case 209: return 267; // KEY_NEXT    -> GLFW_KEY_PAGE_DOWN
        case 207: return 268; // KEY_END     -> GLFW_KEY_END
        case 199: return 269; // KEY_HOME    -> GLFW_KEY_HOME

        default:  return 0;
        }
    }

    // ===================== construction / lifecycle =====================

    protected CefBrowserOsr(CefClient client, String url, boolean transparent, CefRequestContext context,
                            CefBrowserSettings settings) {
        this(client, url, transparent, context, null, null, settings);
    }

    private CefBrowserOsr(CefClient client, String url, boolean transparent,
                          CefRequestContext context, CefBrowserOsr parent, Point inspectAt,
                          CefBrowserSettings settings) {
        super(client, url, context, parent, inspectAt, settings);
        isTransparent_ = transparent;
        renderer_ = new CefRenderer(transparent);
    }

    @Override
    public void createImmediately() {
        justCreated_ = true;
        // Create the browser immediately.
        createBrowserIfRequired(false);
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url,
                                                 CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return null;
    }

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return browser_rect_;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        Point screenPoint = new Point(screenPoint_);
        screenPoint.translate(viewPoint.x, viewPoint.y);
        return screenPoint;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        if(!show) {
            renderer_.clearPopupRects();
            invalidate();
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        renderer_.onPopupSize(size);
    }

    // ===================== paint (CEF thread -> GL thread) =====================

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.add(listener);
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.clear();
        onPaintListeners.add(listener);
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
        onPaintListeners.remove(listener);
    }

    @Override
    public void addOnAcceleratedPaintListener(Consumer<CefAcceleratedPaintEvent> listener) {
        onAcceleratedPaintListeners.add(listener);
    }

    @Override
    public void setOnAcceleratedPaintListener(Consumer<CefAcceleratedPaintEvent> listener) {
        onAcceleratedPaintListeners.clear();
        onAcceleratedPaintListeners.add(listener);
    }

    @Override
    public void removeOnAcceleratedPaintListener(Consumer<CefAcceleratedPaintEvent> listener) {
        onAcceleratedPaintListeners.remove(listener);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width,
                        int height) {
        if(!onPaintListeners.isEmpty()) {
            CefPaintEvent paintEvent = new CefPaintEvent(browser, popup, dirtyRects, buffer, width, height);
            for(Consumer<CefPaintEvent> l : onPaintListeners) {
                l.accept(paintEvent);
            }
        }

        // MCEF face: buffer the frame; the GL upload happens in mcefUpdate().
        if(popup) // OSR popups are not rendered (same as upstream MCEF).
            return;

        final int size = (width * height) << 2;

        synchronized(queue) {
            if(buffer.limit() > size)
                Log.warning("Skipping MCEF browser frame, data is too heavy"); //TODO: Don't spam
            else {
                // Bound memory: keep at most 3 pending frames; dropping
                // intermediates requires a full re-render of the survivor.
                while(queue.size() >= 3)
                    queue.removeFirst();

                PaintData pd = new PaintData();
                pd.buffer = ByteBuffer.allocateDirect(size);
                pd.buffer.position(0);
                pd.buffer.limit(buffer.limit());
                buffer.position(0);
                pd.buffer.put(buffer);
                pd.buffer.position(0);
                pd.buffer.limit(pd.buffer.capacity());

                if(!queue.isEmpty()) // pending frame(s) will be skipped
                    pd.fullReRender = true;

                pd.width = width;
                pd.height = height;
                pd.dirtyRects = dirtyRects;
                queue.addLast(pd);
            }
        }
    }

    @Override
    public void onAcceleratedPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, CefAcceleratedPaintInfo info) {
        if(!onAcceleratedPaintListeners.isEmpty()) {
            CefAcceleratedPaintEvent paintEvent =
                    new CefAcceleratedPaintEvent(browser, popup, dirtyRects, info);
            for(Consumer<CefAcceleratedPaintEvent> l : onAcceleratedPaintListeners) {
                l.accept(paintEvent);
            }
        }
    }

    /**
     * Uploads queued CEF frames to the GL texture. Must be called on the GL
     * (rendering) thread every frame — the embedding mod drives this together
     * with CefApp.N_DoMessageLoopWork() because OSR mode uses CEF's external
     * message pump.
     */
    public void mcefUpdate() {
        PaintData pd = null;

        synchronized(queue) {
            while(!queue.isEmpty()) {
                if(pd != null)
                    pd.fullReRender = true; // this intermediate frame is skipped
                pd = queue.removeFirst();
            }
        }

        if(pd != null) {
            renderer_.onPaint(false, pd.dirtyRects, pd.buffer, pd.width, pd.height, pd.fullReRender);
        }

        //So sadly this is the only way I could get around the "youtube not rendering video if the mouse doesn't move bug"
        //Even the test browser from the original JCEF library doesn't fix this...
        //What I hope, however, is that it doesn't redraw the entire browser... otherwise I could just call "invalidate"
        sendMouseEvent(lastMouseEvent);
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, final int cursorType) {
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return true;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
    }

    private void createBrowserIfRequired(boolean hasParent) {
        long windowHandle = 0;
        if (getNativeRef("CefBrowser") == 0) {
            if (getParentBrowser() != null) {
                createDevTools(getParentBrowser(), getClient(), windowHandle, true, isTransparent_,
                        getInspectAt());
            } else {
                createBrowser(getClient(), windowHandle, getUrl(), true, isTransparent_,
                        getRequestContext());
            }
        } else if (hasParent && justCreated_) {
            notifyAfterParentChanged();
            setFocus(true);
            justCreated_ = false;
        }
    }

    private void notifyAfterParentChanged() {
        // With OSR there is no native window to reparent but we still need to send the
        // notification.
        getClient().onAfterParentChanged(this);
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        screenInfo.Set(scaleFactor_, depth, depth_per_component, false, browser_rect_.getBounds(),
                browser_rect_.getBounds());

        return true;
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        return null;
    }

    // ===================== IBrowser (montoyo API) =====================

    @Override
    public void close() {
        if(CLEANUP) {
            if(MCEF.PROXY instanceof ClientProxy)
                ((ClientProxy) MCEF.PROXY).removeBrowser(this);
            renderer_.cleanup();
        }

        super.close(true); //true to ignore confirmation popups
    }

    @Override
    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    @Override
    public void draw(double x1, double y1, double x2, double y2) {
        renderer_.render(x1, y1, x2, y2);
    }

    @Override
    public int getTextureID() {
        return renderer_.texture_id_[0];
    }

    @Override
    public void injectMouseMove(int x, int y, int mods, boolean left) {
        //FIXME: 'left' is not used as it causes bugs since MCEF 1.11

        CefMouseEvent ev = new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, 0, 0, awtToGlfwMods(mods));
        lastMouseEvent = ev;
        sendMouseEvent(ev);
    }

    @Override
    public void injectMouseButton(int x, int y, int mods, int btn, boolean pressed, int ccnt) {
        int glfwBtn = awtToGlfwButton(btn);
        if(glfwBtn < 0)
            return;

        int buttonMask = CefMouseEvent.BUTTON1_MASK << glfwBtn; // 0x10 / 0x20 / 0x40
        int m = awtToGlfwMods(mods);
        if(pressed)
            m |= buttonMask;
        else
            m &= ~buttonMask;

        CefMouseEvent ev = new CefMouseEvent(pressed ? GLFW_PRESS : GLFW_RELEASE, x, y, ccnt, glfwBtn, m);
        sendMouseEvent(ev);
    }

    @Override
    public void injectKeyTyped(char c, int mods) {
        CefKeyEvent ev = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, awtToGlfwMods(mods));
        sendKeyEvent(ev);
    }

    /**
     * Char-first press entry point used by 1.7.10 embedders (mcphone_browser
     * bridge). Special keys arrive as '\0'; control characters are mapped to
     * GLFW key codes so the Linux bridge produces proper X keysyms.
     */
    public void injectKeyPressed(char c, int mods) {
        CefKeyEvent ev = new CefKeyEvent(CefKeyEvent.KEY_PRESS, controlCharToGlfwKey(c), c, awtToGlfwMods(mods));
        sendKeyEvent(ev);
    }

    /**
     * Char-first release entry point (see {@link #injectKeyPressed}).
     */
    public void injectKeyReleased(char c, int mods) {
        CefKeyEvent ev = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, controlCharToGlfwKey(c), c, awtToGlfwMods(mods));
        sendKeyEvent(ev);
    }

    @Override
    public void injectKeyPressedByKeyCode(int keyCode, char c, int mods) {
        if(c != '\0') {
            synchronized(WORST_HACK) {
                WORST_HACK.put(keyCode, c);
            }
        }

        int glfw = remapKeycode(keyCode);
        if(glfw == 0)
            glfw = c; // native bridge falls back to key_char

        CefKeyEvent ev = new CefKeyEvent(CefKeyEvent.KEY_PRESS, glfw, c, awtToGlfwMods(mods));
        sendKeyEvent(ev);
    }

    @Override
    public void injectKeyReleasedByKeyCode(int keyCode, char c, int mods) {
        if(c == '\0') {
            synchronized(WORST_HACK) {
                Character ch = WORST_HACK.get(keyCode);
                c = (ch == null) ? '\0' : ch;
            }
        }

        int glfw = remapKeycode(keyCode);
        if(glfw == 0)
            glfw = c;

        CefKeyEvent ev = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, glfw, c, awtToGlfwMods(mods));
        sendKeyEvent(ev);
    }

    @Override
    public void injectMouseWheel(int x, int y, int mods, int amount, int rot) {
        // WHEEL_UNIT_SCROLL: the JNI bridge reads getUnitsToScroll()
        // (= amount * delta), matching historical MCEF (±120 px per notch).
        CefMouseWheelEvent ev = new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, x, y, rot,
                awtToGlfwMods(mods));
        ev.amount = amount;
        sendMouseWheelEvent(ev);
    }

    @Override
    public void runJS(String script, String frame) {
        executeJavaScript(script, frame, 0);
    }

    @Override
    public void visitSource(IStringVisitor isv) {
        getSource(new StringVisitor(isv));
    }

    @Override
    public boolean isPageLoading() {
        return isLoading();
    }
}
