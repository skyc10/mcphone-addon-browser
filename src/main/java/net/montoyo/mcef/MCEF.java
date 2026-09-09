package net.montoyo.mcef;

import net.montoyo.mcef.client.ClientProxy;
import net.montoyo.mcef.utilities.Log;

/**
 * Modern-MCEF kernel glue. Unlike the original MCEF mod class, this one is NOT
 * a Forge @Mod: the kernel is embedded inside the mcphone_browser addon and
 * initialized lazily by McefLazyInit (see com.november.mcphone.addon.browser.client).
 * The {@link #PROXY} static field keeps the reflection contract of
 * MCEFApi.getAPI() intact.
 */
public class MCEF {

    public static final String VERSION = "1.20";
    public static String[] CEF_ARGS = new String[0];
    public static boolean CHECK_VRAM_LEAK = false;
    public static boolean SHUTDOWN_JCEF = false;

    public static BaseProxy PROXY = new ClientProxy();

    /**
     * Called when Minecraft is shutting down. The addon's ExitWatchdog triggers
     * this via reflection on JVM shutdown (best effort).
     */
    public static void onMinecraftShutdown() {
        try {
            PROXY.onShutdown();
        } catch(Throwable t) {
            Log.errorEx("Failed to shutdown MCEF gracefully", t);
        }
    }

}
