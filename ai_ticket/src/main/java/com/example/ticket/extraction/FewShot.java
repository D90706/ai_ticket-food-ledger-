package com.example.ticket.extraction;

/**
 * 自动生成的 few-shot 示例。
 *
 * dialogue 是合成出来的示例对话，exampleJson 是它对应的正确答案。
 * 两者必须同时存在：因为 basis 要求「对话原文的逐字片段」，
 * 只给 JSON 不给对话，模型学不到「依据必须从原文里摘」这件事。
 */
public record FewShot(String dialogue, String exampleJson) {
}
