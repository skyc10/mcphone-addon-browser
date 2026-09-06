package com.november.mcphone.addon.browser.net;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import com.november.mcphone.addon.browser.BrowserAddon;
import com.november.mcphone.addon.browser.wd.WdBridge;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 附属网络通道 "mcphone_browser"：
 * <ul>
 * <li>0 = PlaceScreen（C→S）：在玩家面前放置/恢复大屏并加载 URL；</li>
 * <li>1 = HideScreen（C→S）：隐藏（移除）当前大屏，URL 保留在手机 NBT；</li>
 * <li>2 = ScreenResult（S→C）：操作结果，客户端用聊天栏或 SceneToast 反馈。</li>
 * </ul>
 *
 * <p>1.7.10 惯例：包处理直接在主线程执行（与 mcphone 本体 NetworkHandler 一致），
 * 世界操作天然满足「服务端主线程执行」红线。</p>
 */
public final class BrowserNetwork {

    public static SimpleNetworkWrapper INSTANCE;

    private BrowserNetwork() {}

    public static void init() {
        INSTANCE = cpw.mods.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel(BrowserAddon.CHANNEL);
        INSTANCE.registerMessage(PlaceScreen.Handler.class, PlaceScreen.class, 0, Side.SERVER);
        INSTANCE.registerMessage(HideScreen.Handler.class, HideScreen.class, 1, Side.SERVER);
        // 结果包只在客户端处理；Handler 仅在客户端 onMessage 时才会触达 ClientHooks，
        // ClientHooks 只在客户端 classloader 加载（专用服务器上该路径永不执行）。
        INSTANCE.registerMessage(ScreenResult.Handler.class, ScreenResult.class, 2, Side.CLIENT);
    }

    public static void sendToServer(IMessage msg) {
        if (INSTANCE != null) INSTANCE.sendToServer(msg);
    }

    // ===================== 0：放置/恢复大屏 =====================

    public static class PlaceScreen implements IMessage {

        public String url = "";

        public PlaceScreen() {}

        public PlaceScreen(String url) {
            this.url = url == null ? "" : url;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            byte[] data = new byte[buf.readShort()];
            buf.readBytes(data);
            url = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            byte[] data = url.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(data.length);
            buf.writeBytes(data);
        }

        public static class Handler implements IMessageHandler<PlaceScreen, IMessage> {

            @Override
            public IMessage onMessage(PlaceScreen msg, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().playerEntity;
                String err;
                if (!WdBridge.available()) {
                    err = "missing_wd";
                } else {
                    String url = WdBridge.normalize(msg.url);
                    if (url == null) {
                        err = "no_url";
                    } else {
                        err = WdBridge.placeScreen(p, url);
                    }
                }
                BrowserNetwork.INSTANCE.sendTo(new ScreenResult(err == null, err == null ? "placed" : err), p);
                return null;
            }
        }
    }

    // ===================== 1：隐藏大屏 =====================

    public static class HideScreen implements IMessage {

        public HideScreen() {}

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static class Handler implements IMessageHandler<HideScreen, IMessage> {

            @Override
            public IMessage onMessage(HideScreen msg, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().playerEntity;
                String err = WdBridge.hideScreen(p);
                BrowserNetwork.INSTANCE.sendTo(new ScreenResult(err == null, err == null ? "hidden" : err), p);
                return null;
            }
        }
    }

    // ===================== 2：操作结果（S→C） =====================

    public static class ScreenResult implements IMessage {

        public boolean ok;
        public String key = "";

        public ScreenResult() {}

        public ScreenResult(boolean ok, String key) {
            this.ok = ok;
            this.key = key == null ? "" : key;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            ok = buf.readBoolean();
            byte[] data = new byte[buf.readUnsignedByte()];
            buf.readBytes(data);
            key = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBoolean(ok);
            byte[] data = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeByte(Math.min(255, data.length));
            buf.writeBytes(data, 0, Math.min(255, data.length));
        }

        /** 公共 Handler：只把结果转交客户端钩子（本类可在专用服务器安全加载）。 */
        public static class Handler implements IMessageHandler<ScreenResult, IMessage> {

            @Override
            public IMessage onMessage(ScreenResult msg, MessageContext ctx) {
                ClientHooks.showResult(msg.ok, msg.key);
                return null;
            }
        }
    }

    // ===================== 客户端反馈（聊天兜底） =====================

    /** 服务端直接可用的聊天反馈（未来扩展用）。 */
    static void chat(EntityPlayerMP p, String key) {
        p.addChatMessage(new ChatComponentText(StatCollector.translateToLocal("msg.mcphone_browser." + key)));
    }
}
