package com.november.mcphone.addon.browser.client;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.StatCollector;

import com.november.mcphone.addon.browser.core.AddonStore;
import com.november.mcphone.addon.browser.core.Urls;

/**
 * 全屏虚拟浏览器（不放置任何方块）：16:9、约占游戏窗口 80% 的 CEF 页面，
 * 顶部工具栏（后退/刷新/地址栏/主页/关闭/全屏）。页面纹理由 MCEF 离屏渲染，
 * 鼠标/键盘/滚轮注入 CEF。
 *
 * <p>注入约定（与 MCEF 0.6/0.7 CefBrowserOsr 对齐）：
 * 鼠标按钮为 AWT 编号（1=左 2=中 3=右），modifiers 从 LWJGL 键状态实时算出
 * （SHIFT 64 / CTRL 128 / ALT 512，native 经 getModifiersEx 读取）；injectMouseMove
 * 的 focus=false 才是 MOUSE_MOVED（true=EXITED）；键盘 Pressed → (chr≠0 时) Typed
 * → Released。1.7.10 只在按下时回调 keyTyped，Released 在 handleKeyboardInput
 * 里补发。OSR 焦点在首帧上传/页面点击后重挂（create 时 native browser 尚未
 * 建好，一次性 setFocus 会被静默丢弃）。</p>
 */
public class BrowserScreen extends GuiScreen {

    /** 16:9 且覆盖约 80% 游戏窗口。 */
    private static final double SCALE = 0.8;
    private static final int BAR = 22;

    private volatile BrowserHandle browser;
    /**
     * 地址栏 = vanilla GuiTextField（唯一真源）。自带选中模型 + Ctrl+A/C/V/X
     * （char 码 1/3/22/24）、Delete(211)/Home(199)/End(207)、Shift 选区、点击
     * 定位——手写 StringBuilder 版缺失这些（Ctrl+A 的 char=1 被 >=32 过滤丢弃，
     * 合成整串输入同理），是地址栏输入 bug 的根因。
     */
    private GuiTextField addrField;
    private boolean addressMode;
    private int viewW;
    private int viewH;
    /** CEF 渲染视口（像素）。默认 = viewW/H × guiScale（物理分辨率，清晰）；
     * 也可由用户手动指定固定高度档（720/1080/1440/2160）。 */
    private int cefW;
    private int cefH;
    private final String pendingUrl;
    private boolean created;
    private String createError;
    private long lastUrlSync;
    private boolean lastInPage;
    private int pressedCefBtn = -1;
    private boolean clickDiagDone; // 首次页面点击诊断日志只打一次
    private boolean viewportAsserted; // 首帧后校验 CEF 视口并重断言 resize（每 browser 一次）
    private boolean wheelDiagDone; // 首次滚轮诊断日志只打一次
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
        computeCefSize();
    }

    /**
     * CEF 渲染视口（像素）：
     * <ul>
     * <li>自适应（默认）：= 页面 GUI 尺寸 × GUI 缩放系数 = 物理像素数，
     * 1 CEF 像素 ↔ 1 屏幕像素，最清晰；</li>
     * <li>固定高度档（720/1080/1440/2160）：按 16:9 等比定宽，CEF 内部按此
     * 分辨率排版/渲染，再缩放到 GUI 矩形上——高分档字更小更密（等效「缩小」
     * 网页），低分档字更大（等效「放大」）。</li>
     * </ul>
     * 鼠标坐标注入前必须按 cefW/viewW、cefH/viewH 缩放。
     */
    private void computeCefSize() {
        float guiScale = this.width > 0 && this.height > 0
            ? (float) this.mc.displayWidth / this.width : 1f;
        int mode = AddonStore.resolutionMode(); // 0=自适应, 否则=固定高度
        if (mode <= 0) {
            // 自适应：直接取页面矩形物理像素（clamp 到 16:9 双侧对齐）
            cefW = Math.max(64, (int) Math.round(viewW * guiScale));
            cefH = Math.max(36, (int) Math.round(viewH * guiScale));
        } else {
            cefH = mode;
            cefW = Math.max(64, (int) Math.round((long) mode * 16 / 9.0));
        }
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
        return 80; // 前面 50..76 让给了分辨率按钮（46..76）
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

    /** 分辨率按钮（刷新右侧、地址栏左侧）。 */
    private boolean inResolution(int mx, int my) {
        return my >= 5 && my < BAR - 5 && mx >= 46 && mx < 76;
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
        // 退格/Delete/方向键长按连发（vanilla GuiChat 同款）。GuiScreen 默认关闭
        // 键重复，地址栏「按住退格只能删一个字母」即源于此；onGuiClosed 恢复。
        Keyboard.enableRepeatEvents(true);
        // 地址栏控件：位置与 drawScreen 绘制的框一致(文字内缩 5px),无背景绘制
        // (框由 drawRect 画),每次 initGui 重建(分辨率变化/重开 GUI)并保留文本。
        String keep = addrField != null ? addrField.getText() : null;
        addrField = new GuiTextField(fontRendererObj, ax0() + 5, 7, ax1() - ax0() - 10, BAR - 14);
        addrField.setMaxStringLength(2048);
        addrField.setEnableBackgroundDrawing(false);
        addrField.setTextColor(0xFFFFFF);
        addrField.setDisabledTextColour(0xB0C0D0);
        addrField.setFocused(addressMode);
        addrField.setText(keep != null ? keep : (pendingUrl != null ? pendingUrl : AddonStore.home()));
        if (!created) {
            created = true;
            String url = pendingUrl != null ? pendingUrl : AddonStore.home();
            // 惰性初始化触发点：detect() 内部会先执行 McefLazyInit.ensureInitialized()
            // （首次打开浏览器 App 时在主线程拉起 CEF，失败/降级时 available()==false 走错误页）
            McefBridge.detect();
            if (McefBridge.available()) {
                browser = McefBridge.create(url);
                if (browser == null) {
                    createError = McefBridge.failReason();
                } else {
                    computeCefSize();
                    browser.resize(cefW, cefH);
                    // OSR 浏览器必须显式获焦，否则页面内点击/键盘输入可能被 CEF 忽略
                    // （上游 JCEF 在 createBrowserIfRequired 里同样补 setFocus(true)）
                    browser.setFocus(true);
                }
            }
        } else {
            BrowserHandle b = browser;
            if (b != null) {
                computeCefSize();
                b.resize(cefW, cefH);
            }
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false); // 与 initGui 的 enableRepeatEvents(true) 配对
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
            // 纹理初始化兜底（个别 MCEF 构建仍可能孤儿化 initialize()；内部只跑一次）
            b.ensureRendererInitialized();
            // 帧泵：GTNH 下 MCEF 自带的 RenderTickEvent 泵不工作，onPaint 缓存的帧
            // 永远等不到上传 → 在 GL 线程自驱动 N_DoMessageLoopWork + mcefUpdate
            b.pumpFrameUpload();

            // 首帧视口校验：MCEF 0.6 的 resize() 直接 invokespecial N_WasResized
            // （native），browser 异步创建完成前调用会被静默丢弃——initGui 里
            // create 后立刻调的那次可能没生效，实际视口=创建默认尺寸。显示仍
            // 正常（纹理被拉伸到 GUI 矩形），但点击坐标按错误比例缩放 → 落点
            // 全错 → 「点击无反应」（键盘无坐标不受影响，与 beta.6 症状自洽）。
            // 首帧上传后实际视口已确定，此时校验并重断言一次 resize。
            if (!viewportAsserted && b.cefViewWidth() > 0) {
                viewportAsserted = true;
                int actualW = b.cefViewWidth();
                int actualH = b.cefViewHeight();
                if (actualW != cefW || actualH != cefH) {
                    System.out.println("[mcphone_browser] viewport mismatch (actual "
                        + actualW + "x" + actualH + " != expected " + cefW + "x" + cefH
                        + ") — re-asserting resize");
                    b.resize(cefW, cefH);
                }
                b.setFocus(true);
            }
        }

        // 后退 ◀ (6..24)
        drawRect(6, 5, 24, BAR - 5, b != null ? 0xFF4A4A4A : 0xFF383838);
        fontRendererObj.drawStringWithShadow("<", 12, 8, b != null ? 0xFFFFFF : 0x909090);
        // 刷新 R (26..44)
        drawRect(26, 5, 44, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow("R", 32, 8, 0xFFFFFF);
        // 分辨率 (46..76)：显示当前档位，点击循环 Auto→720→1080→1440→2160
        String res = resLabel(AddonStore.resolutionMode());
        drawRect(46, 5, 76, BAR - 5, 0xFF4A4A4A);
        fontRendererObj.drawStringWithShadow(res, 74 - fontRendererObj.getStringWidth(res), 8, 0xB8E8B8);

        // 地址栏 (50..ax1)：框照旧手绘，文字/光标/选区全权交给 vanilla
        // GuiTextField.drawTextBox（无背景模式文字画在 xPosition,yPosition）。
        int ax0 = ax0();
        int ax1 = ax1();
        drawRect(ax0, 4, ax1, BAR - 4, addressMode ? 0xFF141414 : 0xFF101010);
        drawRect(ax0, 4, ax0 + 1, BAR - 4, 0xFF666666);
        drawRect(ax1 - 1, 4, ax1, BAR - 4, 0xFF666666);
        drawRect(ax0, 4, ax1, 5, 0xFF666666);
        drawRect(ax0, BAR - 5, ax1, BAR - 4, 0xFF666666);

        // 非编辑态节流跟随真实 URL（页面跳转后同步）。setText 内部已把光标
        // 移到末尾并等效清掉选区。
        if (!addressMode && b != null && System.currentTimeMillis() - lastUrlSync > 500) {
            lastUrlSync = System.currentTimeMillis();
            String cur = b.getURL();
            if (cur != null && !cur.isEmpty() && !cur.equals(addrField.getText())) {
                addrField.setText(cur);
            }
        }
        addrField.updateCursorCounter();
        addrField.drawTextBox();

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
        // cefViewWidth()>0 = 至少成功上传过一帧（纹理有内容）；==0 = textureId 非 0
        // 但帧从未上传（空纹理=透明，正是 GTNH 症状）→ 走 loading/stuck 分支；
        // ==-1 = 旧版 MCEF 无诊断字段，退回只看 textureId 的旧行为。
        if (b != null && b.textureId() != 0 && b.cefViewWidth() != 0) {
            stuckSince = 0;
            // 自绘四边形（绕开 MCEF CefRenderer.render 的 UV 缺陷 + GLSM 状态风险，
            // 显式关 alpha test/blend——详见 BrowserHandle.drawSelf 注释）
            b.drawSelf(bx, by, bx + viewW, by + viewH);
        } else {
            String msg;
            String detail = null;
            if (createError != null) {
                msg = StatCollector.translateToLocal("err.mcphone_browser.create_failed");
                detail = createError;
            } else if (McefBridge.available()) {
                msg = StatCollector.translateToLocal("msg.mcphone_browser.loading");
                // 超时诊断：CEF 存活但帧始终没上屏。queue>0 = 帧在排队、上传环节
                // 没跑；queue==0 = CEF 根本没产帧（页面没加载/渲染进程异常）。
                long now = System.currentTimeMillis();
                if (stuckSince == 0) {
                    stuckSince = now;
                } else if (now - stuckSince > 15000) {
                    detail = StatCollector.translateToLocal("msg.mcphone_browser.texture_stuck")
                        + " [queue=" + (b != null ? b.queuedFrames() : -1)
                        + " view=" + (b != null ? b.cefViewWidth() : -1) + "]";
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

    /**
     * 当前 AWT 修饰键掩码（从 LWJGL 键盘状态实时读取）。JCEF native 层经
     * {@code KeyEvent.getModifiersEx} 读修饰键；恒传 0 会让 CEF 认为无修饰
     * ——Shift+字符、Ctrl+V 等在页面内全部失效。无修饰键时返回 0。
     */
    private static int awtModifiers() {
        int m = 0;
        if (Keyboard.isKeyDown(42) || Keyboard.isKeyDown(54)) m |= 64;   // SHIFT_DOWN_MASK
        if (Keyboard.isKeyDown(29) || Keyboard.isKeyDown(157)) m |= 128; // CTRL_DOWN_MASK
        if (Keyboard.isKeyDown(56) || Keyboard.isKeyDown(184)) m |= 512; // ALT_DOWN_MASK
        return m;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) { // Esc
            if (addressMode) {
                setEditMode(false);
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
            // 其余全权委托 vanilla textbox：Backspace/Delete、方向键、Home/End、
            // Ctrl+A/C/V/X（char 码 1/3/22/24，手写版把这些当普通字符过滤掉）、
            // Shift 选区、Ctrl+词跳转。粘贴文本经 ChatAllowedCharacters 过滤。
            addrField.textboxKeyTyped(typedChar, keyCode);
            return;
        }
        // 页面模式：Pressed → (chr≠0 时) Typed；Released 由 handleKeyboardInput 补发。
        // MCEF 0.6/0.7 的 keyCode 恒为 0，非字符键（方向键等）无法表达——接受此限制。
        BrowserHandle b = browser;
        if (b != null) {
            int mods = awtModifiers();
            b.injectKeyPressed(typedChar, mods);
            if (typedChar != 0) {
                b.injectKeyTyped(typedChar, mods);
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
                b.injectKeyReleased(Keyboard.getEventCharacter(), awtModifiers());
            }
        }
    }

    private void navigate() {
        setEditMode(false);
        String url = addrField.getText().trim();
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

    /** 进入/退出地址编辑态：焦点与 addressMode 单点同步。 */
    private void setEditMode(boolean on) {
        addressMode = on;
        addrField.setFocused(on);
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

    /**
     * AWT 按钮 → {@code InputEvent.BUTTONx_DOWN_MASK}（左 1024 / 中 2048 / 右 4096）。
     * JCEF 原生层经 {@code getModifiersEx} 读掩码判定按的是哪个按钮——恒传 0 会被
     * 当成「无按钮按下」而整个点击被 Blink 忽略（页面点击无反应的根因）。
     */
    private static int toAwtMask(int awtBtn) {
        return awtBtn == 1 ? 1024 : awtBtn == 3 ? 4096 : 2048;
    }

    /** GUI 页面坐标 → CEF 视口像素坐标（CEF 分辨率与 GUI 矩形尺寸解耦后必须缩放）。 */
    private int cefX(int guiX) {
        return (int) Math.round((guiX - boxX()) * (double) cefW / Math.max(1, viewW));
    }

    private int cefY(int guiY) {
        return (int) Math.round((guiY - boxY()) * (double) cefH / Math.max(1, viewH));
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) {
        // 地址栏点击：热区扩大到整个手绘框（控件矩形略窄，先判 inAddressBar 再
        // 强制聚焦）；vanilla 负责按 x 定位 caret/选区。框外点击经 vanilla 的
        // canLoseFocus 自动失焦（点击页面/工具栏都会退出编辑态）。
        if (inAddressBar(mx, my)) {
            addrField.mouseClicked(mx, my, btn);
            addrField.setFocused(true);
        } else {
            addrField.mouseClicked(mx, my, btn);
        }
        addressMode = addrField.isFocused();
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
            if (inResolution(mx, my)) {
                cycleResolution();
                return;
            }
            if (inAddressBar(mx, my)) {
                return; // 已由地址栏处理
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
            // OSR 焦点重挂：create 时 native browser 尚在异步创建，那时的一次性
            // setFocus 很可能被丢弃——CEF 无焦点时点击/键盘事件被整体忽略
            // （「页面点了没反应」主嫌疑）。点击瞬间补一次，成本可忽略。
            b.setFocus(true);
            pressedCefBtn = toAwtButton(btn);
            int cx = cefX(mx);
            int cy = cefY(my);
            int mask = toAwtMask(pressedCefBtn);
            // 一次性点击诊断：确认坐标缩放与按钮掩码真实到达 CEF（复测后可删）
            if (!clickDiagDone) {
                clickDiagDone = true;
                System.out.println("[mcphone_browser] first click: gui=(" + (mx - boxX()) + ","
                    + (my - boxY()) + ") cef=(" + cx + "," + cy + ") button=" + pressedCefBtn
                    + " mask=" + (mask | awtModifiers()) + " cefViewport=" + cefW + "x" + cefH
                    + " actualViewport=" + b.cefViewWidth() + "x" + b.cefViewHeight());
            }
            b.injectMouseButton(cx, cy, mask | awtModifiers(), pressedCefBtn, true, 1);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mx, int my, int which) {
        if (which != -1) {
            // 释放：无论是否仍在页面内都配对发送，避免 CEF 侧按键卡死。
            // 掩码与按下时一致（JCEF native 按掩码识别按钮，release 传 0 同样失效）。
            if (pressedCefBtn != -1) {
                BrowserHandle b = browser;
                if (b != null) {
                    b.injectMouseButton(cefX(mx), cefY(my), toAwtMask(pressedCefBtn) | awtModifiers(),
                        pressedCefBtn, false, 1);
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
            b.injectMouseMove(cefX(ex), cefY(ey), awtModifiers(), !over);
        }
        lastInPage = over;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && over) {
            // Java MouseWheelEvent：rotation 正值=向下；MC 正值=向上
            int rotation = wheel > 0 ? -1 : 1;
            // 一次性滚轮诊断：滚轮同样携带坐标，若滚轮有效而点击无效，
            // 则「坐标缩放错」假设不成立（复测后可删）
            if (!wheelDiagDone) {
                wheelDiagDone = true;
                System.out.println("[mcphone_browser] first wheel: cef=(" + cefX(ex) + ","
                    + cefY(ey) + ") rotation=" + rotation + " actualViewport="
                    + b.cefViewWidth() + "x" + b.cefViewHeight());
            }
            b.injectMouseWheel(cefX(ex), cefY(ey), awtModifiers(), 120, rotation);
        }
    }

    private void navigateHome() {
        String home = AddonStore.home();
        addrField.setText(home);
        addrField.setCursorPositionEnd();
        BrowserHandle b = browser;
        if (b != null) {
            b.loadURL(home);
        }
        AddonStore.addHistory(home);
        AddonStore.setLastUrl(home);
    }

    // ===================== 分辨率 =====================

    /** 手动分辨率循环档：0=自适应（物理像素），其余=固定高度（按 16:9 定宽）。 */
    private static final int[] RES_MODES = {0, 720, 1080, 1440, 2160};

    private static String resLabel(int mode) {
        return mode <= 0 ? "Auto" : String.valueOf(mode);
    }

    /** 循环切换分辨率档并立即应用到 CEF（resize 后 CEF 内部重排版重渲染）。 */
    private void cycleResolution() {
        int cur = AddonStore.resolutionMode();
        int next = RES_MODES[0];
        for (int i = 0; i < RES_MODES.length; i++) {
            if (RES_MODES[i] == cur) {
                next = RES_MODES[(i + 1) % RES_MODES.length];
                break;
            }
        }
        AddonStore.setResolutionMode(next);
        computeCefSize();
        BrowserHandle b = browser;
        if (b != null) {
            b.resize(cefW, cefH);
            b.setFocus(true);
        }
        System.out.println("[mcphone_browser] resolution mode -> " + resLabel(next)
            + " (cef viewport " + cefW + "x" + cefH + ", gui " + viewW + "x" + viewH + ")");
    }
}
