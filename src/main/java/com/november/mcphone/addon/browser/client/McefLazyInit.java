package com.november.mcphone.addon.browser.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MCEF 惰性初始化（根治「退出游戏进程挂起」）。
 *
 * <p>根因回顾：MCEF 在它自己的 FML init 阶段（{@code MCEF.onInit → PROXY.onInit()}）
 * 就完成资源下载并拉起 CEF，还启动了非守护线程 {@code MCEF-Shutdown}（退出时调用
 * {@code CefApp.dispose()}，真实模式下消息泵已停 → 永久阻塞 → JVM 无法退出）。
 * 即使玩家从不打开浏览器，CEF 也被拉起、退出也要陪跑清理。</p>
 *
 * <p>方案（全程反射，不进编译期依赖）：{@code MCEF.PROXY} 是 {@code @SidedProxy}
 * 注入的 public static 字段（客户端为 ClientProxy）。本附属的 preInit 事件先于
 * MCEF 的 init 事件执行（dependencies 声明 after:MCEF），此时把 PROXY 临时换成
 * 服务端桩 {@code net.montoyo.mcef.BaseProxy} 的实例——MCEF.onInit 对桩只打一行
 * 日志，CEF 完全不启动，MCEF-Shutdown 线程也不存在。玩家第一次打开浏览器
 * App（{@link McefBridge#detect()} 被触发）时再换回真实 ClientProxy 并反射调用其
 * {@code onInit()}，线程上下文与 MCEF 原本在主线程 init 阶段执行完全一致。</p>
 *
 * <p>失败兜底：{@code onInit()} 抛异常 → 换回桩、下次打开重试；
 * {@code onInit()} 正常返回但 {@code ClientProxy.VIRTUAL == true}（下载失败降级
 * 虚拟模式）→ 同样允许下次打开重试（先复位 VIRTUAL）。CefApp 已创建时重复
 * onInit 会在 MCEF 内部 catch 住并降级虚拟模式，不会崩溃（CefApp.getInstance
 * 对已初始化实例抛 IllegalStateException，MCEF 的 catch(Throwable) 接住）。</p>
 *
 * <p>状态机：ABSENT（MCEF 未安装，永远不初始化）/ DEFERRED（已换成桩等首次
 * 打开）/ PASSTHROUGH（延迟失败或环境异常，MCEF 按原版时序自行初始化）。</p>
 */
@SideOnly(Side.CLIENT)
public final class McefLazyInit {

    private static final int STATE_ABSENT = 0;
    private static final int STATE_DEFERRED = 1;
    private static final int STATE_PASSTHROUGH = 2;

    private static final String MCEF_CLASS = "net.montoyo.mcef.MCEF";
    private static final String STUB_CLASS = "net.montoyo.mcef.BaseProxy";
    private static final String CLIENT_PROXY_CLASS = "net.montoyo.mcef.client.ClientProxy";

    private static int state = STATE_ABSENT;
    private static boolean initialized;   // onInit 成功完成（真实 CEF，非虚拟）
    private static Object realProxy;      // net.montoyo.mcef.client.ClientProxy 实例
    private static Object stubProxy;      // BaseProxy 实例（服务端桩）
    private static String failReason = "";

    private McefLazyInit() {}

    /**
     * 附属 preInit 阶段调用：把 MCEF.PROXY 换成服务端桩，冻结 CEF 初始化。
     * 任何失败都回退为「不干预」，MCEF 按原版时序自行初始化。
     */
    public static synchronized void defer() {
        try {
            Class<?> mcef = Class.forName(MCEF_CLASS);
            Field proxy = mcef.getField("PROXY");
            Object current = proxy.get(null);
            if (current == null
                || !CLIENT_PROXY_CLASS.equals(current.getClass().getName())) {
                // PROXY 尚未注入或不是客户端实现（理论不该发生）→ 不干预
                state = STATE_PASSTHROUGH;
                return;
            }
            Class<?> stub = Class.forName(STUB_CLASS);
            stubProxy = stub.getDeclaredConstructor().newInstance();
            proxy.set(null, stubProxy);
            realProxy = current;
            state = STATE_DEFERRED;
            System.out.println("[mcphone_browser] MCEF CEF init deferred until the browser app is first opened");
        } catch (ClassNotFoundException e) {
            state = STATE_ABSENT;
            System.out.println("[mcphone_browser] MCEF not installed, lazy init not armed");
        } catch (Throwable t) {
            state = STATE_PASSTHROUGH;
            System.err.println("[mcphone_browser] Failed to defer MCEF init, MCEF keeps vanilla init timing: " + t);
        }
    }

    /**
     * 首次真正打开浏览器 App 时调用（在客户端主线程，与 MCEF 原装在 FML init
     * 阶段的执行线程一致）：恢复真实 ClientProxy 并执行其 onInit()。
     * 下载失败（VIRTUAL）或异常时保留重试资格，下次打开再试。
     */
    public static synchronized void ensureInitialized() {
        if (state != STATE_DEFERRED || initialized) return;
        try {
            Field proxy = Class.forName(MCEF_CLASS).getField("PROXY");
            proxy.set(null, realProxy);
            setVirtual(false);
            Method onInit = realProxy.getClass().getMethod("onInit");
            onInit.invoke(realProxy);
            if (isVirtual()) {
                initialized = false;
                String status = clientProxyStaticString("NATIVES_STATUS");
                if (status != null && status.equals("downloading")) {
                    failReason = "CEF natives downloading in background (GitHub mirror) - reopen the browser in a moment";
                } else if (status != null && status.startsWith("failed")) {
                    failReason = "CEF natives download failed (" + status + ") - will retry on next open";
                } else {
                    failReason = "MCEF fell back to virtual mode (CEF resources missing? will retry on next open)";
                }
                System.err.println("[mcphone_browser] " + failReason);
            } else {
                initialized = true;
                failReason = "";
                System.out.println("[mcphone_browser] MCEF CEF initialized (lazy, on first browser open)");
            }
        } catch (Throwable t) {
            failReason = String.valueOf(t);
            System.err.println("[mcphone_browser] MCEF lazy init failed (will retry on next open): " + t);
            try {
                // 回滚到桩，避免半初始化状态下其他 mod 经 API 撞上空 CefClient
                if (stubProxy != null) {
                    Class.forName(MCEF_CLASS).getField("PROXY").set(null, stubProxy);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** CEF 是否已（或将按原版时序被）初始化——供退出看门狗条件化判定。 */
    public static synchronized boolean isCefActive() {
        if (state == STATE_PASSTHROUGH) return true;
        return state == STATE_DEFERRED && initialized;
    }

    /** 惰性初始化尚未成功（等待首次打开 / 上次失败待重试）——供管理页横幅提示。 */
    public static synchronized boolean initializationPending() {
        return state == STATE_DEFERRED && !initialized;
    }

    public static synchronized String failReason() {
        return failReason;
    }

    // ===================== ClientProxy.VIRTUAL 反射存取 =====================

    private static boolean isVirtual() {
        try {
            Field f = realProxy.getClass().getField("VIRTUAL");
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false; // 字段不存在（未知 MCEF 版本）时按成功处理
        }
    }

    private static void setVirtual(boolean v) {
        try {
            Field f = realProxy.getClass().getField("VIRTUAL");
            f.setBoolean(null, v);
        } catch (Throwable ignored) {}
    }

    /** Reads a public static String field off ClientProxy (e.g. NATIVES_STATUS); null if absent. */
    private static String clientProxyStaticString(String field) {
        try {
            Field f = realProxy.getClass().getField(field);
            return (String) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
