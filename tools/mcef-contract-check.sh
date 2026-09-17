#!/bin/bash
# mcef-contract-check.sh —— MCEF 契约（docs/MCEF-CONTRACT.md C-a）javap 签名比对脚本
# 用法： tools/mcef-contract-check.sh <path-to-jar>
# 退出码：0 = 全部通过；1 = 存在 FAIL（可作 CI 门禁）
# 依据：docs/MCEF-CONTRACT.md v1.0（feat/modern-mcef-port @ 67c371a）
export LANG=C.UTF-8

set -u

FAILS=0
declare -a RESULTS

check() {  # check <class> <grep-E-pattern> <description>
  local cls="$1" pat="$2" desc="$3" out
  out=$(javap -p -classpath "$JAR" "$cls" 2>/dev/null)
  if [ -z "$out" ]; then
    RESULTS+=("FAIL  [MISSING-CLASS] $cls（$desc）")
    FAILS=$((FAILS+1))
    return
  fi
  # 忽略 START 里的 "Compiled from" 行后做正则比对
  if printf '%s\n' "$out" | grep -vE '^Compiled from' | grep -Eq "$pat"; then
    RESULTS+=("PASS  $cls: $desc")
  else
    RESULTS+=("FAIL  [SIGNATURE] $cls: $desc（期望 /$pat/）")
    FAILS=$((FAILS+1))
  fi
}

# ---------- 1. 定位 jar ----------
JAR="${1:-${MCEF_CONTRACT_JAR:-}}"
if [ -z "$JAR" ]; then
  # 实例 mods 内嵌 MCEF 的附属 jar
  for d in "/mnt/c/Users/陈/AppData/Roaming/PrismLauncher/instances/290b3test/.minecraft/mods" \
           "$(pwd)"; do
    if [ -d "$d" ]; then
      J=$(ls -t "$d"/*.jar 2>/dev/null | while read -r f; do
            jar tf "$f" 2>/dev/null | grep -q '^org/cef/browser/CefBrowserOsr.class' && echo "$f" && break
          done | head -1)
      if [ -n "$J" ]; then JAR="$J"; break; fi
    fi
  done
fi
if [ -z "$JAR" ]; then
  L=$(ls -t "$(dirname "$0")"/../build/libs/*.jar 2>/dev/null | head -1)
  [ -n "$L" ] && JAR="$L"
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "ERROR: 未找到可检查的 jar。用法：$0 <path-to-jar>（或设 MCEF_CONTRACT_JAR）" >&2
  exit 2
fi
echo "== MCEF contract check =="
echo "jar: $JAR"
echo

# ---------- 2. legacy / modern 判别 ----------
if jar tf "$JAR" 2>/dev/null | grep -q 'net/montoyo/mcef/client/ModScheme.class'; then
  echo "FAIL  [KERNEL-GUARD] jar 含 net.montoyo.mcef.client.ModScheme → legacy MCEF 内核；本契约（modern）不适用"
  FAILS=$((FAILS+1))
else
  echo "PASS  [KERNEL-GUARD] 无 ModScheme（legacy scheme 管线未随 jar 携带）"
fi

# ---------- 3. C-a.1 MCEFApi ----------
check net.montoyo.mcef.api.MCEFApi \
  'public static net\.montoyo\.mcef\.api\.API getAPI\(\);' 'API getAPI()'
check net.montoyo.mcef.api.MCEFApi \
  'public static boolean isMCEFLoaded\(\);' 'boolean isMCEFLoaded()'

# ---------- 4. C-a.2 API（10 方法）----------
check net.montoyo.mcef.api.API \
  'net\.montoyo\.mcef\.api\.IBrowser createBrowser\(java\.lang\.String, *boolean\);' 'createBrowser(String,boolean)'
check net.montoyo.mcef.api.API \
  'registerDisplayHandler\(net\.montoyo\.mcef\.api\.IDisplayHandler\);' 'registerDisplayHandler(IDisplayHandler)'
check net.montoyo.mcef.api.API \
  'registerJSQueryHandler\(net\.montoyo\.mcef\.api\.IJSQueryHandler\);' 'registerJSQueryHandler(IJSQueryHandler)'
check net.montoyo.mcef.api.API \
  'boolean isVirtual\(\);' 'boolean isVirtual()'
check net.montoyo.mcef.api.API \
  'java\.lang\.String punycode\(java\.lang\.String\);' 'String punycode(String)'
check net.montoyo.mcef.api.API \
  'registerScheme\(java\.lang\.String, *java\.lang\.Class.*boolean, *boolean, *boolean, *boolean, *boolean, *boolean, *boolean\);' 'registerScheme(9 参)'
check net.montoyo.mcef.api.API \
  'boolean isSchemeRegistered\(java\.lang\.String\);' 'boolean isSchemeRegistered(String)'
check net.montoyo.mcef.api.API \
  'java\.lang\.String mimeTypeFromExtension\(java\.lang\.String\);' 'String mimeTypeFromExtension(String)'
check net.montoyo.mcef.api.API \
  'createBrowser\(java\.lang\.String\);' '1 参 createBrowser（源码 default；jabel 产物打印为 abstract）'

# ---------- 5. C-a.3 IBrowser（19 方法）----------
for m in \
  'void close\(\);' \
  'void resize\(int, *int\);' \
  'void draw\(double, *double, *double, *double\);' \
  'int *getTextureID\(\);' \
  'void injectMouseMove\(int, *int, *int, *boolean\);' \
  'void injectMouseButton\(int, *int, *int, *int, *boolean, *int\);' \
  'void injectKeyTyped\(char, *int\);' \
  'void injectKeyPressedByKeyCode\(int, *char, *int\);' \
  'void injectKeyReleasedByKeyCode\(int, *char, *int\);' \
  'void injectMouseWheel\(int, *int, *int, *int, *int\);' \
  'void runJS\(java\.lang\.String, *java\.lang\.String\);' \
  'void loadURL\(java\.lang\.String\);' \
  'void goBack\(\);' \
  'void goForward\(\);' \
  'java\.lang\.String getURL\(\);' \
  'void visitSource\(net\.montoyo\.mcef\.api\.IStringVisitor\);' \
  'boolean isPageLoading\(\);' ; do
  check net.montoyo.mcef.api.IBrowser "$m" "IBrowser: $m"
done

# ---------- 6. C-a.4 回调接口 ----------
check net.montoyo.mcef.api.IDisplayHandler \
  'void onAddressChange\(net\.montoyo\.mcef\.api\.IBrowser, *java\.lang\.String\);' 'onAddressChange'
check net.montoyo.mcef.api.IDisplayHandler \
  'void onTitleChange\(net\.montoyo\.mcef\.api\.IBrowser, *java\.lang\.String\);' 'onTitleChange'
check net.montoyo.mcef.api.IDisplayHandler \
  'void onStatusMessage\(net\.montoyo\.mcef\.api\.IBrowser, *java\.lang\.String\);' 'onStatusMessage'
check net.montoyo.mcef.api.IJSQueryHandler \
  'boolean handleQuery\(net\.montoyo\.mcef\.api\.IBrowser, *long, *java\.lang\.String, *boolean, *net\.montoyo\.mcef\.api\.IJSQueryCallback\);' 'handleQuery(...)'
check net.montoyo.mcef.api.IJSQueryCallback \
  'void success\(java\.lang\.String\);' 'IJSQueryCallback.success(String)'
check net.montoyo.mcef.api.IStringVisitor \
  'void visit\(java\.lang\.String\);' 'IStringVisitor.visit(String)'

# ---------- 7. C-a.5 CefBrowserOsr ----------
check org.cef.browser.CefBrowserOsr \
  'protected org\.cef\.browser\.CefRenderer *renderer_;' '字段 renderer_'
check org.cef.browser.CefBrowserOsr \
  'protected final java\.util\.LinkedList.*queue;' '字段 queue'
check org.cef.browser.CefBrowserOsr \
  'public static boolean CLEANUP;' '字段 CLEANUP'
check org.cef.browser.CefBrowserOsr \
  'public void mcefUpdate\(\);' 'mcefUpdate()（帧泵）'
check org.cef.browser.CefBrowserOsr \
  'public void injectKeyPressedByKeyCode\(int, *char, *int\);' 'injectKeyPressedByKeyCode(int,char,int)'
check org.cef.browser.CefBrowserOsr \
  'public void injectKeyReleasedByKeyCode\(int, *char, *int\);' 'injectKeyReleasedByKeyCode(int,char,int)'
check org.cef.browser.CefBrowserOsr \
  'public boolean isPageLoading\(\);' 'isPageLoading()'
check org.cef.browser.CefBrowserOsr \
  'public void runJS\(java\.lang\.String, *java\.lang\.String\);' 'runJS(String,String)'
check org.cef.browser.CefBrowserOsr \
  'public void visitSource\(net\.montoyo\.mcef\.api\.IStringVisitor\);' 'visitSource(IStringVisitor)'
check org.cef.browser.CefBrowserOsr \
  'public.*void close\(\);' 'close()（源码 synchronized；jabel 产物不携带该修饰）'
check org.cef.browser.CefBrowserOsr \
  'public void resize\(int, *int\);' 'resize(int,int)'

# ---------- 8. C-a.6 CefRenderer ----------
check org.cef.browser.CefRenderer \
  'public int\[\] *texture_id_;' '字段 texture_id_'
check org.cef.browser.CefRenderer \
  'private int view_width_;' '字段 view_width_'
check org.cef.browser.CefRenderer \
  'private int view_height_;' '字段 view_height_'
check org.cef.browser.CefRenderer \
  'protected void initialize\(\);' 'initialize()'
check org.cef.browser.CefRenderer \
  'protected void cleanup\(\);' 'cleanup()'
check org.cef.browser.CefRenderer \
  'protected void onPaint\(boolean, *java\.awt\.Rectangle\[\], *java\.nio\.ByteBuffer, *int, *int, *boolean\);' 'onPaint 6 参（modern 标记）'

# ---------- 9. C-a.7 CefApp / CefBrowser_N ----------
check org.cef.CefApp \
  'public final native void N_DoMessageLoopWork\(\);' 'N_DoMessageLoopWork()（实例 native）'
check org.cef.browser.CefBrowser_N \
  'public boolean canGoBack\(\);' 'canGoBack()'
check org.cef.browser.CefBrowser_N \
  'public boolean canGoForward\(\);' 'canGoForward()'
check org.cef.browser.CefBrowser_N \
  'public void reload\(\);' 'reload()'
check org.cef.browser.CefBrowser_N \
  'public void setFocus\(boolean\);' 'setFocus(boolean)'

# ---------- 10. 汇总 ----------
echo
for r in "${RESULTS[@]}"; do echo "$r"; done
echo
echo "== summary: $(printf '%s\n' "${RESULTS[@]}" | grep -c '^PASS') PASS, $FAILS FAIL =="
if [ "$FAILS" -gt 0 ]; then
  echo "CONTRACT BROKEN: 同步修订 docs/MCEF-CONTRACT.md 与本脚本，并通知 wiki 维护者（是否影响 C-a…C-f）。"
  exit 1
fi
echo "CONTRACT OK（modern 内核签名面完整）。"
exit 0
