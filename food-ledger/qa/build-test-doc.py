# -*- coding: utf-8 -*-
"""【已归档】项目进入升级阶段后，docs/测试文档.md 与 .docx 已删除（不再作为交付物）。
本脚本保留，仅用于需要时重新生成那份文档；用例的**唯一数据源**仍是 qa/build-testcases.py。


生成《食材采购台账系统 · 测试文档》(docx)。

用例数据**不在这里重复写**：从 qa/build-testcases.py 导入 CASES，
与 Excel 版共用同一份定义。改了用例只改那一个文件，两边一起更新。

用法：
    python qa/build-test-doc.py
输出：
    docs/测试文档.docx
"""

import datetime
import importlib.util
import os

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "测试文档.docx")

# ---------------------------------------------------------------- 用例来源

spec = importlib.util.spec_from_file_location(
    "cases_builder", os.path.join(ROOT, "qa", "build-testcases.py"))
cases_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cases_mod)
CASES = cases_mod.CASES

MODULES = []
for row in CASES:
    if row[1] not in MODULES:
        MODULES.append(row[1])

# 模块说明：文档里要讲清「这一块测什么、为什么单独成块」
MODULE_SCOPE = {
    "A 登录与权限": "两种角色能做什么、越权是否被拦住。登录是共用入口，权限只能靠后端拦。",
    "B 录入端 · AI 记一笔": "AI 抽取的完整链路：说全直接出单、说不全追问、追问到上限转确认、确认才落库。",
    "C 录入端 · 手填": "不依赖模型的传统录入。字段由模板渲染，合规判定与 AI 录入完全同一条路。",
    "D 录入端 · 我的记录与补录": "采购员只能看自己的记录；被退回后能补录并重新进入待审核。",
    "E 管理端 · 台账列表": "查询与筛选的组合、分页边界、只看临期。列表是管理员的主要工作面。",
    "F 管理端 · 详情与审核": "入账/退回/作废的流转规则、合规拦截、并发冲突、批次追溯、附件查看。",
    "G 管理端 · 供应商": "供应商主数据与证照到期预警——食堂检查必看证照是否在有效期内。",
    "H 管理端 · 统计": "金额口径（不含作废）、合规分布自洽、临期明细。",
    "I 导出与附件": "导出文件的版式与筛选一致性；索证索票照片的上传与展示。",
    "J 异常与边界": "网络中断、会话过期、特殊字符、数量为 0 或负数、重复提交等非主干路径。",
    "K 端到端场景": "跨角色、跨模块的完整闭环，用来发现单模块测试看不到的流程断点。",
    "L 安全 · 提示词攻击": "AI 入口的稳定性：指令覆盖、伪造依据、越权读取、绕过合规校验等。",
    "M 安全 · SQL 注入": "登录框、筛选框、路径参数上的经典注入载荷。系统全参数化，目的是证明其无害。",
    "N 本轮人工反馈复测": "人工测试提出的问题逐条复测。每轮反馈都进这个模块，保留「反馈—修复—复测」的对应关系。",
    "O 安装与部署": "从零安装能否一次跑起来；种子数据重跑是否幂等；建表脚本是否与代码脱节。",
}

# 缺陷记录：本轮测试中发现并修复的问题
DEFECTS = [
    ("D-01", "修改台账时只提交部分字段，其余字段被清空——只改一个备注，9 项法定项目全没了，"
             "级别从「记录完整」掉成「不合规」并因此无法入账",
     "更新用整体替换，请求里为 null 的字段也写回库",
     "改为合并语义：null 保留原值，要清空须显式传空字符串", "TC2-12"),
    ("D-02", "填了到期日，系统仍提示「未填写到期日」，临期与过期预警永远不触发",
     "expiry_date 有数据库列，但模板字段清单里没有它，按模板遍历的映射填不进去",
     "模板补 expiry_date 字段（模板版本 1→2）", "TC3-04、TC3-05"),
    ("D-03", "索证索票照片上传成功、URL 也能访问，但台账记录的附件始终为空",
     "映射只遍历模板字段，attachments 有列但不在模板里，被当成自定义字段写进 extra_json",
     "映射改为按「值的 key」分发，不再只认模板字段", "TC8-04"),
    ("D-04", "管理端按「肉类」筛选时，名称是「鸡肉」的记录筛不出来；实际上所有界面录入的记录品类都为空",
     "模板缺 category 字段（品类录不进去）；且合规判定从值表取 category 也取不到，"
     "导致品类永远算缺失、记录永远绿不了",
     "模板补 category（v2→v3）；服务端把品类名解析成 category_id；判定前把品类补进值表",
     "TC10-01~TC10-04、N-04~N-06"),
    ("D-05", "管理员登录后落在采购员的默认页；采购员登录管理端则满屏按钮一点就 403",
     "登录入口共用，未按角色分流",
     "登录时按角色跳转；录入端给管理员一条明确提示；管理端把非管理员送回录入端", "N-01~N-03"),
    ("D-06", "筛选条件改完必须点「刷新」才生效",
     "筛选控件只有回车与按钮触发",
     "加 350ms 防抖 watcher，条件变化自动刷新并回到第 1 页（保留刷新按钮）", "N-07"),
    ("D-07", "上传的图片在页面里显示不出来（直连后端同一个 URL 又是好的）",
     "前端只代理了 /api，<img src=\"/uploads/...\"> 被 vite 当成前端路由，返回 index.html",
     "前端代理补 /uploads；README 注明生产环境需由同一静态服务提供", "N-09"),
    ("D-08", "采购员补录后状态仍是「退回补录」，管理员看不出这条已经补好、可以再审——记录永远卡住",
     "更新时不改变状态，退回后没有回流路径",
     "补录后自动由「退回补录」回到「待入账」，重新进入待审核队列", "TC10-05、N-10"),
    ("D-09", "会话 cookie 失效后，页面停在点不动的状态，不跳登录页",
     "只改 location.hash（录入端没有路由，改了什么都不发生），且管理端守卫缓存未清",
     "整页跳转清内存态；排除 /auth/* 的 401（否则密码错误也会重载页面）", "N-11、N-12"),
    ("D-10", "从零安装时 data.sql 报 Duplicate entry；重跑种子会插出第二份同名模板",
     "data.sql 写死 USE food_ledger（演练时切回生产库）；ledger_template 无唯一键",
     "删掉 USE，库名由命令行指定；加 uk_template_name + INSERT IGNORE 使其幂等",
     "O-01~O-03"),
    ("D-11", "手填提交后点结果卡上的「去「我的记录」」没有任何反应，页面停在手填页",
     "按钮 emit 的是 recorded，而父组件收到 recorded 只刷新列表、不切页签——"
     "按钮在那儿，行为没连上",
     "新增 go-mine 事件：刷新键 +1 并切到「我的记录」页签；AI 录入端补同样的入口",
     "N-13、N-14"),
    ("D-12", "对话里传完凭证、其他项也齐了，AI 回「确认一下就记账？」，"
             "但点确认被后端拒绝——采购员卡在一句问话前面，无处可点",
     "上传后走的是 ASK 分支，而 ASK 不会把会话置为 AWAITING_CONFIRM，"
     "confirm 接口因此拒绝",
     "信息齐全时直接出单（与「说全了就直接记账」的约定一致），不再多问一句",
     "B-11、N-15"),
    ("D-13", "管理员录了一条记录、退回给采购员补录，采购员的「我的记录」里看不到这条——"
             "那条记录谁都补不了，流程卡死",
     "我的记录只按 createdBy 过滤，而这条的 createdBy 是管理员",
     "加 scope=MINE：我录的 + 退回给我补的（不论谁录）；"
     "明确筛了状态时仍按状态走，避免筛「已入账」混进退回记录",
     "N-16"),
    ("D-14", "补录界面没有「产地 / 冷链到货温度 / 农残检测报告号」的输入框，"
             "采购员永远补不到「记录完整」；反复保存状态也不变，像是系统卡住",
     "补录页写死了一份 17 项字段清单，漏了品类与这三个自定义字段；"
     "「扩展字段」又只渲染记录里已经存在的键——没填过的字段连框都没有",
     "补录界面改为按模板渲染（复用 LedgerForm）；新增 PUT /api/ledger/{id}/values，"
     "前端提交「模板 key → 值」，与手填创建对称",
     "N-17、UI-15~UI-17"),
    ("D-15", "「记一笔」说到一半、或「手填」填了一半，切到别的页签再回来内容全没了，"
             "刷新页面同样清空——录入经常被电话打断，等于每次都要重打",
     "两个录入页的状态只存在组件内存里，App.vue 用 v-if 切页签会销毁组件，刷新则整体重挂载",
     "草稿按用户存进 sessionStorage（不落 localStorage：共享终端不把上一个人的录入留给下一个人），"
     "进页面时恢复，提交成功/点清空/退出登录时清除",
     "N-19、N-20、UI-18~UI-20"),
]

LESSONS = [
    ("安全探针第一版全部无效",
     "提示词攻击探针只发了攻击文本，模型抽不到任何字段，10 条全部落在「追问」分支，"
     "一次也没走到写入路径。改成「完整采购描述 + 攻击附加句」后，9 条真正生成了记录，"
     "才测到「值被污染」这条真正的风险路径。",
     "探针必须能走到被测代码路径，否则「全部通过」是假象。"),
    ("拿「有没有抛异常」判断鉴权",
     "验证脚本里未登录请求没有抛异常，于是被判定为「鉴权失效」；实际后端业务错误统一是 "
     "HTTP 200 + body.code，401 也在 body 里。",
     "先确认错误契约（状态码还是业务码），再写断言。"),
    ("僵尸进程污染验证结果",
     "上一次安装验证遗留的 java 进程仍占着 8082，且连着已被删除的临时库，"
     "第二次验证报「登录失败」，看着像种子数据坏了。",
     "按端口清理进程，不要按镜像名杀；spring-boot:run 会 fork 子进程。"),
    ("模板新增字段会连带打破既有断言",
     "模板加 category 后，complete_values 少一个字段，「全齐→GREEN」的 3 条用例集体变红。",
     "这是好事：说明判定逻辑覆盖到了新字段。测试夹具要跟着模板走。"),
    ("「连线」类问题只能靠点",
     "手填结果卡的「去「我的记录」」按钮点下去毫无反应：组件 emit 的事件名和父组件监听的名字"
     "都在，只是那个事件对应的处理函数只刷新列表、不切页签。构建通过、代码读着也对。"
     "同类问题这一轮出现了两次（另一次是筛选不自动刷新）。",
     "凡是「点了之后状态该变」的地方，都要真点一次；"
     "为此加了 qa/ui-click-test.mjs，用 CDP 驱动浏览器点击并断言页面结果。"),
]

# ---------------------------------------------------------------- 样式工具


def set_run_font(run, name="宋体", size=None, bold=None, color=None):
    run.font.name = name
    run._element.rPr.rFonts.set(qn("w:eastAsia"), name)
    if size:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color:
        run.font.color.rgb = color


def shade(cell, hex_color):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), hex_color)
    tcPr.append(shd)


def repeat_header(row):
    trPr = row._tr.get_or_add_trPr()
    el = OxmlElement("w:tblHeader")
    el.set(qn("w:val"), "true")
    trPr.append(el)


def cell_text(cell, text, size=9, bold=False, color=None, align=None):
    cell.text = ""
    p = cell.paragraphs[0]
    if align:
        p.alignment = align
    p.paragraph_format.space_before = Pt(1)
    p.paragraph_format.space_after = Pt(1)
    for i, line in enumerate(str(text).split("\n")):
        if i > 0:
            p = cell.add_paragraph()
            p.paragraph_format.space_before = Pt(0)
            p.paragraph_format.space_after = Pt(1)
        run = p.add_run(line)
        set_run_font(run, size=size, bold=bold, color=color)


# A4 纵向、左右各 2.2/2.0cm 页边距时，可用宽度约 16.8cm。
# 表格列宽必须留余量：一旦合计超过可用宽度，Word/LibreOffice 会把整个内容区撑开，
# 表现为封面标题被裁、居中的东西整体右移——不是「字太大」，是表格太宽。
MAX_TABLE_W = 16.4


def fit_widths(widths_cm):
    """只在超宽时等比收缩，窄表保持原样（封面信息表就不该被拉宽）。"""
    total = sum(widths_cm)
    if total <= MAX_TABLE_W:
        return list(widths_cm)
    factor = MAX_TABLE_W / total
    return [w * factor for w in widths_cm]


def set_fixed_layout(table):
    tblPr = table._tbl.tblPr
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tblPr.append(layout)


def fix_all_tables(doc):
    """收尾统一列宽：用表头行的宽度覆盖所有行，并重写 tblGrid。

    各表在填行时写过绝对列宽（未收缩），这里统一纠正，
    避免出现「表头窄、内容行宽」的错位。
    """
    for table in doc.tables:
        if not table.rows:
            continue
        widths = [cell.width for cell in table.rows[0].cells]
        if all(w is None for w in widths):
            continue
        tmpl = [w.cm if w is not None else 3.0 for w in widths]
        tmpl = fit_widths(tmpl)
        for row in table.rows:
            for i, cell in enumerate(row.cells):
                if i < len(tmpl):
                    cell.width = Cm(tmpl[i])
        # 重写 tblGrid，否则部分渲染器仍按旧网格排版
        grid = table._tbl.find(qn("w:tblGrid"))
        if grid is not None:
            for i, col in enumerate(grid.findall(qn("w:gridCol"))):
                if i < len(tmpl):
                    col.set(qn("w:w"), str(int(tmpl[i] * 567)))


def make_table(doc, headers, widths_cm, font=9):
    widths_cm = fit_widths(widths_cm)
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_fixed_layout(table)
    hdr = table.rows[0]
    for i, h in enumerate(headers):
        cell = hdr.cells[i]
        cell.width = Cm(widths_cm[i])
        cell_text(cell, h, size=font, bold=True, color=RGBColor(0xFF, 0xFF, 0xFF))
        shade(cell, "2E7D32")
    repeat_header(hdr)
    return table


def add_case_table(doc, cases):
    """4 列：编号 / 级别 / 标题 / 内容（前置条件、操作步骤、测试数据、预期结果）。"""
    table = make_table(doc, ["编号", "级别", "用例标题", "前置条件 · 操作步骤 · 测试数据 · 预期结果"],
                       [1.5, 1.1, 3.4, 11.0])
    for cid, _mod, level, title, pre, steps, data, expect in cases:
        row = table.add_row()
        cells = row.cells
        cells[0].width = Cm(1.5)
        cells[1].width = Cm(1.1)
        cells[2].width = Cm(3.4)
        cells[3].width = Cm(11.0)
        cell_text(cells[0], cid, bold=True)
        cell_text(cells[1], level)
        cell_text(cells[2], title)
        # 内容列：带标签的多段
        cells[3].text = ""
        first = True
        for label, value in (("前置条件：", pre), ("操作步骤：", steps),
                             ("测试数据：", data), ("预期结果：", expect)):
            p = cells[3].paragraphs[0] if first else cells[3].add_paragraph()
            first = False
            p.paragraph_format.space_before = Pt(0)
            p.paragraph_format.space_after = Pt(1)
            r1 = p.add_run(label)
            set_run_font(r1, size=8.5, bold=True)
            r2 = p.add_run(value)
            set_run_font(r2, size=8.5)
        if level == "P0":
            shade(cells[1], "FDECEC")
        elif level == "P1":
            shade(cells[1], "FFF7E6")
    doc.add_paragraph()


def add_para(doc, text, size=10.5, bold=False, indent=False, space_after=6, color=None):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(space_after)
    p.paragraph_format.line_spacing = 1.4
    if indent:
        p.paragraph_format.first_line_indent = Cm(0.74)
    run = p.add_run(text)
    set_run_font(run, size=size, bold=bold, color=color)
    return p


def add_heading(doc, text, level=1):
    h = doc.add_heading("", level=level)
    run = h.add_run(text)
    set_run_font(run, name="黑体", size={0: 22, 1: 15, 2: 12.5, 3: 11}[level], bold=True,
                 color=RGBColor(0x1F, 0x3B, 0x2E) if level else RGBColor(0x11, 0x11, 0x11))
    h.paragraph_format.space_before = Pt(10 if level <= 1 else 6)
    h.paragraph_format.space_after = Pt(5)
    return h


def add_footer(section, text):
    p = section.footer.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text + "    第 ")
    set_run_font(run, size=8.5, color=RGBColor(0x77, 0x77, 0x77))
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    p._p.append(fld)
    run2 = p.add_run(" 页")
    set_run_font(run2, size=8.5, color=RGBColor(0x77, 0x77, 0x77))


# ---------------------------------------------------------------- 文档正文


def build():
    doc = Document()

    style = doc.styles["Normal"]
    style.font.name = "宋体"
    style.font.size = Pt(10.5)
    style.element.rPr.rFonts.set(qn("w:eastAsia"), "宋体")

    section = doc.sections[0]
    section.page_width = Cm(21)
    section.page_height = Cm(29.7)
    section.top_margin = Cm(2.2)
    section.bottom_margin = Cm(2.0)
    section.left_margin = Cm(2.2)
    section.right_margin = Cm(2.0)
    add_footer(section, "食材采购台账系统 · 测试文档")

    # ---------- 封面 ----------
    for _ in range(5):
        doc.add_paragraph()
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run("食材采购台账系统")
    set_run_font(r, name="黑体", size=26, bold=True, color=RGBColor(0x1F, 0x3B, 0x2E))

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run("测 试 文 档")
    set_run_font(r, name="黑体", size=22, bold=True)

    doc.add_paragraph()
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run("模块划分 · 用例明细 · 缺陷记录 · 执行说明")
    set_run_font(r, size=11, color=RGBColor(0x55, 0x66, 0x55))

    for _ in range(6):
        doc.add_paragraph()

    today = datetime.date.today().strftime("%Y 年 %m 月 %d 日")
    info = [
        ("文档版本", "V1.0"),
        ("配套执行表", "docs/测试用例-手工执行版.xlsx"),
        ("用例总数", "%d 条（P0 %d 条）" % (
            len(CASES), len([c for c in CASES if c[2] == "P0"]))),
        ("覆盖模块", "%d 个" % len(MODULES)),
        ("编写日期", today),
    ]
    t = doc.add_table(rows=0, cols=2)
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    for k, v in info:
        row = t.add_row()
        row.cells[0].width = Cm(4.2)
        row.cells[1].width = Cm(8.0)
        cell_text(row.cells[0], k, size=10.5, bold=True)
        cell_text(row.cells[1], v, size=10.5)
        for c in row.cells:
            c.paragraphs[0].paragraph_format.space_before = Pt(3)
            c.paragraphs[0].paragraph_format.space_after = Pt(3)

    doc.add_page_break()

    # ---------- 目录（静态） ----------
    add_heading(doc, "目　录", 1)
    toc = [
        ("一", "文档说明", "本文档的用途、读者与配套文件"),
        ("二", "被测系统与测试范围", "系统构成、模块边界、本轮测什么"),
        ("三", "测试环境与数据准备", "软硬件、启动命令、账号、数据造法与清理"),
        ("四", "测试模块划分", "15 个模块及各自的覆盖点与用例分布"),
        ("五", "优先级、判定标准与缺陷分级", "P0/P1/P2、通过判定、缺陷严重级别"),
        ("六", "测试用例明细", "%d 条用例，按模块分节列出" % len(CASES)),
        ("七", "执行与记录方式", "如何在执行表里填结果、如何提缺陷"),
        ("八", "已发现缺陷与修复记录", "%d 个缺陷：现象、根因、修复、回归用例" % len(DEFECTS)),
        ("九", "测试过程中的方法与失误", "四条值得记住的教训"),
        ("十", "未覆盖范围与残余风险", "明确不测什么，避免交付扯皮"),
        ("十一", "附录：自动化回归与命令", "接口回归、单元测试、安装校验、抽取评测"),
    ]
    for num, title, note in toc:
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(4)
        p.paragraph_format.left_indent = Cm(0.5)
        r1 = p.add_run("%s、%s" % (num, title))
        set_run_font(r1, size=11, bold=True)
        r2 = p.add_run("　—— " + note)
        set_run_font(r2, size=9.5, color=RGBColor(0x66, 0x66, 0x66))

    doc.add_page_break()

    # ---------- 一 文档说明 ----------
    add_heading(doc, "一、文档说明", 1)
    add_para(doc, "本文档用于线下测试人员执行手工测试。它包含测试模块划分、"
                  "全部用例的详细步骤与预期结果、缺陷记录，以及执行与记录方式。", indent=True)
    add_para(doc, "文档与配套文件的关系（三者分工不同，不要混用）：", bold=True, space_after=4)
    t = make_table(doc, ["文件", "作用", "谁维护"], [4.6, 9.4, 3.0])
    rows = [
        ("docs/测试文档.docx（本文）", "用例定义与测试方案：模块、步骤、预期结果、缺陷记录", "开发/测试负责人"),
        ("docs/测试用例-手工执行版.xlsx", "执行记录：逐条勾选通过/不通过，登记缺陷编号与执行人", "执行测试的人"),
        ("docs/test-plan.md", "开发侧接口回归的模块划分与缺陷复盘（偏技术）", "开发"),
        ("docs/eval-report.md", "AI 抽取准确率的定量评测与局限说明", "开发"),
    ]
    for r in rows:
        row = t.add_row()
        for i, v in enumerate(r):
            row.cells[i].width = Cm([4.6, 9.4, 3.0][i])
            cell_text(row.cells[i], v, size=9)
    doc.add_paragraph()
    add_para(doc, "为什么文档和执行表要分开：用例是「定义」，同一套用例会被执行多轮"
                  "（每轮修复后回归）。把结果写在用例旁边，第二轮就没法在同一份文件里复用；"
                  "分开之后，执行表可以按轮次另存。", indent=True)

    # ---------- 二 被测系统 ----------
    add_heading(doc, "二、被测系统与测试范围", 1)
    add_heading(doc, "2.1 系统构成", 2)
    add_para(doc, "系统分两个端，同一套后端：", space_after=4)
    for line in [
        "采购录入端（http://localhost:5174/）：一句话录入（AI）、手填、我的记录与补录。给采购员用。",
        "台账管理端（http://localhost:5174/admin/）：台账列表、审核入账、退回补录、作废、"
        "供应商与证照、统计、导出。给管理员用。",
        "后端（http://localhost:8081）：接口、合规判定、AI 抽取、Excel 导出、附件存储。",
        "数据库（MySQL）：库名 food_ledger，7 张表。",
    ]:
        p = doc.add_paragraph(style="List Bullet")
        p.paragraph_format.space_after = Pt(2)
        set_run_font(p.add_run(line), size=10)

    add_heading(doc, "2.2 本轮测什么", 2)
    add_para(doc, "重点是「一笔采购能不能被完整、可追溯地记下来，并且能被审核与检查」：", indent=True)
    for line in [
        "录入：AI 对话录入与手工表单录入两条路都要能走通，且合规判定一致。",
        "判定：法定项目缺失必须拦住入账；临期与过期要给出提示。",
        "流转：待入账 → 入账 / 退回补录 / 作废，退回后补录要能回到待审核。",
        "查证：按日期、供货者、品类、状态、级别、批号都能查；导出可用于检查。",
        "安全：AI 入口不被提示词操纵；查询不被注入。",
    ]:
        p = doc.add_paragraph(style="List Bullet")
        p.paragraph_format.space_after = Pt(2)
        set_run_font(p.add_run(line), size=10)

    add_heading(doc, "2.3 本轮不测什么", 2)
    add_para(doc, "明确不测，避免把时间花在边界外，也避免交付时对「测没测」产生分歧：", indent=True)
    for line in [
        "并发压测：只做两人手工同时保存，不测百人同时操作。",
        "模型抽取准确率：有独立的评测报告（docs/eval-report.md），不在手工用例里重复。",
        "浏览器兼容性：只保证 Chromium 内核（Edge/Chrome）。",
        "权限细分：只有采购员/管理员两种角色，没有按品类或金额分级授权。",
        "数据迁移：不测从旧系统导入台账。",
    ]:
        p = doc.add_paragraph(style="List Bullet")
        p.paragraph_format.space_after = Pt(2)
        set_run_font(p.add_run(line), size=10)

    # ---------- 三 环境 ----------
    add_heading(doc, "三、测试环境与数据准备", 1)
    add_heading(doc, "3.1 软件与端口", 2)
    t = make_table(doc, ["项目", "要求 / 地址"], [4.4, 12.6])
    for row_values in [
        ("JDK", "17 及以上（开发机为 23.0.1）"),
        ("Maven", "3.6 及以上"),
        ("MySQL", "8.0 及以上，字符集 utf8mb4"),
        ("Node.js", "18 及以上（运行前端）"),
        ("后端", "http://localhost:8081"),
        ("前端 · 采购录入端", "http://localhost:5174/"),
        ("前端 · 台账管理端", "http://localhost:5174/admin/"),
        ("数据库", "库名 food_ledger"),
        ("浏览器", "Edge / Chrome 等 Chromium 内核浏览器"),
    ]:
        row = t.add_row()
        cell_text(row.cells[0], row_values[0], size=9.5, bold=True)
        cell_text(row.cells[1], row_values[1], size=9.5)
    doc.add_paragraph()

    add_heading(doc, "3.2 首次安装（含建库建表）", 2)
    add_para(doc, "两条命令缺一不可。注意第二条必须用参数指定库名，种子文件里没有 USE 语句：", space_after=4)
    for line in [
        "cd food-ledger",
        "mysql -u root -p < src/main/resources/db/schema.sql",
        "mysql -u root -p food_ledger < src/main/resources/db/data.sql",
        "mvn spring-boot:run            # 后端，8081",
        "cd web && npm install && npm run dev    # 前端，5174",
    ]:
        p = doc.add_paragraph()
        p.paragraph_format.left_indent = Cm(0.8)
        p.paragraph_format.space_after = Pt(1)
        set_run_font(p.add_run(line), name="Consolas", size=9.5, color=RGBColor(0x22, 0x44, 0x33))
    add_para(doc, "警告：schema.sql 是重建式的（先 DROP 再 CREATE），只在开发期使用。"
                  "库里已有台账数据时不要执行，会清空。演示账号由程序首次启动时自动创建，"
                  "不需要手工插用户。", indent=True, color=RGBColor(0xA0, 0x4A, 0x12))

    add_heading(doc, "3.3 测试账号", 2)
    t = make_table(doc, ["账号", "密码", "角色", "能做什么"], [3.0, 2.4, 2.6, 9.0])
    for account, pwd, role, rights in [
        ("caigou", "123456", "采购员", "录入（AI 与手填）、看自己的记录、被退回后补录、上传索证索票照片"),
        ("guanli", "123456", "管理员", "全量台账查询、入账、退回补录、作废、供应商与证照、统计、导出"),
    ]:
        row = t.add_row()
        for i, x in enumerate((account, pwd, role, rights)):
            row.cells[i].width = Cm([3.0, 2.4, 2.6, 9.0][i])
            cell_text(row.cells[i], x, size=9.5, bold=(i == 0))
    doc.add_paragraph()

    add_heading(doc, "3.4 数据准备与清理", 2)
    add_para(doc, "部分模块依赖既有数据，开始前先看这张表，避免「筛不出来」被误当成缺陷：", space_after=4)
    t = make_table(doc, ["模块", "需要什么数据", "怎么造"], [3.6, 7.0, 6.2])
    for row_values in [
        ("E 台账列表", "红/黄/绿三种级别、四种状态（待入账/已入账/退回补录/已作废）、"
                       "临期与已过期各有若干条", "用 C 模块的「只填名称」造红级；"
                       "用手填完整造绿级；把到期日改成昨天造过期"),
        ("F 详情与审核", "至少一条可入账的（记录完整或待补证）、一条不合规的、一条已入账的",
         "同一条记录依次走：入账、退回、作废"),
        ("G 供应商", "无（新增即可）", "新增时把许可证到期日设为今天+12 天和昨天各一个"),
        ("H 统计", "有已作废记录、有跨月数据", "作废一条；把某条记录的进货日期改到上个月"),
        ("K 端到端", "两个浏览器窗口，分别登录两种账号", "一个用普通窗口，一个用无痕窗口"),
    ]:
        row = t.add_row()
        for i, x in enumerate(row_values):
            row.cells[i].width = Cm([3.6, 7.0, 6.2][i])
            cell_text(row.cells[i], x, size=9)
    doc.add_paragraph()
    add_para(doc, "清理：台账不支持物理删除，测试数据由管理员「作废」并写明原因。"
                  "不要用 SQL 直接删表或删行——会破坏统计口径与可追溯性，"
                  "也会让后续测试的基准数据对不上。", indent=True)

    # ---------- 四 模块划分 ----------
    doc.add_page_break()
    add_heading(doc, "四、测试模块划分", 1)
    add_para(doc, "模块按「用户能看到的功能区块」划分，不按代码目录划分——"
                  "测试人员要按界面操作，而不是按包名。同一个页面上两个按钮可能分属不同模块，"
                  "而一个模块也可能跨两个端。", indent=True, space_after=6)

    t = make_table(doc, ["编号", "模块", "用例数", "P0", "覆盖点"], [1.4, 4.4, 1.6, 1.2, 8.4])
    for mod in MODULES:
        rows = [c for c in CASES if c[1] == mod]
        p0 = len([c for c in rows if c[2] == "P0"])
        row = t.add_row()
        for i, x in enumerate((mod.split(" ")[0], mod.split(" ", 1)[1], len(rows), p0,
                               MODULE_SCOPE.get(mod, ""))):
            row.cells[i].width = Cm([1.4, 4.4, 1.6, 1.2, 8.4][i])
            cell_text(row.cells[i], x, size=9,
                      bold=(i == 1),
                      align=WD_ALIGN_PARAGRAPH.CENTER if i in (0, 2, 3) else None)
    row = t.add_row()
    for i, x in enumerate(("合计", "%d 个模块" % len(MODULES), len(CASES),
                           len([c for c in CASES if c[2] == "P0"]), "")):
        row.cells[i].width = Cm([1.4, 4.4, 1.6, 1.2, 8.4][i])
        cell_text(row.cells[i], x, size=9, bold=True,
                  align=WD_ALIGN_PARAGRAPH.CENTER if i in (0, 2, 3) else None)
        shade(row.cells[i], "EAF3EA")
    doc.add_paragraph()

    # ---------- 五 优先级与判定 ----------
    add_heading(doc, "五、优先级、判定标准与缺陷分级", 1)
    add_heading(doc, "5.1 用例优先级", 2)
    t = make_table(doc, ["优先级", "含义", "对交付的影响"], [2.2, 8.4, 6.4])
    for row_values in [
        ("P0 必测", "主干功能：不通过就无法交付", "任一 P0 不通过，本轮判定为不通过"),
        ("P1 应测", "重要功能：异常会影响使用，但有替代路径", "允许带缺陷交付，但必须登记并在下轮修复"),
        ("P2 可选", "体验与边界：时间允许时执行", "不阻塞交付"),
    ]:
        row = t.add_row()
        for i, x in enumerate(row_values):
            row.cells[i].width = Cm([2.2, 8.4, 6.4][i])
            cell_text(row.cells[i], x, size=9.5, bold=(i == 0))
    doc.add_paragraph()

    add_heading(doc, "5.2 通过判定", 2)
    for line in [
        "单条用例：实际结果与「预期结果」一致，记为通过；不一致记为不通过，并在缺陷记录页登记。",
        "模块：该模块 P0 全部通过，且不通过项已登记缺陷，记为模块通过。",
        "本轮：所有 P0 通过记为通过；任一 P0 不通过记为不通过。",
        "遇到阻塞（如服务起不来、页面白屏）：记为「阻塞」，暂停该模块，先测其它模块，不要空等。",
    ]:
        p = doc.add_paragraph(style="List Bullet")
        p.paragraph_format.space_after = Pt(2)
        set_run_font(p.add_run(line), size=10)

    add_heading(doc, "5.3 缺陷严重级别", 2)
    t = make_table(doc, ["级别", "判定标准", "举例"], [2.0, 8.0, 7.0])
    for row_values in [
        ("致命", "台账数据丢失或错乱、越权能看到别人数据、无法入账",
         "改一个字段把其余法定项目清空（D-01）"),
        ("严重", "主干功能不可用，且没有替代路径",
         "附件挂不到台账上（D-03）、补录后状态不回流（D-08）"),
        ("一般", "功能可用但结果不对",
         "品类筛不出鸡肉（D-04）、图片显示不出来（D-07）"),
        ("轻微", "提示文案错误、样式错位、非关键字段显示异常", "角色提示条文案不通顺"),
        ("建议", "可用性改进，不阻塞交付", "筛选加实时刷新（D-06）"),
    ]:
        row = t.add_row()
        for i, x in enumerate(row_values):
            row.cells[i].width = Cm([2.0, 8.0, 7.0][i])
            cell_text(row.cells[i], x, size=9, bold=(i == 0))
    doc.add_paragraph()

    # ---------- 六 用例明细 ----------
    doc.add_page_break()
    add_heading(doc, "六、测试用例明细", 1)
    add_para(doc, "按模块分节列出，每条用例给出前置条件、操作步骤、测试数据与预期结果。"
                  "执行时把实际结果与结论填在配套的 Excel 执行表里（同编号一一对应）。"
                  "P0 用浅红底、P1 用浅黄底标注，便于优先安排时间。", indent=True, space_after=6)

    for idx, mod in enumerate(MODULES, start=1):
        rows = [c for c in CASES if c[1] == mod]
        add_heading(doc, "6.%d %s（%d 条）" % (idx, mod, len(rows)), 2)
        add_para(doc, MODULE_SCOPE.get(mod, ""), size=9.5, indent=True, space_after=4,
                 color=RGBColor(0x44, 0x55, 0x44))
        add_case_table(doc, rows)

    # ---------- 七 执行方式 ----------
    doc.add_page_break()
    add_heading(doc, "七、执行与记录方式", 1)
    add_heading(doc, "7.1 执行顺序", 2)
    add_para(doc, "建议按模块编号顺序执行：A → B → C → D → E → F → G → H → I → J → L → M → O → N → K。"
                  "N（本轮反馈复测）放在 O 之后，因为它是前一轮问题的验证；"
                  "K（端到端）放最后，它依赖前面造出来的数据。", indent=True)

    add_heading(doc, "7.2 在 Excel 里怎么填", 2)
    for line in [
        "打开 docs/测试用例-手工执行版.xlsx，切到「测试用例」页。",
        "「实际结果」写你实际看到的内容，尤其是提示文案的原文——改文案时最容易发现不一致。",
        "「结论」从下拉里选：通过 / 不通过 / 阻塞 / 未执行。",
        "不通过时，在「缺陷编号」填 D-xx，并到「缺陷记录」页补齐复现步骤、期望、实际、严重级别。",
        "填完「结论」列，「执行汇总」页会自动统计各模块的通过/不通过数量，不用手工算。",
    ]:
        p = doc.add_paragraph(style="List Number")
        p.paragraph_format.space_after = Pt(2)
        set_run_font(p.add_run(line), size=10)

    add_heading(doc, "7.3 提缺陷的最低要求", 2)
    add_para(doc, "一个能修的缺陷至少要说清三件事，缺一条开发就得来回问：", indent=True, space_after=4)
    t = make_table(doc, ["要素", "说明", "反例"], [2.4, 7.4, 7.2])
    for row_values in [
        ("复现步骤", "从登录开始的每一步，别人照着能重复出来", "「筛选有问题」"),
        ("期望 vs 实际", "你认为应该是什么，实际是什么", "「不能筛」"),
        ("环境与数据", "哪个账号、哪个页面、哪条记录、什么筛选条件", "「随便点就出错」"),
    ]:
        row = t.add_row()
        for i, x in enumerate(row_values):
            row.cells[i].width = Cm([2.4, 7.4, 7.2][i])
            cell_text(row.cells[i], x, size=9, bold=(i == 0))
    doc.add_paragraph()
    add_para(doc, "截图很有用，但截图不能代替文字步骤——截图里看不到你点了哪个筛选、用了哪个账号。",
             indent=True, size=10)

    # ---------- 八 缺陷记录 ----------
    doc.add_page_break()
    add_heading(doc, "八、已发现缺陷与修复记录", 1)
    add_para(doc, "下表是截至本文档版本已发现并修复的缺陷。测试人员复测时按「回归用例」一列定位，"
                  "不需要重跑全部用例。", indent=True, space_after=6)

    t = make_table(doc, ["编号", "现象", "根因", "修复", "回归用例"],
                   [1.4, 5.4, 4.6, 4.2, 1.4])
    for cid, sym, cause, fix, cases in DEFECTS:
        row = t.add_row()
        for i, x in enumerate((cid, sym, cause, fix, cases)):
            row.cells[i].width = Cm([1.4, 5.4, 4.6, 4.2, 1.4][i])
            cell_text(row.cells[i], x, size=8.5, bold=(i == 0))
            if i == 4:
                shade(row.cells[i], "F0F6F0")
    doc.add_paragraph()

    add_heading(doc, "8.1 三个值得注意的规律", 2)
    add_para(doc, "把 10 个缺陷放在一起看，有三条重复出现的模式，比单个缺陷更值得记住：", indent=True)
    for line in [
        "「有列、能筛、但不在模板里」——D-02、D-03、D-04 都是这个模式。"
        "字段在库里有列、界面能筛，但模板字段清单里没有对应项，于是录入路径永远填不进去，"
        "而且不会报错，只是数据一直为空。这类问题只能靠「录进去、再筛出来」的完整链路才发现。",
        "「改了内容不改状态」——D-08。写入是正确的，但流程状态没跟着走，"
        "结果是任务卡在原地。凡是涉及审核流的改动，都要问一句「状态该不该变」。",
        "「只在一个环境成立」——D-07。接口测试全过，因为直连后端是好的；"
        "但页面走的是前端代理，代理漏了一条规则。前后端分离的系统，"
        "凡是有静态资源或第二种 URL 前缀，都要在「前端地址」上验一次。",
    ]:
        p = doc.add_paragraph(style="List Bullet")
        p.paragraph_format.space_after = Pt(3)
        set_run_font(p.add_run(line), size=10)

    # ---------- 九 教训 ----------
    add_heading(doc, "九、测试过程中的方法与失误", 1)
    add_para(doc, "这一节记录的是测试本身出的错，不是产品缺陷。写下来是因为它们更容易重犯：",
             indent=True, space_after=6)
    t = make_table(doc, ["问题", "经过", "教训"], [3.6, 8.6, 4.8])
    for name, story, lesson in LESSONS:
        row = t.add_row()
        for i, x in enumerate((name, story, lesson)):
            row.cells[i].width = Cm([3.6, 8.6, 4.8][i])
            cell_text(row.cells[i], x, size=8.5, bold=(i == 0))
    doc.add_paragraph()

    # ---------- 十 未覆盖 ----------
    add_heading(doc, "十、未覆盖范围与残余风险", 1)
    t = make_table(doc, ["未覆盖", "原因", "风险"], [4.4, 7.6, 5.0])
    for row_values in [
        ("真实并发压测", "只做两人手工同时保存，没有压测工具", "高并发下的乐观锁重试与连接池表现未知"),
        ("大模型输出稳定性", "编辑率约 2%~4% 波动（见评测报告），每次抽取都可能不同",
         "同一句话两次录入，字段可能有差异，属已知特性"),
        ("浏览器兼容性", "只验证 Chromium 内核", "Safari/Firefox 下版式与日期控件可能异常"),
        ("权限细分", "只有两种角色", "无法按品类或金额限制可见范围"),
        ("数据迁移", "不测从旧台账导入", "历史纸质台账录入成本未评估"),
        ("附件安全性", "只验扩展名白名单与大小上限，未做病毒扫描",
         "伪装成图片的恶意文件可能被上传（内网场景风险较低）"),
        ("移动端", "按桌面端设计，未做响应式", "手机上表格与筛选条会挤"),
    ]:
        row = t.add_row()
        for i, x in enumerate(row_values):
            row.cells[i].width = Cm([4.4, 7.6, 5.0][i])
            cell_text(row.cells[i], x, size=9, bold=(i == 0))
    doc.add_paragraph()

    # ---------- 十一 附录 ----------
    doc.add_page_break()
    add_heading(doc, "十一、附录：自动化回归与命令", 1)
    add_para(doc, "手工用例之外还有四组自动化检查，用于每次改动后的快速回归。"
                  "它们不能替代手工测试（界面与交互仍需人看），但能挡住大部分回归问题。",
             indent=True, space_after=6)
    t = make_table(doc, ["检查", "命令", "覆盖", "耗时"], [3.4, 6.8, 5.4, 1.6])
    for row_values in [
        ("单元测试", "cd food-ledger && mvn test", "合规判定与状态机的纯逻辑，12 条", "约 10 秒"),
        ("接口回归", "python qa/test-runner.py", "10 个模块 62 条接口断言（需先启动后端）", "约 40 秒"),
        ("安装校验", "python qa/verify-fresh-install.py",
         "在临时库里从零装一遍，逐表逐列比对后自动清理", "约 60 秒"),
        ("抽取评测", "RUN_EVAL=1 mvn test -Dtest=ExtractionEvalTest",
         "16 条真实模型调用的准确率/召回/瞎填/编造率", "约 60 秒"),
        ("提示词攻击", "python qa/probe-attacks.py", "10 条攻击探针打真实接口", "约 40 秒"),
    ]:
        row = t.add_row()
        for i, x in enumerate(row_values):
            row.cells[i].width = Cm([3.4, 6.8, 5.4, 1.6][i])
            cell_text(row.cells[i], x, size=8.5, bold=(i == 0))
    doc.add_paragraph()

    add_para(doc, "当前状态：接口回归 62/62 通过，单元测试 12 条全绿，安装校验四步全过。",
             bold=True, size=10)

    doc.add_paragraph()
    add_para(doc, "—— 文档结束 ——", size=9.5, color=RGBColor(0x88, 0x88, 0x88))
    doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER

    fix_all_tables(doc)
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    doc.save(OUT)
    print("已生成：%s" % OUT)
    print("  用例 %d 条，模块 %d 个，缺陷 %d 条，教训 %d 条"
          % (len(CASES), len(MODULES), len(DEFECTS), len(LESSONS)))
    for mod in MODULES:
        print("    %-28s %2d 条" % (mod, len([c for c in CASES if c[1] == mod])))


if __name__ == "__main__":
    build()
