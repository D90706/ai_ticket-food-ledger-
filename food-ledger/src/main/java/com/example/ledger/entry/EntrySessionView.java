package com.example.ledger.entry;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 会话视图：把两段 JSON 还原成可用的 Map。 */
@Data
public class EntrySessionView {

    private Long id;
    private Long templateId;
    private int templateVersion;
    private String state;
    private int followupCount;
    private Map<String, Object> values = new LinkedHashMap<>();
    private Map<String, String> basis = new LinkedHashMap<>();
    private String draftSummary;
    private Long recordId;
    private String createdBy;

    public void merge(Map<String, Object> newValues, Map<String, String> newBasis) {
        if (newValues != null) {
            newValues.forEach((k, v) -> {
                if (v != null) {
                    values.put(k, v);
                }
            });
        }
        if (newBasis != null) {
            newBasis.forEach((k, v) -> {
                if (v != null) {
                    basis.put(k, v);
                }
            });
        }
    }
}