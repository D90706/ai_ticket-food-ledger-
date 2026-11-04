# -*- coding: utf-8 -*-
"""
SPIKE-01 真实执行器：用同一套通用 prompt 驱动两个差异 schema。
API key 从环境变量 DEEPSEEK_API_KEY 读取，不落盘。
输出：raw-01.json（全部原始响应）
"""
import json
import os
import ssl
import time
import urllib.request

API_URL = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-chat"
CURRENT_DATE = "2026-10-20"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "raw-01.json")


# ---------------------------------------------------------------- schemas
SCHEMAS = {
    "cs_after_sale": {
        "name": "电商售后工单",
        "role": "你是服装店的售后助手。语气克制，不承诺赔付、不承诺到货时间。",
        "knowledge": (
            "【退换货政策】签收 7 天内可无理由退换，商品需不影响二次销售。"
            "吊牌已剪、已洗涤、已穿着外出的不支持无理由退换。"
            "质量问题由卖家承担往返运费；非质量问题由买家承担。"
            "【尺码表】S 适合身高 155-165 / M 适合 165-170 / L 适合 170-178"
        ),
        "decision_options": ["批准换货", "批准退货", "拒绝", "要求补证", "转售后专岗"],
        "fields": [
            {"key": "order_id", "label": "订单号", "type": "text", "required": True},
            {"key": "reason", "label": "原因", "type": "enum", "required": True,
             "options": ["尺码不合适", "质量问题", "发错货", "其他"]},
            {"key": "worn", "label": "是否已穿着或洗涤", "type": "boolean", "required": False},
            {"key": "size_wanted", "label": "希望换成的尺码", "type": "text", "required": False},
            {"key": "evidence", "label": "商品问题照片", "type": "image", "required": False},
        ],
    },
    "it_repair": {
        "name": "IT设备报修工单",
        "role": "你是公司 IT 服务台的接单助手。只做信息整理，不判断责任。",
        "knowledge": (
            "【处理流程】硬件故障转技术支持；账号权限问题转信息科；"
            "网络问题先远程排查再决定是否上门。"
            "影响面为「全公司」的事件需 30 分钟内响应。"
        ),
        "decision_options": ["指派技术支持", "远程处理", "上门维修", "更换设备", "拒绝"],
        "fields": [
            {"key": "device_no", "label": "设备编号", "type": "text", "required": True},
            {"key": "fault_type", "label": "故障类型", "type": "enum", "required": True,
             "options": ["硬件故障", "软件故障", "网络问题", "账号权限", "其他"]},
            {"key": "impact_scope", "label": "影响范围", "type": "enum", "required": True,
             "options": ["仅本人", "整个部门", "全公司"]},
            {"key": "urgent", "label": "是否影响当前业务", "type": "boolean", "required": False},
            {"key": "first_seen", "label": "问题最早出现日期", "type": "date", "required": False},
            {"key": "screenshot", "label": "报错截图", "type": "image", "required": False},
        ],
    },
}


# ---------------------------------------------------------------- cases
CASES = [
    ("A1", "cs_after_sale",
     "客服你好，我上周买的卫衣，订单号 20261008XXXX，收到的 M 码太大了，"
     "我平时穿 S 的，想换成 S。衣服吊牌还没剪，没穿过。"),
    ("A2", "cs_after_sale",
     "我买的裤子有问题，能不能退？"),
    ("A3", "cs_after_sale",
     "收到货发现袖口有一道划痕，缝线也不齐，质量也太差了。"
     "订单号 20261015ABC123，这件我试了一下，还没洗过。"),
    ("A4", "cs_after_sale",
     "我上次买的卫衣换过一次了，现在又出问题了，你们这什么质量？"
     "要么退款要么给我个说法。订单号 20261001XYZ。"),
    ("B1", "it_repair",
     "我这边笔记本开不了机，按电源键没反应，设备编号 PC-2031，"
     "今天早上开始的。整个部门今天要用它做汇报，比较急。"),
    ("B2", "it_repair",
     "OA 系统登不上去了，提示密码错误，重置了也不行。"),
    ("B3", "it_repair",
     "三楼会议室这边的 Wi-Fi 全部连不上，我们组 8 个人都没网，从下午两点开始。"),
    ("B4", "it_repair",
     "打印机打出来的纸是空白的，之前偶尔会这样，这两天一直这样。"),
]


# ---------------------------------------------------------------- prompt
def few_shot(schema):
    """按字段类型机械填一个合法值，自动生成示例（不针对任何具体业务）。"""
    values = {}
    for f in schema["fields"]:
        t = f["type"]
        if t == "text":
            values[f["key"]] = "示例文本"
        elif t == "enum":
            values[f["key"]] = f["options"][0]
        elif t == "boolean":
            values[f["key"]] = False
        elif t == "date":
            values[f["key"]] = "2026-10-01"
        else:  # image
            values[f["key"]] = None
    return json.dumps({
        "values": values,
        "missing_slots": [k for k, v in values.items() if v is None],
        "confidence": 0.9,
        "decision_suggestion": schema["decision_options"][0],
    }, ensure_ascii=False, indent=2)


def build_messages(schema, dialogue):
    lines = []
    for f in schema["fields"]:
        req = "必填" if f["required"] else "选填"
        opt = ""
        if f["type"] == "enum":
            opt = " 候选: " + " / ".join(f["options"])
        lines.append("- %s | %s | %s | %s%s" % (f["key"], f["label"], f["type"], req, opt))
    field_block = "\n".join(lines)

    system = f"""你是「{schema['role']}」。

【领域知识】
{schema['knowledge']}

【可用决策选项】
只能从下列选项中选择一个，禁止自创：
{'; '.join(schema['decision_options'])}

【需要抽取的字段】
{field_block}

【当前日期】
{CURRENT_DATE}

【输出要求】
严格输出一个 JSON 对象。不要解释，不要 markdown 代码块，不要多余文字。
结构：
{{
  "values": {{ "字段key": 值 }},
  "missing_slots": ["字段key"],
  "confidence": 0.0,
  "decision_suggestion": "决策选项之一 或 null"
}}

【硬规则】
1. 无法从对话中确定的值一律填 null，并把该 key 加入 missing_slots。
   禁止猜测，禁止编造，禁止用常识补全。
2. enum 字段只能取给定候选值之一，否则填 null 并计入 missing_slots。
3. boolean 字段只能输出 true 或 false。
   若对话未明确说明，或说明存在歧义（如"试了一下"是否算已穿着），
   一律填 null 并计入 missing_slots。
4. date 字段输出 yyyy-MM-dd。相对时间按【当前日期】换算；
   无法精确到日（如"这两天""前阵子"）一律填 null 并计入 missing_slots。
5. image 类型字段你无法处理，一律填 null 并计入 missing_slots。
6. missing_slots 必须与 values 中的 null 项完全一致，不多不少。
7. confidence 表示你对 values 整体准确性的把握。
8. confidence < 0.6 时 decision_suggestion 必须为 null。

【示例】
{few_shot(schema)}"""
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": "【对话内容】\n" + dialogue},
    ]


# ---------------------------------------------------------------- call
def call(messages, json_mode):
    key = os.environ["DEEPSEEK_API_KEY"]
    payload = {"model": MODEL, "messages": messages,
               "temperature": 0, "max_tokens": 900}
    if json_mode:
        payload["response_format"] = {"type": "json_object"}
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        API_URL, data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=120, context=ctx) as r:
        d = json.loads(r.read().decode("utf-8"))
    return {
        "content": d["choices"][0]["message"]["content"],
        "model_returned": d.get("model"),
        "finish_reason": d["choices"][0].get("finish_reason"),
        "usage": d.get("usage"),
        "latency_s": round(time.time() - t0, 2),
    }


# ---------------------------------------------------------------- checks
def analyze(schema, raw):
    """自动检查：JSON 可解析性 / 枚举越界 / null 与 missing_slots 一致性。"""
    out = {"parsed": False, "fenced": False, "json_error": None}
    txt = (raw or "").strip()
    if txt.startswith("```"):
        out["fenced"] = True
        txt = txt.strip("`")
        if txt.lower().startswith("json"):
            txt = txt[4:]
        txt = txt.strip()
    try:
        obj = json.loads(txt)
    except Exception as e:
        out["json_error"] = "%s: %s" % (type(e).__name__, e)
        return out
    out["parsed"] = True

    values = obj.get("values") or {}
    missing = obj.get("missing_slots") or []
    req_keys = [f["key"] for f in schema["fields"] if f["required"]]

    nulls = [k for k, v in values.items() if v is None]
    absent = [f["key"] for f in schema["fields"] if f["key"] not in values]

    out["null_in_values"] = sorted(nulls)
    out["absent_keys"] = sorted(absent)
    out["missing_slots"] = missing
    out["missing_required"] = sorted([k for k in req_keys
                                      if values.get(k) is None])
    # 一致性：模型报告的 missing 是否等于 values 里所有 null
    out["consistent_with_nulls"] = sorted(missing) == sorted(nulls + absent)
    # 枚举越界
    bad = []
    for f in schema["fields"]:
        if f["type"] == "enum":
            v = values.get(f["key"])
            if v is not None and v not in f["options"]:
                bad.append({"key": f["key"], "value": v})
    out["enum_out_of_range"] = bad
    # 数值合法化：boolean 是否被填成字符串
    wrong_type = []
    for f in schema["fields"]:
        v = values.get(f["key"])
        if v is None:
            continue
        if f["type"] == "boolean" and not isinstance(v, bool):
            wrong_type.append({"key": f["key"], "value": v})
    out["type_mismatch"] = wrong_type

    out["confidence"] = obj.get("confidence")
    out["decision_suggestion"] = obj.get("decision_suggestion")
    return out


def main():
    results = []
    for cid, skey, dialogue in CASES:
        schema = SCHEMAS[skey]
        msgs = build_messages(schema, dialogue)
        for json_mode in (False, True):
            tag = "jsonmode" if json_mode else "natural"
            try:
                r = call(msgs, json_mode)
                a = analyze(schema, r["content"])
            except Exception as e:
                r = {"error": "%s: %s" % (type(e).__name__, e)}
                a = {"parsed": False, "json_error": str(e)}
            results.append({"case": cid, "schema": skey, "mode": tag,
                            "raw": r, "analysis": a})
            print("[%s %s] parsed=%s req_missing=%s lat=%ss" % (
                cid, tag, a.get("parsed"), a.get("missing_required"),
                r.get("latency_s")), flush=True)

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"model_requested": MODEL,
                   "current_date": CURRENT_DATE,
                   "schemas": SCHEMAS,
                   "results": results}, f, ensure_ascii=False, indent=2)
    print("saved:", OUT)


if __name__ == "__main__":
    main()
