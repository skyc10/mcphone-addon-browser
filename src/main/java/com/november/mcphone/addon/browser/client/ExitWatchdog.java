package com.november.mcphone.addon.browser.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 退出看门狗：修复「点击退出游戏后进程不结束」。
 *
 * <p>根因：MCEF 的 MCEF-Shutdown 线程（非守护）在 MC 停止后调用
 * CefApp.dispose()，真实 CEF 模式下消息泵已随主循环停止，dispose 永久阻塞，
 * JVM 因这个非守护线程无法退出（日志最后停在 "Shutting down JCEF..."）。</p>
 *
 * <p>方案：本守护线程监视 Minecraft.running；其变 false（游戏真正退出）后，
 * 先关闭我们打开的浏览器，再宽限 5 秒等 MCEF 正常清理；若 JVM 仍未退出，
 * 说明 dispose 挂死，强制 {@link Runtime#halt(int)} 结束进程。
 * 正常情况下 JVM 会在宽限期内自行退出，守护线程随之消亡，halt 不会执行。</p>
 */
@SideOnly(Side.CLIENT)
public final class ExitWatchdog {

    private static final long GRACE_MS = 5000;

    private ExitWatchdog() {}

    public static void arm() {
        Thread t = new Thread(ExitWatchdog::watch, "mcphone_browser-ExitWatchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void watch() {
        Field running;
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
            running = findRunningField();
        } catch (Throwable t) {
            System.out.println("[mcphone_browser] ExitWatchdog: init failed, disarmed (" + t + ")");
            return;
        }
        if (running == null) {
            System.out.println("[mcphone_browser] ExitWatchdog: Minecraft.running field not found, disarmed");
            return;
        }
        while (true) {
            try {
                // 对静态/实例字段都成立（与 MCEF 同款读法）
                if (!running.getBoolean(mc)) break;
            } catch (Throwable t) {
                System.out.println("[mcphone_browser] ExitWatchdog: read failed, disarmed (" + t + ")");
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
        // 游戏已开始退出：关掉我们自己打开的浏览器，给正常清理留宽限期
        try {
            BrowserScreen.forceClose();
        } catch (Throwable ignored) {}
        try {
            Thread.sleep(GRACE_MS);
        } catch (InterruptedException e) {
            return;
        }
        // 宽限期后只检查 CEF/MCEF 家族的存活非守护线程——这是 dispose 挂死的精确特征；
        // 其它线程（GTNH 正常收尾）不干预，让 JVM 自然退出。
        StringBuilder hung = new StringBuilder();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            String n = t.getName();
            if (t.isAlive() && !t.isDaemon()
                && (n.contains("MCEF") || n.toLowerCase().contains("cef") || n.toLowerCase().contains("jcef"))) {
                if (hung.length() > 0) hung.append(", ");
                hung.append(n);
                StackTraceElement[] st = e.getValue();
                if (st != null && st.length > 0) {
                    hung.append(" at ").append(st[0]);
                }
            }
        }
        if (hung.length() > 0) {
            System.out.println("[mcphone_browser] ExitWatchdog: CEF cleanup threads hung (" + hung + "), forcing halt");
            Runtime.getRuntime().halt(0);
        }
        // 没有挂死特征：线程结束，JVM 自行退出
    }

    /** 与 MCEF 同款探测：Minecraft 里 volatile boolean 字段即 running（1.7.10 为静态）。 */
    private static Field findRunningField() {
        try {
            for (Field f : Minecraft.class.getDeclaredFields()) {
                if (f.getType() == boolean.class && (f.getModifiers() & Modifier.VOLATILE) != 0) {
                    f.setAccessible(true);
                    return f;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
