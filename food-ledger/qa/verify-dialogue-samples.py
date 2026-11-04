# -*- coding: utf-8 -*-
"""
验证「可直接复制的采购描述」是不是真的能一次说全。

给测试人员用的文本不能只是看起来完整：
必须实测它能抽出全部 12 项法定项目并直接出单，否则贴过去还是被追问。

用法：python qa/verify-dialogue-samples.py
"""

import http.cookiejar
import json
import urllib.request

BASE = "http://localhost:8081"
CREATED = []

SAMPLES = [
    ("猪肉", "今天从城南肉联厂进了20斤冷鲜猪肉，规格散装，单价28块5，"
             "生产日期2026-10-26，批号R20261026-3，保质期冷藏3天，"
             "进货日期2026-10-28，供货者地址城南大道88号，电话13800001111，"
             "检疫证明编号SC-2026-A-1234，到期日2026-10-29"),

    ("土豆", "今天从老张蔬菜批发进了50斤土豆，规格散装，单价2块4，"
             "生产日期2026-10-25，批号T20261025-1，保质期15天，"
             "进货日期2026-10-28，供货者地址农贸批发市场B12，电话13900002222，"
             "农残检测报告NY-2026-110，到期日2026-11-09"),

    ("大米", "今天从万家粮油进了5袋大米，规格25kg/袋，单价128元，"
             "生产日期2026-10-01，批号M20261001-7，保质期180天，"
             "进货日期2026-10-28，供货者地址粮贸中心3号库，电话13600004444，"
             "食品经营许可证JY-2026-0088，到期日2027-03-30"),

    ("带鱼", "今天从河海水产进了10斤带鱼，规格冷冻，单价22元，"
             "生产日期2026-09-20，批号H20260920-2，保质期30天，"
             "进货日期2026-10-28，供货者地址滨江路7号，电话13700003333，"
             "检疫证明SC-2026-B-0456，到期日2026-10-20"),

    ("鸡蛋", "今天从绿野蛋业进了12盒鸡蛋，规格30枚/盒，单价24块5，"
             "生产日期2026-10-20，批号E20261020-5，保质期45天，"
             "进货日期2026-10-28，供货者地址绿野镇养殖区8号，电话13500005555，"
             "动物检疫合格证明DJ-2026-0099，到期日2026-12-04"),

    ("牛奶", "今天从绿野蛋业进了3箱纯牛奶，规格1L*12盒/箱，单价68元，"
             "生产日期2026-10-10，批号N20261010-1，保质期21天，"
             "进货日期2026-10-28，供货者地址绿野镇养殖区8号，电话13500005555，"
             "检验合格证明JY-2026-0077，到期日2026-10-31"),
]


def session():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(op, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with op.open(req, timeout=180) as resp:
        return json.loads(resp.read().decode())


def main():
    buyer = session()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})

    print("%-6s %-9s %-8s %-36s %s" % ("样本", "结果", "级别", "还缺什么", "记录"))
    print("-" * 100)
    ok = 0
    for tag, text in SAMPLES:
        sid = call(buyer, "POST", "/api/entry/sessions")["data"]["sessionId"]
        out = call(buyer, "POST", "/api/entry/sessions/%d/messages" % sid,
                   {"content": text})["data"]
        reply = out["replyType"]
        if reply == "RECORD":
            rid = out.get("recordId")
            CREATED.append(rid)
            d = call(buyer, "GET", "/api/ledger/%d" % rid)["data"]
            missing = [m["label"] for m in d["quality"]["missing"]]
            print("%-6s %-9s %-8s %-36s #%s" % (
                tag, reply, d["qualityLabel"], "、".join(missing) or "无（法定齐全）", rid))
            ok += 1
        else:
            print("%-6s %-9s %-8s %s" % (tag, reply, "-", out.get("question") or out.get("missing")))

    print("-" * 100)
    print("一次说全的样本：%d / %d" % (ok, len(SAMPLES)))
    cleanup()


def cleanup():
    admin = session()
    call(admin, "POST", "/api/auth/login", {"username": "guanli", "password": "123456"})
    n = 0
    for rid in CREATED:
        d = call(admin, "GET", "/api/ledger/%d" % rid)
        if d["code"] != 0:
            continue
        rec = d["data"]
        if rec["status"] == "VOID":
            continue
        if call(admin, "POST", "/api/ledger/%d/void" % rid,
                {"version": rec["version"], "reason": "样本验证数据清理"})["code"] == 0:
            n += 1
    print("已作废 %d 条样本记录" % n)


if __name__ == "__main__":
    main()
