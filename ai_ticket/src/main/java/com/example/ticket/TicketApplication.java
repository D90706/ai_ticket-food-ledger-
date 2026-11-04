package com.example.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 启动类。
 *
 * 没有 @MapperScan：MyBatis-Plus 会自动扫描带 @Mapper 的接口。
 * 刻意不加，是因为 @MapperScan 会按包扫描所有接口，
 * 把 LlmClient、Retriever 这类业务接口误注册成 Mapper。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketApplication.class, args);
    }
}
