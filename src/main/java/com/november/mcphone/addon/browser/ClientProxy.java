package com.november.mcphone.addon.browser;

import com.november.mcphone.addon.browser.client.BrowserApp;
import com.november.mcphone.addon.browser.client.ExitWatchdog;
import com.november.mcphone.addon.browser.client.McefLazyInit;

import com.november.mcphone.api.PhoneApi;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端代理：向 MCphone 注册「浏览器」App，冻结 MCEF 的装载期 CEF 初始化，
 * 并启动退出看门狗。
 */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        // 惰性初始化：本附属 preInit 先于 MCEF 的 init 事件（after:MCEF），
        // 此时把 MCEF.PROXY 换成服务端桩，CEF 推迟到首次打开浏览器 App 才拉起
        // （根治「从不使用浏览器也要陪 CEF 清理、退出可能挂起」）。
        try {
            McefLazyInit.defer();
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] McefLazyInit defer failed: " + t);
        }
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        try {
            BrowserApp app = new BrowserApp();
            if (!PhoneApi.register(app)) {
                System.out.println("[mcphone_browser] PhoneApi.register: id '" + app.id()
                    + "' already registered (services auto-discovery)");
            }
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] Failed to register BrowserApp: " + t);
        }
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
        // 退出看门狗：MCEF CefApp.dispose 挂死时强制结束进程（守护线程，正常退出
        // 不会触发；内部条件化——CEF 从未初始化时不做任何宽限/强杀动作）
        try {
            ExitWatchdog.arm();
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] ExitWatchdog arm failed: " + t);
        }
    }
}
