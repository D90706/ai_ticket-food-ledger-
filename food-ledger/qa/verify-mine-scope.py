# -*- coding: utf-8 -*-
"""
验证两个修复：
  1 管理员录的记录被退回后，采购员「我的记录」能收到（scope=MINE）
  2 补录界面能补上「产地 / 冷链到货温度 / 农残检测报告号」，并因此达到「记录完整」

用法：python qa/verify-mine-scope.py
"""

import http.cookiejar
import json
import urllib.parse
import urllib.request

BASE = "http://localhost:8081"
CREATED = []


def sess():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(op, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with op.open(req, timeout=120) as resp:
        return json.loads(resp.read().decode())


def main():
    buyer, mgr = sess(), sess()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
    call(mgr, "POST", "/api/auth/login", {"username": "guanli", "password": "123456"})
    print("=" * 74)

    full = {
        "ingredient_name": "SCOPE-土豆", "spec": "散装", "quantity": "20", "unit": "斤",
        "production_date": "2026-10-26", "batch_no": "SCOPE-1", "shelf_life": "15天",
        "purchase_date": "2026-10-28", "supplier_name": "老张蔬菜批发",
        "supplier_address": "农贸批发市场B12", "supplier_phone": "13900002222",
        "certificate": "SC-SCOPE-1", "expiry_date": "2026-11-10", "category": "蔬菜",
    }

    # ---------- 1 管理员录的、退回给采购员 ----------
    print("\n[1] 管理员自己录一条，然后退回给采购员")
    d = call(mgr, "POST", "/api/ledger/values", {"values": full})["data"]
    CREATED.append(d["id"])
    print("  管理员录入 #%s，createdBy=%s" % (d["id"], d["createdBy"]))
    call(mgr, "POST", "/api/ledger/%d/return" % d["id"],
         {"version": d["version"], "note": "请补产地与冷链温度"})
    print("  已退回，状态=退回补录")

    # 旧行为：只按 createdBy 查
    old = call(mgr, "GET", "/api/ledger?createdBy=" + urllib.parse.quote("李采购"))["data"]
    in_old = any(r["id"] == d["id"] for r in old)
    print("  仅按 createdBy=李采购 查询：%s ← %s"
          % ("能看到" if in_old else "看不到", "旧行为的问题" if not in_old else "意外"))

    # 新行为：scope=MINE
    new = call(mgr, "GET", "/api/ledger?scope=MINE&createdBy="
               + urllib.parse.quote("李采购"))["data"]
    in_new = any(r["id"] == d["id"] for r in new)
    print("  加 scope=MINE 查询：%s ← %s"
          % ("能看到" if in_new else "看不到", "修复生效" if in_new else "仍不行"))

    # 明确筛状态时不该混入退回的（否则筛「已入账」会冒出退回记录）
    posted = call(mgr, "GET", "/api/ledger?scope=MINE&createdBy="
                  + urllib.parse.quote("李采购") + "&status=POSTED")["data"]
    mixed = any(r["status"] == "RETURNED" for r in posted)
    print("  同时筛 status=POSTED：出现退回记录=%s ← %s"
          % ("是" if mixed else "否", "有问题" if mixed else "正确（筛选优先）"))

    # ---------- 2 补录界面能补上那三个字段 ----------
    print("\n[2] 采购员补录：填上产地 / 冷链到货温度 / 农残检测报告号")
    before = call(buyer, "GET", "/api/ledger/%d" % d["id"])["data"]
    print("  补录前 级别=%s 缺=%s"
          % (before["qualityLabel"], [m["label"] for m in before["quality"]["missing"]]))

    # 前端现在提交的就是「模板 key → 值」的表
    values = {
        "ingredient_name": before["ingredientName"],
        "spec": before["spec"], "quantity": before["quantity"], "unit": before["unit"],
        "production_date": before["productionDate"], "batch_no": before["batchNo"],
        "shelf_life": before["shelfLife"], "purchase_date": before["purchaseDate"],
        "supplier_name": before["supplierName"], "supplier_address": before["supplierAddress"],
        "supplier_phone": before["supplierPhone"], "certificate": before["certificate"],
        "expiry_date": before["expiryDate"],
        "category": before["categoryName"],
        "origin": "本地", "cold_chain_temp": "常温", "pesticide_report": "NY-2026-999",
    }
    r = call(buyer, "PUT", "/api/ledger/%d/values" % d["id"],
             {"values": values, "version": before["version"]})
    print("  PUT /api/ledger/{id}/values：code=%s" % r["code"])
    after = r.get("data") or {}
    if after:
        print("  补录后 级别=%s（%s） 状态=%s  修订号=%s"
              % (after["qualityLevel"], after["qualityLabel"],
                 after["statusLabel"], after["revision"]))
        print("  extra=%s" % after["extra"])
        print("  三个字段是否补上：产地=%s 冷链=%s 农残=%s"
              % (after["extra"].get("origin"), after["extra"].get("cold_chain_temp"),
                 after["extra"].get("pesticide_report")))
        print("  是否达到「记录完整」：%s" % ("是" if after["qualityLevel"] == "GREEN" else "否"))
        print("  退回后状态是否回到「待入账」：%s"
              % ("是" if after["status"] == "PENDING" else "否（仍是 %s）" % after["statusLabel"]))

    cleanup()


def cleanup():
    mgr = sess()
    call(mgr, "POST", "/api/auth/login", {"username": "guanli", "password": "123456"})
    n = 0
    for rid in CREATED:
        d = call(mgr, "GET", "/api/ledger/%d" % rid)
        if d["code"] != 0:
            continue
        rec = d["data"]
        if rec["status"] == "VOID":
            continue
        if call(mgr, "POST", "/api/ledger/%d/void" % rid,
                {"version": rec["version"], "reason": "验证数据清理"})["code"] == 0:
            n += 1
    print("\n已作废 %d 条验证记录" % n)


if __name__ == "__main__":
    main()
