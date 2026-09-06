package com.november.mcphone.addon.browser.net;

/**
 * 结果包 → 客户端 UI 的转发点。
 *
 * <p>本类在客户端 classloader 中被 ScreenResult.Handler 引用；showResult 内部
 * 只触碰 Qz-UILib/MCphone 客户端类。为了让专用服务器不因类校验失败，这里用
 * 反射调用 PhoneUi 的 toast（PhoneUi 在专用服务器上不存在，反射调用在客户端
 * classloader 里正常解析）。</p>
 */
public final class ClientHooks {

    private ClientHooks() {}

    public static void showResult(boolean ok, String key) {
        try {
            Class<?> ui = Class.forName("com.november.mcphone.client.scene.PhoneUi");
            Object active = ui.getField("ACTIVE").get(null);
            String text = net.minecraft.util.StatCollector.translateToLocal(
                ok ? "msg.mcphone_browser." + key : "err.mcphone_browser." + key);
            if (active != null) {
                ui.getMethod("toast", String.class).invoke(active, text);
            } else {
                net.minecraft.client.Minecraft.getMinecraft().thePlayer.addChatMessage(
                    new net.minecraft.util.ChatComponentText(text));
            }
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] showResult failed: " + t);
        }
    }
}
