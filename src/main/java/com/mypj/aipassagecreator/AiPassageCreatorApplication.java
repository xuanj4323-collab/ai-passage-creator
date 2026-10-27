package com.mypj.aipassagecreator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.mypj.aipassagecreator.mapper")
@SpringBootApplication
public class AiPassageCreatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiPassageCreatorApplication.class, args);
    }

}

