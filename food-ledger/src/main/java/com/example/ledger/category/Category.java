package com.example.ledger.category;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 食材品类。只做筛选与统计，不做树。 */
@Data
@TableName("ingredient_category")
public class Category {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer sortNo;

    private LocalDateTime createdAt;
}