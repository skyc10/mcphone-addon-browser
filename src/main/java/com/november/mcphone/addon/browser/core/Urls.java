package com.november.mcphone.addon.browser.core;

/**
 * URL 工具。
 */
public final class Urls {

    private Urls() {}

    /**
     * 无协议时补 https://（Bing / GTNH Wiki 均为 https）；空返回 null。
     *
     * <p>P2-10 IDN 治理：中文域名走 MCEF 内核的 punycode（ASCII 输入原样
     * 返回）——<code>gtnh中文wiki</code> 这类输入会被转成 xn-- 形式再交给
     * CEF/解析器。挂一个 try-catch 兜底转译失败时不阻断导航。</p>
     */
    public static String normalize(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        try {
            s = net.montoyo.mcef.MCEF.PROXY.punycode(s);
        } catch (Throwable t) {
            // 域名转译失败不阻断导航
        }
        return s;
    }
}
