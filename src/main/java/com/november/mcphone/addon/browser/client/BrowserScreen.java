package com.november.mcphone.addon.browser.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import com.november.mcphone.addon.browser.core.AddonStore;
import com.november.mcphone.addon.browser.core.Urls;

/**
 * 全屏虚拟浏览器（不放置任何方块）：16:9、约占游戏窗口 80% 的 CEF 页面，
 * 顶部工具栏（后退/刷新/地址栏/主页/关闭/全屏）。页面纹理由 MCEF 离屏渲染，
 * 鼠标/键盘/滚轮注入 CEF。
 *
 * <p>注入约定（与 MCEF 0.7 CefBrowserOsr 对齐）：
 * 鼠标按钮为 AWT 编号（1=左 2=中 3=右）；injectMouseMove 的 focus=false 才是
 * MOUSE_MOVED（true=EXITED）；键盘 Pressed → (chr≠0 时) Typed → Released，
 * modifiers 恒 0。1.7.10 只在按下时回调 keyTyped，Released 在
 * handleKeyboardInput 里补发。</p>
 */
public class BrowserScreen extends GuiScreen {

    /** 16:9 且覆盖约 80% 游戏窗口。 */
    private static final double SCALE = 0.8;
    private static final int BAR = 22;

    private volatile BrowserHandle browser;
    private final StringBuilder addr = new StringBuilder();
    private boolean addressMode;
    private int caret;
    private int viewW;
    private int viewH;
    private final String pendingUrl;
    private boolean created;
    private String createError;
    private long lastUrlSync;
    private boolean lastInPage;
    private int pressedCefBtn = -1;
    private long stuckSince; // textureId()==0 且 MCEF 可用的起始时刻（0=未计时）

    /** 当前打开的 BrowserScreen（供看门狗关闭）。 */
    private static BrowserScreen current;

    public BrowserScreen(String url) {
        this.pendingUrl = url;
        current = this;
    }

    /** 打开浏览器大屏并记录历史/上次网址。 */
    public static void open(String rawUrl) {
        String url = Urls.normalize(rawUrl);
        if (url == null) return;
        AddonStore.setLastUrl(url);
        AddonStore.addHistory(url);
        Minecraft.getMinecraft().displayGuiScreen(new BrowserScreen(url));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ===================== 布局 =====================

    private void computeSize() {
        double k = Math.min(this.width * SCALE / 16.0, this.height * SCALE / 9.0);
        viewW = (int) Math.floor(k * 16.0);
        viewH = (int) Math.floor(k * 9.0);
    }

    private int boxX() {
        return (this.width - viewW) / 2;
    }

    private int boxY() {
        return BAR + 2 + Math.max(0, (this.height - BAR - 2 - viewH) / 2);
    }

    private boolean inPage(int mx, int my) {
        return mx >= boxX() && mx < boxX() + viewW && my >= boxY() && my < boxY() + viewH;
    }

    // 工具栏按钮区（与绘制严格一致）
    private int ax0() {
        return 50;
    }

    private int ax1() {
        return this.width - 130;
    }

    private boolean inAddressBar(int mx, int my) {
        return my >= 4 && my < BAR - 4 && mx >= ax0() && mx < ax1();
    }

    private boolean inBack(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= 6 && mx < 24;
    }

    private boolean inReload(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= 26 && mx < 44;
    }

    private boolean inHome(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= ax1() + 4 && mx < ax1() + 22;
    }

    private boolean inClose(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= ax1() + 26 && mx < ax1() + 44;
    }

    private boolean inFullscreen(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= ax1() + 48 && mx < ax1() + 66;
    }

    // ===================== 生命周期 =====================

    @Override
    public void initGui() {
        super.initGui();
        computeSize();
        if (!created) {
            created = true;
            String url = pendingUrl != null ? pendingUrl : AddonStore.home();
            addr.append(url);
            // 惰性初始化触发点：detect() 内部会先执行 McefLazyInit.ensureInitialized()
            // （首次打开浏览器 App 时在主线程拉起 CEF，失败/降级时 available()==false 走错误页）
            McefBridge.detect();
            if (McefBridge.available()) {
                browser = McefBridge.create(url);
                if (browser == null) {
                    createError = McefBridge.failReason();
                } else {
                    browser.resize(viewW, viewH);
                }
            }
        } else {
            BrowserHandle b = browser;
            if (b != null) {
                b.resize(viewW, viewH);
            }
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (current == this) {
            current = null;
        }
        BrowserHandle b = browser;
        if (b != null) {
            browser = null;
            b.close();
        }
    }

    /** 看门狗用：游戏退出时关掉还活着的浏览器。 */
    /**
     * 退出看门狗调用：异步关闭我们打开的浏览器。
     *
     * <p>close() 最终走到 JCEF 的 native n_Close，MC 主循环停止后 CEF 消息泵
     * 已死，该调用会永久阻塞——绝不能在看门狗线程上同步执行（上一版就是这么
     * 卡死看门狗、导致强杀逻辑永远没跑到的）。放到守护线程里，阻塞也只阻塞它
     * 自己，不影响看门狗的扫描与强杀。</p>
     */
    static void forceClose() {
        BrowserScreen s = current;
        if (s == null) {
            return;
        }
        BrowserHandle b = s.browser;
        if (b == null) {
            return;
        }
        s.browser = null;
        Thread t = new Thread(() -> b.close(), "mcphone_browser-async-close");
        t.setDaemon(true);
        t.start();
    }

    // ===================== 渲染 =====================

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawRect(0, 0, this.width, this.height, 0xD0141414);

        // ---- 顶部工具栏 ----
        drawRect(0, 0, this.width, BAR, 0xF02B2B2B);

        BrowserHandle b = browser;
        if (b != null) {
            // MCEF 上游 CefRenderer.initialize() 孤儿化兜底：Client thread 渲染
            // 路径上有 GL context，是执行 glGenTextures 的安全时机（内部只跑一次）。
            b.ensureRendererInitialized();
        }

        // 后退 ◀ (6..24)
        drawRect(6, 5, 24, BAR - 5, b != null ? 0xFF4A4A4A : 0xFF383838);
        fontRendererObj.drawStringWithShadow("<", 12, 8, b != null ? 0xFFFFFF : 0x909090);
        // 刷新 R (26..44)
        drawRect(26, 5, 44, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("R", 32, 8, 0xFFFFFF);

        // 地址栏 (50..ax1)
        int ax0 = ax0();
        int ax1 = ax1();
        drawRect(ax0, 4, ax1, BAR - 4, addressMode ? 0xFF141414 : 0xFF101010);
        drawRect(ax0, 4, ax0 + 1, BAR - 4, 0xFF666666);
        drawRect(ax1 - 1, 4, ax1, BAR - 4, 0xFF666666);
        drawRect(ax0, 4, ax1, 5, 0xFF666666);
        drawRect(ax0, BAR - 5, ax1, BAR - 4, 0xFF666666);

        // 非编辑态节流跟随真实 URL（页面跳转后同步）
        if (!addressMode && b != null && System.currentTimeMillis() - lastUrlSync > 500) {
            lastUrlSync = System.currentTimeMillis();
            String cur = b.getURL();
            if (cur != null && !cur.isEmpty() && !cur.equals(addr.toString())) {
                addr.setLength(0);
                addr.append(cur);
                caret = addr.length();
            }
        }
        String shown = addr.toString();
        int maxW = ax1 - ax0 - 10;
        while (fontRendererObj.getStringWidth(shown) > maxW && shown.length() > 1) {
            shown = shown.substring(1);
        }
        int tx = ax0 + 5;
        fontRendererObj.drawStringWithShadow(shown, tx, 8, addressMode ? 0xFFFFFF : 0xB0C0D0);
        if (addressMode && (Minecraft.getSystemTime() / 500 & 1) == 0) {
            int cw = fontRendererObj.getStringWidth(shown);
            drawRect(tx + cw + 1, 7, tx + cw + 2, BAR - 7, 0xFFFFFF);
        }

        // 主页 H (ax1+4..ax1+22) / 关闭 X (ax1+26..ax1+44) / 全屏 F (ax1+48..ax1+66)
        drawRect(ax1 + 4, 5, ax1 + 22, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("H", ax1 + 10, 8, 0xFFFFFF);
        drawRect(ax1 + 26, 5, ax1 + 44, BAR - 5, 0xFF8A3A3A);
        fontRendererObj.drawStringWithShadow("X", ax1 + 32, 8, 0xFFFFFF);
        drawRect(ax1 + 48, 5, ax1 + 66, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("F", ax1 + 54, 8, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow("Esc", ax1 + 70, 8, 0x707070);

        // ---- 页面区域 ----
        int bx = boxX();
        int by = boxY();
        if (b != null && b.textureId() != 0) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            b.draw(bx, by, bx + viewW, by + viewH);
            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } else {
            String msg;
            String detail = null;
            if (createError != null) {
                msg = StatCollector.translateToLocal("err.mcphone_browser.create_failed");
                detail = createError;
            } else if (McefBridge.available()) {
                msg = StatCollector.translateToLocal("msg.mcphone_browser.loading");
                // 超时诊断：CEF 存活但纹理始终为 0 —— 兜底已跑过仍未生效，
                // 提示可能是 MCEF 上游 bug（不自动重试，避免刷屏）。
                long now = System.currentTimeMillis();
                if (stuckSince == 0) {
                    stuckSince = now;
                } else if (now - stuckSince > 15000) {
                    detail = StatCollector.translateToLocal("msg.mcphone_browser.texture_stuck");
                }
            } else {
                msg = StatCollector.translateToLocal("err.mcphone_browser.missing_mcef");
                String r = McefBridge.failReason();
                if (r != null && !r.isEmpty()) {
                    detail = r;
                }
            }
            drawRect(bx, by, bx + viewW, by + viewH, 0xFF0A0A0A);
            int mw = fontRendererObj.getStringWidth(msg);
            fontRendererObj.drawStringWithShadow(msg, bx + (viewW - mw) / 2, by + viewH / 2 - 4, 0xFFFF55);
            if (detail != null) {
                fontRendererObj.drawStringWithShadow(detail, bx + 8, by + viewH / 2 + 14, 0xFF5555);
            }
        }

        // 页面边框
        drawRect(bx - 1, by - 1, bx + viewW + 1, by, 0xFF5A5A5A);
        drawRect(bx - 1, by + viewH, bx + viewW + 1, by + viewH + 1, 0xFF5A5A5A);
        drawRect(bx - 1, by, bx, by + viewH, 0xFF5A5A5A);
        drawRect(bx + viewW, by, bx + viewW + 1, by + viewH, 0xFF5A5A5A);

        super.drawScreen(mx, my, pt);
    }

    // ===================== 键盘 =====================

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) { // Esc
            if (addressMode) {
                addressMode = false;
                return;
            }
            closeScreen();
            return;
        }
        if (addressMode) {
            if (keyCode == 28 || keyCode == 156) { // Enter
                navigate();
                return;
            }
            if (keyCode == 14) { // Backspace
                if (caret > 0) {
                    addr.deleteCharAt(caret - 1);
                    caret--;
                }
                return;
            }
            if (keyCode == 203 && caret > 0) { // Left
                caret--;
                return;
            }
            if (keyCode == 205 && caret < addr.length()) { // Right
                caret++;
                return;
            }
            if (keyCode == 47 && isCtrlKeyDown()) { // Ctrl+V（保留空格，仅去换行）
                String clip = getClipboardString();
                if (clip != null) {
                    String clean = clip.replace("\r", "").replace("\n", "").trim();
                    addr.insert(caret, clean);
                    caret += clean.length();
                }
                return;
            }
            if (typedChar >= 32 && typedChar != 127) {
                addr.insert(caret, typedChar);
                caret++;
            }
            return;
        }
        // 页面模式：Pressed → (chr≠0 时) Typed；Released 由 handleKeyboardInput 补发。
        // MCEF 0.7 的 keyCode 恒为 0，非字符键（方向键等）无法表达——接受此限制。
        BrowserHandle b = browser;
        if (b != null) {
            b.injectKeyPressed(typedChar, 0);
            if (typedChar != 0) {
                b.injectKeyTyped(typedChar, 0);
            }
        }
    }

    @Override
    public void handleKeyboardInput() {
        super.handleKeyboardInput();
        // keyTyped 只在按下时回调；这里补发松开事件（修饰键/按键状态不残留）。
        if (!addressMode && !Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()) {
            BrowserHandle b = browser;
            if (b != null) {
                b.injectKeyReleased(Keyboard.getEventCharacter(), 0);
            }
        }
    }

    private void navigate() {
        addressMode = false;
        String url = addr.toString().trim();
        if (url.isEmpty()) return;
        String norm = Urls.normalize(url);
        if (norm == null) return;
        BrowserHandle b = browser;
        if (b != null) {
            b.loadURL(norm);
        }
        AddonStore.addHistory(norm);
        AddonStore.setLastUrl(norm);
    }

    private void closeScreen() {
        this.mc.displayGuiScreen(null);
        this.mc.setIngameFocus();
    }

    // ===================== 鼠标 =====================

    /** MC 按钮 → AWT 按钮（1=左 2=中 3=右）。 */
    private static int toAwtButton(int mcBtn) {
        return mcBtn == 0 ? 1 : mcBtn == 1 ? 3 : 2;
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        if (addressMode && !inAddressBar(mx, my)) {
            addressMode = false;
        }
        if (btn == 0) {
            if (inBack(mx, my)) {
                BrowserHandle b = browser;
                if (b != null) b.goBack();
                return;
            }
            if (inReload(mx, my)) {
                BrowserHandle b = browser;
                if (b != null) {
                    String cur = b.getURL();
                    if (cur != null && !cur.isEmpty()) b.loadURL(cur);
                }
                return;
            }
            if (inAddressBar(mx, my)) {
                if (!addressMode) {
                    addressMode = true;
                    caret = addr.length();
                }
                return;
            }
            if (inHome(mx, my)) {
                navigateHome();
                return;
            }
            if (inClose(mx, my)) {
                closeScreen();
                return;
            }
            if (inFullscreen(mx, my)) {
                this.mc.toggleFullscreen();
                return;
            }
        }
        BrowserHandle b = browser;
        if (inPage(mx, my) && b != null) {
            addressMode = false;
            pressedCefBtn = toAwtButton(btn);
            b.injectMouseButton(mx - boxX(), my - boxY(), 0, pressedCefBtn, true, 1);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        if (which != -1) {
            // 释放：无论是否仍在页面内都配对发送，避免 CEF 侧按键卡死
            if (pressedCefBtn != -1) {
                BrowserHandle b = browser;
                if (b != null) {
                    b.injectMouseButton(mx - boxX(), my - boxY(), 0, pressedCefBtn, false, 1);
                }
                pressedCefBtn = -1;
            }
        }
        // 纯移动由 handleMouseInput 统一处理（1.7.10 纯移动不会走到这里）
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        BrowserHandle b = browser;
        if (b == null) {
            lastInPage = false;
            return;
        }
        int ex = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int ey = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        boolean over = inPage(ex, ey);
        // focus=false → MOUSE_MOVED；true → MOUSE_EXITED（离开页面时补发一次）
        if (over || lastInPage) {
            b.injectMouseMove(ex - boxX(), ey - boxY(), 0, !over);
        }
        lastInPage = over;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && over) {
            // Java MouseWheelEvent：rotation 正值=向下；MC 正值=向上
            int rotation = wheel > 0 ? -1 : 1;
            b.injectMouseWheel(ex - boxX(), ey - boxY(), 0, 120, rotation);
        }
    }

    private void navigateHome() {
        String home = AddonStore.home();
        addr.setLength(0);
        addr.append(home);
        caret = addr.length();
        BrowserHandle b = browser;
        if (b != null) {
            b.loadURL(home);
        }
        AddonStore.addHistory(home);
        AddonStore.setLastUrl(home);
    }
}
