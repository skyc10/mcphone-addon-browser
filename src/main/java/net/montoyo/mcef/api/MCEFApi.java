package net.montoyo.mcef.api;

import cpw.mods.fml.common.Loader;

public class MCEFApi {

    /**
     * Call this to get the API instance.
     * @return the MCEF API or null if something failed.
     */
    public static API getAPI() {
        try {
            Class cls = Class.forName("net.montoyo.mcef.MCEF");
            return (API) cls.getField("PROXY").get(null);
        } catch(Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    /**
     * Checks if MCEF was loaded by forge.
     * @return true if it is loaded. false otherwise.
     */
    public static boolean isMCEFLoaded() {
        try {
            return Loader.isModLoaded("mcef");
        } catch(Throwable t) {
            // Modern kernel is embedded in the mcphone_browser addon; no
            // separate "mcef" mod container exists. Kernel presence is
            // equivalent to this class being loadable.
            try {
                Class.forName("net.montoyo.mcef.MCEF");
                return true;
            } catch(Throwable t2) {
                return false;
            }
        }
    }

}
