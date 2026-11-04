package com.example.ticket.quality;

/**
 * 忠实性检查的单字段结果。
 *
 * inDialogue 是这里唯一有价值的判断：
 * 它把「模型是否编造了引用」变成一次字符串包含。
 * 模型可以编字段值，但很难在编值的同时逐字抄一段原文。
 *
 * 必须记住它的边界：inDialogue 为 true 只说明「依据确实在原文里」，
 * 不说明「依据支持这个值」。
 *   「你们这什么质量」是真原文 → 校验通过
 *   reason = 质量问题   → 但这是误读
 * 所以 basis 必须常驻显示，让误读有被看见的机会。
 */
public record BasisCheck(String field, boolean hasBasis, boolean inDialogue) {

    public boolean passed() {
        return hasBasis && inDialogue;
    }
}
