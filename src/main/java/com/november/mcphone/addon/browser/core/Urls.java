package com.november.mcphone.addon.browser.core;

/**
 * URL 工具。
 */
public final class Urls {

    private Urls() {}

    /** 无协议时补 https://（Bing / GTNH Wiki 均为 https）；空返回 null。 */
    public static String normalize(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        return s;
    }
}
