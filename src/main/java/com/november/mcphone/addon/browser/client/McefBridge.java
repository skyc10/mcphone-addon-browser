package com.november.mcphone.addon.browser.client;

import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MCEF 反射桥（虚拟浏览器大屏的唯一后端；不再使用 WebDisplays 方块）。
 *
 * <p>通过 net.montoyo.mcef.api.MCEFApi.getAPI() 拿到 API 实现者（MCEF.PROXY），
 * createBrowser(String) 走本附属给 MCEF 0.7 打的 default 方法桥接
 * （→ createBrowser(url, false)，与 MCEF 0.1 老语义一致）。</p>
 */
@SideOnly(Side.CLIENT)
public final class McefBridge {

    private static boolean detected;
    private static Object api;            // net.montoyo.mcef.api.API 实例
    private static Method mCreate;        // createBrowser(String) -> IBrowser
    private static Method mIsVirtual;     // isVirtual()
    private static String failReason = "";

    private McefBridge() {}

    public static synchronized void detect() {
        if (detected) return;
        detected = true;
        try {
            ClassLoader cl = McefBridge.class.getClassLoader();
            Class<?> mcefApi = cl.loadClass("net.montoyo.mcef.api.MCEFApi");
            Method get = mcefApi.getMethod("getAPI");
            api = get.invoke(null);
            if (api == null) {
                failReason = "MCEF PROXY is null (MCEF not initialized)";
                return;
            }
            mIsVirtual = api.getClass().getMethod("isVirtual");
            mCreate = api.getClass().getMethod("createBrowser", String.class);
        } catch (Throwable t) {
            api = null;
            failReason = String.valueOf(t);
            System.out.println("[mcphone_browser] MCEF bridge unavailable: " + t);
        }
    }

    /** MCEF 真实模式（非虚拟模式）且 API 可用。 */
    public static boolean available() {
        detect();
        if (api == null) return false;
        try {
            Object v = mIsVirtual.invoke(api);
            return !Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String failReason() {
        return failReason;
    }

    /** 创建浏览器；失败返回 null。 */
    public static BrowserHandle create(String url) {
        detect();
        if (api == null) return null;
        try {
            Object b = mCreate.invoke(api, url);
            return b == null ? null : new BrowserHandle(b);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] createBrowser failed: " + t);
            return null;
        }
    }
}
