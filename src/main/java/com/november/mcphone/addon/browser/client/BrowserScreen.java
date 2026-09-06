package com.november.mcphone.addon.browser.client;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import com.november.mcphone.addon.browser.core.AddonStore;
import com.november.mcphone.addon.browser.wd.WdBridge;

/**
 * 全屏虚拟浏览器（不放置任何方块）：16:9、约占游戏窗口 80% 的 CEF 页面，
 * 顶部工具栏（后退/刷新/地址栏/主页/关闭）。页面纹理由 MCEF 离屏渲染，
 * 鼠标/键盘/滚轮注入 CEF。
 */
public class BrowserScreen extends GuiScreen {

    /** 16:9 且覆盖约 80% 游戏窗口。 */
    private static final double SCALE = 0.8;
    private static final int BAR = 22;

    private BrowserHandle browser;
    private final StringBuilder addr = new StringBuilder();
    private boolean addressMode;
    private int caret;
    private int viewW;
    private int viewH;
    private String pendingUrl;
    private boolean created;

    public BrowserScreen(String url) {
        this.pendingUrl = url;
        current = this;
    }

    /** 打开浏览器大屏并记录历史/上次网址。 */
    public static void open(String rawUrl) {
        String url = WdBridge.normalize(rawUrl);
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

    private boolean inAddressBar(int mx, int my) {
        return my >= 4 && my < BAR - 4 && mx >= 50 && mx < this.width - 130;
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
            McefBridge.detect();
            if (McefBridge.available()) {
                browser = McefBridge.create(url);
                if (browser != null) {
                    browser.resize(viewW, viewH);
                }
            }
        } else if (browser != null) {
            browser.resize(viewW, viewH);
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (current == this) {
            current = null;
        }
        if (browser != null) {
            browser.close();
            browser = null;
        }
    }

    // ===================== 渲染 =====================

    @Override
    public void drawScreen(int mx, int my, float pt) {
        drawRect(0, 0, this.width, this.height, 0xD0141414);

        // ---- 顶部工具栏 ----
        drawRect(0, 0, this.width, BAR, 0xF02B2B2B);

        // 后退 ◀ (6..24)
        drawRect(6, 5, 24, BAR - 5, browser != null ? 0xFF4A4A4A : 0xFF383838);
        fontRendererObj.drawStringWithShadow("<", 12, 8, browser != null ? 0xFFFFFF : 0x909090);
        // 刷新 ⟳ (26..44)
        drawRect(26, 5, 44, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("R", 32, 8, 0xFFFFFF);

        // 地址栏 (50..width-130)
        int ax0 = 50;
        int ax1 = this.width - 130;
        drawRect(ax0, 4, ax1, BAR - 4, addressMode ? 0xFF141414 : 0xFF101010);
        drawRect(ax0, 4, ax0 + 1, BAR - 4, 0xFF666666);
        drawRect(ax1 - 1, 4, ax1, BAR - 4, 0xFF666666);
        drawRect(ax0, 4, ax1, 5, 0xFF666666);
        drawRect(ax0, BAR - 5, ax1, BAR - 4, 0xFF666666);

        // 非编辑态跟随真实 URL（页面跳转后同步）
        if (!addressMode && browser != null) {
            String cur = browser.getURL();
            if (cur != null && !cur.isEmpty()) {
                String shownNow = addr.toString();
                if (!cur.equals(shownNow) && (System.currentTimeMillis() - lastUrlSync) > 500) {
                    addr.setLength(0);
                    addr.append(cur);
                    caret = addr.length();
                }
            }
        }
        String shown = addr.toString();
        int maxW = ax1 - ax0 - 10;
        if (addressMode) {
            while (fontRendererObj.getStringWidth(shown) > maxW && shown.length() > 1) {
                shown = shown.substring(1);
            }
        }
        int tx = ax0 + 5;
        fontRendererObj.drawStringWithShadow(shown, tx, 8, addressMode ? 0xFFFFFF : 0xB0C0D0);
        if (addressMode && (Minecraft.getSystemTime() / 500 & 1) == 0) {
            int cw = fontRendererObj.getStringWidth(shown);
            drawRect(tx + cw + 1, 7, tx + cw + 2, BAR - 7, 0xFFFFFF);
        }

        // 主页 H (ax1+4 .. ax1+22)
        drawRect(ax1 + 4, 5, ax1 + 22, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("H", ax1 + 10, 8, 0xFFFFFF);
        // 关闭 X
        drawRect(this.width - 124, 5, this.width - 106, BAR - 5, 0xFF8A3A3A);
        fontRendererObj.drawStringWithShadow("X", this.width - 118, 8, 0xFFFFFF);
        // 全屏 F
        drawRect(this.width - 102, 5, this.width - 84, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("F", this.width - 96, 8, 0xFFFFFF);
        // 提示
        fontRendererObj.drawStringWithShadow("Esc", this.width - 78, 8, 0x707070);

        // ---- 页面区域 ----
        int bx = boxX();
        int by = boxY();
        if (browser != null && browser.textureId() != 0) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            browser.draw(bx, by, bx + viewW, by + viewH);
            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } else {
            String msg = McefBridge.available()
                ? StatCollector.translateToLocal("msg.mcphone_browser.loading")
                : StatCollector.translateToLocal("err.mcphone_browser.missing_mcef");
            drawRect(bx, by, bx + viewW, by + viewH, 0xFF0A0A0A);
            int mw = fontRendererObj.getStringWidth(msg);
            fontRendererObj.drawStringWithShadow(msg, bx + (viewW - mw) / 2, by + viewH / 2 - 4, 0xFFFF55);
            if (!McefBridge.available()) {
                String r = McefBridge.failReason();
                if (r != null && !r.isEmpty()) {
                    fontRendererObj.drawStringWithShadow(r, bx + 8, by + viewH / 2 + 14, 0xFF5555);
                }
            }
        }

        // 页面边框
        drawRect(bx - 1, by - 1, bx + viewW + 1, by, 0xFF5A5A5A);
        drawRect(bx - 1, by + viewH, bx + viewW + 1, by + viewH + 1, 0xFF5A5A5A);
        drawRect(bx - 1, by, bx, by + viewH, 0xFF5A5A5A);
        drawRect(bx + viewW, by, bx + viewW + 1, by + viewH, 0xFF5A5A5A);

        super.drawScreen(mx, my, pt);
    }

    private long lastUrlSync;

    // ===================== 输入 =====================

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
            if (keyCode == 47 && isCtrlKeyDown()) { // Ctrl+V
                String clip = getClipboardString();
                if (clip != null) {
                    String clean = clip.trim().replaceAll("\\s+", "");
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
        // 页面模式：转发按键给 CEF
        if (browser != null) {
            browser.injectKeyTyped(typedChar, keyCode);
            browser.injectKeyPressed(typedChar, keyCode);
        }
    }

    private void navigate() {
        addressMode = false;
        String url = addr.toString().trim();
        if (url.isEmpty()) return;
        String norm = WdBridge.normalize(url);
        if (norm == null) return;
        if (browser != null) {
            browser.loadURL(norm);
        }
        AddonStore.addHistory(norm);
        AddonStore.setLastUrl(norm);
    }

    private void closeScreen() {
        this.mc.displayGuiScreen(null);
        this.mc.setIngameFocus();
    }

    /** 看门狗用：游戏退出时关掉还活着的浏览器（CEF 侧清理由 MCEF 负责）。 */
    static void forceClose() {
        BrowserScreen s = current;
        if (s != null && s.browser != null) {
            try {
                s.browser.close();
            } catch (Throwable ignored) {}
            s.browser = null;
        }
    }

    /** 当前打开的 BrowserScreen（供看门狗关闭）。 */
    private static BrowserScreen current;

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        if (btn == 0) {
            if (inAddressBar(mx, my)) {
                if (!addressMode) {
                    addressMode = true;
                    caret = addr.length();
                }
                return;
            }
            if (my >= 5 && my < BAR - 5) {
                if (mx >= 6 && mx < 24) { // 后退
                    if (browser != null) browser.goBack();
                    return;
                }
                if (mx >= 26 && mx < 44) { // 刷新
                    if (browser != null) {
                        String cur = browser.getURL();
                        if (cur != null && !cur.isEmpty()) browser.loadURL(cur);
                    }
                    return;
                }
                int ax1 = this.width - 130;
                if (mx >= ax1 + 4 && mx < ax1 + 22) { // 主页
                    navigateHome();
                    return;
                }
                if (mx >= this.width - 124 && mx < this.width - 106) { // 关闭
                    closeScreen();
                    return;
                }
                if (mx >= this.width - 102 && mx < this.width - 84) { // 全屏切换
                    this.mc.toggleFullscreen();
                    return;
                }
            }
        }
        if (addressMode && !inAddressBar(mx, my)) {
            addressMode = false;
        }
        if (inPage(mx, my) && browser != null) {
            addressMode = false;
            // MC 按钮 0=左 1=右 2=中 → CEF 0=左 1=中 2=右
            int cef = btn == 0 ? 0 : btn == 1 ? 2 : 1;
            browser.injectMouseButton(mx - boxX(), my - boxY(), cef, 1, true, 0);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        if (browser != null) {
            boolean over = inPage(mx, my);
            if (over) {
                browser.injectMouseMove(mx - boxX(), my - boxY(), 0, true);
            }
            if (which != -1 && over) {
                int cef = which == 0 ? 0 : which == 1 ? 2 : 1;
                browser.injectMouseButton(mx - boxX(), my - boxY(), cef, 1, false, 0);
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && browser != null) {
            int mx = Mouse.getX() * this.width / this.mc.displayWidth;
            int my = this.height - Mouse.getY() * this.height / this.mc.displayHeight - 1;
            if (inPage(mx, my)) {
                // Java MouseWheelEvent: rotation 正值=向下；MC 正值=向上
                int rotation = wheel > 0 ? -1 : 1;
                browser.injectMouseWheel(mx - boxX(), my - boxY(), 0, 120, rotation);
            }
        }
    }

    private void navigateHome() {
        String home = AddonStore.home();
        addr.setLength(0);
        addr.append(home);
        caret = addr.length();
        if (browser != null) {
            browser.loadURL(home);
        }
        AddonStore.addHistory(home);
        AddonStore.setLastUrl(home);
    }
}
