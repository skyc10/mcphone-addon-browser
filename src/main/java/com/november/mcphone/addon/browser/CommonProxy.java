package com.november.mcphone.addon.browser;

import com.november.mcphone.addon.browser.net.BrowserNetwork;
import com.november.mcphone.addon.browser.wd.WdBridge;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 服务端/公共代理：注册网络通道并探测 WebDisplays 是否在 classpath 上。
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        BrowserNetwork.init();
        // 双端都探测一次：服务端也要能判断 WD 是否存在（放置屏幕在服务端执行）。
        WdBridge.detect();
    }

    public void init(FMLInitializationEvent event) {}

    public void postInit(FMLPostInitializationEvent event) {}
}
