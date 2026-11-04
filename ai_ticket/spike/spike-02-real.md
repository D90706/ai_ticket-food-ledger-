================================================================
SPIKE-02 · 真实 API 执行结果（覆盖 SPIKE-01 的模拟结论）
日期：2026-10-20
数据：spike/raw-01.json  （16 次真实调用，原始响应）
      spike/report-01.txt （逐条精简报告）
================================================================

----------------------------------------------------------------
【零、执行元信息】
----------------------------------------------------------------

  端点        https://api.deepseek.com/chat/completions
  请求模型    deepseek-chat
  返回模型    deepseek-flash          ← 注意：与请求不一致
  temperature 0
  调用次数    8 用例 × 2 模式 = 16
  模式        natural（不带 response_format）
              jsonmode（response_format = json_object）
  耗时        0.63s ~ 1.30s，平均约 0.95s
  结果文件    raw-01.json / report-01.txt

  关于 model_returned 不一致：
    请求 deepseek-chat，响应体里 model 字段是 deepseek-flash。
    可能是该 key 背后的网关做了模型映射，也可能是别名。
    需确认，因为这直接影响抽取质量与计费。
    （本报告全部结论都基于实际返回的这个模型。）


----------------------------------------------------------------
【一、全部用例的抽取结果（真实数据）】
----------------------------------------------------------------

▍A1 完整案
  natural  values={order_id:20261008XXXX, reason:尺码不合适,
                   worn:false, size_wanted:S, evidence:null}
           missing=[evidence]  conf=0.9  suggest=批准换货
  jsonmode 完全相同
  ✅ 两项都正确。选填项 evidence 进 missing，符合规则 6。

▍A2 几乎无信息（"我买的裤子有问题，能不能退？"）
  natural  values={全 null}  conf=0.2  suggest=null
  jsonmode  values={reason:"质量问题", 其余 null}  conf=0.4  suggest=null
  ⚠️ 两种模式不一致：jsonmode 把"有问题"猜成了"质量问题"。
     ← json_mode 反而更容易编。

▍A3 模糊布尔（"试了一下，还没洗过"）
  natural  values={worn:null, ...}  conf=0.55  suggest=null
  jsonmode  values={worn:null, ...}  conf=0.75  suggest=要求补证
  ✅ worn 老实留空了 —— 与 SPIKE-01 的模拟预测相反。
     但注意：prompt 规则 3 里恰好写了"试了一下"这个原话作为反例，
     等于给了提示。测试存在污染（见第三节 F5）。

▍A4 语义模糊 + 情绪（"又出问题了…你们这什么质量"）
  natural  values={reason:"质量问题", size_wanted:null, ...}
           missing=[worn, evidence]  ← ✗ 漏了 size_wanted
           conf=0.55  suggest=null
  jsonmode  values 同上
           missing=[worn, size_wanted, evidence]  ← 完整
           conf=0.55  suggest=null
  ❌ reason 被编成"质量问题"。两种模式都复现。
     因果很清楚：顾客说的是情绪（"什么质量"），不是事实（"质量问题"）。

▍B1 完整案（"整个部门今天要用它做汇报"）
  natural / jsonmode  values={device_no:PC-2031, fault_type:硬件故障,
                   impact_scope:整个部门, urgent:true,
                   first_seen:2026-10-20, screenshot:null}
           missing=[screenshot]  conf=0.9  suggest=指派技术支持
  ⚠️ impact_scope 是推断出来的：原话说的是"用途"，不是"影响范围"。
     date 换算正确（"今天早上" → 2026-10-20）。

▍B2 缺设备编号
  natural / jsonmode  values={device_no:null, fault_type:账号权限,
                   impact_scope:null, ...}
           conf=0.7  suggest=null
  ✅ 行为正确。但 conf=0.7 而缺两个必填项 —— 见 F2。

▍B3 必填项天然不可满足（会议室 Wi-Fi）
  natural   values={device_no:null, fault_type:网络问题,
                    impact_scope:整个部门, first_seen:2026-10-20, ...}
            conf=0.75  suggest=远程处理        ← ✗
  jsonmode  values={device_no:null, fault_type:网络问题,
                    impact_scope:整个部门, first_seen:null, ...}
            conf=0.75  suggest=远程处理        ← ✗
  ❌ 两个问题：
     (1) device_no 必填但永远问不出来 → SPIKE-01 预判的结构性卡死，复现。
     (2) 必填项缺失的情况下，conf=0.75 且给出了决策建议。
         规则 8 只在 conf<0.6 时禁用建议，0.75 直接放行了。
         这张工单会带着"远程处理"这个建议流到人工手里。
  ⚠️ first_seen 两种模式不一致：一个填了 2026-10-20，一个是 null。

▍B4 相对时间模糊（"这两天一直这样"）
  natural / jsonmode  values={device_no:null, fault_type:硬件故障,
                   impact_scope:null, first_seen:null, ...}
           conf=0.55 / 0.5  suggest=null
  ✅ first_seen 没有硬凑日期。两种模式都守住了。


----------------------------------------------------------------
【二、量化统计】
----------------------------------------------------------------

  JSON 可解析              16 / 16   （包括 natural 模式，全部纯 JSON，
                                     没有出现 markdown 代码块）
  枚举越界                 0 / 16
  类型错误（boolean 给字符串） 0 / 16
  missing_slots 与 null 一致  15 / 16  （A4 natural 漏了一项）
  出现必填字段缺失的用例     8 / 16   （设计使然：4 条含缺失压力）

  非确定性：同一输入跑两次出现差异的用例 —— 2 条
      A2  reason：null  vs "质量问题"
      B3  first_seen：2026-10-20 vs null
      → temperature=0 也不保证幂等。


----------------------------------------------------------------
【三、六个发现（按重要性排序）】
----------------------------------------------------------------

▍F1【最重要】missing_slots 不能当作真相来源

  A4 natural 的 values 里有 3 个 null（worn / size_wanted / evidence），
  但 missing_slots 只报了 2 个，漏了 size_wanted。
  而这条是唯一一处"一致性"失败，占 1/16。

  结论：values 是事实，missing_slots 是模型的自我描述，两者会不一致。
  代码必须以 values + schema 推导缺失，而不是相信 missing_slots。

  → 修正 R1（见第四节）


▍F2 confidence 与"信息完整度"完全无关，不能承担任何流程控制

  B3：缺 1 个必填项，conf = 0.75
  B2：缺 2 个必填项，conf = 0.70
  B4：缺 2 个必填项，conf = 0.50~0.55

  同一个数据集里，缺得少的反而给得高，缺得多的也不低。
  confidence 度量的是"它对已填值的把握"，不是"这张工单够不够用"。
  这恰好是两个不同的东西，而流程控制需要的是后者。

  → 修正 R2、R3


▍F3 主要风险面是 enum，不是 boolean

  SPIKE-01 的模拟预测是"布尔歧义会硬填"，实测不支持（A3 两次都留空）。
  真实的高危点是枚举：

      A2 jsonmode  reason ← 把"有问题"猜成"质量问题"
      A4 两模式     reason ← 把情绪"什么质量"读成事实"质量问题"
      B1 两模式     impact_scope ← 把"用途"推断成"影响范围"

  为什么 enum 比 boolean 危险得多：
      工单的 decision_options 决策几乎全部由 enum 字段驱动。
      reason 一旦猜错，人工会直接按错误的原因批准换货或退货。
      而 boolean 填错，通常只是少一个辅助判断。

  → 修正 R4


▍F4 json_mode 让格式变可靠，但让内容更容易编

  A2 是唯一一个两模式结论不同的用例：
      natural  reason=null（保守）
      jsonmode reason="质量问题"（编造）

  机制推测：response_format=json_object 把模型的注意力
  推向"输出一个完整合法的 JSON 对象"，于是它更不愿留空。

  这是个真实的权衡：
      json_mode  → 解析成功率 16/16，但可能更爱猜
      natural    → 同样 16/16，猜得少一点，但缺项概率高一点（A4）

  建议：仍然用 json_mode（格式稳定是第一位的），
       但用 R2/R3 的代码兜底去抵消它"爱猜"的副作用。


▍F5 规则里写具体反例有效，但产品无法复制这个技巧

  表现最好的两条（A3 的 worn、B4 的 first_seen）恰好是
  规则 3、4 里点了具体反例的字段：
      规则 3 写了"如'试了一下'是否算已穿着"
      规则 4 写了"如'这两天''前阵子'"
  而 A3 的原话就是"试了一下"——等于把答案写进了 prompt。

  所以 A3 的通过不能算模型"老实"，只能算规则"针对性强"。
  这个技巧在演示上很有用（可以逐字段加反例），
  但在产品上不可持续：用户定义的字段和未知的歧义无法预先枚举。

  → 结论：反例是优化手段，不是保障机制。兜底仍要靠代码。


▍F6 同输入不同输出，temperature=0 也不幂等

  A2 和 B3 各有一处差异。这意味着：
      如果你提供"重新抽取"按钮，
      同一段对话第二次生成的工单可能与第一次不同。

  → 修正 R5


----------------------------------------------------------------
【四、最终设计修正（本报告取代 SPIKE-01 第七节）】
----------------------------------------------------------------

▍R1  缺失判定以 values 为准，missing_slots 只作参考

  代码逻辑：
      null_fields = [k for k,v in values.items() if v is None]
                  + [字段 in schema 但不在 values 里]
      missing_required = null_fields ∩ 必填
      missing_optional = null_fields ∩ 选填

  模型的 missing_slots 只用于日志/调试，不参与任何分支判断。


▍R2  决策建议的放行权交给代码，不交给 confidence

  一旦 missing_required 非空：
      强制将 decision_suggestion 置空
      即使模型给了建议（如 B3 的"远程处理"）也丢弃

  理由：B3 证明模型会在缺必填项时自信地给建议。
      这条规则必须写在代码里，写不进 prompt。

  prompt 规则 8 可以保留（聊胜于无），但不能作为唯一防线。


▍R3  confidence 降级为"工单上的一个展示字段"

  不参与熔断、不参与分支、不驱动任何状态变更。
  工单上以"AI 自评"标注，供人工参考。

  理由：F2 已证明它与信息完整度无关。


▍R4  enum 字段必须可追溯：增加 basis（原文依据）

  在输出结构里，对每个非 null 的 enum 字段要求附带原文依据：

      "values": { "reason": "质量问题" },
      "basis":  { "reason": "你们这什么质量？" }    ← 必须是从对话中截取的原文

  用途：
      (1) 逼模型"举证"。A4 那种情绪误读，在要求引用原文后
          很难自圆其说——"什么质量"不是"质量问题"的陈述。
      (2) 即使它仍然猜错，人工在工单上能看到依据，警觉性大幅提高。
      (3) basis 为空但 enum 非 null → 代码直接判定为不可信，转人工。

  成本：prompt 加一段，输出结构加一个字段，工单详情加一行小字。
  这是本轮所有修正里性价比最高的一条。


▍R5  工单一旦生成即固化，不做重复抽取

  A2 / B3 证明了非幂等。所以：
      - 不提供"重新抽取"按钮（或提供了必须提示"将覆盖现有工单"）
      - 抽取结果 + schema 快照 一起落库，之后只读
      - 需要修正时走"人工编辑工单"，不重新调用模型


▍R6  追问出口仍然必须存在（SPIKE-01 修正 3 保留，且被 B3 坐实）

  追问次数 >= 2 且 missing_required 仍非空
      → 强制出单，状态标「信息不全」
      → 工单顶部显式列出缺失项

  B3 是标准案例：会议室 Wi-Fi 没有设备编号，
  这个必填项永远无法满足。没有这条出口，工单会永久卡死。


▍R7  模板默认带 summary 说明字段（SPIKE-01 修正 4 保留）

  A4 的激烈情绪在结构化工单里完全不可见。
  A3 的"缝线也不齐"这类描述也没有落点。
  加一个不可删除的 L2 字段：

      summary  text  一句顾客诉求与情绪的概述（不参与决策）

  它保住 schema 表达不了的信息，成本一个字段。


----------------------------------------------------------------
【五、假设判定（最终）】
----------------------------------------------------------------

  A. 一套 prompt 能适配任意 schema
     ✅ 成立 【真实数据】
     16/16 纯 JSON，0 枚举越界，0 类型错误，
     两个结构差异很大的 schema 用同一份 prompt 全部跑通，
     prompt 里没有任何 schema 专属指令。

  B. 模型会如实输出 missing_slots / confidence
     ❌ 不成立 【真实数据】
     但失败方式和模拟预测的不同（预测布尔，实际是枚举 + 一致性 + 置信度）。
     追问闭环必须由代码掌控，这是本次最重要的架构级结论。

  C. 抽取够准，人工能 30 秒决策
     ⚠️ 有条件成立
     信息完整的两条（A1、B1）质量确实够，人工能快速决策。
     但 A4 这类"原因被判错"的工单会误导决策，
     危害比"字段空缺"更大 —— 空缺会被发现，错误不会被发现。

     → 由此可推出一条新的指标：
        「隐性错误率」比「字段准确率」更重要。
        人工抽查时应该专门检查：填了值的 enum 是否真在原文里有依据。
        R4 的 basis 设计正好让这个检查变成可执行的动作。


----------------------------------------------------------------
【六、给实现的直接影响】
----------------------------------------------------------------

  数据表要多两列：
      ticket.basis        JSON     ← enum 的原文依据（R4）
      ticket.summary      text     ← L2 说明字段（R7）

  ticket 状态枚举要多一个：
      信息不全（追问超限强制出单）

  Orchestrator 的分支判断完全基于代码：
      null_fields（从 values 推导）
        ├─ missing_required 为空 → 出单
        └─ 非空 → 追问；followup_count>=2 → 强制出单(信息不全)

  decision_suggestion 的写入条件：
      missing_required 为空 且 basis 无明显缺失 → 采纳
      否则 → 置空


================================================================
一句话
================================================================
通用性成立（16/16）；但追问闭环、决策建议放行、
缺失判定，三件事都必须由代码掌控，不能交给模型的自我报告。
风险的真实位置是枚举字段的过度推断，不是布尔字段。
================================================================
