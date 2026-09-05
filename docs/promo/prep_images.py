# -*- coding: utf-8 -*-
"""推广 PPT 配图预处理：裁浏览器边框/Dock、抹 vConsole、统一输出到 assets/final/"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SHOTS = os.path.join(HERE, "assets", "shots")
OUT = os.path.join(HERE, "assets", "final")
os.makedirs(OUT, exist_ok=True)


def patch_vconsole(im, box=None):
    """用左侧邻近像素色块盖掉右下角 vConsole 绿色按钮。box=(x1,y1,x2,y2)"""
    w, h = im.size
    if box is None:
        box = (int(w * 0.945), int(h * 0.945), w, int(h * 0.995))
    sample = im.getpixel((box[0] - 40, (box[1] + box[3]) // 2))
    im.paste(sample, box)
    return im


def run(name, src, crop=None, vconsole=False, target_aspect=None, max_w=2200):
    im = Image.open(os.path.join(SHOTS, src)).convert("RGB")
    if crop:
        im = im.crop(crop)
    if vconsole:
        im = patch_vconsole(im)
    if target_aspect:
        w, h = im.size
        cur = w / h
        if cur > target_aspect:  # 太宽 → 裁宽
            nw = int(h * target_aspect)
            x0 = (w - nw) // 2
            im = im.crop((x0, 0, x0 + nw, h))
        else:  # 太高 → 裁高
            nh = int(w / target_aspect)
            im = im.crop((0, 0, w, nh))
    if im.size[0] > max_w:
        im = im.resize((max_w, int(im.size[1] * max_w / im.size[0])), Image.LANCZOS)
    path = os.path.join(OUT, name + ".jpg")
    im.save(path, quality=90)
    print(name, im.size)


# 3584x2178 浏览器截屏: chrome 顶部 0-168, dock 2060 起
B1 = dict(crop=(0, 168, 3584, 2060), vconsole=True)
# 封面: 在 B1 基础上再裁成 16:9（横向窗口 180-3544）
run("cover", "f-login.jpg", crop=(180, 168, 3544, 2060), vconsole=True)
run("cockpit", "f-cockpit.jpg", **B1)
run("wayline", "f-wayline.jpg", **B1)
# 3808x2282 浏览器窗口带黑边: 窗口内容区
run("cockpit-ir", "f-cockpit-ir.jpg", crop=(120, 248, 3680, 2240), vconsole=True)
# 全屏页面(无 chrome)
run("visible-sunset", "video-001-30s.jpg", crop=(18, 12, 3566, 2166))
run("ir-full", "f-ir-try-48.jpg", crop=(220, 280, 3700, 2200))
run("night", "f-night.jpg", crop=(84, 44, 3724, 2236))
# 实拍与标注图
run("drone-truck", "f-drone-truck.jpg")
run("fire-annotated", "f-firecand-5.jpg")
run("msdk-home", "msdk-home.png")
