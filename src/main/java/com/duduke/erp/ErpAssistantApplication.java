package com.duduke.erp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ERP 智能助手启动类。
 */
@SpringBootApplication
@MapperScan("com.duduke.erp.mapper")
public class ErpAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpAssistantApplication.class, args);
    }

}
