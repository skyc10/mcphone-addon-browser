package com.november.mcphone.addon.browser.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * 书签与历史记录的客户端持久化（JSON）。
 *
 * <p>目录约定：.minecraft/mcphone/addons/browser/（mcphone 未提供 appDataDir 时
 * 自建该目录，路径写死约定——见附属需求书）。</p>
 *
 * <p>全部在客户端 UI 线程调用；文件很小，同步 IO 可接受（与 MCphone 便签/相册一致）。</p>
 */
public final class AddonStore {

    /** 书签：名称 + URL。 */
    public static final class Bookmark {

        public String name = "";
        public String url = "";
    }

    /** 历史条目：URL + 时间戳（毫秒）。 */
    public static final class HistoryEntry {

        public String url = "";
        public long ts;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_HISTORY = 20;

    /** 默认主页：Bing 搜索。 */
    public static final String DEFAULT_HOME = "https://www.bing.com";
    /** 默认自带书签：GTNH 中文 Wiki 首页。 */
    public static final String DEFAULT_BOOKMARK_URL = "https://gtnh.huijiwiki.com/wiki/%E9%A6%96%E9%A1%B5";
    public static final String DEFAULT_BOOKMARK_NAME = "GTNH 中文 Wiki";

    private static List<Bookmark> bookmarks;
    private static List<HistoryEntry> history;
    private static String home;
    private static String lastUrl;

    private AddonStore() {}

    private static File dir() {
        File d = new File(Minecraft.getMinecraft().mcDataDir, "mcphone/addons/browser");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File bookmarksFile() {
        return new File(dir(), "bookmarks.json");
    }

    private static File settingsFile() {
        return new File(dir(), "settings.json");
    }

    // ===================== 上次网址 =====================

    /** 上次浏览的网址（settings.json 的 lastUrl；null = 从未浏览）。 */
    public static synchronized String lastUrl() {
        if (lastUrl == null) {
            java.util.Map<?, ?> m = readSettings();
            if (m != null && m.get("lastUrl") instanceof String) {
                String s = (String) m.get("lastUrl");
                lastUrl = s.isEmpty() ? null : s;
            }
        }
        return lastUrl;
    }

    public static synchronized void setLastUrl(String url) {
        if (url == null || url.isEmpty()) return;
        lastUrl = url;
        saveSettings();
    }

    private static java.util.Map<?, ?> readSettings() {
        File f = settingsFile();
        if (!f.isFile()) return null;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, java.util.Map.class);
        } catch (Exception e) {
            System.err.println("[mcphone_browser] Failed to load settings.json: " + e);
            return null;
        }
    }

    private static void saveSettings() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(settingsFile(), false), StandardCharsets.UTF_8)) {
            // 合并：只覆盖本次真正改动的键，避免抹掉尚未加载进缓存的另一半配置
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            java.util.Map<?, ?> old = readSettings();
            if (old != null) {
                for (java.util.Map.Entry<?, ?> e : old.entrySet()) {
                    if (e.getKey() instanceof String && e.getValue() instanceof String) {
                        m.put((String) e.getKey(), (String) e.getValue());
                    }
                }
            }
            if (home != null) m.put("home", home);
            if (lastUrl != null) m.put("lastUrl", lastUrl);
            GSON.toJson(m, w);
        } catch (Exception e) {
            System.err.println("[mcphone_browser] Failed to save settings.json: " + e);
        }
    }

    private static File historyFile() {
        return new File(dir(), "history.json");
    }

    // ===================== 主页 =====================

    /** 当前主页设置（无设置时默认 Bing）。 */
    public static synchronized String home() {
        if (home == null) {
            String from = null;
            java.util.Map<?, ?> m = readSettings();
            if (m != null && m.get("home") instanceof String) {
                from = (String) m.get("home");
            }
            home = (from == null || from.isEmpty()) ? DEFAULT_HOME : from;
        }
        return home;
    }

    /** 设置主页并持久化。 */
    public static synchronized void setHome(String url) {
        if (url == null || url.isEmpty()) return;
        home = url;
        saveSettings();
    }

    // ===================== 书签 =====================

    public static synchronized List<Bookmark> bookmarks() {
        if (bookmarks == null) {
            bookmarks = load(bookmarksFile(), new TypeToken<List<Bookmark>>() {});
            if (bookmarks == null) {
                // 仅在从未创建过书签文件时预置默认书签；玩家删光的空列表保持为空
                bookmarks = new ArrayList<>();
                Bookmark b = new Bookmark();
                b.name = DEFAULT_BOOKMARK_NAME;
                b.url = DEFAULT_BOOKMARK_URL;
                bookmarks.add(b);
                save(bookmarksFile(), bookmarks);
            }
        }
        return bookmarks;
    }

    public static synchronized void addBookmark(String url) {
        if (url == null || url.isEmpty()) return;
        List<Bookmark> list = bookmarks();
        for (Bookmark b : list) {
            if (url.equals(b.url)) return; // 去重
        }
        Bookmark b = new Bookmark();
        b.url = url;
        b.name = hostOf(url);
        list.add(b);
        save(bookmarksFile(), list);
    }

    public static synchronized void removeBookmark(int index) {
        List<Bookmark> list = bookmarks();
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        save(bookmarksFile(), list);
    }

    // ===================== 历史 =====================

    public static synchronized List<HistoryEntry> history() {
        if (history == null) {
            history = load(historyFile(), new TypeToken<List<HistoryEntry>>() {});
            if (history == null) history = new ArrayList<>();
        }
        return history;
    }

    public static synchronized void addHistory(String url) {
        if (url == null || url.isEmpty()) return;
        List<HistoryEntry> list = history();
        HistoryEntry e = new HistoryEntry();
        e.url = url;
        e.ts = System.currentTimeMillis();
        list.add(0, e); // 新的在前
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        save(historyFile(), list);
    }

    // ===================== 底层 =====================

    private static <T> List<T> load(File f, TypeToken<List<T>> type) {
        if (!f.isFile()) return null;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type.getType());
        } catch (Exception e) {
            System.err.println("[mcphone_browser] Failed to load " + f.getName() + ": " + e);
            return null;
        }
    }

    private static void save(File f, Object data) {
        // 统一 UTF-8 写盘（Java 17 + zh-CN Windows 默认 GBK，中文书签名必须显式 UTF-8）
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            System.err.println("[mcphone_browser] Failed to save " + f.getName() + ": " + e);
        }
    }

    /** 从 URL 提取主机名作为默认书签名。 */
    public static String hostOf(String url) {
        try {
            String s = url;
            int i = s.indexOf("://");
            if (i >= 0) s = s.substring(i + 3);
            int slash = s.indexOf('/');
            if (slash >= 0) s = s.substring(0, slash);
            int port = s.indexOf(':');
            if (port >= 0) s = s.substring(0, port);
            return s.isEmpty() ? url : s;
        } catch (Throwable t) {
            return url;
        }
    }
}
