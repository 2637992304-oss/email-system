package com.shixun;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.shixun.**.mapper")
public class MailSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailSystemApplication.class, args);
    }
}
