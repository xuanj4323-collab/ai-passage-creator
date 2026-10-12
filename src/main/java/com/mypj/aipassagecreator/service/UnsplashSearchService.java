package com.mypj.aipassagecreator.service;

import com.mypj.aipassagecreator.config.UnsplashConfig;
import com.mypj.aipassagecreator.model.enums.ImageMethodEnum;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


import java.io.IOException;

/**
 * unsplash 图片检索服务
 *
 */
@Service
@Slf4j
public class UnsplashSearchService implements ImageSearchService {

    @Resource
    private UnsplashConfig unsplashConfig;

    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    public String searchImage(String keywords) {
        try {
            String url = buildSearchUrl(keywords);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", unsplashConfig.getAccessKey())
                    .build();
            System.out.println("Unsplash API 请求: " + request.url());

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Unsplash API 调用失败: {}", response.code());
                    return null;
                }
                String responseBody = response.body().string();
                return extractImageUrl(responseBody, keywords);
            }
        } catch (IOException e) {
            log.error("Unsplash API 调用异常", e);
            return null;
        }
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.UNSPLASH;
    }

    @Override
    public String getFallbackImage(int position) {
        return "https://picsum.photos/800/600?random=" + position;
    }

    /**
     * 构建搜索 URL
     *
     * @param keywords 搜索关键词
     * @return 完整的搜索 URL
     */
    private String buildSearchUrl(String keywords) {
        return String.format("%s?query=%s&per_page=%d&orientation=%s",
                unsplashConfig.getApiUrl(),
                keywords,
                unsplashConfig.getDefaultPerPage(),
                unsplashConfig.getDefaultOrientation());
    }

    /**
     * 从响应中提取图片 URL
     *
     * @param responseBody 响应体
     * @param keywords     搜索关键词（用于日志）
     * @return 图片 URL，未找到返回 null
     */
    private String extractImageUrl(String responseBody, String keywords) {
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray photos = jsonObject.getAsJsonArray("photos");

        if (photos.isEmpty()) {
            log.warn("unsplash 未检索到图片: {}", keywords);
            return null;
        }

        JsonObject photo = photos.get(0).getAsJsonObject();
        JsonObject src = photo.getAsJsonObject("src");
        return src.get("large").getAsString();
    }
}
