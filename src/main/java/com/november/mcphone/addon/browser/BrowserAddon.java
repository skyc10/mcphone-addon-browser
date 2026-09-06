package com.november.mcphone.addon.browser;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * MCphone 附属：浏览器 App（mcphone_browser）。
 *
 * <p>点击手机上的「浏览器」图标直接打开一块 16:9 的全屏虚拟大屏（不放置任何
 * 方块），由 MCEF/CEF 离屏渲染真实网页，加载上次 URL 或默认主页；Shift+点击
 * 打开管理页（URL 输入、书签、历史）。</p>
 *
 * <p>前置：<b>MCphone</b>（必需）、<b>MCEF 0.7</b> 真实浏览器模式（必需）。
 * MCEF 缺失或处于虚拟模式时显示错误页，绝不崩溃。</p>
 *
 * <p>技术红线：本附属全程 try-catch，任何初始化失败只打日志，绝不拖垮手机本体与游戏。</p>
 */
@Mod(
    modid = BrowserAddon.MODID,
    name = "MCphone Addon - Browser",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:mcphone;after:MCEF")
public class BrowserAddon {

    public static final String MODID = "mcphone_browser";

    /** 手机 NBT 键前缀（技术红线：只写自己的键）。 */
    public static final String NBT_LAST_URL = "mcphone:browser:lastUrl";

    @Mod.Instance(MODID)
    public static BrowserAddon INSTANCE;

    @SidedProxy(
        clientSide = "com.november.mcphone.addon.browser.ClientProxy",
        serverSide = "com.november.mcphone.addon.browser.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            proxy.preInit(event);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] preInit failed (addon disabled, game continues): " + t);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        try {
            proxy.init(event);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] init failed (addon disabled, game continues): " + t);
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        try {
            proxy.postInit(event);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] postInit failed (addon disabled, game continues): " + t);
        }
    }
}
