# -*- coding: utf-8 -*-
"""
验证本轮三个反馈的修复：
  4 图片加载（/uploads 代理）
  5 补录后状态回流（退回补录 -> 待入账）
  6 会话失效跳登录（401 行为）
用法：python qa/verify-round2.py
"""

import http.cookiejar
import json
import struct
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

BACK = "http://localhost:8081"
DEV = "http://localhost:5174"


def sess():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(op, method, path, body=None, base=BACK):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with op.open(req, timeout=180) as resp:
        return json.loads(resp.read().decode())


def png():
    def chunk(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\x00\x00\xff\x00"))
            + chunk(b"IEND", b""))


def main():
    buyer, mgr = sess(), sess()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
    call(mgr, "POST", "/api/auth/login", {"username": "guanli", "password": "123456"})
    print("=" * 74)

    # ---------- 反馈 4：图片加载 ----------
    print("\n[反馈 4] 图片能不能加载")
    boundary = "----" + uuid.uuid4().hex
    body = (("--%s\r\n" % boundary).encode()
            + b'Content-Disposition: form-data; name="file"; filename="v.png"\r\n'
            + b"Content-Type: image/png\r\n\r\n" + png()
            + ("\r\n--%s--\r\n" % boundary).encode())
    req = urllib.request.Request(BACK + "/api/uploads", data=body, method="POST",
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with mgr.open(req, timeout=60) as resp:
        url = json.loads(resp.read().decode())["data"]["url"]
    print("  上传返回 url: %s" % url)

    ok_back = False
    with mgr.open(BACK + url, timeout=30) as r:
        ok_back = r.status == 200 and r.headers.get("Content-Type", "").startswith("image/png")
    print("  直连后端 8081:  HTTP 200 + image/png = %s" % ok_back)

    try:
        with mgr.open(DEV + url, timeout=30) as r:
            ctype = r.headers.get("Content-Type", "")
            data = r.read()
        ok_dev = r.status == 200 and ctype.startswith("image/png") and data == png()
        print("  经前端 5174:    HTTP %s, Content-Type=%s, 字节=%d -> %s"
              % (r.status, ctype.split(";")[0], len(data), "正确" if ok_dev else "错误"))
        if not ok_dev and ctype.startswith("text/html"):
            print("    ↑ 拿到的是 index.html：说明 /uploads 没被代理，<img> 自然显示不出来")
    except urllib.error.HTTPError as e:
        print("  经前端 5174:    HTTP %s（失败）" % e.code)

    # ---------- 反馈 5：补录后状态 ----------
    print("\n[反馈 5] 补录后状态是否回流")
    vals = {
        "ingredient_name": "V-白菜", "spec": "散装", "quantity": "10", "unit": "斤",
        "production_date": "2026-10-26", "batch_no": "V-1", "shelf_life": "3天",
        "purchase_date": "2026-10-28", "supplier_name": "V-供货",
        "supplier_address": "V地址", "supplier_phone": "13800001111",
        "certificate": "V-CERT", "category": "蔬菜",
    }
    d = call(buyer, "POST", "/api/ledger/values", {"values": vals})["data"]
    rid = d["id"]
    print("  新建 #%s 状态=%s 级别=%s" % (rid, d["statusLabel"], d["qualityLabel"]))

    r = call(mgr, "POST", "/api/ledger/%s/return" % rid,
             {"version": d["version"], "note": "请补到期日与仓库"})
    print("  管理员退回 -> %s（退回次数 %s）" % (r["data"]["statusLabel"], r["data"]["returnCount"]))

    cur = call(buyer, "GET", "/api/ledger/%s" % rid)["data"]
    up = call(buyer, "PUT", "/api/ledger/%s" % rid,
              {"version": cur["version"], "expiry_date": "2026-11-01", "warehouse": "一号库"})
    after = up["data"]
    print("  采购员补录 -> 状态=%s 级别=%s 修订号=%s"
          % (after["statusLabel"], after["qualityLabel"], after["revision"]))
    print("  状态是否回流到「待入账」: %s" % ("是（修复生效）" if after["status"] == "PENDING" else "否 ← 仍是断点"))

    # 补录完应该还能入账（流程通了）
    post = call(mgr, "POST", "/api/ledger/%s/post" % rid, {"version": after["version"]})
    print("  补录后管理员入账: code=%s %s" % (post["code"], post["message"]))

    # ---------- 反馈 6：会话失效 ----------
    print("\n[反馈 6] 会话失效后的行为（接口层）")
    anon = sess()   # 不带 cookie，等价于 cookie 被清掉

    def probe(path):
        """后端把业务错误放在 HTTP 200 + body.code 里，必须读 code 而不是看有没有抛异常。"""
        try:
            body = call(anon, "GET", path)
            return body.get("code"), body.get("message")
        except urllib.error.HTTPError as e:
            return e.code, "http-error"

    code, msg = probe("/api/ledger/page")
    print("  未登录访问台账: code=%s（%s）→ 前端整页跳登录 %s"
          % (code, msg, "✓" if code == 401 else "✗"))
    code2, msg2 = probe("/api/auth/me")
    print("  未登录探测身份: code=%s（%s）→ silent，不弹提示不跳转 %s"
          % (code2, msg2, "✓" if code2 == 401 else "✗"))
    print("  注意：后端业务错误统一是 HTTP 200 + body.code，")
    print("        所以「有没有抛异常」不能用来判断权限是否生效。")

    # 清理
    latest = call(mgr, "GET", "/api/ledger/%s" % rid)["data"]
    if latest["status"] != "VOID":
        call(mgr, "POST", "/api/ledger/%s/void" % rid,
             {"version": latest["version"], "reason": "验证脚本测试数据"})
        print("\n已作废测试记录 #%s" % rid)


if __name__ == "__main__":
    main()
