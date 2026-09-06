# -*- coding: utf-8 -*-
"""Generate the browser app icon (128x128, gradient rounded bg + browser window glyph)."""
from PIL import Image, ImageDraw
import os

S = 512
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "src/main/resources/assets/mcphone_browser/textures/ui")
os.makedirs(OUT, exist_ok=True)


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGBA", size)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        d.line([(0, y), (w, y)], fill=c)
    return img


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def save(img, name):
    img = img.resize((128, 128), Image.LANCZOS)
    img.save(os.path.join(OUT, name))
    print("wrote", name)


W = (255, 255, 255, 255)
W2 = (255, 255, 255, 200)

# ---------- browser：地址栏 + 地球 ----------
img = vertical_gradient((S, S), (46, 110, 142), (24, 64, 92))
img.putalpha(rounded_mask((S, S), int(S * 0.22)))
d = ImageDraw.Draw(img)
# 浏览器窗口
d.rounded_rectangle([84, 110, 428, 412], radius=36, fill=W)
# 顶栏
d.rounded_rectangle([84, 110, 428, 190], radius=36, fill=(36, 88, 116, 255))
d.rectangle([84, 150, 428, 172], fill=(36, 88, 116, 255))
# 红黄绿点
d.ellipse([110, 132, 134, 156], fill=(255, 110, 96, 255))
d.ellipse([146, 132, 170, 156], fill=(255, 200, 80, 255))
d.ellipse([182, 132, 206, 156], fill=(120, 220, 120, 255))
# 地球（圆 + 经纬线）
cx, cy, r = 256, 300, 92
d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(36, 88, 116, 255), width=16)
d.ellipse([cx - 40, cy - r, cx + 40, cy + r], outline=(36, 88, 116, 255), width=12)
d.line([cx - r, cy, cx + r, cy], fill=(36, 88, 116, 255), width=12)
d.arc([cx - r, cy - 130, cx + r, cy + 130], 20, 160, fill=(36, 88, 116, 255), width=12)
save(img, "app_browser.png")

print("browser icon done ->", OUT)
