package com.november.mcphone.addon.browser.client;

import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MCEF 反射桥（虚拟浏览器大屏的唯一后端；不依赖 WebDisplays 方块）。
 *
 * <p>通过 net.montoyo.mcef.api.MCEFApi.getAPI() 拿到 API 实现者（MCEF.PROXY）。
 * createBrowser(String) 优先走本项目给 MCEF 0.7 打的 default 方法桥接
 * （→ createBrowser(url, false)）；对未打补丁的原版 MCEF 0.7 自动回退到
 * 双参 createBrowser(String, boolean)。</p>
 */
@SideOnly(Side.CLIENT)
public final class McefBridge {

    private static boolean detected;
    private static Object api;            // net.montoyo.mcef.api.API 实例
    private static Method mCreate;        // createBrowser(String) 或 createBrowser(String, boolean)
    private static boolean createTwoArg;  // true = 原版双参签名
    private static Method mIsVirtual;     // isVirtual()（可缺省）
    private static String failReason = "";

    private McefBridge() {}

    public static synchronized void detect() {
        if (detected) return;
        // 惰性初始化：第一次触碰 MCEF API 时才真正拉起 CEF（本方法只会在玩家
        // 打开浏览器大屏/管理页时于主线程被调用，与 MCEF 原本在 FML init 阶段
        // 的执行线程一致）。初始化尚未成功（首次待执行/失败待重试）时不缓存
        // 结果，以便下次打开时重新探测。
        McefLazyInit.ensureInitialized();
        if (McefLazyInit.initializationPending()) {
            failReason = McefLazyInit.failReason().isEmpty()
                ? "MCEF lazy init not completed yet"
                : McefLazyInit.failReason();
            System.out.println("[mcphone_browser] MCEF bridge: init pending (" + failReason + ")");
            return;
        }
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
            try {
                mCreate = api.getClass().getMethod("createBrowser", String.class);
                createTwoArg = false;
            } catch (NoSuchMethodException e) {
                // 未打补丁的原版 MCEF 0.7：createBrowser(String, boolean)
                mCreate = api.getClass().getMethod("createBrowser", String.class, boolean.class);
                createTwoArg = true;
            }
            try {
                mIsVirtual = api.getClass().getMethod("isVirtual");
            } catch (Throwable t) {
                mIsVirtual = null; // 缺该方法时按「非虚拟」处理
            }
            System.out.println("[mcphone_browser] MCEF bridge ready (createBrowser "
                + (createTwoArg ? "String,boolean" : "String") + ")");
            // 探测 MCEF 0.6/0.7 上游 bug 的特征：CefBrowserOsr 是否带 renderer_
            // 字段（其 initialize() 在上游被孤儿化、纹理 id 恒 0）。存在则说明
            // BrowserHandle 的纹理初始化兜底会生效。
            try {
                Class<?> osr = cl.loadClass("org.cef.browser.CefBrowserOsr");
                osr.getDeclaredField("renderer_");
                System.out.println("[mcphone_browser] OSR renderer texture-init shim armed");
            } catch (Throwable t) {
                System.out.println("[mcphone_browser] OSR renderer_ field absent ("
                    + t.getClass().getSimpleName() + "), texture-init shim not needed/applicable");
            }
        } catch (Throwable t) {
            api = null;
            failReason = String.valueOf(t);
            System.out.println("[mcphone_browser] MCEF bridge unavailable: " + t);
        }
    }

    /** MCEF 可用且非虚拟模式。 */
    public static boolean available() {
        detect();
        if (api == null) return false;
        if (mIsVirtual == null) return true;
        try {
            return !Boolean.TRUE.equals(mIsVirtual.invoke(api));
        } catch (Throwable t) {
            return true;
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
            Object b = createTwoArg ? mCreate.invoke(api, url, Boolean.FALSE) : mCreate.invoke(api, url);
            return b == null ? null : new BrowserHandle(b);
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] createBrowser failed: " + t);
            return null;
        }
    }
}
