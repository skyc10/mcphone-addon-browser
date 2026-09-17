package com.november.mcphone.addon.browser.client;

import java.util.List;

import net.minecraft.util.StatCollector;

import club.heiqi.uilib.ui.scene.control.SceneButton;
import club.heiqi.uilib.ui.scene.control.SceneButtonVariant;
import club.heiqi.uilib.ui.scene.control.SceneInputType;
import club.heiqi.uilib.ui.scene.control.SceneTextInput;
import club.heiqi.uilib.ui.scene.input.SceneEventType;
import club.heiqi.uilib.ui.scene.layout.CrossAxisAlign;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneScrolls;
import club.heiqi.uilib.ui.reactive.Signal;

import com.november.mcphone.addon.browser.core.AddonStore;
import com.november.mcphone.addon.browser.core.Urls;
import com.november.mcphone.client.scene.PhoneUi;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 浏览器管理页（页面型场景树）：
 * URL 输入 + 「打开浏览器」、主页直达、书签列表（点直达/删）、历史（最近 20 条）。
 * MCEF 缺失或惰性初始化未完成时顶部显示提示横幅（不会在此页拉起 CEF）；
 * 打开动作仍可用（BrowserScreen 内会显示错误/初始化结果）。
 *
 * <p>树一次性构建；变更（加/删书签等）经 {@link BrowserApp#rebuildManagementPage}
 * 重建整页（与 MCphone 便签页同模式）。</p>
 */
@SideOnly(Side.CLIENT)
final class BrowserPages {

    private static final int COL_TEXT = 0xFFE8EDF2;
    private static final int COL_MUTED = 0xFF8B98A8;
    private static final int COL_PANEL = 0x33FFFFFF;
    private static final int COL_WARN = 0xFFE8B84E;

    private BrowserPages() {}

    static SceneNode create(PhoneUi ui) {
        SceneNode page = scrollColumn(ui);
        page.appendChild(PhoneUi.title(tr("app.mcphone_browser.browser")));

        // 惰性初始化：管理页不拉起 CEF——仅在「初始化尚未成功」时提示；
        // CEF 真正的初始化发生在首次打开全屏浏览器大屏（BrowserScreen）时。
        if (McefLazyInit.initializationPending()) {
            SceneNode banner = new SceneNode();
            String r = McefLazyInit.failReason();
            banner.setText(r.isEmpty() ? tr("msg.mcphone_browser.lazy_init") : r);
            banner.setTextColor(COL_WARN);
            banner.setFontSize(PhoneUi.fs(14));
            banner.setHitTestable(false);
            page.appendChild(banner);
        } else {
            McefBridge.detect();
            if (!McefBridge.available()) {
                SceneNode banner = new SceneNode();
                banner.setText(tr("err.mcphone_browser.missing_mcef"));
                banner.setTextColor(COL_WARN);
                banner.setFontSize(PhoneUi.fs(14));
                banner.setHitTestable(false);
                page.appendChild(banner);
            }
        }

        // ============ URL 输入行 ============
        Signal<String> urlValue = Signal.create("");

        SceneNode inputRow = SceneNode.row();
        inputRow.setFillParentWidth(true);
        inputRow.setCrossAxisAlign(CrossAxisAlign.CENTER);
        inputRow.setGap(8);

        // 输入框塞进 flexGrow 容器，避免和按钮互相挤压
        SceneNode inputBox = SceneNode.column();
        inputBox.setFlexGrow(1);
        ui.runtime()
            .mount(inputBox, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
                urlValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
                tr("label.mcphone_browser.url"), 256,
                SceneInputType.TEXT, urlValue::set)))
            .getRoot()
            .setFillParentWidth(true);
        inputRow.appendChild(inputBox);

        SceneButton.Props openProps = new SceneButton.Props(
            Signal.create(tr("btn.mcphone_browser.open")), Signal.create(Boolean.TRUE),
            () -> ui.post(() -> openUrl(ui, urlValue.get())),
            SceneButtonVariant.PRIMARY);
        ui.runtime().mount(inputRow, SceneButton.create(ui.runtime(), openProps)).getRoot();

        page.appendChild(inputRow);

        // ============ 主页 ============
        SceneNode homeRow = SceneNode.row();
        homeRow.setFillParentWidth(true);
        appendButton(ui, homeRow, tr("btn.mcphone_browser.home"), () -> openUrl(ui, AddonStore.home()));
        page.appendChild(homeRow);

        // ============ 设为主页（P2-4）：预填最近浏览 URL，可手输 ============
        addHomeEditRow(ui, page);

        // ============ 新增书签（P2-3 管理页手动加） ============
        addBookmarkRow(ui, page);

        // ============ 书签 ============
        page.appendChild(sectionTitle(tr("label.mcphone_browser.bookmarks")));

        List<AddonStore.Bookmark> marks = AddonStore.bookmarks();
        if (marks.isEmpty()) {
            page.appendChild(PhoneUi.muted(tr("msg.mcphone_browser.no_bookmarks")));
        }
        for (int i = 0; i < marks.size(); i++) {
            final int idx = i;
            AddonStore.Bookmark b = marks.get(i);
            page.appendChild(listRow(ui, b.name, b.url, () -> openUrl(ui, b.url), () -> {
                AddonStore.removeBookmark(idx);
                BrowserApp.rebuildManagementPage(ui);
            }));
        }

        // ============ 历史（P2-6：单条删除 + 清空 + 时间） ============
        SceneNode histHead = SceneNode.row();
        histHead.setFillParentWidth(true);
        histHead.setCrossAxisAlign(CrossAxisAlign.CENTER);
        histHead.appendChild(sectionTitle(tr("label.mcphone_browser.history")));
        SceneNode histSpacer = SceneNode.column();
        histSpacer.setFlexGrow(1);
        histSpacer.setHitTestable(false);
        histHead.appendChild(histSpacer);
        appendButton(ui, histHead, tr("btn.mcphone_browser.clear_history"), () -> ui.post(() -> {
            AddonStore.clearHistory();
            BrowserApp.rebuildManagementPage(ui);
        }));
        page.appendChild(histHead);

        List<AddonStore.HistoryEntry> hist = AddonStore.history();
        if (hist.isEmpty()) {
            page.appendChild(PhoneUi.muted(tr("msg.mcphone_browser.no_history")));
        }
        for (int i = 0; i < hist.size(); i++) {
            final int hidx = i;
            AddonStore.HistoryEntry h = hist.get(i);
            final String hts = histTimeString(h.ts);
            page.appendChild(historyRow(ui, h.url, hts,
                () -> openUrl(ui, h.url),
                () -> {
                    AddonStore.removeHistory(hidx);
                    BrowserApp.rebuildManagementPage(ui);
                }));
        }

        return page;
    }

    // ===================== 动作 =====================

    /** 从管理页打开 URL：关闭手机 → 全屏虚拟浏览器。 */
    private static void openUrl(PhoneUi ui, String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            ui.toast(tr("err.mcphone_browser.no_url"));
            return;
        }
        ui.closePhone();
        BrowserScreen.open(url);
    }

    // ===================== P2-4/P2-3 管理页行 =====================

    /** P2-4 「设为主页」：预填最近浏览 URL，空输入 = 用最近浏览页。 */
    private static void addHomeEditRow(PhoneUi ui, SceneNode page) {
        Signal<String> homeValue = Signal.create(
            AddonStore.lastUrl() != null ? AddonStore.lastUrl() : "");

        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(8);

        SceneNode box = SceneNode.column();
        box.setFlexGrow(1);
        ui.runtime().mount(box, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
            homeValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
            tr("hint.mcphone_browser.set_home"), 2048,
            SceneInputType.TEXT, homeValue::set))).getRoot();
        row.appendChild(box);

        SceneButton.Props props = new SceneButton.Props(
            Signal.create(tr("btn.mcphone_browser.set_home")), Signal.create(Boolean.TRUE),
            () -> ui.post(() -> {
                String typed = homeValue.get() == null ? "" : homeValue.get().trim();
                String url = typed.isEmpty() ? AddonStore.lastUrl() : Urls.normalize(typed);
                if (url == null) {
                    ui.toast(tr("err.mcphone_browser.no_url"));
                    return;
                }
                AddonStore.setHome(url);
                ui.toast(tr("msg.mcphone_browser.home_set"));
            }),
            SceneButtonVariant.PRIMARY);
        ui.runtime().mount(row, SceneButton.create(ui.runtime(), props)).getRoot();
        page.appendChild(row);
    }

    /** P2-3 「新增书签」：名称可空（自动取主机名）、URL 空 = 最近浏览页。 */
    private static void addBookmarkRow(PhoneUi ui, SceneNode page) {
        Signal<String> nameValue = Signal.create("");
        Signal<String> urlValue = Signal.create("");

        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(8);

        SceneNode nameBox = SceneNode.column();
        nameBox.setFlexGrow(1);
        ui.runtime().mount(nameBox, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
            nameValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
            tr("label.mcphone_browser.new_name"), 64,
            SceneInputType.TEXT, nameValue::set))).getRoot();
        row.appendChild(nameBox);

        SceneNode urlBox = SceneNode.column();
        urlBox.setFlexGrow(2);
        ui.runtime().mount(urlBox, SceneTextInput.create(ui.runtime(), new SceneTextInput.Props(
            urlValue, Signal.create(Boolean.TRUE), Signal.create(Boolean.FALSE),
            tr("label.mcphone_browser.url"), 2048,
            SceneInputType.TEXT, urlValue::set))).getRoot();
        row.appendChild(urlBox);

        SceneButton.Props props = new SceneButton.Props(
            Signal.create(tr("btn.mcphone_browser.add")), Signal.create(Boolean.TRUE),
            () -> ui.post(() -> {
                String typed = urlValue.get() == null ? "" : urlValue.get().trim();
                String url = typed.isEmpty() ? AddonStore.lastUrl() : Urls.normalize(typed);
                if (url == null) {
                    ui.toast(tr("err.mcphone_browser.no_url"));
                    return;
                }
                String name = nameValue.get() == null ? "" : nameValue.get().trim();
                AddonStore.addBookmark(name.isEmpty() ? null : name, url);
                BrowserApp.rebuildManagementPage(ui);
                ui.toast(tr("msg.mcphone_browser.bookmarked"));
            }),
            SceneButtonVariant.PRIMARY);
        ui.runtime().mount(row, SceneButton.create(ui.runtime(), props)).getRoot();
        page.appendChild(row);
    }

    /** P2-6：时间戳以本地式样显示（SHORT = 2026/9/17 20:30 大小）。 */
    private static String histTimeString(long ts) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT).format(new java.util.Date(ts));
    }

    // ===================== 构件 =====================

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /**
     * 滚动列容器。必须补 {@link SceneScrolls#attach}（Qz 4.10.0 公开 API，
     * 与宿主 ScenePages 同型修复 [A9]G1/D-1）：只 setScrollable(true) 不挂
     * attach 时滚轮 SCROLL 事件无 handler，管理页滚不动。本处已
     * setScrollable(true)，attach 的前置校验安全通过。
     */
    private static SceneNode scrollColumn(PhoneUi ui) {
        SceneNode col = SceneNode.column();
        col.setFillParentWidth(true);
        col.setFlexGrow(1);
        col.setPadding(12);
        col.setGap(10);
        col.setScrollable(true);
        col.setClipChildren(true);
        SceneScrolls.attach(ui.runtime(), col);
        return col;
    }

    private static SceneNode sectionTitle(String text) {
        SceneNode n = new SceneNode();
        n.setText(text);
        n.setTextColor(COL_MUTED);
        n.setFontSize(PhoneUi.fs(14));
        n.setHitTestable(false);
        return n;
    }

    /** 书签行：点名称直达 + 删除钮（行本身不挂点击，避免与删除钮冒泡冲突）。 */
    private static SceneNode listRow(PhoneUi ui, String name, String url, Runnable onOpen, Runnable onDelete) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(6);
        row.setPadding(6, 6, 6, 6);
        row.setCornerRadius(6);
        row.setBackgroundColor(COL_PANEL);

        SceneNode label = new SceneNode();
        label.setText(name);
        label.setTextColor(COL_TEXT);
        label.setFontSize(PhoneUi.fs(15));
        label.setMaxTextWidth(ui.panelWidth() - 150);
        row.appendChild(label);
        ui.runtime().on(label, SceneEventType.CLICK, (e, ctx) -> ui.post(onOpen));

        SceneNode spacer = SceneNode.column();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        row.appendChild(spacer);

        SceneButton.Props delProps = new SceneButton.Props(
            Signal.create(tr("btn.mcphone_browser.del")), Signal.create(Boolean.TRUE),
            () -> ui.post(onDelete));
        ui.runtime().mount(row, SceneButton.create(ui.runtime(), delProps)).getRoot();
        return row;
    }

    /**
     * P2-6 历史行：点 URL 直达 + 时间列 + 删除钮。删除走 ui.post（红线 R4：
     * 点击回调内改树由 rebuildManagementPage 在下一帧执行）。
     */
    private static SceneNode historyRow(PhoneUi ui, String url, String time,
            Runnable onOpen, Runnable onDelete) {
        SceneNode row = SceneNode.row();
        row.setFillParentWidth(true);
        row.setCrossAxisAlign(CrossAxisAlign.CENTER);
        row.setGap(6);
        row.setPadding(5, 6, 5, 6);
        row.setCornerRadius(6);
        row.setBackgroundColor(COL_PANEL);

        SceneNode label = new SceneNode();
        label.setText(url);
        label.setTextColor(COL_MUTED);
        label.setFontSize(PhoneUi.fs(13));
        label.setMaxTextWidth(ui.panelWidth() - 190);
        row.appendChild(label);
        ui.runtime().on(label, SceneEventType.CLICK, (e, ctx) -> ui.post(onOpen));

        SceneNode spacer = SceneNode.column();
        spacer.setFlexGrow(1);
        spacer.setHitTestable(false);
        row.appendChild(spacer);

        SceneNode ts = new SceneNode();
        ts.setText(time);
        ts.setTextColor(COL_MUTED);
        ts.setFontSize(PhoneUi.fs(11));
        ts.setHitTestable(false);
        row.appendChild(ts);

        SceneButton.Props delProps = new SceneButton.Props(
            Signal.create(tr("btn.mcphone_browser.del")), Signal.create(Boolean.TRUE),
            () -> ui.post(onDelete));
        ui.runtime().mount(row, SceneButton.create(ui.runtime(), delProps)).getRoot();
        return row;
    }

    private static void appendButton(PhoneUi ui, SceneNode parent, String label, Runnable onClick) {
        SceneButton.Props props = new SceneButton.Props(
            Signal.create(label), Signal.create(Boolean.TRUE), () -> ui.post(onClick));
        ui.runtime().mount(parent, SceneButton.create(ui.runtime(), props)).getRoot();
    }
}
