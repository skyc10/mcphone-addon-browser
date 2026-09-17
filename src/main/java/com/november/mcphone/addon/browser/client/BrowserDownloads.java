package com.november.mcphone.addon.browser.client;

import java.io.File;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDownloadHandlerAdapter;

import net.minecraft.client.Minecraft;

/**
 * P2-7 下载简版：页面触发下载时静默落盘到 {@code <gamedir>/mcphone/downloads/}，
 * 起/完成的反馈走 {@link BrowserScreen#pushNotice}（volatile，主线程
 * drawScreen 的横幅自会取显——回调都在 CEF UI 线程，本类绝不触碰 MC UI 树）。
 *
 * <p>线程契约（R-5）：onBeforeDownload/onDownloadUpdated 由 CEF 线程回调；
 * 与 CEF 的唯一交互是 {@code callback.Continue(path, false)}（立即返回、
 * 不弹「另存为」、不阻塞消息泵）。文件写盘由 CEF 自身完成。</p>
 */
public final class BrowserDownloads extends CefDownloadHandlerAdapter {

    private static final String NOTES = "[mcphone_browser] download";

    private static File dir() {
        File d = new File(Minecraft.getMinecraft().mcDataDir, "mcphone/downloads");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    @Override
    public boolean onBeforeDownload(CefBrowser browser, CefDownloadItem item,
            String suggestedName, CefBeforeDownloadCallback callback) {
        try {
            String name = sanitize(item != null && item.getSuggestedFileName() != null
                ? item.getSuggestedFileName() : suggestedName);
            File dst = unique(new File(dir(), name));
            // 立即 Continue，不阻塞 CEF 消息泵；showDialog=false 静默落盘
            callback.Continue(dst.getAbsolutePath(), false);
            BrowserScreen.pushNotice("↓ 下载开始: " + dst.getName());
            System.out.println(NOTES + " started -> " + dst.getPath());
            return true;
        } catch (Throwable t) {
            System.err.println(NOTES + " accept failed (default handling): " + t);
            BrowserScreen.pushNotice("下载启动失败: " + t);
            return false;
        }
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem item,
            CefDownloadItemCallback callback) {
        try {
            if (item == null || !item.isValid()) {
                return;
            }
            if (item.isComplete()) {
                String shown = new File(item.getFullPath()).getName();
                BrowserScreen.pushNotice("下载完成: " + shown
                    + " (" + (item.getTotalBytes() / 1024) + " KiB) -> mcphone/downloads");
                System.out.println(NOTES + " complete -> " + item.getFullPath());
            } else if (item.isCanceled()) {
                BrowserScreen.pushNotice("下载取消/中断: " + item.getSuggestedFileName());
                System.out.println(NOTES + " canceled: " + item.getURL());
            }
        } catch (Throwable t) {
            System.out.println(NOTES + " update notify failed: " + t);
        }
    }

    /** 文件名消毒：砍掉路径分隔与控制字符，防目录穿越。 */
    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "download-" + System.currentTimeMillis();
        }
        String s = name.trim().replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1);
        s = s.replaceAll("[^a-zA-Z0-9_. \\-]", "_");  // 消毒：仅保留安全字符
        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
            return "download-" + System.currentTimeMillis();
        }
        return s;
    }

    /** 避免覆盖既有文件：存在则追加序号。 */
    private static File unique(File f) {
        if (!f.isFile()) {
            return f;
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File cand = new File(f.getParentFile(), base + "-" + i + ext);
            if (!cand.isFile()) {
                return cand;
            }
        }
        return new File(f.getParentFile(), base + "-" + System.currentTimeMillis() + ext);
    }
}
