package com.november.mcphone.addon.browser.client;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.november.mcphone.addon.browser.core.AddonStore;
import com.november.mcphone.addon.browser.core.PhoneNbt;
import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.client.scene.PhoneUi;

import club.heiqi.uilib.ui.scene.node.SceneNode;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「浏览器」App（虚拟大屏方案）。
 *
 * <p><b>点击</b>：直接打开全屏虚拟浏览器（16:9、约 80% 游戏窗口），
 * 加载上次网址或默认主页（Bing），不放置任何方块。</p>
 *
 * <p><b>Shift+点击</b>：打开管理页（页面型：URL 输入、书签、历史、打开浏览器）。</p>
 *
 * <p>实现说明：直达型 App 的 Shift+点击进入 {@link #onActivate(PhoneUi, boolean)}
 * （shift=true）。打开管理页需要 {@link PhoneUi#openApp(String)}，而它会拒绝直达型
 * App——这里用「临时翻转变量为 false」的窗口期让 openApp 正常建页（全程在
 * PhoneUi.post 的单线程回调内，无竞态）。</p>
 */
@SideOnly(Side.CLIENT)
public class BrowserApp implements IPhoneApp {

    private static BrowserApp instance;

    /** 正在以「页面型」身份打开管理页（isDirectAction 短暂返回 false）。 */
    private boolean openingPage;

    public BrowserApp() {
        instance = this;
    }

    @Override
    public String id() {
        return "browser";
    }

    @Override
    public String displayName() {
        return StatCollector.translateToLocal("app.mcphone_browser.browser");
    }

    @Override
    public int iconColor() {
        return 0xFF2E6E8E;
    }

    @Override
    public String iconGlyph() {
        return "览";
    }

    @Override
    public String iconTexture() {
        return "mcphone_browser:textures/ui/app_browser.png";
    }

    @Override
    public boolean isBuiltin() {
        return false;
    }

    @Override
    public boolean isDirectAction() {
        return !openingPage;
    }

    @Override
    public void onActivate(PhoneUi ui, boolean shift) {
        if (shift) {
            openManagementPage(ui);
            return;
        }
        // 直达：打开全屏虚拟浏览器。URL 优先级：手机 NBT → 本地 lastUrl → 默认主页 Bing
        String url = PhoneNbt.readLastUrl(ui.phoneStack());
        if (url == null) {
            url = AddonStore.lastUrl();
        }
        if (url == null) {
            url = AddonStore.home();
        }
        BrowserScreen.open(url);
    }

    @Override
    public SceneNode createPage(PhoneUi ui) {
        return BrowserPages.create(ui);
    }

    /** 打开（或重建）管理页；供本 App 与 BrowserPages 在列表变更后刷新。 */
    static void openManagementPage(PhoneUi ui) {
        BrowserApp app = instance != null ? instance : new BrowserApp();
        app.openingPage = true;
        try {
            ui.openApp(app.id());
        } finally {
            app.openingPage = false;
        }
    }

    /** 在管理页状态下重建页面（书签/历史增删后调用）。 */
    static void rebuildManagementPage(PhoneUi ui) {
        BrowserApp app = instance;
        if (app == null) return;
        app.openingPage = true;
        try {
            ui.rebuildPage();
        } finally {
            app.openingPage = false;
        }
    }

    /** 当前手机实例上的手机物品（客户端同步副本）。 */
    static ItemStack phoneOf(PhoneUi ui) {
        return ui.phoneStack();
    }
}
