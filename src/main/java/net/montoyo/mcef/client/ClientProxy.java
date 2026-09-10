package net.montoyo.mcef.client;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefLifeSpanHandlerAdapter;

import net.minecraft.client.Minecraft;
import net.montoyo.mcef.BaseProxy;
import net.montoyo.mcef.MCEF;
import net.montoyo.mcef.api.API;
import net.montoyo.mcef.api.IBrowser;
import net.montoyo.mcef.api.IDisplayHandler;
import net.montoyo.mcef.api.IJSQueryHandler;
import net.montoyo.mcef.api.IScheme;
import net.montoyo.mcef.compat.TarExtractor;
import net.montoyo.mcef.utilities.Log;
import net.montoyo.mcef.virtual.VirtualBrowser;

/**
 * Modern-MCEF client proxy (GTNH 1.7.10 port of the CCBlueX MCEF glue).
 *
 * Differences from the legacy 1.7.10 ClientProxy:
 *  - Natives are resolved with System.load(absolute path) only (done inside
 *    CefApp.startup via the "jcef.path" system property); the legacy
 *    java.library.path / ClassLoader.usr_paths reflection hack is gone.
 *  - Natives are downloaded from the GitHub mirror first (release assets
 *    windows_amd64.tar.gz / linux_amd64.tar.gz of skyc10/mcef-resources, tag
 *    mcef-cef-<jcef-commit>), then from the CCBlueX resource hosts
 *    (<host>/mcef-cef/<jcef-commit>/<platform> — NO .tar.gz suffix, the API
 *    307-redirects to S3; checksum at <platform>/checksum) and extracted with
 *    a dependency-free ustar parser into <gamedir>/mcefmodern/<commit>/.
 *  - The download runs on a background daemon thread: onInit never blocks the
 *    client thread (a 140+ MB fetch on the main thread freezes the game).
 *    It flips VIRTUAL=true and the addon retries on the next browser open;
 *    NATIVES_STATUS exposes the progress for the addon UI.
 *  - When natives are already on disk (or the download finished), the rest of
 *    the initialization is synchronous, as before; any failure flips
 *    {@link #VIRTUAL} to true and the addon retries next time.
 */
public class ClientProxy extends BaseProxy implements API {

    public static String ROOT;
    public static boolean VIRTUAL = false;

    /** Natives download progress for the addon UI: "", downloading, ready, "failed: <reason>". */
    public static volatile String NATIVES_STATUS = "";

    public static final String JCEF_COMMIT = readJcefCommit();
    /** Release-asset base on our mirror repo; asset names are <platform>.tar.gz(.sha256). */
    public static final String MIRROR_RELEASE_BASE =
            "https://github.com/skyc10/mcef-resources/releases/download/mcef-cef-" + JCEF_COMMIT;
    public static final String DEFAULT_HOST = "https://api.liquidbounce.net/api/v3/resource";
    public static final String FALLBACK_HOST = "https://api.ccbluex.net/api/v3/resource";
    public static final String[] FALLBACK_HOSTS = new String[] {
            "https://api.ccbluex.net/api/v3/resource",
            "http://nossl.api.liquidbounce.net/api/v3/resource"
    };

    private static final Pattern MimeTypePattern = Pattern.compile("^(\\S+)\\s+(\\S+)\\s*(\\S*)\\s*(\\S*)$");
    private static final Map<String, String> FALLBACK_MIME_TYPES = new HashMap<String, String>();

    private CefApp cefApp;
    private org.cef.CefClient cefClient;
    private CefMessageRouter cefRouter;
    private DisplayHandler displayHandler;
    private final LinkedList<CefBrowserOsr> browsers = new LinkedList<CefBrowserOsr>();

    private static boolean sRegistered = false;
    private static String sSchemePkg = "net.montoyo.mcef.scheme";
    private static List<String> sRegisteredSchemes = new ArrayList<String>();

    static {
        FALLBACK_MIME_TYPES.put("htm", "text/html");
        FALLBACK_MIME_TYPES.put("html", "text/html");
        FALLBACK_MIME_TYPES.put("css", "text/css");
        FALLBACK_MIME_TYPES.put("js", "text/javascript");
        FALLBACK_MIME_TYPES.put("png", "image/png");
        FALLBACK_MIME_TYPES.put("jpg", "image/jpg");
        FALLBACK_MIME_TYPES.put("jpeg", "image/jpg");
        FALLBACK_MIME_TYPES.put("gif", "image/gif");
        FALLBACK_MIME_TYPES.put("svg", "image/svg+xml");
        FALLBACK_MIME_TYPES.put("xml", "text/xml");
        FALLBACK_MIME_TYPES.put("txt", "text/plain");
    }

    private static String readJcefCommit() {
        try (InputStream is = ClientProxy.class.getResourceAsStream("/jcef.commit")) {
            if(is == null) {
                Log.warning("Missing /jcef.commit resource; falling back to pinned commit");
                return "b853a9d87fd0a7553001ce0785fee73d55be8d64";
            }

            StringBuilder sb = new StringBuilder();
            int c;
            while((c = is.read()) >= 0) {
                if(Character.isWhitespace((char) c))
                    break;
                sb.append((char) c);
            }

            String str = sb.toString().trim();
            return (str.length() > 0) ? str : "b853a9d87fd0a7553001ce0785fee73d55be8d64";
        } catch(Throwable t) {
            Log.errorEx("Couldn't read /jcef.commit", t);
            return "b853a9d87fd0a7553001ce0785fee73d55be8d64";
        }
    }

    @Override
    public void onInit() {
        Log.info("Loading MCEF (modern CEF kernel, jcef %s)", JCEF_COMMIT);

        try {
            Minecraft mc = Minecraft.getMinecraft();
            ROOT = mc.mcDataDir.getPath();
            ROOT = ROOT.replace('\\', '/');

            if(ROOT.length() > 0 && ROOT.charAt(ROOT.length() - 1) == '.')
                ROOT = ROOT.substring(0, ROOT.length() - 1);

            if(ROOT.length() > 0 && ROOT.charAt(ROOT.length() - 1) == '/')
                ROOT = ROOT.substring(0, ROOT.length() - 1);
        } catch(Throwable t) {
            Log.errorEx("Couldn't locate the Minecraft data dir", t);
            VIRTUAL = true;
            return;
        }

        String platform = getPlatform();
        if(platform == null) {
            Log.error("Unsupported platform: os.name=%s os.arch=%s. Switching to virtual mode.", System.getProperty("os.name"), System.getProperty("os.arch"));
            VIRTUAL = true;
            return;
        }

        File modernRoot = new File(ROOT, "mcefmodern");
        File commitDir = new File(modernRoot, JCEF_COMMIT);
        File platformDir = new File(commitDir, platform);

        // 1. Natives
        try {
            if(!haveNatives(platformDir, platform)) {
                // Never block the client thread: a 140+ MB fetch on the main
                // thread freezes the game. Kick off a background daemon thread
                // (once) and let the addon retry on the next browser open.
                if(DOWNLOAD_RUNNING.compareAndSet(false, true)) {
                    Log.info("MCEF natives not found; downloading CEF %s for %s in background...", JCEF_COMMIT, platform);
                    startAsyncNativesPrep(commitDir, platform);
                } else {
                    Log.info("MCEF natives download still running in background (%s)...", NATIVES_STATUS);
                }

                VIRTUAL = true;
                return;
            } else
                Log.info("MCEF natives found in %s", platformDir.getPath());

            if(!haveNatives(platformDir, platform))
                throw new Exception("Natives still missing after download attempt");

            // Natives are loaded with System.load(absolute path) inside
            // CefApp.startup(), based on this property. Never touch
            // java.library.path or ClassLoader.usr_paths here.
            System.setProperty("jcef.path", platformDir.getAbsolutePath());

            if(!org.cef.OS.isWindows())
                makeExecutable(new File(platformDir, "jcef_helper"));

            CefApp.startup(new String[0]);
        } catch(Throwable t) {
            Log.errorEx("Failed to prepare/load CEF natives", t);
            VIRTUAL = true;
            return;
        }

        // 2. CEF initialization
        try {
            ArrayList<String> switches = new ArrayList<String>();
            switches.add("--autoplay-policy=no-user-gesture-required");
            switches.add("--disable-web-security");

            for(String a: MCEF.CEF_ARGS) {
                if(a != null && !a.trim().isEmpty())
                    switches.add(a.trim());
            }

            if(org.cef.OS.isLinux()) {
                switches.add("--use-angle=gl");
                switches.add("--ozone-platform=x11");
            }

            CefSettings settings = new CefSettings();
            settings.windowless_rendering_enabled = true;
            settings.background_color = settings.new ColorType(0, 255, 255, 255);
            settings.cache_path = new File(commitDir, "Cache").getAbsolutePath();
            settings.user_agent_product = "MCEF/2";
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;

            cefApp = CefApp.getInstance(switches.toArray(new String[switches.size()]), settings);
            cefClient = cefApp.createClient();
            cefRouter = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("mcefQuery", "mcefCancel"));
            cefClient.addMessageRouter(cefRouter);

            displayHandler = new DisplayHandler();
            cefClient.addDisplayHandler(displayHandler);
            cefClient.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                @Override
                public boolean doClose(org.cef.browser.CefBrowser browser) {
                    browser.close(true);
                    return false;
                }
            });

            loadMimeTypeMapping();
        } catch(Throwable t) {
            Log.errorEx("Failed to initialize CEF", t);
            VIRTUAL = true;
            return;
        }

        Log.info("MCEF initialized successfully.");
    }

    private static final AtomicBoolean DOWNLOAD_RUNNING = new AtomicBoolean(false);

    /**
     * Downloads + verifies + extracts natives on a daemon thread, then primes
     * "jcef.path". Never touches CEF itself (the caller retries onInit on the
     * next browser open once haveNatives() passes). Updates NATIVES_STATUS so
     * the addon UI can show progress instead of the game appearing frozen.
     */
    private static void startAsyncNativesPrep(File commitDir, String platform) {
        NATIVES_STATUS = "downloading";
        Thread t = new Thread(() -> {
            try {
                downloadNatives(commitDir, platform);
                if(haveNatives(new File(commitDir, platform), platform)) {
                    NATIVES_STATUS = "ready";
                    Log.info("MCEF natives download finished; reopen the browser to start CEF.");
                } else {
                    NATIVES_STATUS = "failed: archive extracted but natives still missing";
                    Log.error("MCEF natives download finished but natives are still missing");
                }
            } catch(Throwable th) {
                NATIVES_STATUS = "failed: " + th;
                Log.errorEx("MCEF natives background download failed", th);
            } finally {
                DOWNLOAD_RUNNING.set(false);
            }
        }, "MCEF-Natives-Download");
        t.setDaemon(true);
        t.start();
    }

    private static String getPlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        boolean amd64 = osArch.contains("amd64") || osArch.contains("x86_64");
        if(!amd64)
            return null; // arm/aarch64 natives are not shipped by the hosts

        if(osName.contains("win"))
            return "windows_amd64";
        if(osName.contains("linux") || osName.contains("nix") || osName.contains("nux"))
            return "linux_amd64";

        return null; // macOS natives are not required on GTNH 1.7.10
    }

    private static boolean haveNatives(File platformDir, String platform) {
        if(!platformDir.isDirectory())
            return false;

        String[] required;
        if(platform.equals("windows_amd64"))
            required = new String[] {"d3dcompiler_47.dll", "libGLESv2.dll", "libEGL.dll", "chrome_elf.dll", "libcef.dll", "jcef.dll"};
        else
            required = new String[] {"libcef.so", "libjcef.so"};

        for(String lib: required) {
            if(!new File(platformDir, lib).isFile()) {
                Log.warning("Missing native library %s in %s", lib, platformDir.getPath());
                return false;
            }
        }

        return true;
    }

    private static String getHost() {
        String prop = System.getProperty("mcefmodern.host");
        if(prop != null && !prop.trim().isEmpty())
            return prop.trim();

        return DEFAULT_HOST;
    }

    private static void downloadNatives(File commitDir, String platform) throws Exception {
        Exception last = null;

        // 1) GitHub release-asset mirror (fast from mainland China, no TLS-SNI interference)
        try {
            downloadFromMirror(commitDir, platform);
            return;
        } catch(Exception e) {
            Log.errorEx("Download from mirror %s failed", e, MIRROR_RELEASE_BASE);
            last = e;
        }

        // 2) CCBlueX hosts as fallback (URL has NO .tar.gz suffix; the API
        //    307-redirects to a signed S3 URL, HttpURLConnection follows it)
        String host = getHost();
        String[] hosts = new String[FALLBACK_HOSTS.length + 1];
        hosts[0] = host;
        System.arraycopy(FALLBACK_HOSTS, 0, hosts, 1, FALLBACK_HOSTS.length);

        for(String h: hosts) {
            try {
                downloadFrom(h, commitDir, platform);
                return;
            } catch(Exception e) {
                Log.errorEx("Download from %s failed", e, h);
                last = e;
            }
        }

        throw (last != null) ? last : new Exception("No download host available");
    }

    /** Mirror layout: <MIRROR_RELEASE_BASE>/<platform>.tar.gz and <platform>.tar.gz.sha256. */
    private static void downloadFromMirror(File commitDir, String platform) throws Exception {
        Files.createDirectories(commitDir.toPath());

        Path archive = new File(commitDir, platform + ".tar.gz").toPath();
        Path checksumFile = new File(commitDir, platform + ".tar.gz.sha256").toPath();

        httpDownload(MIRROR_RELEASE_BASE + "/" + platform + ".tar.gz", archive);
        httpDownload(MIRROR_RELEASE_BASE + "/" + platform + ".tar.gz.sha256", checksumFile);
        verifyChecksum(archive, checksumFile);

        // CCBlueX archives contain a <platform>/ prefix; extract into commitDir.
        new TarExtractor(commitDir.toPath()).extractTarGz(archive);

        try {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(checksumFile);
        } catch(Throwable t) {
            Log.warning("Couldn't clean up downloaded archive: %s", t.toString());
        }
    }

    private static void verifyChecksum(Path archive, Path checksumFile) throws Exception {
        // Verify sha256 (checksum file = bare 64-hex digest)
        String expected = new String(Files.readAllBytes(checksumFile), java.nio.charset.StandardCharsets.US_ASCII).trim();
        if(expected.length() != 64)
            throw new Exception("Invalid checksum file content: " + expected);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(archive)) {
            byte[] buf = new byte[65536];
            int rd;
            while((rd = is.read(buf)) > 0)
                md.update(buf, 0, rd);
        }

        String actual = String.format("%064x", new BigInteger(1, md.digest()));
        if(!actual.equalsIgnoreCase(expected))
            throw new Exception("SHA-256 mismatch: expected " + expected + ", got " + actual);

        Log.info("SHA-256 ok: %s", actual);
    }

    private static void downloadFrom(String host, File commitDir, String platform) throws Exception {
        Files.createDirectories(commitDir.toPath());

        String base = host + "/mcef-cef/" + JCEF_COMMIT;
        // CCBlueX resource API serves the archive under the bare platform name
        // (307 -> signed S3); "<platform>.tar.gz" is a 404 there.
        String archiveName = platform;

        Path archive = new File(commitDir, archiveName + ".tar.gz").toPath();
        Path checksumFile = new File(commitDir, archiveName + ".checksum").toPath();

        httpDownload(base + "/" + archiveName, archive);
        httpDownload(base + "/checksum", checksumFile);
        verifyChecksum(archive, checksumFile);

        // CCBlueX archives contain a <platform>/ prefix; extract into commitDir.
        new TarExtractor(commitDir.toPath()).extractTarGz(archive);

        try {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(checksumFile);
        } catch(Throwable t) {
            Log.warning("Couldn't clean up downloaded archive: %s", t.toString());
        }
    }

    private static void httpDownload(String url, Path dst) throws Exception {
        Log.info("Downloading %s ...", url);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "MCEF/" + MCEF.VERSION);

        int code = conn.getResponseCode();
        if(code != 200)
            throw new Exception("HTTP " + code + " for " + url);

        try (InputStream is = conn.getInputStream();
             OutputStream os = Files.newOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int rd;
            while((rd = is.read(buf)) > 0)
                os.write(buf, 0, rd);
        }
    }

    private static void makeExecutable(File f) throws Exception {
        if(!f.isFile())
            throw new Exception("Missing helper binary: " + f.getPath());

        if(!f.setExecutable(true, false))
            throw new Exception("Couldn't chmod +x " + f.getPath());
    }

    private void loadMimeTypeMapping() {
        try (InputStream is = ClientProxy.class.getResourceAsStream("/assets/mcef/mime.types")) {
            if(is == null) {
                Log.warning("Missing /assets/mcef/mime.types; using fallback map only");
                return;
            }

            java.util.Scanner scan = new java.util.Scanner(is, "UTF-8");
            try {
                while(scan.hasNextLine()) {
                    String line = scan.nextLine();
                    Matcher m = MimeTypePattern.matcher(line);

                    if(m.matches()) {
                        String mimeType = m.group(2);
                        for(int i = 1; i <= 4; i++) {
                            String ext = m.group(i);
                            if(ext != null && i != 2)
                                FALLBACK_MIME_TYPES.put(ext, mimeType);
                        }
                    }
                }
            } finally {
                scan.close();
            }
        } catch(Throwable t) {
            Log.errorEx("Couldn't load the mime.types mapping", t);
        }
    }

    @Override
    public IBrowser createBrowser(String url, boolean transparent) {
        if(VIRTUAL || cefApp == null)
            return new VirtualBrowser();

        CefBrowserOsr ret = (CefBrowserOsr) cefClient.createBrowser(url, transparent);
        ret.setCloseAllowed();
        ret.createImmediately();
        browsers.add(ret);

        return ret;
    }

    public void removeBrowser(CefBrowserOsr b) {
        browsers.remove(b);
    }

    public CefApp getCefApp() {
        return cefApp;
    }

    @Override
    public void registerDisplayHandler(IDisplayHandler idh) {
        if(displayHandler != null && !VIRTUAL)
            displayHandler.addHandler(idh);
        else
            Log.warning("A mod tried to register a display handler while MCEF isn't loaded!");
    }

    @Override
    public void registerJSQueryHandler(IJSQueryHandler iqh) {
        if(cefRouter != null && !VIRTUAL)
            cefRouter.addHandler(new MessageRouter(iqh), false);
        else
            Log.warning("A mod tried to register a JS query handler while MCEF isn't loaded!");
    }

    @Override
    public boolean isVirtual() {
        return VIRTUAL;
    }

    @Override
    public void openExampleBrowser(String url) {
        Log.warning("The example browser UI is not shipped with the modern kernel. URL: %s", url);
    }

    @Override
    public String mimeTypeFromExtension(String ext) {
        if(ext == null)
            return null;

        String e = ext.toLowerCase(Locale.ROOT);
        if(e.charAt(0) == '.')
            e = e.substring(1);

        return FALLBACK_MIME_TYPES.get(e);
    }

    @Override
    public void registerScheme(String name, Class<? extends IScheme> schemeClass, boolean std, boolean local, boolean displayIsolated, boolean secure, boolean corsEnabled, boolean cspBypassing, boolean fetchEnabled) {
        if(VIRTUAL || cefApp == null) {
            Log.warning("A mod called API.registerScheme() while MCEF isn't loaded!");
            return;
        }

        // The modern kernel registers schemes natively before CEF startup; the
        // legacy reflection-driven scheme pipeline is not ported. Register the
        // name so isSchemeRegistered() behaves sanely.
        if(!sRegisteredSchemes.contains(name))
            sRegisteredSchemes.add(name);
    }

    @Override
    public boolean isSchemeRegistered(String name) {
        return sRegisteredSchemes.contains(name);
    }

    public void onTick() {
        if(VIRTUAL || cefApp == null)
            return;

        try {
            cefApp.N_DoMessageLoopWork();

            for(CefBrowserOsr b: browsers) {
                if(b != null)
                    b.mcefUpdate();
            }

            if(displayHandler != null)
                displayHandler.update();
        } catch(Throwable t) {
            Log.errorEx("Error in MCEF tick", t);
        }
    }

    @Override
    public void onShutdown() {
        if(VIRTUAL || cefApp == null)
            return;

        try {
            CefBrowserOsr.CLEANUP = false; // Don't wipe the GL textures while context is dying

            for(CefBrowserOsr b: browsers) {
                try {
                    b.close();
                } catch(Throwable t) {
                    Log.errorEx("Error closing browser", t);
                }
            }

            browsers.clear();
            runMessageLoopFor(100);

            if(cefRouter != null) {
                try {
                    cefRouter.dispose();
                } catch(Throwable t) {
                    Log.errorEx("Error disposing message router", t);
                }
                cefRouter = null;
            }

            if(cefClient != null) {
                try {
                    cefClient.dispose();
                } catch(Throwable t) {
                    Log.errorEx("Error disposing CEF client", t);
                }
                cefClient = null;
            }

            if(cefApp != null) {
                cefApp.dispose();

                if(MCEF.SHUTDOWN_JCEF) {
                    try {
                        cefApp.N_Shutdown();
                    } catch(Throwable t) {
                        Log.errorEx("Error during native CEF shutdown", t);
                    }
                }
            }

            cefApp = null;
        } catch(Throwable t) {
            Log.errorEx("Error during MCEF shutdown", t);
        }
    }

    private void runMessageLoopFor(long ms) {
        long time = System.currentTimeMillis();

        while(System.currentTimeMillis() - time < ms) {
            try {
                if(cefApp != null)
                    cefApp.N_DoMessageLoopWork();
            } catch(Throwable t) {
                break;
            }

            try {
                Thread.sleep(10);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

}
