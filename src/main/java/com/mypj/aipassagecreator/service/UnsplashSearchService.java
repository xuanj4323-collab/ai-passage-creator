package com.mypj.aipassagecreator.service;

import com.mypj.aipassagecreator.config.UnsplashConfig;
import com.mypj.aipassagecreator.model.enums.ImageMethodEnum;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;


import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.mypj.aipassagecreator.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

/**
 * unsplash 图片检索服务
 *
 */
@Service
@Slf4j
public class UnsplashSearchService implements ImageSearchService {

    /**
     * 搜索结果数组所在字段：
     * /search/photos 返回 { total, total_pages, results: [...] }，故 results 优先；
     * photos 仅为兼容旧写法保留
     */
    private static final String[] PHOTO_ARRAY_FIELDS = {"results", "photos"};

    /**
     * 图片地址所在容器字段：官方接口为 urls（src 为部分第三方封装的字段，做兼容）
     */
    private static final String[] URL_CONTAINER_FIELDS = {"urls", "src"};

    /**
     * 图片地址候选键，按正文配图优先级排列
     */
    private static final String[] URL_KEYS = {"regular", "large", "full", "medium", "small", "raw", "thumb", "small_s3", "original"};

    /**
     * 日志中响应体最大长度，避免刷屏
     */
    private static final int MAX_LOG_BODY_LENGTH = 500;

    private static final int DEFAULT_PER_PAGE = 1;

    private static final String DEFAULT_ORIENTATION = "landscape";

    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 5000L;

    private static final long DEFAULT_READ_TIMEOUT_MS = 10000L;

    @Resource
    private UnsplashConfig unsplashConfig;

    private OkHttpClient httpClient;

    @PostConstruct
    public void initHttpClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(resolveTimeout(unsplashConfig.getConnectTimeout(), DEFAULT_CONNECT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                .readTimeout(resolveTimeout(unsplashConfig.getReadTimeout(), DEFAULT_READ_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public String searchImage(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            log.warn("unsplash 检索关键词为空，跳过检索");
            return null;
        }

        try {
            String url = buildSearchUrl(keywords);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Client-ID " + unsplashConfig.getAccessKey())
                    .build();

            try (Response response = httpClient().newCall(request).execute()) {
                // 响应体只能读取一次，先取出供成功/失败两条分支使用
                String responseBody = response.body() == null ? "" : response.body().string();

                if (!response.isSuccessful()) {
                    log.error("Unsplash API 调用失败: code={}, body={}", response.code(), abbreviate(responseBody));
                    return null;
                }
                return extractImageUrl(responseBody, keywords);
            }
        } catch (IOException e) {
            log.error("Unsplash API 调用异常, keywords={}", keywords, e);
            return null;
        }
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.UNSPLASH;
    }

    @Override
    public String getFallbackImage(int position) {
        // 模板内是 %d 占位符，必须格式化，否则会拼出 https://picsum.photos/800/600?random=%d1
        return String.format(PICSUM_URL_TEMPLATE, position);
    }

    /**
     * 构建搜索 URL
     *
     * @param keywords 搜索关键词
     * @return 完整的搜索 URL
     */
    private String buildSearchUrl(String keywords) {
        Integer perPage = unsplashConfig.getDefaultPerPage();
        String orientation = unsplashConfig.getDefaultOrientation();

        return String.format("%s?query=%s&per_page=%d&orientation=%s",
                unsplashConfig.getApiUrl(),
                URLEncoder.encode(keywords, StandardCharsets.UTF_8),
                perPage == null || perPage <= 0 ? DEFAULT_PER_PAGE : perPage,
                orientation == null || orientation.isBlank() ? DEFAULT_ORIENTATION : orientation);
    }

    /**
     * 从响应中提取图片 URL
     * <p>
     * 解析失败一律返回 null，交由上层降级为随机图，不抛异常中断整篇文章生成。
     *
     * @param responseBody 响应体
     * @param keywords     搜索关键词（用于日志）
     * @return 图片 URL，未找到返回 null
     */
    private String extractImageUrl(String responseBody, String keywords) {
        JsonElement root;
        try {
            root = JsonParser.parseString(responseBody);
        } catch (JsonSyntaxException | IllegalStateException e) {
            log.error("Unsplash 响应不是合法 JSON, keywords={}, body={}", keywords, abbreviate(responseBody), e);
            return null;
        }

        JsonArray photos = extractPhotoArray(root, keywords);
        if (photos == null || photos.isEmpty()) {
            log.warn("unsplash 未检索到图片: {}, body={}", keywords, abbreviate(responseBody));
            return null;
        }

        for (JsonElement photo : photos) {
            String imageUrl = extractUrlFromPhoto(photo);
            if (imageUrl != null) {
                return imageUrl;
            }
        }

        log.warn("unsplash 响应中未找到可用图片地址: {}, body={}", keywords, abbreviate(responseBody));
        return null;
    }

    /**
     * 定位响应中的图片数组
     *
     * @param root     响应根节点
     * @param keywords 搜索关键词（用于日志）
     * @return 图片数组，未找到返回 null
     */
    private JsonArray extractPhotoArray(JsonElement root, String keywords) {
        if (root == null || root.isJsonNull()) {
            return null;
        }

        // /photos 端点直接返回数组，做兼容
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }

        if (!root.isJsonObject()) {
            return null;
        }

        JsonObject jsonObject = root.getAsJsonObject();
        for (String field : PHOTO_ARRAY_FIELDS) {
            JsonElement element = jsonObject.get(field);
            if (element != null && element.isJsonArray()) {
                return element.getAsJsonArray();
            }
        }

        // 官方接口出错时会返回 { "errors": [...] }
        if (jsonObject.has("errors")) {
            log.error("Unsplash 返回错误信息: keywords={}, errors={}", keywords, jsonObject.get("errors"));
        }
        return null;
    }

    /**
     * 从单张图片对象中提取 URL
     *
     * @param photo 图片对象
     * @return 图片 URL，未找到返回 null
     */
    private String extractUrlFromPhoto(JsonElement photo) {
        if (photo == null || !photo.isJsonObject()) {
            return null;
        }

        JsonObject photoObject = photo.getAsJsonObject();
        for (String containerField : URL_CONTAINER_FIELDS) {
            JsonElement container = photoObject.get(containerField);
            if (container == null || !container.isJsonObject()) {
                continue;
            }

            JsonObject urlObject = container.getAsJsonObject();
            for (String key : URL_KEYS) {
                JsonElement urlElement = urlObject.get(key);
                if (urlElement == null || !urlElement.isJsonPrimitive()) {
                    continue;
                }
                String imageUrl = urlElement.getAsString();
                if (imageUrl != null && !imageUrl.isBlank()) {
                    return imageUrl;
                }
            }
        }
        return null;
    }

    /**
     * 获取 OkHttpClient（@PostConstruct 未执行时兜底初始化，便于单元测试直接 new）
     */
    private OkHttpClient httpClient() {
        if (httpClient == null) {
            initHttpClient();
        }
        return httpClient;
    }

    /**
     * 超时配置兜底，避免配置缺失导致 OkHttp 直接抛异常
     */
    private long resolveTimeout(Integer configured, long defaultValue) {
        return configured == null || configured <= 0 ? defaultValue : configured;
    }

    /**
     * 截断响应体用于日志
     */
    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String singleLine = body.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_LOG_BODY_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_LOG_BODY_LENGTH) + "...";
    }
}
