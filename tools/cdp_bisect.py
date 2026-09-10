#!/usr/bin/env python3
"""CDP bisect tool for the MCEF modern.5 diag build (CEF on Windows,
reachable from WSL2 thanks to mirrored networking).

Usage:
  python3 cdp_bisect.py list                 # enumerate CEF page targets
  python3 cdp_bisect.py state                # read probe counters + focus
  python3 cdp_bisect.py eval '<js>'          # evaluate JS in the page
  python3 cdp_bisect.py click X Y            # trusted mousePressed+Released
  python3 cdp_bisect.py move X Y             # trusted mouseMoved
  python3 cdp_bisect.py key 'a'              # trusted keydown/char/keyup
  python3 cdp_bisect.py wheel X Y DY         # trusted mouseWheel
"""
import asyncio, json, sys, urllib.request

BASE = "http://127.0.0.1:9333"

def http_targets():
    with urllib.request.urlopen(BASE + "/json/list", timeout=5) as r:
        return json.loads(r.read().decode())

async def session(cmd, args):
    import websockets
    targets = [t for t in http_targets() if t.get("type") == "page"]
    if not targets:
        print("NO-PAGE-TARGETS"); return
    ws_url = targets[0]["webSocketDebuggerUrl"]
    print(f"# target: {targets[0].get('title','')[:60]} url={targets[0].get('url','')[:80]}")
    async with websockets.connect(ws_url, max_size=50*1024*1024) as ws:
        mid = 0
        async def call(method, params=None):
            nonlocal mid
            mid += 1
            await ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
            while True:
                msg = json.loads(await ws.recv())
                if msg.get("id") == mid:
                    return msg
        def ev(expr):
            return call("Runtime.evaluate",
                        {"expression": expr, "returnByValue": True, "awaitPromise": False})

        if cmd == "list":
            for t in targets:
                print(json.dumps({k: t.get(k) for k in ("title", "url", "type")}, ensure_ascii=False))
        elif cmd == "state":
            r = await ev("JSON.stringify({diag: window.__mcefDiag || null,"
                         " hasFocus: document.hasFocus(),"
                         " active: document.activeElement ? document.activeElement.tagName : null,"
                         " url: location.href})")
            print(r.get("result", {}).get("result", {}).get("value", r))
        elif cmd == "eval":
            r = await ev(args[0])
            v = r.get("result", {}).get("result", {})
            print(v.get("value") if "value" in v else json.dumps(r)[:800])
        elif cmd == "move":
            x, y = int(args[0]), int(args[1])
            await call("Input.dispatchMouseEvent",
                       {"type": "mouseMoved", "x": x, "y": y, "button": "none",
                        "buttons": 0, "clickCount": 0})
            print("moved")
        elif cmd == "click":
            x, y = int(args[0]), int(args[1])
            for t_, b_ in (("mousePressed", "left"), ("mouseReleased", "left")):
                await call("Input.dispatchMouseEvent",
                           {"type": t_, "x": x, "y": y, "button": b_,
                            "buttons": 1 if t_ == "mousePressed" else 0, "clickCount": 1})
            print("clicked")
        elif cmd == "wheel":
            x, y, dy = int(args[0]), int(args[1]), float(args[2])
            await call("Input.dispatchMouseEvent",
                       {"type": "mouseWheel", "x": x, "y": y, "button": "none",
                        "deltaX": 0, "deltaY": dy})
            print("wheeled")
        elif cmd == "key":
            ch = args[0]
            await call("Input.dispatchKeyEvent",
                       {"type": "keyDown", "text": ch, "unmodifiedText": ch,
                        "windowsVirtualKeyCode": ord(ch.upper()), "nativeVirtualKeyCode": ord(ch.upper())})
            await call("Input.dispatchKeyEvent",
                       {"type": "char", "text": ch, "unmodifiedText": ch,
                        "windowsVirtualKeyCode": ord(ch.upper()), "nativeVirtualKeyCode": ord(ch.upper())})
            await call("Input.dispatchKeyEvent",
                       {"type": "keyUp", "text": ch, "unmodifiedText": ch,
                        "windowsVirtualKeyCode": ord(ch.upper()), "nativeVirtualKeyCode": ord(ch.upper())})
            print("keyed", ch)
        else:
            print("unknown cmd")

if __name__ == "__main__":
    c = sys.argv[1] if len(sys.argv) > 1 else "state"
    a = sys.argv[2:]
    asyncio.run(session(c, a))
