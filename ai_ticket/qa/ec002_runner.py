# -*- coding: utf-8 -*-
"""
EC-002 端到端验收脚本（对应 qa/EC-002.md）。

用法：
    1. 先启动后端： mvn spring-boot:run
    2. 再执行：      python qa/ec002_runner.py

它走接口层，不操作浏览器。18 条断言覆盖：
    顾客确认 → 人工登录 → 乐观锁 → 状态回执 → 收尾

每次执行会新建一个会话和一张工单，不清库、不改已有数据。
"""

import http.cookiejar
import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
results = []


def session():
    """每个"人"（顾客/张三/李四）一个独立 cookie 罐"""
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(opener, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        BASE + path, data=data, method=method,
        headers={"Content-Type": "application/json"})
    with opener.open(request, timeout=180) as resp:
        return json.loads(resp.read().decode("utf-8"))


def check(no, desc, ok, actual):
    results.append((no, desc, bool(ok)))
    print("  [%s] %-3s %-42s %s" % ("PASS" if ok else "FAIL", no, desc, actual))


def main():
    customer = session()
    zhang = session()
    li = session()

    print("=== EC-002 执行 ===\n")

    # ---------------------------------------------------------- 阶段一
    print("-- 阶段一：顾客侧 --")

    options = call(customer, "GET", "/api/chat/templates")["data"]
    sid = call(customer, "POST", "/api/chat/sessions",
               {"templateId": options[0]["id"]})["data"]["sessionId"]
    check("A1", "会话创建成功，返回 sessionId", sid, "session=%s" % sid)

    for index, text in enumerate(["我买的衣服不太满意", "就是穿着感觉不对劲"], 1):
        out = call(customer, "POST", "/api/chat/sessions/%s/messages" % sid,
                   {"content": text})["data"]
        check("A%d" % (index + 1), "第%d轮：缺必填 → 追问 ASK" % index,
              out["replyType"] == "ASK",
              "%s | %s" % (out["replyType"], out.get("question")))

    out = call(customer, "POST", "/api/chat/sessions/%s/messages" % sid,
               {"content": "具体我也说不上来"})["data"]
    check("A4", "第3轮：追问达上限 → CONFIRM 而非出单",
          out["replyType"] == "CONFIRM",
          "%s | 缺=%s" % (out["replyType"], out.get("missing")))

    call(zhang, "POST", "/api/auth/login", {"username": "zhangsan", "password": "123456"})
    count_before = len(call(zhang, "GET", "/api/tickets")["data"])

    edited = "顾客称衣服穿着不适，说不清具体原因，也未提供订单号；已确认内容无误。"
    res = call(customer, "POST", "/api/chat/sessions/%s/confirm" % sid,
               {"summary": edited})["data"]
    tid = res.get("ticketId")
    count_after = len(call(zhang, "GET", "/api/tickets")["data"])
    check("A5", "确认前不落库；确认后才出单",
          tid and count_after == count_before + 1,
          "工单数 %d → %d，新工单 #%s" % (count_before, count_after, tid))

    # ---------------------------------------------------------- 阶段二
    print("\n-- 阶段二：人工侧 --")

    anon = session()
    check("A6", "未登录访问工单接口 → 401",
          call(anon, "GET", "/api/tickets")["code"] == 401,
          call(anon, "GET", "/api/tickets")["message"])

    detail = call(zhang, "GET", "/api/tickets/%s" % tid)["data"]
    check("A7", "工单为 RED / INFO_INCOMPLETE / 顾客已确认",
          detail["qualityLevel"] == "RED"
          and detail["status"] == "INFO_INCOMPLETE"
          and detail["customerConfirmed"],
          "level=%s status=%s confirmed=%s"
          % (detail["qualityLevel"], detail["status"], detail["customerConfirmed"]))
    check("A8", "摘要采用顾客改过的那句",
          detail["summary"] == edited, detail["summary"])

    r = call(zhang, "PATCH", "/api/tickets/%s" % tid, {"status": "ASSIGNED"})
    check("A9", "不带 version 保存 → 400 拒绝无锁更新", r["code"] == 400, r["message"])

    r = call(zhang, "PATCH", "/api/tickets/%s" % tid,
             {"version": detail["version"], "status": "ASSIGNED"})
    ok = (r["code"] == 0
          and r["data"]["version"] == detail["version"] + 1
          and r["data"]["assignee"] == "张三")
    check("A10", "带正确 version → 成功，版本+1，自动认领", ok,
          "v%s→v%s assignee=%s lastActor=%s"
          % (detail["version"], r.get("data", {}).get("version"),
             r.get("data", {}).get("assignee"), r.get("data", {}).get("lastActor")))

    # ---------------------------------------------------------- 阶段三
    print("\n-- 阶段三：顾客侧状态回执 --")

    v1 = call(customer, "GET", "/api/chat/sessions/%s/status" % sid)["data"]
    check("A11", "受理后首次轮询 → 有新回执",
          v1["changed"] and "受理" in (v1["notice"] or ""), v1["notice"])

    v2 = call(customer, "GET", "/api/chat/sessions/%s/status" % sid)["data"]
    check("A12", "再轮询 → 不重复回执（幂等）", not v2["changed"],
          "changed=%s" % v2["changed"])

    # ---------------------------------------------------------- 阶段四
    print("\n-- 阶段四：并发冲突 --")

    call(li, "POST", "/api/auth/login", {"username": "lisi", "password": "123456"})
    stale = detail["version"]      # 李四手上是张三改动之前的版本
    r = call(li, "PATCH", "/api/tickets/%s" % tid,
             {"version": stale, "status": "PROCESSING"})
    check("A13", "李四用旧版本保存 → 409，提示含两个版本号",
          r["code"] == 409 and ("v%d" % stale) in r["message"], r["message"])

    fresh = call(li, "GET", "/api/tickets/%s" % tid)["data"]
    r = call(li, "PATCH", "/api/tickets/%s" % tid,
             {"version": fresh["version"], "status": "RESOLVED", "decision": "批准换货"})
    check("A14", "李四刷新后保存 → 成功，lastActor 变李四",
          r["code"] == 0 and r["data"]["lastActor"] == "李四",
          "v%s status=%s lastActor=%s"
          % (r["data"]["version"], r["data"]["status"], r["data"]["lastActor"]))

    # ---------------------------------------------------------- 阶段五
    print("\n-- 阶段五：收尾 --")

    v3 = call(customer, "GET", "/api/chat/sessions/%s/status" % sid)["data"]
    check("A15", "解决后回执带处理结果",
          v3["changed"] and "批准换货" in (v3["notice"] or ""), v3["notice"])

    v4 = call(customer, "GET", "/api/chat/sessions/%s/status" % sid)["data"]
    check("A16", "终态后不再产生新回执", not v4["changed"],
          "changed=%s" % v4["changed"])

    r = call(customer, "POST", "/api/chat/sessions/%s/messages" % sid,
             {"content": "还有个事"})
    check("A17", "已出单的会话不能再发消息 → 409", r["code"] == 409, r["message"])

    messages = call(customer, "GET", "/api/chat/sessions/%s/messages" % sid)["data"]
    receipts = [m for m in messages if "工单 #" in m["content"]]
    check("A18", "会话历史里留下 2 条状态回执", len(receipts) == 2,
          "回执数=%d" % len(receipts))

    # ---------------------------------------------------------- 汇总
    passed = sum(1 for r in results if r[2])
    print("\n=== 结果：%d/%d 通过 ===" % (passed, len(results)))
    if passed != len(results):
        print("失败项：", [r[0] for r in results if not r[2]])
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as e:
        print("HTTP 错误：%s %s" % (e.code, e.read().decode("utf-8", "ignore")))
        sys.exit(2)
    except urllib.error.URLError:
        print("连不上后端，先启动：mvn spring-boot:run")
        sys.exit(2)
