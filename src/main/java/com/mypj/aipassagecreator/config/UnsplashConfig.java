package com.mypj.aipassagecreator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Unsplash API 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "unsplash")
public class UnsplashConfig {

    /**
     * Unsplash Access Key（Client-ID）
     */
    private String accessKey;

    /**
     * API 基础地址
     */
    private String apiUrl = "https://api.unsplash.com/search/photos";

    /**
     * 默认每页返回数量（Unsplash 最大 30）
     */
    private Integer defaultPerPage = 1;

    /**
     * 默认图片方向：landscape / portrait / squarish
     */
    private String defaultOrientation = "landscape";

    /**
     * 连接超时（毫秒）
     */
    private Integer connectTimeout = 5000;

    /**
     * 读取超时（毫秒）
     */
    private Integer readTimeout = 10000;
}
