# -*- coding: utf-8 -*-
"""
食材采购台账系统 · 接口级回归测试

用法：
    1 启动后端： cd food-ledger && mvn spring-boot:run      # 8081
    2 执行：     python qa/test-runner.py

它覆盖 9 个模块的核心用例，全部是接口级断言。
前端交互与视觉部分不在这里（见 docs/test-plan.md 的「手工用例」）。

注意：会真实调用模型（M4 AI 录入，约 4 次），其余纯接口。
测试数据以 TC- 前缀命名，执行完会清理。
"""

import datetime
import http.cookiejar
import io
import json
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
import zlib

BASE = "http://localhost:8081"
TODAY = datetime.date.today()
RESULTS = []
CREATED = []


# ---------------------------------------------------------------- 基础设施

def session():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(op, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with op.open(req, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))


def raw(op, method, path, body=None, content_type=None):
    req = urllib.request.Request(BASE + path, data=body, method=method,
                                 headers={"Content-Type": content_type} if content_type else {})
    with op.open(req, timeout=180) as resp:
        return resp.status, dict(resp.headers), resp.read()


def case(cid, desc, ok, actual):
    RESULTS.append((cid, desc, bool(ok), actual))
    print("  [%s] %-8s %-40s %s" % ("PASS" if ok else "FAIL", cid, desc, actual))


def section(title):
    print("\n== %s ==" % title)


def complete_values(name="TC-冷鲜猪肉", **override):
    v = {
        "ingredient_name": name, "spec": "散装", "quantity": "20", "unit": "斤",
        "production_date": "2026-10-26", "batch_no": "TC-P20261026",
        "shelf_life": "冷藏3天", "purchase_date": TODAY.isoformat(),
        "supplier_name": "TC-城南肉联厂", "supplier_address": "城南大道88号",
        "supplier_phone": "13800001111", "certificate": "TC-检疫2026-A-1234",
        "unit_price": "28.5", "warehouse": "一号冷库", "receiver": "李采购",
        "expiry_date": (TODAY + datetime.timedelta(days=30)).isoformat(),
        # 品类与三个自定义字段也算"齐"，否则 complete_values 其实不完整；
        # 模板新增字段后这里也要跟上，否则 GREEN 断言会掉成 YELLOW
        "category": "肉类",
        "origin": "本地", "cold_chain_temp": "4", "pesticide_report": "无",
    }
    v.update(override)
    return v


def png_bytes():
    def chunk(t, data):
        c = t + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)
    rawpix = b"\x00\xff\x00\x00" + b"\x00\x00\xff\x00"
    ihdr = struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(rawpix)) + chunk(b"IEND", b""))


# ---------------------------------------------------------------- M1 登录与权限

def m1():
    section("M1 登录与权限")
    anon, buyer, mgr = session(), session(), session()

    r = call(anon, "GET", "/api/ledger/page")
    case("TC1-01", "未登录访问台账 → 401", r["code"] == 401, r["message"])

    r = call(anon, "POST", "/api/auth/login", {"username": "caigou", "password": "wrong"})
    case("TC1-02", "错误密码 → 401 且不泄露账号是否存在",
         r["code"] == 401 and r["message"] == "用户名或密码不正确", r["message"])

    r = call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
    case("TC1-03", "采购员登录", r["code"] == 0 and r["data"]["role"] == "PURCHASER",
         r["data"]["displayName"] + "/" + r["data"]["role"])

    r = call(mgr, "POST", "/api/auth/login", {"username": "guanli", "password": "123456"})
    case("TC1-04", "管理员登录", r["code"] == 0 and r["data"]["role"] == "MANAGER",
         r["data"]["displayName"] + "/" + r["data"]["role"])

    # 采购员录一条
    d = call(buyer, "POST", "/api/ledger/values", {"values": complete_values()})["data"]
    CREATED.append(d["id"])

    r = call(buyer, "POST", "/api/ledger/%d/post" % d["id"], {"version": d["version"]})
    case("TC1-05", "采购员不能入账 → 403", r["code"] == 403, r["message"])

    r = call(mgr, "POST", "/api/ledger/%d/post" % d["id"], {"version": d["version"]})
    d06 = r.get("data") or {}
    case("TC1-06", "管理员可以入账", r["code"] == 0 and d06.get("status") == "POSTED",
         d06.get("statusLabel") or r["message"])

    # 采购员改别人的记录：管理员先录一条
    d2 = call(mgr, "POST", "/api/ledger/values",
              {"values": complete_values("TC-管理员录的")})["data"]
    CREATED.append(d2["id"])
    r = call(buyer, "PUT", "/api/ledger/%d" % d2["id"],
             {"version": d2["version"], "ingredientName": "TC-篡改"})
    case("TC1-07", "采购员改别人的记录 → 403", r["code"] == 403, r["message"])

    call(buyer, "POST", "/api/auth/logout")
    r = call(buyer, "GET", "/api/ledger/page")
    case("TC1-08", "退出后再访问 → 401", r["code"] == 401, r["message"])
    return mgr


# ---------------------------------------------------------------- M2 录入与状态

def m2(mgr):
    section("M2 台账录入与状态机")
    buyer = session()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})

    r = call(buyer, "POST", "/api/ledger/values", {"values": complete_values("TC-金额校验")})
    d = r["data"]
    CREATED.append(d["id"])
    case("TC2-01", "手填完整 → PENDING，金额自动算",
         r["code"] == 0 and d["status"] == "PENDING" and abs(float(d["amount"]) - 570.0) < 0.01,
         "status=%s amount=%s" % (d["status"], d["amount"]))

    r = call(buyer, "POST", "/api/ledger/values",
             {"values": {"ingredient_name": "TC-只有名字", "quantity": "5", "unit": "斤"}})
    d2 = r["data"]
    CREATED.append(d2["id"])
    case("TC2-02", "手填缺法定 → RED 且列出缺失", r["code"] == 0
         and d2["qualityLevel"] == "RED" and len(d2["quality"]["missing"]) > 0,
         "level=%s 缺%d项" % (d2["qualityLevel"], len(d2["quality"]["missing"])))

    r = call(buyer, "POST", "/api/ledger/values", {"values": {"quantity": "5"}})
    case("TC2-03", "名称为空 → 400", r["code"] == 400, r["message"])

    r = call(buyer, "PUT", "/api/ledger/%d" % d["id"], {"ingredientName": "TC-改名"})
    case("TC2-04", "修改不带 version → 400", r["code"] == 400, r["message"])

    r = call(buyer, "PUT", "/api/ledger/%d" % d["id"],
             {"version": 999, "ingredientName": "TC-改名"})
    case("TC2-05", "旧 version → 409 且提示两个版本",
         r["code"] == 409 and "v999" in r["message"], r["message"])

    r = call(buyer, "PUT", "/api/ledger/%d" % d["id"],
             {"version": d["version"], "ingredientName": "TC-改名成功"})
    case("TC2-06", "正确 version → 成功，revision+1",
         r["code"] == 0 and r["data"]["id"] == d["id"] and r["data"]["ingredientName"] == "TC-改名成功",
         "revision=%s" % (r.get("data") or {}).get("revision"))

    # 入账后再改内容
    latest = call(buyer, "GET", "/api/ledger/%d" % d["id"])["data"]
    pr = call(mgr, "POST", "/api/ledger/%d/post" % d["id"], {"version": latest["version"]})
    case("TC2-06b", "（前置）该记录可以入账", pr["code"] == 0, pr["message"])
    posted = call(buyer, "GET", "/api/ledger/%d" % d["id"])["data"]
    r = call(buyer, "PUT", "/api/ledger/%d" % d["id"],
             {"version": posted["version"], "ingredientName": "TC-已入账还想改"})
    case("TC2-07", "已入账的记录不能改内容 → 409", r["code"] == 409, r["message"])

    r = call(mgr, "POST", "/api/ledger/%d/void" % d["id"], {"version": posted["version"]})
    case("TC2-08", "作废不填原因 → 400", r["code"] == 400, r["message"])

    r = call(mgr, "POST", "/api/ledger/%d/void" % d["id"],
             {"version": posted["version"], "reason": "TC 录错了"})
    case("TC2-09", "作废成功 → VOID", r["code"] == 0 and r["data"]["status"] == "VOID",
         ((r.get("data") or {}).get("statusLabel") or r["message"]))

    voided = call(buyer, "GET", "/api/ledger/%d" % d["id"])["data"]
    r = call(mgr, "POST", "/api/ledger/%d/post" % d["id"], {"version": voided["version"]})
    case("TC2-10", "已作废的不能再入账 → 409", r["code"] == 409, r["message"])

    # 回归：部分字段修改不能把其它字段清空（曾经是整体替换，改个名字就抹掉 9 项法定项目）
    d3 = call(buyer, "POST", "/api/ledger/values",
              {"values": complete_values("TC-部分更新")})["data"]
    CREATED.append(d3["id"])
    call(buyer, "PUT", "/api/ledger/%d" % d3["id"],
         {"version": d3["version"], "remark": "只改备注"})
    d3b = call(buyer, "GET", "/api/ledger/%d" % d3["id"])["data"]
    case("TC2-12", "部分字段修改不清空其它字段",
         d3b["ingredientName"] == "TC-部分更新" and d3b["qualityLevel"] == "GREEN",
         "名称=%s 级别=%s" % (d3b["ingredientName"], d3b["qualityLevel"]))

    r = call(buyer, "GET", "/api/ledger/99999999")
    case("TC2-11", "不存在的记录 → 404", r["code"] == 404, r["message"])


# ---------------------------------------------------------------- M3 合规判定

def m3(mgr):
    section("M3 合规判定（接口层抽检）")
    buyer = session()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})

    def create(**over):
        d = call(buyer, "POST", "/api/ledger/values", {"values": complete_values(**over)})["data"]
        CREATED.append(d["id"])
        return d

    d = create()
    case("TC3-01", "法定齐 + 选填齐 → GREEN", d["qualityLevel"] == "GREEN",
         "%s 缺%d项" % (d["qualityLevel"], len(d["quality"]["missing"])))

    d = create(spec="")
    case("TC3-02", "缺一项法定 → RED", d["qualityLevel"] == "RED",
         d["quality"]["messages"][0] if d["quality"]["messages"] else "")

    d = create(certificate="", unit_price="", warehouse="", receiver="")
    case("TC3-03", "缺法定（凭证）→ RED", d["qualityLevel"] == "RED",
         "缺%d项" % len([m for m in d["quality"]["missing"] if m["required"]]))

    d = create(expiry_date=(TODAY - datetime.timedelta(days=2)).isoformat())
    case("TC3-04", "已过期 → 级别不变但给出警告",
         d["qualityLevel"] == "GREEN" and any("已过保质期" in w for w in d["warnings"]),
         d["warnings"][0] if d["warnings"] else "无警告")

    d = create(expiry_date=(TODAY + datetime.timedelta(days=2)).isoformat())
    case("TC3-05", "距到期 2 天 → 临期警告",
         any("距到期仅剩" in w for w in d["warnings"]),
         d["warnings"][0] if d["warnings"] else "无警告")

    d = create(supplier_phone="   ")
    case("TC3-06", "空白字符串等于没填 → RED", d["qualityLevel"] == "RED",
         "缺%d项" % len([m for m in d["quality"]["missing"] if m["required"]]))


# ---------------------------------------------------------------- M4 AI 录入

def m4():
    section("M4 AI 录入")
    buyer = session()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
    before = call(buyer, "GET", "/api/ledger/page?page=1&size=1")["data"]["total"]

    # TC4-01 一次说全
    s = call(buyer, "POST", "/api/entry/sessions")["data"]["sessionId"]
    out = call(buyer, "POST", "/api/entry/sessions/%d/messages" % s, {
        "content": "今天从城南肉联厂进了20斤冷鲜猪肉，单价28块5，规格散装，"
                   "生产日期2026-10-26，批号TC-AI-001，冷藏保质3天，"
                   "地址城南大道88号，电话13800001111，检疫证明TC-AI-1234"
    })["data"]
    case("TC4-01", "一次说全 → 直接生成记录", out["replyType"] == "RECORD",
         "%s #%s 级别=%s" % (out["replyType"], out.get("recordId"), out.get("level")))
    if out.get("recordId"):
        CREATED.append(out["recordId"])
        d = call(buyer, "GET", "/api/ledger/%d" % out["recordId"])["data"]
        case("TC4-02", "AI 记录带溯源：来源/会话/依据",
             d["sourceType"] == "AI" and d["sourceSessionId"] == s and len(d["basis"]) > 0,
             "source=%s 依据%d条" % (d["sourceType"], len(d["basis"])))

    # TC4-03/04 说不全 → 追问 → 超限转确认
    s2 = call(buyer, "POST", "/api/entry/sessions")["data"]["sessionId"]
    o1 = call(buyer, "POST", "/api/entry/sessions/%d/messages" % s2,
              {"content": "老张送了一箱土豆"})["data"]
    case("TC4-03", "信息不全 → 追问 ASK", o1["replyType"] == "ASK", o1.get("question"))
    o2 = call(buyer, "POST", "/api/entry/sessions/%d/messages" % s2,
              {"content": "大概五十斤"})["data"]
    o3 = call(buyer, "POST", "/api/entry/sessions/%d/messages" % s2,
              {"content": "别的记不清了"})["data"]
    case("TC4-04", "追问达上限 → 转 CONFIRM", o3["replyType"] == "CONFIRM",
         "%s 缺=%s" % (o3["replyType"], o3.get("missing")))

    mid = call(buyer, "GET", "/api/ledger/page?page=1&size=1")["data"]["total"]
    case("TC4-05", "确认前不落库", mid == before + 1,
         "工单数 %d（AI 已生成 1 条）" % mid)

    rec = call(buyer, "POST", "/api/entry/sessions/%d/confirm" % s2,
               {"summary": "TC 老张送的一箱土豆约五十斤"})["data"]
    if rec.get("recordId"):
        CREATED.append(rec["recordId"])
    after = call(buyer, "GET", "/api/ledger/page?page=1&size=1")["data"]["total"]
    case("TC4-06", "确认后才生成记录", rec["replyType"] == "RECORD" and after == mid + 1,
         "记录 #%s 级别=%s" % (rec.get("recordId"), rec.get("level")))

    r = call(buyer, "POST", "/api/entry/sessions/%d/messages" % s2, {"content": "还有事"})
    case("TC4-07", "已生成后继续说话 → 409", r["code"] == 409, r["message"])


# ---------------------------------------------------------------- M5 供应商

def m5(mgr):
    section("M5 供应商与证照")
    soon = (TODAY + datetime.timedelta(days=12)).isoformat()
    far = (TODAY + datetime.timedelta(days=400)).isoformat()

    r = call(mgr, "POST", "/api/suppliers", {
        "name": "TC-临期供应商", "address": "测试路1号", "contact": "张经理",
        "phone": "13800001111", "licenseNo": "TC-JY001", "licenseExpiry": soon,
        "businessLicenseNo": "TC-BL001", "businessExpiry": far})
    s = r["data"]
    case("TC5-01", "新增供应商", r["code"] == 0 and s["licenseDaysLeft"] == 12,
         "licenseDaysLeft=%s" % s.get("licenseDaysLeft"))

    case("TC5-02", "证照 12 天后到期 → 预警", s["licenseWarning"] is True,
         "licenseWarning=%s" % s.get("licenseWarning"))

    r = call(mgr, "POST", "/api/suppliers", {"address": "缺名称"})
    case("TC5-03", "缺名称 → 400", r["code"] == 400, r["message"])

    r = call(mgr, "GET", "/api/suppliers?keyword=" + urllib.parse.quote("TC-临期"))
    case("TC5-04", "关键字搜索命中", r["code"] == 0 and len(r["data"]) >= 1,
         "命中 %d 家" % len(r["data"]))

    r = call(mgr, "PUT", "/api/suppliers/%d" % s["id"], {
        "name": "TC-临期供应商（已改名）", "phone": "13900002222",
        "licenseNo": "TC-JY001", "licenseExpiry": far, "businessExpiry": far})
    case("TC5-05", "编辑后证照延长 → 预警消失",
         r["code"] == 0 and r["data"]["licenseWarning"] is False,
         "licenseDaysLeft=%s" % (r.get("data") or {}).get("licenseDaysLeft"))


# ---------------------------------------------------------------- M6 查询/分页/导出

def m6(mgr):
    section("M6 查询 / 分页 / 导出")
    page1 = call(mgr, "GET", "/api/ledger/page?page=1&size=5")["data"]
    page2 = call(mgr, "GET", "/api/ledger/page?page=2&size=5")["data"]
    ids1 = {r["id"] for r in page1["rows"]}
    ids2 = {r["id"] for r in page2["rows"]}
    case("TC6-01", "分页两页无重复且合计等于总数",
         not (ids1 & ids2) and page1["total"] == page2["total"],
         "total=%d 第1页%d 第2页%d" % (page1["total"], len(ids1), len(ids2)))

    big = call(mgr, "GET", "/api/ledger/page?page=1&size=100000")["data"]
    case("TC6-02", "size 请求过大 → 夹到 200", big["size"] == 200, "size=%s" % big["size"])

    red = call(mgr, "GET", "/api/ledger/page?page=1&size=50&level=RED")["data"]
    case("TC6-03", "按级别筛选生效",
         all(r["qualityLevel"] == "RED" for r in red["rows"]), "共 %d 条 RED" % red["total"])

    status, headers, body = raw(mgr, "GET", "/api/ledger/export")
    is_xlsx = body[:2] == b"PK"
    has_headers = False
    if is_xlsx:
        z = zipfile.ZipFile(io.BytesIO(body))
        shared = z.read("xl/sharedStrings.xml").decode("utf-8")
        has_headers = all(k in shared for k in ["食材采购台账", "生产批号", "索证索票凭证"])
    case("TC6-04", "导出为合法 xlsx 且含法定表头", is_xlsx and has_headers,
         "%d 字节，Content-Type=%s" % (len(body), headers.get("Content-Type", "")[:40]))

    allrows = call(mgr, "GET", "/api/ledger")["data"]
    case("TC6-05", "全量接口不受分页影响（导出完整）",
         len(allrows) == page1["total"], "%d 条" % len(allrows))

    batch = call(mgr, "GET", "/api/ledger/trace?batchNo=TC-P20261026")
    case("TC6-06", "按批号追溯", batch["code"] == 0 and len(batch["data"]) >= 1,
         "同批 %d 条" % len(batch["data"]))


# ---------------------------------------------------------------- M7 统计

def m7(mgr):
    section("M7 统计口径")
    s = call(mgr, "GET", "/api/stats/overview")["data"]
    case("TC7-01", "统计返回记录数与金额", s["totalRecords"] > 0 and float(s["totalAmount"]) >= 0,
         "记录 %d，总金额 %s" % (s["totalRecords"], s["totalAmount"]))
    case("TC7-02", "已入账金额 ≤ 总金额",
         float(s["postedAmount"]) <= float(s["totalAmount"]) + 0.001,
         "已入账 %s / 总 %s" % (s["postedAmount"], s["totalAmount"]))
    case("TC7-03", "按月分组非空", len(s["byMonth"]) >= 1,
         "月份数 %d" % len(s["byMonth"]))
    case("TC7-04", "合规分布之和 = 记录数",
         s["redCount"] + s["yellowCount"] + s["greenCount"] == s["totalRecords"],
         "红%d 黄%d 绿%d / 共%d" % (s["redCount"], s["yellowCount"], s["greenCount"],
                                   s["totalRecords"]))
    has_expired = any(e["daysLeft"] < 0 for e in s["expiring"])
    case("TC7-05", "临期明细含已过期项（负数天数）", has_expired,
         "临期 %d 条，其中过期 %d 条" % (s["expiringCount"],
                                       len([e for e in s["expiring"] if e["daysLeft"] < 0])))


# ---------------------------------------------------------------- M8 附件

def m8(mgr, buyer):
    section("M8 附件上传")
    boundary = "----" + uuid.uuid4().hex
    body = b""
    body += ("--%s\r\n" % boundary).encode()
    body += ('Content-Disposition: form-data; name="file"; filename="TC-检疫证明.png"\r\n').encode()
    body += b"Content-Type: image/png\r\n\r\n" + png_bytes()
    body += ("\r\n--%s--\r\n" % boundary).encode()
    status, _, resp = raw(mgr, "POST", "/api/uploads", body,
                          "multipart/form-data; boundary=" + boundary)
    up = json.loads(resp.decode("utf-8"))
    case("TC8-01", "上传 png 成功", up["code"] == 0, up["data"]["url"] if up["code"] == 0 else up["message"])

    url = up["data"]["url"]
    status, headers, content = raw(mgr, "GET", url)
    case("TC8-02", "附件可访问且类型正确",
         status == 200 and headers.get("Content-Type", "").startswith("image/png")
         and content == png_bytes(),
         "HTTP %d %s %d字节" % (status, headers.get("Content-Type"), len(content)))

    body2 = b""
    body2 += ("--%s\r\n" % boundary).encode()
    body2 += ('Content-Disposition: form-data; name="file"; filename="TC-evil.exe"\r\n').encode()
    body2 += b"Content-Type: application/octet-stream\r\n\r\nMZ..."
    body2 += ("\r\n--%s--\r\n" % boundary).encode()
    _, _, resp2 = raw(mgr, "POST", "/api/uploads", body2,
                      "multipart/form-data; boundary=" + boundary)
    bad = json.loads(resp2.decode("utf-8"))
    case("TC8-03", "拒绝非白名单扩展名", bad["code"] == 400, bad["message"])

    vals = complete_values("TC-带附件")
    vals["attachments"] = [up["data"]]
    d = call(buyer, "POST", "/api/ledger/values", {"values": vals})["data"]
    CREATED.append(d["id"])
    case("TC8-04", "附件可挂到台账记录上", len(d["attachments"]) == 1,
         "附件数 %d" % len(d["attachments"]))


# ---------------------------------------------------------------- M9 并发与审计

def m9(mgr):
    section("M9 并发与审计")
    buyer = session()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
    d = call(buyer, "POST", "/api/ledger/values",
             {"values": complete_values("TC-并发")})["data"]
    CREATED.append(d["id"])

    stale = d["version"]
    r1 = call(mgr, "PUT", "/api/ledger/%d" % d["id"],
              {"version": stale, "receiver": "王管理"})
    r2 = call(mgr, "PUT", "/api/ledger/%d" % d["id"],
              {"version": stale, "receiver": "李采购"})
    case("TC9-01", "同一版本先后保存，第二次 409",
         r1["code"] == 0 and r2["code"] == 409 and "v%d" % stale in r2["message"],
         r2["message"])

    latest = call(buyer, "GET", "/api/ledger/%d" % d["id"])["data"]
    case("TC9-02", "lastActor 记录最后修改者", latest["lastActor"] == "王管理",
         "lastActor=%s" % latest["lastActor"])

    case("TC9-03", "revision 随修改递增", latest["revision"] == 2,
         "revision=%s" % latest["revision"])


# ---------------------------------------------------------------- M10 品类与筛选（人工反馈回归）

def m10(mgr):
    section("M10 品类与筛选（人工反馈回归）")
    buyer = session()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
    cats = call(mgr, "GET", "/api/categories")["data"]
    meat = next(c for c in cats if c["name"] == "肉类")

    vals = complete_values("TC-鸡肉")
    vals["category"] = "肉类"
    d = call(buyer, "POST", "/api/ledger/values", {"values": vals})["data"]
    CREATED.append(d["id"])
    case("TC10-01", "手填品类名 → 解析成 category_id",
         d["categoryId"] == meat["id"] and d["categoryName"] == "肉类",
         "categoryId=%s name=%s" % (d["categoryId"], d["categoryName"]))

    pg = call(mgr, "GET", "/api/ledger/page?page=1&size=50&categoryId=%d" % meat["id"])["data"]
    hit = any(r["id"] == d["id"] for r in pg["rows"])
    case("TC10-02", "按「肉类」筛选能筛到鸡肉", hit,
         "肉类共 %d 条，含该记录=%s" % (pg["total"], hit))

    un = call(mgr, "GET", "/api/ledger/page?page=1&size=50&categoryId=-1")["data"]
    all_null = all(r["categoryId"] is None for r in un["rows"])
    case("TC10-03", "「未分类」筛选只返回无品类记录", all_null,
         "未分类 %d 条，全部无品类=%s" % (un["total"], all_null))

    tpl = call(mgr, "GET", "/api/templates/default")["data"]
    keys = [f["key"] for f in tpl["fields"]]
    case("TC10-04", "模板里存在 category 字段（录入入口存在）",
         "category" in keys, "字段数 %d，含 category=%s" % (len(keys), "category" in keys))

    # 退回 -> 补录 -> 状态必须回流，否则记录永远卡在「退回补录」，管理员看不出可以再审
    vals2 = complete_values("TC-回流验证")
    d5 = call(buyer, "POST", "/api/ledger/values", {"values": vals2})["data"]
    CREATED.append(d5["id"])
    call(mgr, "POST", "/api/ledger/%d/return" % d5["id"],
         {"version": d5["version"], "note": "TC 退回"})
    cur5 = call(buyer, "GET", "/api/ledger/%d" % d5["id"])["data"]
    up5 = call(buyer, "PUT", "/api/ledger/%d" % d5["id"],
               {"version": cur5["version"], "remark": "TC 补录完成"})
    case("TC10-05", "补录后状态从「退回补录」回到「待入账」",
         up5["code"] == 0 and (up5.get("data") or {}).get("status") == "PENDING",
         "退回后=%s，补录后=%s" % (cur5["status"], (up5.get("data") or {}).get("status")))


# ---------------------------------------------------------------- 清理与汇总

def cleanup(mgr):
    section("清理测试数据")
    ok = 0
    for rid in CREATED:
        latest = call(mgr, "GET", "/api/ledger/%d" % rid)
        if latest["code"] != 0:
            continue
        d = latest["data"]
        if d["status"] == "VOID":
            continue
        r = call(mgr, "POST", "/api/ledger/%d/void" % rid,
                 {"version": d["version"], "reason": "TC 测试数据清理"})
        if r["code"] == 0:
            ok += 1
    print("  已作废 %d 条测试记录（台账不删除，只作废）" % ok)


def main():
    try:
        mgr = m1()
        m2(mgr)
        m3(mgr)
        m4()
        m5(mgr)
        m6(mgr)
        m7(mgr)
        buyer = session()
        call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
        m8(mgr, buyer)
        m9(mgr)
        m10(mgr)
        cleanup(mgr)
    except urllib.error.URLError:
        print("连不上后端，先启动： cd food-ledger && mvn spring-boot:run")
        return 2

    passed = len([r for r in RESULTS if r[2]])
    print("\n" + "=" * 70)
    print("结果：%d/%d 通过" % (passed, len(RESULTS)))
    failed = [r for r in RESULTS if not r[2]]
    if failed:
        print("\n失败明细：")
        for cid, desc, _, actual in failed:
            print("  %s %s → %s" % (cid, desc, actual))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
