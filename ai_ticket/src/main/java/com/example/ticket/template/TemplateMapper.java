package com.example.ticket.template;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus 的 BaseMapper 已提供单表 CRUD，
 * 本项目四张表没有关联查询，不需要写 XML。
 *
 * 加 @Mapper 让它被自动扫描——启动类刻意不加 @MapperScan，
 * 否则会按包扫描所有接口，把 LlmClient 之类误注册成 Mapper。
 */
@Mapper
public interface TemplateMapper extends BaseMapper<Template> {
}
