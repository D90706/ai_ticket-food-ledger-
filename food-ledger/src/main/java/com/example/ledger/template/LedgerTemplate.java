package com.example.ledger.template;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 台账模板。
 *
 * 「字段由业务用户自己设计」这条想法就落在这里：
 * 法定 12 项固定（fixed=true，不许删），其余由单位自己加。
 * fields 是 JSON 数组，元素见 TemplateField。
 */
@Data
@TableName("ledger_template")
public class LedgerTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer version;

    /** JSON 数组字符串 */
    private String fields;

    /** 临期预警天数，各模板可覆盖全局默认 */
    private Integer shelfLifeWarnDays;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}