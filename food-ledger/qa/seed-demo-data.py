# -*- coding: utf-8 -*-
"""
批量造演示数据：让台账列表 / 统计 / 导出 / 筛选都有东西可看。

为什么需要它：
空库点不出任何效果——筛选筛不出东西、统计全是 0、导出一张空表。
而手工录 30 条太费时间，且很难造出「跨月、四种状态、临期与过期」这些组合。

用法：
    python qa/seed-demo-data.py            # 追加 30 条左右
    python qa/seed-demo-data.py --clean    # 先把 DEMO- 前缀的记录全部作废

数据都以 DEMO- 开头命名，方便识别与清理。
"""

import datetime
import http.cookiejar
import json
import sys
import urllib.request

BASE = "http://localhost:8081"
TODAY = datetime.date.today()

# 供货者：其中两家的证照会临期/过期，用来演示证照预警
SUPPLIERS = [
    ("城南肉联厂", "城南大道88号", "13800001111"),
    ("老张蔬菜批发", "农贸批发市场B12", "13900002222"),
    ("河海水产", "滨江路7号", "13700003333"),
    ("万家粮油", "粮贸中心3号库", "13600004444"),
    ("绿野蛋业", "绿野镇养殖区8号", "13500005555"),
]

# 品名、品类、规格、单位、单价
GOODS = [
    ("冷鲜猪肉", "肉类", "散装", "斤", 28.5, 3),
    ("五花肉", "肉类", "散装", "斤", 26.0, 3),
    ("鸡胸肉", "肉类", "2.5kg/袋", "袋", 42.0, 5),
    ("白条鸡", "肉类", "整只", "只", 35.0, 2),
    ("土豆", "蔬菜", "散装", "斤", 2.4, 15),
    ("大白菜", "蔬菜", "散装", "斤", 1.8, 10),
    ("青椒", "蔬菜", "散装", "斤", 4.2, 7),
    ("西红柿", "蔬菜", "散装", "斤", 3.8, 7),
    ("带鱼", "水产", "冷冻", "斤", 22.0, 30),
    ("鲈鱼", "水产", "鲜活", "斤", 32.0, 2),
    ("大米", "粮油", "25kg/袋", "袋", 128.0, 180),
    ("面粉", "粮油", "25kg/袋", "袋", 112.0, 120),
    ("鸡蛋", "蛋奶", "30枚/盒", "盒", 24.5, 45),
    ("纯牛奶", "蛋奶", "1L*12盒/箱", "箱", 68.0, 21),
]

# 分布：日期跨越 3 个月，状态覆盖四种
PLAN = [
    # (商品下标, 供货者下标, 进货日期偏移天数, 保质天数, 状态)
    (0, 0, -82, 3, "POSTED"), (4, 1, -80, 15, "POSTED"),
    (10, 3, -78, 180, "POSTED"), (12, 4, -75, 45, "POSTED"),
    (1, 0, -58, 3, "POSTED"), (5, 1, -56, 10, "POSTED"),
    (8, 2, -54, 30, "POSTED"), (13, 4, -52, 21, "POSTED"),
    (2, 0, -33, 5, "POSTED"), (6, 1, -31, 7, "RETURNED"),
    (11, 3, -30, 120, "POSTED"), (9, 2, -28, 2, "VOID"),
    (3, 0, -12, 2, "PENDING"), (7, 1, -10, 7, "PENDING"),
    (0, 0, -8, 3, "POSTED"), (4, 1, -7, 15, "PENDING"),
    (2, 0, -6, 5, "PENDING"), (8, 2, -5, 30, "RETURNED"),
    (12, 4, -4, 45, "POSTED"), (5, 1, -3, 10, "PENDING"),
    (13, 4, -2, 21, "PENDING"), (6, 1, -1, 7, "PENDING"),
    # 最近三天进的：用来演示「临期」与「已过期」
    (9, 2, -1, 2, "PENDING"), (0, 0, 0, 3, "PENDING"),
    (7, 1, 0, 7, "PENDING"), (12, 4, 0, 45, "POSTED"),
    (1, 0, 0, 3, "PENDING"), (4, 1, 0, 15, "PENDING"),
    (3, 0, 0, 2, "PENDING"), (11, 3, 0, 120, "PENDING"),
]


def session():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(op, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with op.open(req, timeout=120) as resp:
        return json.loads(resp.read().decode())


def main():
    clean = "--clean" in sys.argv
    mgr = session()
    call(mgr, "POST", "/api/auth/login", {"username": "guanli", "password": "123456"})
    buyer = session()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})

    if clean:
        rows = call(mgr, "GET", "/api/ledger?keyword=DEMO-")["data"]
        n = 0
        for r in rows:
            d = call(mgr, "GET", "/api/ledger/%d" % r["id"])["data"]
            if d["status"] == "VOID":
                continue
            if call(mgr, "POST", "/api/ledger/%d/void" % r["id"],
                    {"version": d["version"], "reason": "清理演示数据"})["code"] == 0:
                n += 1
        print("已作废 %d 条演示数据" % n)
        return

    # 先把证照数据造出来：一家临期、一家已过期，用来演示供应商预警
    by_name = {}
    for i, (name, addr, phone) in enumerate(SUPPLIERS):
        expiry = (TODAY + datetime.timedelta(days=[400, 12, -3, 200, 400][i])).isoformat()
        r = call(mgr, "POST", "/api/suppliers", {
            "name": name, "address": addr, "contact": "联系人" + str(i + 1),
            "phone": phone, "licenseNo": "JY-%03d" % (i + 1),
            "licenseExpiry": expiry, "businessExpiry": expiry,
        })
        by_name[name] = r["data"]["id"] if r["code"] == 0 else None

    created = 0
    for idx, (gi, si, day_off, shelf, target) in enumerate(PLAN, start=1):
        name, cat, spec, unit, price, _ = GOODS[gi]
        supplier = SUPPLIERS[si][0]
        purchase = TODAY + datetime.timedelta(days=day_off)
        production = purchase - datetime.timedelta(days=1)
        qty = [20, 30, 5, 12, 40, 25, 15, 8, 6, 10][idx % 10]
        values = {
            "ingredient_name": "DEMO-" + name,
            "spec": spec,
            "quantity": str(qty),
            "unit": unit,
            "production_date": production.isoformat(),
            "batch_no": "DEMO%02d%02d" % (purchase.month, idx),
            "shelf_life": "%d天" % shelf,
            "purchase_date": purchase.isoformat(),
            "supplier_name": supplier,
            "supplier_address": SUPPLIERS[si][1],
            "supplier_phone": SUPPLIERS[si][2],
            "certificate": "SC-2026-%03d" % idx,
            "expiry_date": (production + datetime.timedelta(days=shelf)).isoformat(),
            "unit_price": str(price),
            "warehouse": "一号库" if idx % 2 else "二号冷库",
            "receiver": "李采购",
            "category": cat,
            "origin": ["本地", "外省", "进口"][idx % 3],
            "cold_chain_temp": "4" if cat in ("肉类", "水产", "蛋奶") else "常温",
            "pesticide_report": "NY-%03d" % idx if cat == "蔬菜" else "无",
        }
        r = call(buyer, "POST", "/api/ledger/values", {"values": values})
        if r["code"] != 0:
            print("  第 %d 条失败：%s" % (idx, r["message"]))
            continue
        created += 1
        rec = r["data"]

        if target == "POSTED":
            latest = call(mgr, "GET", "/api/ledger/%d" % rec["id"])["data"]
            call(mgr, "POST", "/api/ledger/%d/post" % rec["id"],
                 {"version": latest["version"]})
        elif target == "RETURNED":
            latest = call(mgr, "GET", "/api/ledger/%d" % rec["id"])["data"]
            call(mgr, "POST", "/api/ledger/%d/return" % rec["id"],
                 {"version": latest["version"], "note": "请补充验收人与冷链温度"})
        elif target == "VOID":
            latest = call(mgr, "GET", "/api/ledger/%d" % rec["id"])["data"]
            call(mgr, "POST", "/api/ledger/%d/void" % rec["id"],
                 {"version": latest["version"], "reason": "到货不符，已退货"})

    # 汇总
    allrows = call(mgr, "GET", "/api/ledger")["data"]
    demo = [r for r in allrows if (r["ingredientName"] or "").startswith("DEMO-")]
    stat = {}
    for r in demo:
        stat[r["status"]] = stat.get(r["status"], 0) + 1
    lvl = {}
    for r in demo:
        lvl[r["qualityLevel"]] = lvl.get(r["qualityLevel"], 0) + 1
    s = call(mgr, "GET", "/api/stats/overview")["data"]

    print("=" * 70)
    print("已生成 %d 条演示数据（DEMO- 前缀），台账合计 %d 条" % (created, len(allrows)))
    print("  状态分布：%s" % stat)
    print("  级别分布：%s" % lvl)
    print("  统计口径：总金额 %s，已入账 %s，临期/过期 %s 条"
          % (s["totalAmount"], s["postedAmount"], s["expiringCount"]))
    print("  清理：python qa/seed-demo-data.py --clean")
    print("=" * 70)


if __name__ == "__main__":
    main()
