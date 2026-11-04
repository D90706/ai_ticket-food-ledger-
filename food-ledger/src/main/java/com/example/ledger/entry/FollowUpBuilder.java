package com.example.ledger.entry;

import com.example.ledger.template.TemplateField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 追问话术。
 *
 * 由代码拼，不由模型生成：追问只是「缺什么」的确定性表达，
 * 交给模型只会引入波动，还可能把追问变成一段不再提问的客套话。
 *
 * 第二次开始换措辞：同一句话问两遍，采购员会觉得系统没在听。
 */
@Component
public class FollowUpBuilder {

    private static final String[] OPENERS = {
            "为了把这笔采购记进台账，还需要确认：",
            "还差一点就能记账了，请补充：",
            "再补两项就能提交了："
    };

    public String build(List<TemplateField> missing, int askedBefore) {
        String labels = missing.stream()
                .map(TemplateField::getLabel)
                .collect(Collectors.joining("、"));
        String opener = OPENERS[Math.min(askedBefore, OPENERS.length - 1)];
        String unit = missing.size() > 1 ? " 项" : "";
        String base = opener + labels + (missing.size() > 1 ? "（共 " + missing.size() + unit + "）" : "") + "。";

        // 凭证这一项，照片比编号更有用：提示一句可以直接传图。
        // 不提示的话，采购员会以为非得打字把许可证号抄上去——
        // 而抄来的编号并不是凭证，检查时要的是那张照片。
        boolean asksCertificate = missing.stream()
                .anyMatch(f -> "certificate".equals(f.getKey()));
        return asksCertificate ? base + "凭证可以直接传照片，不用手抄编号。" : base;
    }

    public String recordNotice(String statusLabel, Long recordId) {
        return "已记入台账，记录号 #" + recordId + "，当前状态：" + statusLabel + "。";
    }

    public String confirmPrompt() {
        return "信息还不太完整。我先按理解整理了一版，请确认后提交，或者直接补充缺的内容。";
    }
}