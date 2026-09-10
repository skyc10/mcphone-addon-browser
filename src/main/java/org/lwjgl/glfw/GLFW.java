// Part of the GTNH 1.7.10 modern-MCEF port (mcphone-addon-browser).
//
// Compile-time constant stub for org.lwjgl.glfw.GLFW. The CCBlueX java-cef
// fork's natives (jcef.dll / libjcef.so, CEF 143) resolve GLFW constants via
// GetStaticFieldID on every mouse/key JNI bridge entry and bail out silently
// when a lookup fails (ScopedJNIClass(env, "org/lwjgl/glfw/GLFW") +
// lazy field-ID caches in CefBrowser_N.cpp). GTNH ships LWJGL 3.4.2 +
// lwjgl3ify, neither of which carries the GLFW binding class, so the class
// lookup originally failed with "Class bytes are null for
// org.lwjgl.glfw.GLFW" and all input was silently dropped.
//
// FIELD NAMING IS LOAD-BEARING: the natives request the full LWJGL 3 names
// WITH the "GLFW_" prefix ("GLFW_PRESS", "GLFW_MOUSE_BUTTON_1",
// "GLFW_KEY_ENTER", "GLFW_MOD_SHIFT", ... — 34 names, verbatim from
// jcef.dll's .rdata). A stub built with unprefixed names ("PRESS",
// "MOUSE_BUTTON_1", ...) compiles fine and makes wheel events (the only
// entry that touches no GLFW field) work, while silently killing every
// mouse/keyboard event. Modern.5 root cause, found by bisecting injected
// vs CDP-trusted input and disassembling jcef.dll's N_SendMouseEvent.
//
// This is NOT the LWJGL binding: no static initializers, no native calls, no
// version checks. Only the symbols the fork's natives reference are defined;
// scancode values are IBM PC Set 1, matching the hardcoded table in
// MapScanCodeGLFW (CefBrowser_N.cpp).

package org.lwjgl.glfw;

public final class GLFW {

    private GLFW() {
    }

    /* Key codes (GLFW 3.x official values). */
    public static final int GLFW_KEY_ESCAPE = 256;
    public static final int GLFW_KEY_ENTER = 257;
    public static final int GLFW_KEY_TAB = 258;
    public static final int GLFW_KEY_BACKSPACE = 259;
    public static final int GLFW_KEY_INSERT = 260;
    public static final int GLFW_KEY_DELETE = 261;
    public static final int GLFW_KEY_RIGHT = 262;
    public static final int GLFW_KEY_LEFT = 263;
    public static final int GLFW_KEY_DOWN = 264;
    public static final int GLFW_KEY_UP = 265;
    public static final int GLFW_KEY_PAGE_UP = 266;
    public static final int GLFW_KEY_PAGE_DOWN = 267;
    public static final int GLFW_KEY_HOME = 268;
    public static final int GLFW_KEY_END = 269;
    public static final int GLFW_KEY_CAPS_LOCK = 280;
    public static final int GLFW_KEY_SCROLL_LOCK = 281;
    public static final int GLFW_KEY_NUM_LOCK = 282;
    public static final int GLFW_KEY_PRINT_SCREEN = 283;
    public static final int GLFW_KEY_PAUSE = 284;
    public static final int GLFW_KEY_KP_2 = 322;
    public static final int GLFW_KEY_KP_4 = 324;
    public static final int GLFW_KEY_KP_6 = 326;
    public static final int GLFW_KEY_KP_8 = 328;
    public static final int GLFW_KEY_KP_ENTER = 335;
    public static final int GLFW_KEY_LEFT_CONTROL = 341;
    public static final int GLFW_KEY_RIGHT_CONTROL = 345;

    /* Modifier masks. */
    public static final int GLFW_MOD_SHIFT = 1;
    public static final int GLFW_MOD_CONTROL = 2;
    public static final int GLFW_MOD_ALT = 4;
    public static final int GLFW_MOD_SUPER = 8;

    /* Mouse buttons. */
    public static final int GLFW_MOUSE_BUTTON_1 = 0;
    public static final int GLFW_MOUSE_BUTTON_2 = 1;
    public static final int GLFW_MOUSE_BUTTON_3 = 2;

    /* Actions. */
    public static final int GLFW_RELEASE = 0;
    public static final int GLFW_PRESS = 1;
    public static final int GLFW_REPEAT = 2;

    /**
     * Pure-Java stand-in for the native glfwGetKeyScancode (IBM PC Set 1).
     * Consumed two ways: MapScanCodeGLFW (CefBrowser_N.cpp) calls it over JNI
     * for BACKSPACE/KP_2/4/6/8/PRINT_SCREEN/SCROLL_LOCK/CAPS_LOCK/NUM_LOCK/
     * PAUSE/INSERT, and CefBrowserOsr.keyEvent() stores it in
     * CefKeyEvent.scancode for the keys MapScanCodeGLFW passes through
     * (TAB, ESCAPE are in neither its lookup nor its hardcoded tables, so
     * the Windows bridge derives VkCode from this field). Returns 0 for
     * anything else, which the natives handle like upstream.
     */
    public static int glfwGetKeyScancode(int key) {
        switch (key) {
            case GLFW_KEY_ESCAPE: return 1;
            case GLFW_KEY_TAB: return 15;
            case GLFW_KEY_BACKSPACE: return 14;
            case GLFW_KEY_KP_4: return 75;
            case GLFW_KEY_KP_8: return 72;
            case GLFW_KEY_KP_6: return 77;
            case GLFW_KEY_KP_2: return 80;
            case GLFW_KEY_PRINT_SCREEN: return 55;
            case GLFW_KEY_SCROLL_LOCK: return 70;
            case GLFW_KEY_CAPS_LOCK: return 58;
            case GLFW_KEY_NUM_LOCK: return 69;
            case GLFW_KEY_PAUSE: return 29;
            case GLFW_KEY_INSERT: return 82;
            default: return 0;
        }
    }
}
