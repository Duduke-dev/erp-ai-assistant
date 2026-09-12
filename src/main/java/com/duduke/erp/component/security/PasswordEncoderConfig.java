package com.duduke.erp.component.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码散列配置。
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * BCrypt 散列器。强度 10 是安全性与单次校验耗时的常用折中。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

}
