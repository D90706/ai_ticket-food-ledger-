# -*- coding: utf-8 -*-
"""
生成《食材采购台账系统 · 课设答辩》PPT（第二版）。

相对第一版的变化：
  1 去掉「开发中踩到的坑」「不足与下一步」两页
  2 模块组成从 4 页扩到 6 页（分层 / 后端包 / record 内部分块 / 前端 / 数据模型 / 模块-功能-接口对照）
  3 加入 5 页真实界面截图（共 9 张，来自 docs/screenshots，由 qa/capture-shots.mjs 采集）

写法约定：Python 字符串一律用 ASCII 双引号；正文里的中文引号用「」，否则会截断字符串。

用法：python qa/build-ppt.py
输出：docs/答辩PPT-食材采购台账系统.pptx
"""

import os

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.util import Cm, Pt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "答辩PPT-食材采购台账系统.pptx")
SHOTS = os.path.join(ROOT, "docs", "screenshots")

GREEN_DARK = RGBColor(0x14, 0x53, 0x2D)
GREEN = RGBColor(0x2E, 0x7D, 0x32)
GREEN_LIGHT = RGBColor(0xE6, 0xF2, 0xE8)
AMBER = RGBColor(0xB4, 0x53, 0x09)
AMBER_LIGHT = RGBColor(0xFD, 0xF3, 0xE1)
RED = RGBColor(0xB9, 0x1C, 0x1C)
BLUE = RGBColor(0x1D, 0x4E, 0xD8)
INK = RGBColor(0x1F, 0x29, 0x37)
MUTED = RGBColor(0x6B, 0x72, 0x80)
LINE = RGBColor(0xD5, 0xE0, 0xD8)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
PAPER = RGBColor(0xF7, 0xFA, 0xF7)

FONT = "微软雅黑"
W, H = Cm(33.87), Cm(19.05)
SHOT_RATIO = 900.0 / 1440.0   # 截图 1440x900


def set_font(run, size, bold=False, color=INK, font=FONT):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    run.font.name = font
    from lxml import etree
    rPr = run._r.get_or_add_rPr()
    ea = rPr.find('{http://schemas.openxmlformats.org/drawingml/2006/main}ea')
    if ea is None:
        ea = etree.SubElement(
            rPr, '{http://schemas.openxmlformats.org/drawingml/2006/main}ea')
    ea.set('typeface', font)


def textbox(slide, x, y, w, h, lines, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP,
            space_after=4, line_spacing=1.25):
    box = slide.shapes.add_textbox(x, y, w, h)
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = tf.margin_right = Cm(0.08)
    tf.margin_top = tf.margin_bottom = 0
    first = True
    for item in lines:
        text, size, bold, color = item[0], item[1], item[2], item[3]
        p = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        p.alignment = align
        p.space_after = Pt(space_after)
        p.line_spacing = line_spacing
        run = p.add_run()
        run.text = text
        set_font(run, size, bold, color)
    return box


def rect(slide, x, y, w, h, fill=None, line=None, radius=None,
         shape=MSO_SHAPE.ROUNDED_RECTANGLE):
    shp = slide.shapes.add_shape(shape, x, y, w, h)
    if radius is not None and shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        shp.adjustments[0] = radius
    if fill is None:
        shp.fill.background()
    else:
        shp.fill.solid()
        shp.fill.fore_color.rgb = fill
    if line is None:
        shp.line.fill.background()
    else:
        shp.line.color.rgb = line
        shp.line.width = Pt(1)
    shp.shadow.inherit = False
    return shp


def card(slide, x, y, w, h, title, lines, accent=GREEN, fill=WHITE,
         title_size=12, body_size=10.5):
    rect(slide, x, y, w, h, fill=fill, line=LINE, radius=0.08)
    rect(slide, x, y, Cm(0.22), h, fill=accent, shape=MSO_SHAPE.RECTANGLE)
    textbox(slide, x + Cm(0.55), y + Cm(0.3), w - Cm(0.85), Cm(0.9),
            [(title, title_size, True, accent)], line_spacing=1.05)
    textbox(slide, x + Cm(0.55), y + Cm(1.25), w - Cm(0.85), h - Cm(1.5),
            [(t, body_size, False, INK) for t in lines], space_after=3, line_spacing=1.2)


def arrow(slide, x, y, length, vertical=False, color=GREEN):
    shp = MSO_SHAPE.DOWN_ARROW if vertical else MSO_SHAPE.RIGHT_ARROW
    w = Cm(0.55) if vertical else length
    h = length if vertical else Cm(0.55)
    return rect(slide, x, y, w, h, fill=color, shape=shp)


def label(slide, x, y, text, size=9, color=MUTED, bold=False,
          align=PP_ALIGN.CENTER, w=Cm(4)):
    textbox(slide, x, y, w, Cm(0.9), [(text, size, bold, color)],
            align=align, line_spacing=1.05)


def pic(slide, name, x, y, w, border=LINE):
    """按宽度放截图，高度按 1440x900 自动算；下面留一条说明文字的位置。"""
    path = os.path.join(SHOTS, name)
    h = w * SHOT_RATIO
    frame = rect(slide, x - Cm(0.08), y - Cm(0.08), w + Cm(0.16), h + Cm(0.16),
                 fill=WHITE, line=border, radius=0.02)
    slide.shapes.add_picture(path, x, y, width=w)
    return h


# ---------------------------------------------------------------- 答辩分工
#
# 五个人分讲：每页角上标出主讲人，答辩时不用临时找「这页谁讲」。
# 分工按「内容成块」而不是按页数平均：一个人讲完一个完整主题
#（背景 / 录入与状态 / AI 与架构 / 核心包与前端 / 数据与收尾），
# 中途换人会让人觉得「讲到一半换人了」。
SPEAKERS = {
    1: "① 开场", 2: "① 开场", 3: "① 背景", 4: "① 背景", 5: "① 目的",
    6: "② 录入", 7: "② 状态", 8: "② 判定", 9: "② AI流程",
    10: "③ AI实拍", 11: "③ 两条路径", 12: "③ 架构", 13: "③ 后端包",
    14: "④ 核心包", 15: "④ 前端", 16: "④ 实拍", 17: "④ 实拍",
    18: "⑤ 数据", 19: "⑤ 对照", 20: "⑤ 实拍", 21: "⑤ 实拍",
    22: "⑤ 取舍", 23: "⑤ 成果", 24: "⑤ 收尾",
}


def stamp_speaker(slide, text):
    """在页脚右侧标出主讲人：答辩时一眼看出这页归谁。"""
    label(slide, Cm(22.6), Cm(17.6), "主讲 " + text, 8.5, GREEN, True,
          PP_ALIGN.RIGHT, Cm(5.0))


class Deck:
    def __init__(self):
        self.prs = Presentation()
        self.prs.slide_width = W
        self.prs.slide_height = H
        self.n = 0

    def new(self, title, kicker=None):
        slide = self.prs.slides.add_slide(self.prs.slide_layouts[6])
        self.n += 1
        rect(slide, 0, 0, W, H, fill=PAPER, shape=MSO_SHAPE.RECTANGLE)
        rect(slide, Cm(1.6), Cm(1.15), Cm(0.9), Cm(0.9), fill=GREEN,
             shape=MSO_SHAPE.RECTANGLE)
        textbox(slide, Cm(2.85), Cm(1.02), Cm(27), Cm(1.2),
                [(title, 24, True, GREEN_DARK)], line_spacing=1.0)
        if kicker:
            textbox(slide, Cm(2.9), Cm(2.3), Cm(29), Cm(0.8),
                    [(kicker, 11.5, False, MUTED)], line_spacing=1.0)
        rect(slide, Cm(1.6), Cm(3.05), Cm(30.6), Cm(0.06), fill=LINE,
             shape=MSO_SHAPE.RECTANGLE)
        label(slide, Cm(1.6), Cm(17.6), "食材采购台账系统 · 课程设计答辩",
              9, MUTED, False, PP_ALIGN.LEFT, Cm(16))
        label(slide, Cm(28.0), Cm(17.6), "%02d" % self.n, 9, MUTED,
              False, PP_ALIGN.RIGHT, Cm(4.2))
        return slide

    def save(self, path):
        self.prs.save(path)


# ---------------------------------------------------------------- 各页

def s_cover(d):
    s = d.prs.slides.add_slide(d.prs.slide_layouts[6])
    d.n += 1
    rect(s, 0, 0, W, H, fill=GREEN_DARK, shape=MSO_SHAPE.RECTANGLE)
    rect(s, 0, Cm(11.3), W, Cm(0.12), fill=GREEN, shape=MSO_SHAPE.RECTANGLE)
    textbox(s, Cm(3.2), Cm(3.2), Cm(8), Cm(1.0),
            [("课程设计 · 答辩", 13, False, RGBColor(0xA7, 0xD3, 0xB2))])
    textbox(s, Cm(3.2), Cm(4.2), Cm(27), Cm(3.2),
            [("食材采购台账系统", 46, True, WHITE)], line_spacing=1.0)
    textbox(s, Cm(3.2), Cm(7.9), Cm(27), Cm(1.2),
            [("一句话录入 · 法定项目自动判定 · 全流程可追溯", 18, False,
              RGBColor(0xC8, 0xE6, 0xD0))], line_spacing=1.0)
    textbox(s, Cm(3.2), Cm(11.8), Cm(27), Cm(2.4),
            [("答辩人：____________        指导教师：____________", 13, False,
              RGBColor(0xC8, 0xE6, 0xD0)),
             ("2026 年 10 月", 13, False, RGBColor(0xA7, 0xD3, 0xB2))],
            space_after=10, line_spacing=1.3)


def s_agenda(d):
    s = d.new("汇报路线", "背景 → 目的 → 业务逻辑 → 模块组成 → 成果；每个部分都配界面实拍")
    items = [
        ("01", "选题背景", "食堂采购的记录现状，以及法律对进货查验记录的硬要求", GREEN),
        ("02", "解决目的", "要解决什么、不解决什么；产品定位一句话", AMBER),
        ("03", "项目业务逻辑", "生命周期、合规判定、AI 录入流程，配 3 页界面实拍", GREEN_DARK),
        ("04", "项目模块组成", "分层架构、13 个后端包、record 内部分块、前端、7 张表、模块对照表", BLUE),
        ("05", "成果与验证", "功能、量化指标、测试规模与抽取评测", RGBColor(0x7C, 0x3A, 0xED)),
        ("06", "关键设计取舍", "五条当时可以那么做、但我们选了这么做的决定", RED),
    ]
    y = Cm(3.5)
    for num, title, desc, color in items:
        rect(s, Cm(1.8), y, Cm(30), Cm(2.05), fill=WHITE, line=LINE, radius=0.1)
        rect(s, Cm(1.8), y, Cm(1.7), Cm(2.05), fill=color, shape=MSO_SHAPE.RECTANGLE)
        textbox(s, Cm(1.95), y + Cm(0.45), Cm(1.4), Cm(1.0),
                [(num, 15, True, WHITE)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, Cm(3.9), y + Cm(0.42), Cm(6.4), Cm(1.0),
                [(title, 14, True, INK)], line_spacing=1.0)
        textbox(s, Cm(10.4), y + Cm(0.52), Cm(20.8), Cm(1.0),
                [(desc, 10.5, False, MUTED)], line_spacing=1.0)
        y += Cm(2.3)


def s_bg_problem(d):
    s = d.new("选题背景：食堂食材采购的记录现状",
              "问题不在买不到，而在记不全、查不到、算不清")
    rows = [
        ("纸质台账", "采购员在库房手写本子，笔迹潦草、容易漏项；一本写完换一本，历史难翻", RED),
        ("检查要求", "监管来查进货查验记录，要当场拿出名称、批号、供货者、凭证——本子常缺项", AMBER),
        ("统计靠人算", "月底要报采购金额与供应商排名，只能按计算器，还分不清哪些已入账、哪些挂在待审", AMBER),
        ("无追溯", "同批食材要退货时，翻不出这一批进了哪几笔、有没有已经用掉", RED),
    ]
    y = Cm(3.5)
    for title, desc, color in rows:
        rect(s, Cm(1.8), y, Cm(30), Cm(2.75), fill=WHITE, line=LINE, radius=0.08)
        rect(s, Cm(1.8), y, Cm(0.28), Cm(2.75), fill=color, shape=MSO_SHAPE.RECTANGLE)
        textbox(s, Cm(2.5), y + Cm(0.5), Cm(6.2), Cm(1.0),
                [(title, 14, True, color)], line_spacing=1.0)
        textbox(s, Cm(9.0), y + Cm(0.45), Cm(22.2), Cm(1.9),
                [(desc, 11, False, INK)], line_spacing=1.25)
        y += Cm(3.0)


def s_bg_law(d):
    s = d.new("选题背景：法律把该记什么写死了",
              "《食品安全法》第五十三条 · 进货查验记录")
    rect(s, Cm(1.8), Cm(3.5), Cm(14.6), Cm(9.2), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(2.3), Cm(3.9), Cm(13.6), Cm(1.0),
            [("进货查验记录应当记录 12 项", 13, True, GREEN_DARK)])
    fields = ["名称", "规格", "数量", "单位", "生产日期", "生产批号",
              "保质期", "进货日期", "供货者名称", "供货者地址", "联系方式", "索证索票凭证"]
    x0, y0 = Cm(2.3), Cm(5.2)
    for i, f in enumerate(fields):
        col, row = i % 3, i // 3
        bx = x0 + Cm(col * 4.5)
        by = y0 + Cm(row * 1.85)
        rect(s, bx, by, Cm(4.2), Cm(1.5), fill=GREEN_LIGHT, line=GREEN, radius=0.12)
        textbox(s, bx, by + Cm(0.4), Cm(4.2), Cm(0.9),
                [(f, 11, True, GREEN_DARK)], align=PP_ALIGN.CENTER, line_spacing=1.0)

    rect(s, Cm(17.4), Cm(3.5), Cm(14.8), Cm(9.2), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(17.9), Cm(3.9), Cm(13.8), Cm(1.0),
            [("这句话对系统的含义", 13, True, AMBER)])
    points = [
        "法律固定 → 数据库 schema 必须固定：这 12 项要建列，不能塞进一个 JSON",
        "有列才能按日期筛、按供应商汇总、按批号追溯、导出 Excel",
        "缺任何一项都不该允许入账——这是合规与记了的分界",
        "生产日期与生产批号是或的关系：散装食材没有批号，填生产日期即可",
    ]
    textbox(s, Cm(17.9), Cm(5.2), Cm(13.9), Cm(7.0),
            [("· " + p, 11, False, INK) for p in points], space_after=10, line_spacing=1.3)


def s_goal(d):
    s = d.new("解决目的：四个要",
              "产品定位：把一笔采购记成一条能过检查、能算账、能追溯的记录")
    cols = [
        ("录得进", ["一句话说（AI）", "照表单填（手填）", "凭证拍照上传", "被打断不丢草稿"], GREEN),
        ("判得准", ["级别由代码判定", "不交给模型", "同一笔采购不会", "判出两个级别"], GREEN_DARK),
        ("查得到", ["日期/供货者/品类", "状态/级别/批号", "组合筛选", "按批号追溯同批"], AMBER),
        ("留得住", ["谁录的", "AI 从哪句话抽的", "改过几次、谁改的", "不物理删除，只作废"], BLUE),
    ]
    x = Cm(1.8)
    for title, lines, color in cols:
        rect(s, x, Cm(3.6), Cm(7.3), Cm(6.4), fill=WHITE, line=LINE, radius=0.06)
        rect(s, x, Cm(3.6), Cm(7.3), Cm(1.35), fill=color, radius=0.06)
        textbox(s, x, Cm(3.95), Cm(7.3), Cm(1.0),
                [(title, 15, True, WHITE)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x + Cm(0.45), Cm(5.3), Cm(6.4), Cm(4.4),
                [("· " + l, 10.5, False, INK) for l in lines], space_after=6, line_spacing=1.2)
        x += Cm(7.7)

    rect(s, Cm(1.8), Cm(10.8), Cm(30), Cm(4.9), fill=GREEN_LIGHT, line=GREEN, radius=0.05)
    textbox(s, Cm(2.4), Cm(11.15), Cm(29), Cm(1.0),
            [("明确不做（划清边界也是设计的一部分）", 12.5, True, GREEN_DARK)])
    textbox(s, Cm(2.4), Cm(12.15), Cm(28.8), Cm(3.3),
            [("· 不做采购下单、库存扣减、财务对账——只负责进货查验记录这一段，把它做扎实", 10.5, False, INK),
             ("· 不训练模型：抽取用现成大模型，确定性规则（合规判定）放在代码里", 10.5, False, INK),
             ("· 不做多租户与复杂权限：只有采购员/管理员两种角色，先跑通一个食堂", 10.5, False, INK)],
            space_after=9, line_spacing=1.2)


def s_shot_two_ends(d):
    s = d.new("系统实拍（一）两个端、两个角色",
              "同一套后端：采购员在一句话里记账，管理员在列表里审核")
    w = Cm(14.5)
    x1 = Cm(1.8)
    x2 = Cm(17.6)
    label(s, x1, Cm(3.35), "采购录入端 · 记一笔（AI 直接出单）", 10.5, GREEN_DARK,
          True, PP_ALIGN.LEFT, Cm(14))
    pic(s, "02-采购端-记一笔-结果卡.png", x1, Cm(4.1), w)
    label(s, x1, Cm(13.4), "对话里说全 → 立刻生成「待入账」，附记录号与级别",
          9.5, MUTED, False, PP_ALIGN.LEFT, Cm(14))

    label(s, x2, Cm(3.35), "台账管理端 · 台账列表（筛选 / 分页 / 导出）", 10.5, GREEN_DARK,
          True, PP_ALIGN.LEFT, Cm(15))
    pic(s, "06-管理端-台账列表.png", x2, Cm(4.1), w)
    label(s, x2, Cm(13.4), "级别、状态、到期天数同屏；点任意一行进详情审核",
          9.5, MUTED, False, PP_ALIGN.LEFT, Cm(15))


def s_lifecycle(d):
    s = d.new("业务逻辑（一）一笔采购的生命周期",
              "状态只能按规定路线走：没有改回上一步，也没有删除")
    boxes = [
        (Cm(1.9), Cm(4.0), "待入账", "刚录完 / 补录完|等管理员审核", GREEN),
        (Cm(13.3), Cm(4.0), "已入账", "正式入账|之后只能作废", BLUE),
        (Cm(24.7), Cm(4.0), "已作废", "失效留痕|必须写原因", MUTED),
        (Cm(13.3), Cm(10.2), "退回补录", "采购员补齐后|自动回待入账", AMBER),
    ]
    for x, y, title, desc, color in boxes:
        rect(s, x, y, Cm(7.0), Cm(3.0), fill=WHITE, line=color, radius=0.08)
        rect(s, x, y, Cm(7.0), Cm(1.2), fill=color, radius=0.08)
        textbox(s, x, y + Cm(0.33), Cm(7.0), Cm(0.9),
                [(title, 14, True, WHITE)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x, y + Cm(1.35), Cm(7.0), Cm(1.5),
                [(d0, 10, False, INK) for d0 in desc.split("|")],
                align=PP_ALIGN.CENTER, space_after=2, line_spacing=1.15)

    arrow(s, Cm(9.2), Cm(5.2), Cm(3.7))
    label(s, Cm(8.9), Cm(5.85), "管理员入账", 9.5, GREEN_DARK, True, PP_ALIGN.CENTER, Cm(4.5))
    arrow(s, Cm(20.6), Cm(5.2), Cm(3.7), color=MUTED)
    label(s, Cm(20.3), Cm(5.85), "作废", 9.5, MUTED, True, PP_ALIGN.CENTER, Cm(4.5))
    arrow(s, Cm(15.95), Cm(7.2), Cm(2.7), vertical=True, color=AMBER)
    label(s, Cm(16.7), Cm(8.1), "退回（最多 2 次）", 9.5, AMBER, True, PP_ALIGN.LEFT, Cm(7))
    rect(s, Cm(9.6), Cm(11.1), Cm(3.5), Cm(0.55), fill=AMBER, shape=MSO_SHAPE.LEFT_ARROW)
    label(s, Cm(2.2), Cm(11.85), "补录后自动回到「待入账」", 9.5, AMBER, True,
          PP_ALIGN.LEFT, Cm(7))
    label(s, Cm(2.2), Cm(12.6), "（这一步曾经断掉：记录永远卡在退回补录）",
          9, MUTED, False, PP_ALIGN.LEFT, Cm(9))

    rect(s, Cm(1.9), Cm(13.9), Cm(30.3), Cm(2.6), fill=GREEN_LIGHT, line=GREEN, radius=0.06)
    textbox(s, Cm(2.5), Cm(14.25), Cm(29.2), Cm(2.0),
            [("为什么用状态机，而不是一个是否已入账的布尔值：退回过几次、谁改的、"
              "哪次改完又入账，检查时要能说清；台账不物理删除，作废也要留痕。",
              11, False, GREEN_DARK)], line_spacing=1.25)


def s_compliance(d):
    s = d.new("业务逻辑（二）合规判定：红 / 黄 / 绿",
              "判定是纯函数：构造时传模板，不碰数据库、不依赖框架")
    levels = [
        ("红色 · 不合规", "缺任一法定项目", ["不能入账", "只能补齐或退回"], RED,
         RGBColor(0xFC, 0xE9, 0xE9)),
        ("黄色 · 待补证", "法定齐，缺选填", ["可以直接入账", "建议后续补"], AMBER, AMBER_LIGHT),
        ("绿色 · 记录完整", "法定 + 选填都齐", ["可直接入账", "检查最省事"], GREEN, GREEN_LIGHT),
    ]
    x = Cm(1.8)
    for title, cond, effect, color, fill in levels:
        rect(s, x, Cm(3.5), Cm(9.6), Cm(4.9), fill=fill, line=color, radius=0.06)
        textbox(s, x, Cm(3.82), Cm(9.6), Cm(0.9),
                [(title, 15, True, color)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x + Cm(0.7), Cm(4.9), Cm(8.2), Cm(3.3),
                [("判定条件：" + cond, 11, False, INK)] +
                [("· " + e, 11, True, color) for e in effect],
                space_after=8, line_spacing=1.25)
        x += Cm(10.3)

    rect(s, Cm(1.8), Cm(8.9), Cm(30.2), Cm(3.2), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(2.4), Cm(9.2), Cm(29.2), Cm(2.7),
            [("一个刻意的取舍：过期不改级别", 12.5, True, GREEN_DARK),
             ("过期是业务事实（该退货），缺字段是记录缺陷（该补资料）。混成一个分数，"
              "人就不知道该干什么。所以已过保质期只做警告，级别仍按字段齐全度算。",
              10.5, False, INK)], space_after=7, line_spacing=1.25)

    rect(s, Cm(1.8), Cm(12.5), Cm(30.2), Cm(3.9), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(2.4), Cm(12.8), Cm(29.2), Cm(3.3),
            [("凭证的照片与编号等效", 12.5, True, GREEN_DARK),
             ("法律要求的是保存相关凭证。一串抄下来的编号不是凭证——检查时要看的是那张"
              "许可证或检疫证明。所以传了照片就不再追问编号，判定也认可照片；两者都没有时仍判缺。",
              10.5, False, INK)], space_after=7, line_spacing=1.25)


def s_ai_flow(d):
    s = d.new("业务逻辑（三）AI 录入的对话流程",
              "模型只负责抽值，级别与落库都由代码控制")
    steps = [
        ("① 一句话", "今天从城南肉联厂|进了20斤猪肉", GREEN),
        ("② 抽取", "模型按模板字段|输出 JSON 与依据", GREEN_DARK),
        ("③ 判定缺什么", "用同一套判定|算还缺哪几项", AMBER),
        ("④ 追问 / 确认", "缺则追问（最多2次）|超限转确认卡", AMBER),
        ("⑤ 落库", "生成待入账记录|与原文依据", BLUE),
    ]
    x = Cm(1.7)
    for i, (title, desc, color) in enumerate(steps):
        rect(s, x, Cm(3.5), Cm(5.4), Cm(3.9), fill=WHITE, line=color, radius=0.07)
        rect(s, x, Cm(3.5), Cm(5.4), Cm(1.2), fill=color, radius=0.07)
        textbox(s, x, Cm(3.82), Cm(5.4), Cm(0.9),
                [(title, 12.5, True, WHITE)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x + Cm(0.3), Cm(5.0), Cm(4.8), Cm(2.2),
                [(d0, 10, False, INK) for d0 in desc.split("|")],
                align=PP_ALIGN.CENTER, space_after=2, line_spacing=1.2)
        if i < len(steps) - 1:
            arrow(s, x + Cm(5.45), Cm(5.35), Cm(0.85))
        x += Cm(6.3)

    cards = [
        ("说全了就一次过", ["信息够就直接生成记录，不再问", "省的是采购员的时间"], GREEN),
        ("说不全就问，到上限出确认卡", ["追问硬上限 2 次，第 3 次转确认",
                             "确认卡可编辑摘要再提交"], AMBER),
        ("确认之前不落库", ["对话中途只在会话里攒值", "避免台账里出现半成品"], BLUE),
        ("依据必须来自原文", ["每个字段记原文哪句话", "不是原文子串的依据一律丢弃"], GREEN_DARK),
    ]
    x = Cm(1.7)
    for title, lines, color in cards:
        card(s, x, Cm(8.4), Cm(7.55), Cm(5.4), title, lines, accent=color,
             title_size=11.5, body_size=10)
        x += Cm(7.9)

    rect(s, Cm(1.7), Cm(14.5), Cm(30.4), Cm(2.1), fill=AMBER_LIGHT, line=AMBER, radius=0.06)
    textbox(s, Cm(2.3), Cm(14.85), Cm(29.4), Cm(1.6),
            [("降级也有边界：模型两次都拿不到可用 JSON 就按空值处理，"
              "下游判成不合规并要求补录——宁可让采购员重说一遍，也不生成一条假记录。",
              10.5, False, AMBER)], line_spacing=1.25)


def s_shot_ai(d):
    s = d.new("系统实拍（二）AI 对话的两种结局",
              "说不全 → 追问；说全 → 直接出单")
    w = Cm(14.5)
    label(s, Cm(1.8), Cm(3.35), "① 说不全：追问缺哪几项（并提示凭证可传照片）",
          10.5, AMBER, True, PP_ALIGN.LEFT, Cm(15))
    pic(s, "01-采购端-记一笔-追问.png", Cm(1.8), Cm(4.1), w)
    label(s, Cm(17.6), Cm(3.35), "② 说全了：直接生成「待入账」，给出记录号与级别",
          10.5, GREEN_DARK, True, PP_ALIGN.LEFT, Cm(15))
    pic(s, "02-采购端-记一笔-结果卡.png", Cm(17.6), Cm(4.1), w)
    label(s, Cm(1.8), Cm(13.4),
          "追问有硬上限（2 次），第 3 次转确认卡，不会无限追问", 9.5, MUTED,
          False, PP_ALIGN.LEFT, Cm(15))
    label(s, Cm(17.6), Cm(13.4),
          "记录状态是「待入账」——入账必须由管理员再做一步", 9.5, MUTED,
          False, PP_ALIGN.LEFT, Cm(15))


def s_two_paths(d):
    s = d.new("业务逻辑（四）AI 是加速器，不是唯一入口",
              "两条录入路径在值映射处汇合，判定规则不会分叉")
    rect(s, Cm(1.8), Cm(3.6), Cm(10.6), Cm(2.9), fill=WHITE, line=GREEN, radius=0.07)
    textbox(s, Cm(2.2), Cm(3.95), Cm(9.8), Cm(2.2),
            [("记一笔（AI）", 13, True, GREEN),
             ("跟 AI 说话 → 得到一张字段值表", 10.5, False, INK)], space_after=8)
    rect(s, Cm(1.8), Cm(9.6), Cm(10.6), Cm(2.9), fill=WHITE, line=AMBER, radius=0.07)
    textbox(s, Cm(2.2), Cm(9.95), Cm(9.8), Cm(2.2),
            [("手填（表单）", 13, True, AMBER),
             ("照模板一项项填 → 也是同一张表", 10.5, False, INK)], space_after=8)

    arrow(s, Cm(12.6), Cm(4.7), Cm(3.0))
    arrow(s, Cm(12.6), Cm(10.7), Cm(3.0))

    rect(s, Cm(15.8), Cm(6.0), Cm(7.8), Cm(3.4), fill=GREEN_LIGHT, line=GREEN, radius=0.07)
    textbox(s, Cm(16.2), Cm(6.35), Cm(7.1), Cm(2.8),
            [("LedgerValueMapper", 12.5, True, GREEN_DARK),
             ("同一个映射器：法定项进列，自定义项进 extra_json，品类名解析成 id",
              10, False, INK)], space_after=6, line_spacing=1.2)

    arrow(s, Cm(23.9), Cm(7.4), Cm(2.5))
    rect(s, Cm(26.7), Cm(6.0), Cm(5.3), Cm(3.4), fill=WHITE, line=GREEN_DARK, radius=0.07)
    textbox(s, Cm(27.1), Cm(6.35), Cm(4.6), Cm(2.8),
            [("同一段判定", 12.5, True, GREEN_DARK),
             ("红/黄/绿 + 待入账", 10, False, INK)], space_after=6, line_spacing=1.2)

    rect(s, Cm(1.8), Cm(13.4), Cm(30.2), Cm(2.6), fill=AMBER_LIGHT, line=AMBER, radius=0.06)
    textbox(s, Cm(2.4), Cm(13.75), Cm(29.2), Cm(2.1),
            [("为什么强调这一点：只有 AI 能录入的台账系统，模型挂了、网断了就没法记账；"
              "而两条路各写一套映射，迟早出现 AI 填的能存、手填的丢了的毛病。",
              11, False, AMBER)], line_spacing=1.25)


def s_arch(d):
    s = d.new("模块组成（一）总体架构：分层",
              "接口层只管参数与权限，判定放在领域层，外部依赖全部收口在基础层")
    bands = [
        ("接入层", "登录拦截 · 统一异常 · 统一响应 { code, message, data }",
         RGBColor(0xE8, 0xEE, 0xF7), INK),
        ("接口层", "8 个 Controller · 29 个接口（台账 / AI 录入 / 供应商 / 统计 / 导出 / 附件 / 模板 / 品类）",
         RGBColor(0xDD, 0xE7, 0xF3), INK),
        ("应用编排层", "EntryService 对话编排 · LedgerService 录入流转查询 · SupplierService · StatsService · ExportService",
         RGBColor(0xE6, 0xF2, 0xE8), GREEN_DARK),
        ("领域规则层", "ComplianceEvaluator 判定 · LedgerStatus 状态机 · LedgerValueMapper · TemplateSchema",
         RGBColor(0xD3, 0xEA, 0xD8), GREEN_DARK),
        ("基础与数据层", "LlmClient（唯一调模型处）· UploadController · MyBatis-Plus Mapper · MySQL 7 张表 · uploads 目录",
         RGBColor(0xEC, 0xEC, 0xEC), INK),
    ]
    y = Cm(3.4)
    for name, desc, fill, color in bands:
        rect(s, Cm(1.8), y, Cm(30.2), Cm(2.3), fill=fill, line=LINE, radius=0.05)
        textbox(s, Cm(2.3), y + Cm(0.52), Cm(5.4), Cm(1.0),
                [(name, 12.5, True, color)], line_spacing=1.0)
        textbox(s, Cm(8.0), y + Cm(0.58), Cm(23.4), Cm(1.2),
                [(desc, 10, False, INK)], line_spacing=1.15)
        y += Cm(2.5)
    textbox(s, Cm(1.8), Cm(16.1), Cm(30), Cm(1.2),
            [("依赖只能单向向下：Controller → Service → 领域/数据。核心域不认识 AI——"
              "LedgerService 不知道记录是 AI 录的还是人填的。", 10.5, False, MUTED)],
            line_spacing=1.15)


def s_backend(d):
    s = d.new("模块组成（二）后端 13 个包 / 64 个类",
              "按职责分四组；每张表只有一个模块写")
    groups = [
        ("基础层", ["common 公共契约", "config 框架配置"], RGBColor(0xEC, 0xEC, 0xEC), Cm(4.4)),
        ("身份与主数据", ["auth 登录会话权限", "staff 人员", "template 模板与字段",
                     "category 品类", "supplier 供应商与证照"],
         RGBColor(0xDD, 0xE7, 0xF3), Cm(8.4)),
        ("核心业务", ["record 台账全生命周期", "（录入 / 判定 /", "状态 / 查询）"],
         RGBColor(0xD3, 0xEA, 0xD8), Cm(6.2)),
        ("应用编排", ["entry AI 对话编排", "llm 模型客户端"], RGBColor(0xE6, 0xF2, 0xE8), Cm(5.2)),
        ("输出层", ["stats 统计聚合", "export Excel 导出", "upload 附件"],
         RGBColor(0xFD, 0xF3, 0xE1), Cm(4.6)),
    ]
    x = Cm(1.7)
    for name, items, fill, w in groups:
        rect(s, x, Cm(3.4), w, Cm(1.2), fill=fill, line=LINE, radius=0.08)
        textbox(s, x, Cm(3.65), w, Cm(0.9),
                [(name, 11.5, True, GREEN_DARK)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        rect(s, x, Cm(4.8), w, Cm(6.6), fill=WHITE, line=LINE, radius=0.05)
        textbox(s, x + Cm(0.25), Cm(5.05), w - Cm(0.5), Cm(6.2),
                [("· " + i, 9.5, False, INK) for i in items], space_after=7, line_spacing=1.2)
        x += w + Cm(0.35)

    rect(s, Cm(1.7), Cm(11.9), Cm(30.2), Cm(4.8), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(2.3), Cm(12.2), Cm(29), Cm(0.9),
            [("record 包里再分四块（读代码按这个顺序）", 12, True, GREEN_DARK)])
    sub = [("数据", "LedgerRecord / Mapper"), ("判定", "ComplianceEvaluator / LedgerQuality"),
           ("状态", "LedgerStatus 状态机"), ("映射", "LedgerValues / LedgerValueMapper")]
    x = Cm(2.3)
    for name, cls in sub:
        rect(s, x, Cm(13.2), Cm(7.0), Cm(3.0), fill=GREEN_LIGHT, line=GREEN, radius=0.08)
        textbox(s, x, Cm(13.5), Cm(7.0), Cm(0.9),
                [(name, 11.5, True, GREEN_DARK)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x + Cm(0.3), Cm(14.4), Cm(6.4), Cm(1.7),
                [(cls, 9, False, INK)], align=PP_ALIGN.CENTER, line_spacing=1.15)
        x += Cm(7.4)


def s_record_inner(d):
    s = d.new("模块组成（三）核心包 record：判定 / 状态 / 映射",
              "台账的一切规则都在这个包里，且判定与状态机不依赖数据库和框架")
    blocks = [
        ("ComplianceEvaluator（158 行）", [
            "输入：值表 + 今天日期；输出：级别 + 缺什么 + 提示",
            "红：缺法定 → 不许入账",
            "黄：缺选填 → 可入账，建议后续补",
            "绿：全齐",
            "纯函数，12 条单元测试直接调它",
        ], RED),
        ("LedgerStatus（状态机）", [
            "待入账 → 已入账 / 退回补录 / 已作废",
            "退回补录 → 待入账（补录后自动回流）",
            "已入账、已作废是终态，只能作废不能回退",
            "退回次数上限 2 次，防止来回踢皮球",
        ], AMBER),
        ("LedgerValueMapper（140 行）", [
            "值表 → 录入请求：法定项进列，自定义项进 extra_json",
            "品类名 → category_id（服务端解析，前端不认 id）",
            "附件 JSON ↔ List；数字容忍「20斤」这类写法",
            "AI 录入与手填共用它，规则只有一份",
        ], GREEN),
        ("LedgerService（570 行）", [
            "录入 / 修改 / 入账 / 退回 / 作废 / 查询 / 分页",
            "乐观锁 version + 修订号 revision",
            "修改用合并语义：null 保留原值",
            "已知问题：读写混在一起，应拆 Command / Query",
        ], BLUE),
    ]
    x = Cm(1.7)
    for title, lines, color in blocks:
        card(s, x, Cm(3.5), Cm(7.55), Cm(11.2), title, lines, accent=color,
             title_size=11, body_size=9.5)
        x += Cm(7.9)


def s_frontend(d):
    s = d.new("模块组成（四）前端：两个端 + 一份共享层",
              "一套依赖、两个入口（MPA），按角色分开界面")
    blocks = [
        ("shared 共享", ["api/http.js 统一解包与 401 跳登录", "api/*.js 按域拆包",
                      "components/LedgerForm 按模板渲染", "components/GradeTag 级别标签",
                      "draft.js 草稿按用户暂存"], GREEN_DARK, Cm(1.8), Cm(9.4)),
        ("user 采购录入端", ["App.vue 三页签 + 角色分流", "EntryView AI 对话",
                         "ManualEntryView 手填", "MyRecordsView 我的记录与补录"],
         GREEN, Cm(12.1), Cm(9.4)),
        ("admin 台账管理端", ["router hash 路由与守卫", "LedgerListView 筛选分页导出",
                          "LedgerDetailView 审核动作", "SupplierListView / StatsView"],
         BLUE, Cm(22.4), Cm(9.6)),
    ]
    for name, items, color, x, w in blocks:
        rect(s, x, Cm(3.4), w, Cm(5.9), fill=WHITE, line=color, radius=0.05)
        rect(s, x, Cm(3.4), w, Cm(1.2), fill=color, radius=0.05)
        textbox(s, x, Cm(3.72), w, Cm(0.9),
                [(name, 12, True, WHITE)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x + Cm(0.35), Cm(4.85), w - Cm(0.7), Cm(4.2),
                [("· " + i, 9.5, False, INK) for i in items], space_after=6, line_spacing=1.2)

    rect(s, Cm(1.8), Cm(9.9), Cm(30.2), Cm(2.4), fill=AMBER_LIGHT, line=AMBER, radius=0.06)
    textbox(s, Cm(2.4), Cm(10.2), Cm(29.2), Cm(1.9),
            [("前端唯一的架构约定：表单字段一律来自 /api/templates/default，前端不写死字段清单。",
              11.5, True, AMBER),
             ("这条约定是被真实缺陷换来的：补录页曾写死 17 个字段，导致产地、冷链温度、农残报告"
              "在界面上根本没有输入框。", 10, False, AMBER)], space_after=6, line_spacing=1.2)

    rect(s, Cm(1.8), Cm(12.7), Cm(14.8), Cm(3.9), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(2.3), Cm(13.0), Cm(13.9), Cm(3.3),
            [("规模", 11.5, True, GREEN_DARK),
             ("前端 24 个文件 / 3129 行", 10, False, INK),
             ("后端 64 个类 / 4541 行", 10, False, INK),
             ("接口 29 个 · 数据表 7 张", 10, False, INK)], space_after=6, line_spacing=1.2)

    rect(s, Cm(17.2), Cm(12.7), Cm(14.8), Cm(3.9), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(17.7), Cm(13.0), Cm(13.9), Cm(3.3),
            [("前端只做三件事", 11.5, True, GREEN_DARK),
             ("① 按模板渲染表单与详情", 10, False, INK),
             ("② 收集输入、提交接口", 10, False, INK),
             ("③ 显示后端算好的级别与状态", 10, False, INK)], space_after=6, line_spacing=1.2)


def s_shot_drawer(d):
    s = d.new("系统实拍（三）补录界面：按模板渲染，字段一个不少",
              "每一栏下面还带「依据：原文」，能追问这条数据是从哪句话来的")
    pic(s, "05-采购端-补录抽屉.png", Cm(1.8), Cm(3.4), Cm(19.6))
    x = Cm(22.2)
    textbox(s, x, Cm(3.5), Cm(10), Cm(1.0),
            [("界面上能看到什么", 12.5, True, GREEN_DARK)])
    points = [
        ("合规提示条", "「待补证：缺 1 项选填（不影响入账，可后续补）」"),
        ("法定项目分组", "12 项带红色星号，缺一项就不许入账"),
        ("扩展字段分组", "产地、冷链到货温度、农残报告号——按模板动态渲染"),
        ("字段级依据", "每个字段下方标出原文，AI 抽取可追溯"),
        ("唯一入口", "补录与录入共用同一个表单组件"),
    ]
    y = Cm(4.6)
    for name, desc in points:
        rect(s, x, y, Cm(10), Cm(2.2), fill=WHITE, line=LINE, radius=0.08)
        textbox(s, x + Cm(0.35), y + Cm(0.28), Cm(9.3), Cm(0.8),
                [(name, 10.5, True, GREEN_DARK)], line_spacing=1.0)
        textbox(s, x + Cm(0.35), y + Cm(1.1), Cm(9.3), Cm(1.0),
                [(desc, 9, False, MUTED)], line_spacing=1.15)
        y += Cm(2.4)


def s_shot_manual(d):
    s = d.new("系统实拍（四）手填表单与我的记录",
              "AI 之外的常规路径：按模板填表；录过的记录按状态分类，被退回的在这里补")
    w = Cm(14.5)
    label(s, Cm(1.8), Cm(3.35), "手填：法定项目分组 + 扩展字段分组 + 凭证照片上传",
          10.5, AMBER, True, PP_ALIGN.LEFT, Cm(15))
    pic(s, "03-采购端-手填.png", Cm(1.8), Cm(4.1), w)
    label(s, Cm(17.6), Cm(3.35), "我的记录：只看自己的，含被退回待补录的",
          10.5, GREEN_DARK, True, PP_ALIGN.LEFT, Cm(15))
    pic(s, "04-采购端-我的记录.png", Cm(17.6), Cm(4.1), w)
    label(s, Cm(1.8), Cm(13.4),
          "字段、必填、可选值全部来自模板接口，前端不写死字段清单", 9.5, MUTED,
          False, PP_ALIGN.LEFT, Cm(15))
    label(s, Cm(17.6), Cm(13.4),
          "状态列区分待入账 / 已入账 / 退回补录；退回的记录会带说明", 9.5, MUTED,
          False, PP_ALIGN.LEFT, Cm(15))


def s_data(d):
    s = d.new("模块组成（五）数据模型：7 张表，各归一个模块写",
              "法定项目建列，单位自定义字段进 extra_json")
    tables = [
        ("staff", "人员与角色", "staff"),
        ("supplier", "供应商 + 证照到期日", "supplier"),
        ("ingredient_category", "品类主数据（8 项）", "category"),
        ("ledger_template", "模板与字段定义", "template"),
        ("ledger_record", "台账主表（45 列）", "record"),
        ("entry_session", "AI 一次录入会话", "entry"),
        ("entry_message", "对话原文（依据来源）", "entry"),
    ]
    y = Cm(3.4)
    for name, desc, owner in tables:
        rect(s, Cm(1.8), y, Cm(15.6), Cm(1.55), fill=WHITE, line=LINE, radius=0.08)
        textbox(s, Cm(2.2), y + Cm(0.4), Cm(6.4), Cm(0.9),
                [(name, 10.5, True, GREEN_DARK)], line_spacing=1.0)
        textbox(s, Cm(8.6), y + Cm(0.43), Cm(5.0), Cm(0.9),
                [(desc, 9.5, False, MUTED)], line_spacing=1.0)
        textbox(s, Cm(13.9), y + Cm(0.43), Cm(3.2), Cm(0.9),
                [(owner, 9, False, GREEN)], align=PP_ALIGN.RIGHT, line_spacing=1.0)
        y += Cm(1.75)

    rect(s, Cm(18.4), Cm(3.4), Cm(13.8), Cm(5.9), fill=GREEN_LIGHT, line=GREEN, radius=0.05)
    textbox(s, Cm(18.9), Cm(3.7), Cm(12.8), Cm(5.3),
            [("ledger_record 里有什么", 12, True, GREEN_DARK),
             ("· 12 项法定项目 → 独立列（可筛可汇总可导出）", 10, False, INK),
             ("· 9 项自定义字段 → extra_json（模板声明）", 10, False, INK),
             ("· 判定结果 quality_level / quality_detail", 10, False, INK),
             ("· 溯源 basis、source_type、session_id", 10, False, INK),
             ("· 乐观锁 version、修订号 revision、最后修改人", 10, False, INK),
             ("· schema_snapshot：记录自己的模板快照", 10, False, INK)],
            space_after=5, line_spacing=1.2)

    rect(s, Cm(18.4), Cm(9.7), Cm(13.8), Cm(6.3), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(18.9), Cm(10.0), Cm(12.8), Cm(5.7),
            [("为什么带 schema_snapshot", 12, True, AMBER),
             ("模板会改（我们改过 4 版：补到期日、补品类…）。历史记录必须按当时那版模板判定，"
              "否则今天加一个必填字段，昨天的记录会集体变成不合规。", 10, False, INK)],
            space_after=8, line_spacing=1.25)


def s_mapping(d):
    s = d.new("模块组成（六）模块 ↔ 功能 ↔ 接口对照",
              "一张表看清「看到的按钮」与「背后的包和接口」")
    rows = [
        ("登录与权限", "auth · staff", "POST /api/auth/login、GET /api/auth/me", "两个角色、越权拦截"),
        ("AI 录入", "entry · llm", "POST /api/entry/sessions/{id}/messages", "抽取、追问、确认、凭 attaches"),
        ("手填录入", "record · template", "POST /api/ledger/values", "模板渲染 + 同一段判定"),
        ("台账查询", "record", "GET /api/ledger/page、GET /api/ledger/trace", "12 种筛选组合、分页、批号追溯"),
        ("审核流转", "record", "POST /api/ledger/{id}/post|return|void", "入账 / 退回 / 作废，带乐观锁"),
        ("供应商证照", "supplier", "GET/POST /api/suppliers", "到期天数与预警"),
        ("统计", "stats", "GET /api/stats/overview", "四张卡片 + 四张明细表"),
        ("导出", "export", "GET /api/ledger/export", "复用筛选，23 列 xlsx"),
        ("附件", "upload", "POST /api/uploads", "白名单、大小、UUID 文件名"),
        ("模板", "template", "GET /api/templates/default", "前端表单与 AI 提示词的唯一来源"),
    ]
    y = Cm(3.35)
    widths = [Cm(5.4), Cm(5.6), Cm(11.6), Cm(7.6)]
    heads = ["功能", "后端模块", "主要接口", "要点"]
    x = Cm(1.8)
    rect(s, Cm(1.8), y, Cm(30.2), Cm(1.15), fill=GREEN, radius=0.05)
    for i, h in enumerate(heads):
        textbox(s, x, y + Cm(0.3), widths[i], Cm(0.8),
                [(h, 10.5, True, WHITE)], line_spacing=1.0)
        x += widths[i]
    y += Cm(1.25)
    for i, row in enumerate(rows):
        h = Cm(1.28)
        rect(s, Cm(1.8), y, Cm(30.2), h, fill=WHITE if i % 2 == 0 else RGBColor(0xF2, 0xF7, 0xF2),
             line=LINE, radius=0.02)
        x = Cm(1.8)
        for j, cell in enumerate(row):
            textbox(s, x, y + Cm(0.28), widths[j], Cm(0.9),
                    [(cell, 9, j == 0, GREEN_DARK if j == 0 else INK)], line_spacing=1.0)
            x += widths[j]
        y += h


def s_shot_detail(d):
    s = d.new("系统实拍（五）台账详情：字段级依据 + 审核动作",
              "管理员在同一页看到合规结论、全部字段、原文依据、凭证照片，然后决定入账或退回")
    pic(s, "07-管理端-台账详情.png", Cm(1.8), Cm(3.4), Cm(19.6))
    x = Cm(22.2)
    textbox(s, x, Cm(3.5), Cm(10), Cm(1.0),
            [("这一页回答四个问题", 12.5, True, GREEN_DARK)])
    qa = [
        ("合不合规", "顶部绿条「记录完整 · 法定项目齐全」"),
        ("够不够入账", "级别 + 状态并排，红色时入账按钮直接禁用"),
        ("数据从哪来", "每个字段下方「依据：原文」，会话号可回溯"),
        ("凭证呢", "底部「索证索票附件」，图片可点开放大"),
    ]
    y = Cm(4.6)
    for name, desc in qa:
        rect(s, x, y, Cm(10), Cm(2.5), fill=WHITE, line=LINE, radius=0.08)
        textbox(s, x + Cm(0.35), y + Cm(0.3), Cm(9.3), Cm(0.8),
                [(name, 10.5, True, GREEN_DARK)], line_spacing=1.0)
        textbox(s, x + Cm(0.35), y + Cm(1.15), Cm(9.3), Cm(1.2),
                [(desc, 9, False, MUTED)], line_spacing=1.15)
        y += Cm(2.7)


def s_shot_stats(d):
    s = d.new("系统实拍（六）统计与证照预警",
              "统计口径与证照到期都直接来自数据库，不需要另做报表")
    w = Cm(14.5)
    label(s, Cm(1.8), Cm(3.35), "统计：记录数 / 采购金额 / 合规分布 / 临期过期", 10.5,
          GREEN_DARK, True, PP_ALIGN.LEFT, Cm(15))
    pic(s, "08-管理端-统计.png", Cm(1.8), Cm(4.1), w)
    label(s, Cm(17.6), Cm(3.35), "供应商与证照：剩 N 天 / 已过期，浅红高亮", 10.5,
          AMBER, True, PP_ALIGN.LEFT, Cm(15))
    pic(s, "09-管理端-供应商与证照.png", Cm(17.6), Cm(4.1), w)
    label(s, Cm(1.8), Cm(13.4), "总金额不含已作废；已入账金额单独一列，避免月初虚高",
          9.5, MUTED, False, PP_ALIGN.LEFT, Cm(15))
    label(s, Cm(17.6), Cm(13.4), "证照 30 天内到期即预警——食堂检查最先看这一项",
          9.5, MUTED, False, PP_ALIGN.LEFT, Cm(15))


def s_decisions(d):
    s = d.new("关键设计取舍", "五条，每条都是当时可以那么做、但我们选了这么做")
    rows = [
        ("法定项目建列，自定义字段进 JSON",
         "法律固定 → schema 固定；建列才谈得上按日期筛、按供应商汇总、按批号追溯"),
        ("判定在代码，不交给模型",
         "确定性规则不能交给概率系统：同一笔采购不能两次判出两个级别"),
        ("AI 只能产出待入账",
         "入账是管理员的责任。AI 负责省打字，不负责决定这笔算不算记账"),
        ("模板是字段的唯一来源",
         "前端表单、AI 提示词、补录界面、导出列共用一份；否则必出现有列但录不进去"),
        ("不物理删除，只能作废",
         "台账是检查依据：这条记错了和这条不存在，是两件事"),
    ]
    y = Cm(3.4)
    for i, (title, why) in enumerate(rows, start=1):
        rect(s, Cm(1.8), y, Cm(30.2), Cm(2.6), fill=WHITE, line=LINE, radius=0.08)
        rect(s, Cm(1.8), y, Cm(1.3), Cm(2.6), fill=GREEN, shape=MSO_SHAPE.RECTANGLE)
        textbox(s, Cm(1.85), y + Cm(0.88), Cm(1.2), Cm(0.9),
                [(str(i), 13, True, WHITE)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, Cm(3.5), y + Cm(0.38), Cm(12.2), Cm(1.9),
                [(title, 11.5, True, INK)], line_spacing=1.15)
        textbox(s, Cm(16.0), y + Cm(0.38), Cm(15.4), Cm(1.9),
                [(why, 10, False, MUTED)], line_spacing=1.2)
        y += Cm(2.8)


def s_results(d):
    s = d.new("成果与验证", "不只是能跑：有接口回归、界面点击、手工用例与抽取评测")
    feats = [
        ("录入", ["AI 一句话录入（追问/确认）", "手填（模板驱动）", "凭证照片上传", "草稿暂存"]),
        ("审核", ["入账 / 退回 / 作废", "退回后自动回流", "乐观锁防覆盖", "按批号追溯"]),
        ("查证", ["多条件组合筛选", "分页", "导出 Excel", "按月/供应商/品类统计"]),
        ("主数据", ["供应商与证照预警", "8 个品类", "模板可扩字段", "两种角色权限"]),
    ]
    x = Cm(1.8)
    for name, items in feats:
        rect(s, x, Cm(3.4), Cm(7.3), Cm(4.0), fill=WHITE, line=LINE, radius=0.06)
        textbox(s, x, Cm(3.65), Cm(7.3), Cm(0.9),
                [(name, 12, True, GREEN_DARK)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x + Cm(0.35), Cm(4.65), Cm(6.6), Cm(2.6),
                [("· " + i, 9.5, False, INK) for i in items], space_after=4, line_spacing=1.15)
        x += Cm(7.7)

    metrics = [("29", "后端接口"), ("7", "数据表"), ("133", "手工用例"),
               ("62", "接口回归"), ("20", "界面点击"), ("12", "单元测试")]
    x = Cm(1.8)
    for num, name in metrics:
        rect(s, x, Cm(7.8), Cm(4.85), Cm(2.3), fill=GREEN_LIGHT, line=GREEN, radius=0.06)
        textbox(s, x, Cm(8.02), Cm(4.85), Cm(1.0),
                [(num, 20, True, GREEN_DARK)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        textbox(s, x, Cm(9.1), Cm(4.85), Cm(0.8),
                [(name, 9.5, False, INK)], align=PP_ALIGN.CENTER, line_spacing=1.0)
        x += Cm(5.08)

    rect(s, Cm(1.8), Cm(10.6), Cm(30.2), Cm(5.7), fill=WHITE, line=LINE, radius=0.05)
    textbox(s, Cm(2.4), Cm(10.9), Cm(29.2), Cm(0.9),
            [("AI 抽取的定量评测（16 条自建口述用例）", 12, True, GREEN_DARK)])
    textbox(s, Cm(2.4), Cm(11.95), Cm(13.8), Cm(3.9),
            [("字段准确率 100%（82/82）", 10.5, False, INK),
             ("有值召回率 100%（67/67）", 10.5, False, INK),
             ("瞎填率 0%（0/15）", 10.5, False, INK),
             ("级别一致率 100%（16/16）", 10.5, False, INK)], space_after=5, line_spacing=1.2)
    textbox(s, Cm(16.6), Cm(11.95), Cm(14.8), Cm(3.9),
            [("依据编造率 3.7%（5/135，被校验丢弃）", 10.5, False, INK),
             ("说明：样本仅 16 条且与提示词同源，正确说法是", 10, False, MUTED),
             ("「在这 16 条上 100%」，不是「准确率 100%」。", 10, True, AMBER),
             ("编造率在 temperature=0 下仍会浮动（1.5%~3.7%），所以忠实性校验必须留在链路上。",
              9.5, False, MUTED)], space_after=5, line_spacing=1.2)


def s_end(d):
    s = d.prs.slides.add_slide(d.prs.slide_layouts[6])
    d.n += 1
    rect(s, 0, 0, W, H, fill=GREEN_DARK, shape=MSO_SHAPE.RECTANGLE)
    textbox(s, Cm(3.2), Cm(6.2), Cm(28), Cm(2.4),
            [("谢谢，请老师指正", 34, True, WHITE)], line_spacing=1.0)
    textbox(s, Cm(3.2), Cm(9.3), Cm(28), Cm(2.6),
            [("核心问题只有一句：怎么让食堂的一笔采购，", 13, False,
              RGBColor(0xC8, 0xE6, 0xD0)),
             ("既能被检查认可，又能被拿来算账。", 13, False, RGBColor(0xC8, 0xE6, 0xD0))],
            space_after=6, line_spacing=1.3)
    rect(s, Cm(3.2), Cm(12.4), Cm(9), Cm(0.1), fill=GREEN, shape=MSO_SHAPE.RECTANGLE)
    textbox(s, Cm(3.2), Cm(12.9), Cm(26), Cm(1.0),
            [("相关文档：docs/升级方案.md · docs/模块划分.md · docs/测试数据.md", 9.5,
              False, RGBColor(0x8F, 0xBF, 0x9C))], line_spacing=1.0)


def main():
    missing = [n for n in os.listdir(SHOTS) if n.endswith('.png')] if os.path.isdir(SHOTS) else []
    if len(missing) < 9:
        raise SystemExit("截图不足（当前 %d 张），先跑 node qa/capture-shots.mjs" % len(missing))

    d = Deck()
    s_cover(d)
    s_agenda(d)
    s_bg_problem(d)
    s_bg_law(d)
    s_goal(d)
    s_shot_two_ends(d)
    s_lifecycle(d)
    s_compliance(d)
    s_ai_flow(d)
    s_shot_ai(d)
    s_two_paths(d)
    s_arch(d)
    s_backend(d)
    s_record_inner(d)
    s_frontend(d)
    s_shot_drawer(d)
    s_shot_manual(d)
    s_data(d)
    s_mapping(d)
    s_shot_detail(d)
    s_shot_stats(d)
    s_decisions(d)
    s_results(d)
    s_end(d)

    # 逐页标出主讲人（封面与结束页不标）
    for idx, slide in enumerate(d.prs.slides, start=1):
        who = SPEAKERS.get(idx)
        if who and who != "全员":
            stamp_speaker(slide, who)

    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    # 文件可能正被 PowerPoint / WPS 打开（PermissionError）。
    # 依次换候选名，直到能写：不要求人为了重新生成 PPT 先去关软件。
    base, ext = os.path.splitext(OUT)
    candidates = [OUT, base + "-new" + ext] + ["%s-v%d%s" % (base, i, ext) for i in range(2, 20)]
    target = None
    for cand in candidates:
        try:
            d.save(cand)
            target = cand
            break
        except PermissionError:
            continue
    if target is None:
        raise SystemExit("所有候选文件名都被占用，请关掉已打开的 PPT 后重试")
    if os.path.abspath(target) != os.path.abspath(OUT):
        print("提示：%s 被占用（可能正开着），本次写到：%s"
              % (os.path.basename(OUT), os.path.basename(target)))
    print("已生成：%s" % target)
    print("  共 %d 页，其中 6 页界面实拍（10 张截图）" % len(d.prs.slides._sldIdLst))


if __name__ == "__main__":
    main()
