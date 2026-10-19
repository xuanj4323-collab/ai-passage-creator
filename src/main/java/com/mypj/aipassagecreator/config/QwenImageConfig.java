package com.mypj.aipassagecreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "dashscope.qwen-image")
@Data
public class QwenImageConfig {
    private String alyApiKey;
    private String baseHttpApiUrl;

    /**
     * 模型名称
     */
    private String model = "qwen-image-2.1-turbo";


    private String size = "1024*1024";
}



