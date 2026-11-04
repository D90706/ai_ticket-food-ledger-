# -*- coding: utf-8 -*-
"""
验证「凭证可以上传照片」这条链路。

背景：AI 追问里会点名「索证索票凭证」，但法定要求是**保存凭证**，
一张许可证/检疫证明的照片才是凭证，一串抄下来的编号不是。
所以：照片和编号等效，传了照片就不再追问凭证，也不再判缺凭证。

用法：python qa/verify-certificate-upload.py
"""

import http.cookiejar
import json
import struct
import urllib.request
import uuid
import zlib

BASE = "http://localhost:8081"


def sess():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(op, method, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
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


def upload(op):
    boundary = "----" + uuid.uuid4().hex
    body = (("--%s\r\n" % boundary).encode()
            + 'Content-Disposition: form-data; name="file"; filename="检疫证明.png"\r\n'.encode()
            + b"Content-Type: image/png\r\n\r\n" + png()
            + ("\r\n--%s--\r\n" % boundary).encode())
    req = urllib.request.Request(BASE + "/api/uploads", data=body, method="POST",
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with op.open(req, timeout=60) as resp:
        return json.loads(resp.read().decode())["data"]


CREATED = []


def main():
    buyer = sess()
    call(buyer, "POST", "/api/auth/login", {"username": "caigou", "password": "123456"})
    print("=" * 74)

    # ---------- 1 对话里追问凭证 ----------
    print("\n[1] AI 追问里会不会点名凭证")
    sid = call(buyer, "POST", "/api/entry/sessions")["data"]["sessionId"]
    a = call(buyer, "POST", "/api/entry/sessions/%d/messages" % sid, {
        "content": "今天从城南肉联厂进了20斤土豆，规格散装，生产日期2026-10-26，"
                   "批号CERT-1，保质期7天，进货日期2026-10-28"
    })["data"]
    print("  replyType=%s" % a["replyType"])
    print("  问句：%s" % a.get("question"))
    mentions = any(k in (a.get("question") or "") for k in ("凭证", "证明"))
    print("  追问里提到凭证：%s" % ("是" if mentions else "否"))
    print("  是否提示可以直接传照片：%s"
          % ("是" if "传照片" in (a.get("question") or "") else "否 ← 需要补提示"))

    # ---------- 2 上传照片 ----------
    print("\n[2] 上传凭证照片并挂到这次录入上")
    att = upload(buyer)
    print("  上传结果：%s (%s 字节)" % (att["url"], att["size"]))
    r = call(buyer, "POST", "/api/entry/sessions/%d/attachments" % sid, att)["data"]
    print("  挂载后 replyType=%s" % r["replyType"])
    print("  回复：%s" % r.get("question"))
    after = r.get("question") or ""
    stub = "索证索票凭证" in after
    print("  还差列表里仍有凭证：%s" % ("是 ← 没生效" if stub else "否（照片已被当成凭证）"))

    # ---------- 3 出单后判定 ----------
    print("\n[3] 生成的台账记录：凭证是否仍算缺失")
    done = r if r["replyType"] == "RECORD" else (
        call(buyer, "POST", "/api/entry/sessions/%d/messages" % sid,
             {"content": "供货者地址城南大道88号，电话13800001111"})["data"] or {})
    rid = done.get("recordId")
    if rid:
        CREATED.append(rid)
        d = call(buyer, "GET", "/api/ledger/%d" % rid)["data"]
        missing = [m["label"] for m in d["quality"]["missing"]]
        print("  记录 #%s 级别=%s 附件=%d 个" % (d["id"], d["qualityLevel"], len(d["attachments"])))
        print("  仍缺：%s" % ("、".join(missing) if missing else "无"))
        print("  凭证是否被算作缺失：%s"
              % ("是 ← 没生效" if "索证索票凭证" in missing else "否"))
    else:
        print("  未出单，replyType=%s" % done.get("replyType"))

    # ---------- 4 手填带附件 ----------
    print("\n[4] 手填：只传凭证照片、不填编号")
    att2 = upload(buyer)
    vals = {
        "ingredient_name": "CERT-白菜", "spec": "散装", "quantity": "10", "unit": "斤",
        "production_date": "2026-10-26", "batch_no": "CERT-2", "shelf_life": "3天",
        "purchase_date": "2026-10-28", "supplier_name": "CERT-供货",
        "supplier_address": "CERT地址", "supplier_phone": "13800001111",
        "expiry_date": "2026-11-02",
        "attachments": [att2],
    }
    d2 = call(buyer, "POST", "/api/ledger/values", {"values": vals})["data"]
    CREATED.append(d2["id"])
    missing2 = [m["label"] for m in d2["quality"]["missing"]]
    print("  记录 #%s 级别=%s（%s）" % (d2["id"], d2["qualityLevel"], d2["qualityLabel"]))
    print("  仍缺：%s" % ("、".join(missing2) if missing2 else "无"))
    print("  没填编号但传了照片 → 凭证算有：%s"
          % ("是" if "索证索票凭证" not in missing2 else "否 ← 没生效"))

    # ---------- 5 一张都没传时仍应判缺 ----------
    print("\n[5] 反例：既没编号也没照片，凭证必须仍算缺失")
    vals3 = dict(vals, ingredient_name="CERT-萝卜", batch_no="CERT-3")
    vals3.pop("attachments")
    d3 = call(buyer, "POST", "/api/ledger/values", {"values": vals3})["data"]
    CREATED.append(d3["id"])
    miss3 = [m["label"] for m in d3["quality"]["missing"]]
    print("  记录 #%s 级别=%s 仍缺：%s" % (d3["id"], d3["qualityLevel"], "、".join(miss3)))
    print("  凭证仍算缺失：%s" % ("是（正确）" if "索证索票凭证" in miss3 else "否 ← 过宽了"))

    cleanup()


def cleanup():
    admin = sess()
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
                {"version": rec["version"], "reason": "凭证上传验证数据"})["code"] == 0:
            n += 1
    print("\n已作废 %d 条验证记录" % n)


if __name__ == "__main__":
    main()
