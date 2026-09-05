# -*- coding: utf-8 -*-
"""生成《智能集群大载重无人机灭火系统》推广 PPT（图文版）。

用法: 先跑 prep_images.py 生成 assets/final/ 配图，再跑本脚本。
输出: docs/promo/智能集群大载重无人机灭火系统-推广.pptx
"""
import os
from PIL import Image, ImageDraw, ImageFilter
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "assets")
FINAL = os.path.join(ASSETS, "final")
OUT = os.path.join(HERE, "智能集群大载重无人机灭火系统-推广.pptx")

# ---------- 配色 ----------
NAVY_TOP = (8, 15, 31)
NAVY_BOT = (16, 30, 56)
CARD = RGBColor(0x14, 0x23, 0x3E)
CARD_LIGHT = RGBColor(0x1B, 0x2E, 0x4F)
BORDER = RGBColor(0x2E, 0x44, 0x6B)
ORANGE = RGBColor(0xFF, 0x6B, 0x35)
AMBER = RGBColor(0xFF, 0xB0, 0x3A)
CYAN = RGBColor(0x3A, 0xC8, 0xE0)
WHITE = RGBColor(0xF5, 0xF8, 0xFF)
GREY = RGBColor(0xA8, 0xB6, 0xD0)
DIM = RGBColor(0x6E, 0x7E, 0x9C)
FONT = "PingFang SC"

W, H = Inches(13.333), Inches(7.5)


# ---------- PIL 背景 ----------
def _glow(img, center, radius, color, alpha):
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    d.ellipse([center[0] - radius, center[1] - radius,
               center[0] + radius, center[1] + radius],
              fill=color + (alpha,))
    overlay = overlay.filter(ImageFilter.GaussianBlur(radius // 2))
    img.alpha_composite(overlay)


def make_bg(path, cover=False):
    w, h = 1920, 1080
    img = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        c = tuple(int(NAVY_TOP[i] + (NAVY_BOT[i] - NAVY_TOP[i]) * t) for i in range(3))
        d.line([(0, y), (w, y)], fill=c + (255,))
    if cover:
        _glow(img, (1700, 950), 560, (255, 95, 40), 70)
        _glow(img, (180, 80), 420, (40, 170, 220), 45)
        for rad in (340, 430, 520):
            d.ellipse([1700 - rad, 950 - rad, 1700 + rad, 950 + rad],
                      outline=(255, 140, 70, 26), width=2)
    else:
        _glow(img, (1820, 1040), 380, (255, 95, 40), 34)
        _glow(img, (90, 40), 300, (40, 170, 220), 26)
    img.convert("RGB").save(path, quality=92)


# ---------- pptx 工具 ----------
def set_font(run, size, color=WHITE, bold=False, name=FONT):
    f = run.font
    f.size = Pt(size)
    f.bold = bold
    f.color.rgb = color
    f.name = name
    rPr = run._r.get_or_add_rPr()
    for tag in ("a:ea", "a:cs"):
        e = rPr.find(qn(tag))
        if e is None:
            e = rPr.makeelement(qn(tag), {})
            rPr.append(e)
        e.set("typeface", name)


def add_text(slide, x, y, w, h, lines, align=PP_ALIGN.LEFT,
             anchor=MSO_ANCHOR.TOP, line_spacing=1.0):
    tb = slide.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    for i, (text, size, color, bold) in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        if line_spacing != 1.0:
            p.line_spacing = line_spacing
        p.space_after = Pt(size * 0.35)
        run = p.add_run()
        run.text = text
        set_font(run, size, color, bold)
    return tb


def add_rect(slide, x, y, w, h, fill, line=None, radius=0.10):
    shp = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, y, w, h)
    try:
        shp.adjustments[0] = radius
    except Exception:
        pass
    shp.fill.solid()
    shp.fill.fore_color.rgb = fill
    if line:
        shp.line.color.rgb = line
        shp.line.width = Pt(1)
    else:
        shp.line.fill.background()
    shp.shadow.inherit = False
    return shp


def add_bar(slide, x, y, w, h, color):
    shp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, w, h)
    shp.fill.solid()
    shp.fill.fore_color.rgb = color
    shp.line.fill.background()
    shp.shadow.inherit = False
    return shp


_IMG_SIZE = {}


def pic_h(key, w_inch):
    if key not in _IMG_SIZE:
        _IMG_SIZE[key] = Image.open(os.path.join(FINAL, key + ".jpg")).size
    iw, ih = _IMG_SIZE[key]
    return w_inch * ih / iw


def add_pic(slide, key, x, y, w, caption=None, cap_color=GREY):
    """按宽度等比放置图片，带边框；可选下方小字说明。返回图片高度(英寸)。"""
    h = pic_h(key, w)
    pic = slide.shapes.add_picture(os.path.join(FINAL, key + ".jpg"),
                                   Inches(x), Inches(y), Inches(w), Inches(h))
    pic.line.color.rgb = BORDER
    pic.line.width = Pt(1.2)
    pic.shadow.inherit = False
    if caption:
        add_bar(slide, Inches(x), Inches(y + h + 0.12), Inches(0.05),
                Inches(0.22), ORANGE)
        add_text(slide, Inches(x + 0.14), Inches(y + h + 0.10), Inches(w - 0.1),
                 Inches(0.35), [(caption, 11.5, cap_color, False)])
    return h


def new_slide(prs, bg):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    slide.shapes.add_picture(bg, 0, 0, W, H)
    return slide


def header(slide, tag, title, sub=None):
    add_bar(slide, Inches(0.62), Inches(0.50), Inches(0.07), Inches(0.62), ORANGE)
    add_text(slide, Inches(0.88), Inches(0.42), Inches(9), Inches(0.35),
             [(tag, 13, CYAN, True)])
    add_text(slide, Inches(0.88), Inches(0.74), Inches(11.5), Inches(0.7),
             [(title, 29, WHITE, True)])
    if sub:
        add_text(slide, Inches(0.88), Inches(1.40), Inches(11.6), Inches(0.4),
                 [(sub, 13.5, GREY, False)])
    add_text(slide, Inches(11.6), Inches(7.08), Inches(1.3), Inches(0.3),
             [("UAVFIRE", 10, DIM, False)], align=PP_ALIGN.RIGHT)


def feature_cards(slide, cards, top=2.15, cols=2, left=0.88, width=11.6,
                  gap=0.3, card_h=2.05, title_size=16.5, desc_size=12.5):
    cw = (width - gap * (cols - 1)) / cols
    for i, (icon, title, desc) in enumerate(cards):
        r, c = divmod(i, cols)
        x = left + c * (cw + gap)
        y = top + r * (card_h + gap)
        add_rect(slide, Inches(x), Inches(y), Inches(cw), Inches(card_h), CARD)
        add_bar(slide, Inches(x), Inches(y + 0.25), Inches(0.05),
                Inches(card_h - 0.5), ORANGE)
        add_text(slide, Inches(x + 0.3), Inches(y + 0.24), Inches(cw - 0.55),
                 Inches(0.5), [(f"{icon}  {title}", title_size, WHITE, True)])
        add_text(slide, Inches(x + 0.3), Inches(y + 0.78), Inches(cw - 0.55),
                 Inches(card_h - 0.95), [(desc, desc_size, GREY, False)],
                 line_spacing=1.15)


def feature_rows(slide, rows, left, top, width, row_h=1.18, gap=0.18):
    """紧凑的纵向功能条目（标题 + 描述），用于图文混排页。"""
    for i, (icon, title, desc) in enumerate(rows):
        y = top + i * (row_h + gap)
        add_rect(slide, Inches(left), Inches(y), Inches(width), Inches(row_h), CARD)
        add_bar(slide, Inches(left), Inches(y + 0.16), Inches(0.05),
                Inches(row_h - 0.32), ORANGE)
        add_text(slide, Inches(left + 0.26), Inches(y + 0.13), Inches(width - 0.5),
                 Inches(0.4), [(f"{icon}  {title}", 14.5, WHITE, True)])
        add_text(slide, Inches(left + 0.26), Inches(y + 0.52), Inches(width - 0.5),
                 Inches(row_h - 0.6), [(desc, 11, GREY, False)], line_spacing=1.1)


def chips_row(slide, items, y, left=0.88):
    x = left
    for text in items:
        wch = 0.34 + 0.155 * len(text)
        add_rect(slide, Inches(x), Inches(y), Inches(wch), Inches(0.42),
                 CARD_LIGHT, line=CYAN, radius=0.5)
        add_text(slide, Inches(x), Inches(y + 0.065), Inches(wch), Inches(0.3),
                 [(text, 12, CYAN, False)], align=PP_ALIGN.CENTER)
        x += wch + 0.22


# ---------- 生成 ----------
def main():
    bg_cover = os.path.join(ASSETS, "bg_cover.jpg")
    bg_page = os.path.join(ASSETS, "bg_page.jpg")
    make_bg(bg_cover, cover=True)
    make_bg(bg_page, cover=False)

    prs = Presentation()
    prs.slide_width, prs.slide_height = W, H

    # ===== 1 封面：系统登录页主视觉全幅 =====
    s = prs.slides.add_slide(prs.slide_layouts[6])
    s.shapes.add_picture(os.path.join(FINAL, "cover.jpg"), 0, 0, W, H)
    add_rect(s, Inches(0.55), Inches(6.72), Inches(3.7), Inches(0.5),
             RGBColor(0x10, 0x1C, 0x33), line=ORANGE, radius=0.5)
    add_text(s, Inches(0.55), Inches(6.81), Inches(3.7), Inches(0.34),
             [("产品推介 · 2026  |  实机系统界面", 12.5, AMBER, True)],
             align=PP_ALIGN.CENTER)

    # ===== 2 行业痛点 =====
    s = new_slide(prs, bg_page)
    header(s, "行业背景", "森林防火，难在「早发现、快处置」",
           "火情发现越晚，扑救代价呈指数级上升 —— 传统手段正面临四大瓶颈")
    feature_cards(s, [
        ("🔭", "发现难", "人工瞭望塔与地面巡山覆盖范围有限、盲区多，偏远林区初期火点极易错过最佳处置窗口。"),
        ("🌫", "看不清", "浓烟、夜间、逆光环境下，单一可见光手段难以确认火情真伪与蔓延态势，误报漏报并存。"),
        ("📍", "定位慢", "依赖目视估算与口头上报，火点位置模糊，地面力量往往「找火」比「灭火」耗时更长。"),
        ("⚠️", "处置险", "山区地形复杂，扑救人员抵达慢、作业风险高，初期小火常因处置不及时酿成大灾。"),
    ])

    # ===== 3 方案总览 =====
    s = new_slide(prs, bg_page)
    header(s, "解决方案", "从发现到扑灭，一个平台全闭环",
           "无人机自动巡航 + AI 实时识别 + 精准定位 + 指挥审批 + 灭火弹投放，六步形成完整处置闭环")
    steps = [("01", "自动巡航", "按预设航线全自动巡查林区"),
             ("02", "AI 识别", "机载画面实时烟火检测告警"),
             ("03", "热成像确认", "红外测温复核，排除误报"),
             ("04", "火点定位", "画面目标解算为地理坐标"),
             ("05", "指挥决策", "大屏研判，一键审批处置"),
             ("06", "精准投放", "灭火弹自动飞抵定点投放")]
    cw, gap = 1.82, 0.18
    for i, (num, title, desc) in enumerate(steps):
        x = 0.88 + i * (cw + gap)
        y = 2.45
        add_rect(s, Inches(x), Inches(y), Inches(cw), Inches(2.5), CARD)
        add_text(s, Inches(x), Inches(y + 0.25), Inches(cw), Inches(0.6),
                 [(num, 26, ORANGE if i in (1, 5) else CYAN, True)],
                 align=PP_ALIGN.CENTER)
        add_text(s, Inches(x), Inches(y + 0.95), Inches(cw), Inches(0.45),
                 [(title, 16, WHITE, True)], align=PP_ALIGN.CENTER)
        add_text(s, Inches(x + 0.14), Inches(y + 1.45), Inches(cw - 0.28), Inches(1.0),
                 [(desc, 11, GREY, False)], align=PP_ALIGN.CENTER, line_spacing=1.1)
        if i < 5:
            add_text(s, Inches(x + cw - 0.06), Inches(y + 1.0), Inches(0.32),
                     Inches(0.5), [("›", 24, ORANGE, True)], align=PP_ALIGN.CENTER)
    add_rect(s, Inches(0.88), Inches(5.4), Inches(11.6), Inches(1.15), CARD_LIGHT)
    add_text(s, Inches(1.2), Inches(5.62), Inches(11), Inches(0.7),
             [("全流程在线、留痕、可追溯 —— 发现即定位、定位即决策、决策即处置，"
               "把「黄金扑救期」牢牢握在手中。", 15, AMBER, True)],
             anchor=MSO_ANCHOR.MIDDLE)

    # ===== 4 系统架构（含机载端实机截图）=====
    s = new_slide(prs, bg_page)
    header(s, "系统架构", "四端协同，一体化作战体系",
           "基于大疆行业级无人机生态构建，云端 + 机载端深度联动")
    feature_cards(s, [
        ("🖥", "指挥驾驶舱", "面向指挥员的一屏总览：实时视频、火情事件、航线进度、态势地图，支持全屏大屏模式。"),
        ("☁️", "业务指挥平台", "任务、航线、设备、审批、媒体统一中枢，负责航线下发、指令调度与全程数据留痕。"),
        ("🧠", "AI 火情识别引擎", "持续分析直播视频流，烟火检测、风险分级、事件上报，7×24 小时在线值守。"),
        ("📡", "机载智控终端", "运行于大疆遥控器：视频推流、红外热成像、飞控指令执行、灭火弹脱钩，遥测实时回传。"),
    ], left=0.88, width=7.35, card_h=2.0, title_size=15.5, desc_size=11.5)
    add_pic(s, "msdk-home", 8.55, 2.35, 4.25,
            caption="机载智控终端 · 遥控器端实机主界面")

    # ===== 5 双光直播（可见光 + 红外实拍）=====
    s = new_slide(prs, bg_page)
    header(s, "核心能力 01", "双光实时直播 — 火场尽收眼底",
           "可见光 + 红外热成像双通道，白天黑夜、有烟无烟都看得清")
    add_pic(s, "visible-sunset", 0.88, 2.0, 5.9,
            caption="可见光通道 · 全屏火情监测画面（实机直播截图）")
    add_pic(s, "ir-full", 7.05, 2.0, 5.9,
            caption="红外热成像通道 · 一键切换，穿透烟雾锁定温度异常")
    chips_row(s, ["低延迟推流", "多路同屏", "远程变焦", "可见光/红外一键切换"], 6.35)

    # ===== 6 AI 识别（真实标注图）=====
    s = new_slide(prs, bg_page)
    header(s, "核心能力 02", "AI 火情识别 — 7×24 小时智能瞭望",
           "深度学习烟火检测模型持续分析直播画面，秒级发现、分级告警")
    add_pic(s, "fire-annotated", 0.88, 2.05, 6.35,
            caption="真实燃烧试验 · AI 同时锁定烟雾与多个明火目标（含置信度）")
    feature_rows(s, [
        ("🔥", "烟·火双目标检测", "1920 像素高分辨率推理，远距离小目标也能捕捉，早于人眼发现。"),
        ("📊", "三级风险分级", "低/中/高风险自动分级，一眼分清「需要关注」与「立即处置」。"),
        ("🔕", "智能去重告警", "时间窗去重，杜绝轰炸式重复告警，事件自动归档可回溯。"),
        ("♻️", "持续巡检模式", "随直播自动启停、断流自愈，无人值守全天候在线。"),
    ], left=7.6, top=2.05, width=4.85, row_h=1.02, gap=0.16)

    # ===== 7 火点定位 =====
    s = new_slide(prs, bg_page)
    header(s, "核心能力 03", "火点精准定位 — 从画面到坐标",
           "射线-地形求交（Ray-DEM）算法：相机看到哪里，地图就标到哪里")
    add_rect(s, Inches(0.88), Inches(2.1), Inches(5.6), Inches(4.3), CARD)
    add_text(s, Inches(1.2), Inches(2.4), Inches(5.0), Inches(0.5),
             [("🎯  定位原理", 17, WHITE, True)])
    add_text(s, Inches(1.2), Inches(3.0), Inches(5.0), Inches(3.2),
             [("① 获取无人机实时位置、云台姿态与相机参数", 13, GREY, False),
              ("② 由画面中的火点像素构建空间视线射线", 13, GREY, False),
              ("③ 射线与数字高程模型（DEM）地形求交", 13, GREY, False),
              ("④ 解算出火点真实经纬度，自动落图标绘", 13, GREY, False)],
             line_spacing=1.3)
    add_rect(s, Inches(6.78), Inches(2.1), Inches(5.7), Inches(4.3), CARD)
    add_text(s, Inches(7.1), Inches(2.4), Inches(5.1), Inches(0.5),
             [("⚡  指挥价值", 17, WHITE, True)])
    add_text(s, Inches(7.1), Inches(3.0), Inches(5.1), Inches(3.2),
             [("• AI 识别框即点即定位，不再依赖目视估算", 13, GREY, False),
              ("• 山地复杂地形下依然给出可信坐标", 13, GREY, False),
              ("• 坐标直接驱动后续灭火航线自动规划", 13, GREY, False),
              ("• 处置力量「按图索骥」，大幅缩短找火时间", 13, GREY, False)],
             line_spacing=1.3)

    # ===== 8 FC100 投放（航线规划截图）=====
    s = new_slide(prs, bg_page)
    header(s, "核心能力 04", "灭火弹精准投放 — 发现即可扑灭",
           "对接大载重灭火无人机（FC100），从火点坐标到灭火弹落点全自动")
    feature_rows(s, [
        ("🗺", "灭火航线一键生成", "基于火点坐标自动规划进场航点与投放点，规划即合规、即可飞。"),
        ("✅", "投放审批工作流", "申请—审批—执行全程在线留痕，重大处置有授权、可追溯。"),
        ("🪂", "到点悬停自动脱钩", "抵达投放点自动悬停并脱钩投放，全程无需飞手手动干预。"),
        ("🛡", "安全校验体系", "航线安全检查与投放条件校验前置，每次投放安全可控。"),
    ], left=0.88, top=2.05, width=5.35, row_h=1.02, gap=0.16)
    add_pic(s, "wayline", 6.55, 2.05, 6.0,
            caption="航线任务工作台 · 监测/灭火航线在线规划与一键下发")

    # ===== 9 远程飞控（夜航实拍）=====
    s = new_slide(prs, bg_page)
    header(s, "核心能力 05", "远程飞行控制 — 指挥室里的驾驶舱",
           "指令从大屏直达机载端，秒级下发、秒级执行确认")
    feature_rows(s, [
        ("🎮", "全要素远程操控", "云台回中、相机变焦、夜航灯、虚拟摇杆精调机位，一个面板全搞定。"),
        ("⏱", "秒级指令闭环", "下发—执行—确认全链路状态可见，每条指令都有明确回执。"),
        ("💓", "设备实时心跳", "在线状态、电量、位置持续回传，设备健康一目了然。"),
        ("🌙", "夜间作业能力", "夜航灯远程开启，夜间巡护与处置同样从容。"),
    ], left=0.88, top=2.05, width=5.35, row_h=1.02, gap=0.16)
    add_pic(s, "night", 6.55, 2.05, 6.0,
            caption="夜间实飞 · 远程开启夜航灯，飞行面板实时操控（实机截图）")

    # ===== 10 领导驾驶舱（实机大图）=====
    s = new_slide(prs, bg_page)
    header(s, "核心能力 06", "领导驾驶舱 — 一屏纵览全局",
           "为指挥决策而生的实时态势大屏（以下均为实机运行界面）")
    add_pic(s, "cockpit", 0.88, 2.0, 8.0,
            caption="森林灭火综合驾驶舱 · 活跃火情/风险等级/AI 识别/直播/投放任务一屏聚合")
    add_pic(s, "cockpit-ir", 9.2, 2.0, 3.55,
            caption="火情监测画面 · 红外模式")
    add_text(s, Inches(9.2), Inches(4.6), Inches(3.6), Inches(2.2),
             [("• 实时态势图：火点热力与事件聚合", 12.5, GREY, False),
              ("• 视频矩阵：可见光/红外一键切换", 12.5, GREY, False),
              ("• 火情事件流：附快照与风险等级", 12.5, GREY, False),
              ("• 全屏模式适配指挥中心大屏", 12.5, GREY, False)],
             line_spacing=1.35)

    # ===== 11 实战场景（时间轴 + 实拍）=====
    s = new_slide(prs, bg_page)
    header(s, "实战验证", "一次完整的火情处置，只需几分钟",
           "核心功能均已完成真机实飞验证")
    timeline = [
        ("T+0", "无人机按既定航线自动巡航，AI 持续分析机载画面"),
        ("T+发现", "AI 检出烟火目标并告警，红外热成像确认高温火点"),
        ("T+30 秒", "Ray-DEM 解算火点坐标，自动落图推送指挥大屏"),
        ("T+1 分钟", "指挥员大屏研判，审批通过灭火处置任务"),
        ("T+数分钟", "灭火机沿自动航线飞抵火点上空，悬停自动脱钩投放"),
        ("T+处置后", "巡查机回场复查，红外确认无复燃，数据归档"),
    ]
    y = 2.1
    for i, (t, desc) in enumerate(timeline):
        add_rect(s, Inches(0.88), Inches(y), Inches(1.6), Inches(0.58),
                 CARD_LIGHT, line=ORANGE if i in (1, 4) else None, radius=0.3)
        add_text(s, Inches(0.88), Inches(y + 0.12), Inches(1.6), Inches(0.35),
                 [(t, 12.5, AMBER if i in (1, 4) else CYAN, True)],
                 align=PP_ALIGN.CENTER)
        add_text(s, Inches(2.72), Inches(y + 0.05), Inches(5.0), Inches(0.52),
                 [(desc, 12.5, WHITE if i in (1, 4) else GREY, i in (1, 4))],
                 line_spacing=1.05)
        y += 0.74
    add_pic(s, "drone-truck", 8.15, 2.1, 4.55,
            caption="外场实飞 · 机动部署，随到随飞")

    # ===== 12 平台优势 =====
    s = new_slide(prs, bg_page)
    header(s, "为什么选择我们", "不止于「看见」，更能「扑灭」",
           "多数方案止步于监测告警，我们交付的是完整处置能力")
    feature_cards(s, [
        ("🔁", "端到端闭环", "业内少有的「识别—定位—投放」全链路打通方案，发现火情的系统同时就是扑灭火情的系统。"),
        ("✈️", "真机实飞验证", "全部核心功能在大疆行业机型上完成真机实飞验证——本册所有界面均为实机截图。"),
        ("🏗", "成熟生态 + 自主平台", "底层依托大疆行业级飞行平台，上层业务系统完全自研，支持私有化部署与定制扩展。"),
        ("📈", "面向集群演进", "架构原生支持多机协同，可平滑扩展至多机巡护、集群投放的规模化作战形态。"),
    ])

    # ===== 13 尾页 =====
    s = new_slide(prs, bg_cover)
    add_text(s, Inches(0.92), Inches(2.3), Inches(11.5), Inches(1.6),
             [("让每一片森林", 44, WHITE, True),
              ("都在守护之下", 44, WHITE, True)])
    add_bar(s, Inches(0.98), Inches(4.15), Inches(2.6), Inches(0.045), ORANGE)
    add_text(s, Inches(0.96), Inches(4.4), Inches(10), Inches(0.5),
             [("智能集群大载重无人机灭火系统 · 期待与您共筑森林安全防线", 16, GREY, False)])
    add_rect(s, Inches(0.96), Inches(5.3), Inches(4.2), Inches(0.6),
             CARD_LIGHT, line=ORANGE, radius=0.5)
    add_text(s, Inches(0.96), Inches(5.43), Inches(4.2), Inches(0.4),
             [("预约现场演示 · 欢迎垂询", 15, AMBER, True)], align=PP_ALIGN.CENTER)

    prs.save(OUT)
    print("saved:", OUT, "slides:", len(prs.slides._sldIdLst))


if __name__ == "__main__":
    main()
