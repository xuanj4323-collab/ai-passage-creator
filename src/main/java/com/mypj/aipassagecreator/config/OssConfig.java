package com.mypj.aipassagecreator.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 配置
 *
 */
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
@Data
public class OssConfig {

    /**
     * Secret ID
     */
    private String accessKeyId;

    /**
     * ACCESS_KEY_SECRET
     */
    private String accessKeySecret;

    /**
     * 地域
     */
    private String region;

    /**
     * 存储桶
     */
    private String bucket;

    /**
     * 对外访问域名（不含协议），例如 oss-cn-beijing.aliyuncs.com
     * 注意：region 是 SDK 需要的区域 ID（如 cn-beijing），不能直接拿来拼访问域名
     */
    private String endpoint;
}
