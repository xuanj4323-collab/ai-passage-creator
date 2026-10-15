package com.mypj.aipassagecreator.service;


import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import com.mypj.aipassagecreator.config.OssConfig;
import com.mypj.aipassagecreator.model.dto.image.ImageData;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云 OSS 服务
 *
 */
@Service
@Slf4j
public class OssService {

    /**
     * 下载外部图片的最大字节数（10MB），避免异常大图占满内存
     */
    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;

    /**
     * 下载外部图片的连接/读取超时（毫秒）
     */
    private static final long DOWNLOAD_CONNECT_TIMEOUT_MS = 5000L;

    private static final long DOWNLOAD_READ_TIMEOUT_MS = 15000L;

    /**
     * 对象默认 Content-Type
     */
    private static final String DEFAULT_IMAGE_MIME_TYPE = "image/jpeg";

    /**
     * folder 缺省值
     */
    private static final String DEFAULT_FOLDER = "images";

    @Resource
    private OssConfig ossConfig;

    private OSSClient ossClient;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    @PostConstruct
    public void init() {
        // 使用阿里云 OSS V2 SDK 构建客户端
        ossClient = OSSClient.newBuilder()
                .credentialsProvider(resolveCredentialsProvider())
                .region(ossConfig.getRegion())  // 例如 "cn-beijing"
                .build();

        log.info("OSS 客户端初始化完成, region={}, bucket={}, endpoint={}",
                ossConfig.getRegion(), ossConfig.getBucket(), ossConfig.getEndpoint());
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            try {
                // OSSClient 继承 AutoCloseable，close() 声明抛出 Exception
                ossClient.close();
                log.info("OSS 客户端已关闭");
            } catch (Exception e) {
                log.warn("关闭 OSS 客户端失败", e);
            }
        }
    }

    /**
     * 上传 ImageData 到 OSS（统一入口）
     * 根据数据类型自动选择上传方式
     *
     * @param imageData 图片数据对象
     * @param folder    文件夹
     * @return OSS 图片 URL，上传失败返回 null
     */
    public String uploadImageData(ImageData imageData, String folder) {
        if (imageData == null || !imageData.isValid()) {
            log.warn("ImageData 无效，无法上传, dataType={}", imageData == null ? null : imageData.getDataType());
            return null;
        }

        String normalizedFolder = normalizeFolder(folder);
        try {
            return switch (imageData.getDataType()) {
                case BYTES -> uploadBytes(imageData.getBytes(), imageData.getMimeType(), normalizedFolder);
                case URL -> uploadFromUrl(imageData.getUrl(), normalizedFolder);
                case DATA_URL -> uploadFromDataUrl(imageData, normalizedFolder);
            };
        } catch (Exception e) {
            // 内部方法只做异常包装，堆栈统一在这里记录一次，避免同一个异常打印两遍
            log.error("上传图片到 OSS 失败, dataType={}, folder={}", imageData.getDataType(), normalizedFolder, e);
            return null;
        }
    }

    /**
     * 上传字节数据到 OSS
     *
     * @param bytes    图片字节
     * @param mimeType MIME 类型（允许带参数，如 image/jpeg; charset=UTF-8）
     * @param folder   已规范化的文件夹
     * @return OSS 图片 URL，数据为空返回 null
     */
    private String uploadBytes(byte[] bytes, String mimeType, String folder) {
        if (bytes == null || bytes.length == 0) {
            log.warn("字节数据为空，无法上传");
            return null;
        }

        String contentType = resolveImageContentType(mimeType);
        String objectKey = folder + "/" + UUID.randomUUID() + ImageData.getExtensionFromMimeType(contentType);

        // V2 使用 Builder 构造请求
        PutObjectRequest request = PutObjectRequest.newBuilder()
                .bucket(ossConfig.getBucket())
                .key(objectKey)
                // 必须显式设置，否则对象会以 application/octet-stream 存储
                .contentType(contentType)
                .body(BinaryData.fromBytes(bytes))
                .build();

        ossClient.putObject(request);

        String ossUrl = buildOssUrl(objectKey);
        log.info("字节数据上传成功, size={} bytes, contentType={}, url={}", bytes.length, contentType, ossUrl);
        return ossUrl;
    }

    /**
     * 从外部 URL 下载并上传到 OSS
     *
     * @param imageUrl 外部图片 URL
     * @param folder   已规范化的文件夹
     * @return OSS 图片 URL，失败返回 null
     */
    private String uploadFromUrl(String imageUrl, String folder) {
        if (imageUrl == null || imageUrl.isBlank()) {
            log.warn("图片 URL 为空，无法上传");
            return null;
        }
        if (!isHttpUrl(imageUrl)) {
            log.warn("图片 URL 协议不受支持，仅允许 http/https: {}", imageUrl);
            return null;
        }

        Request request = new Request.Builder().url(imageUrl).build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("下载图片失败: {}, code={}", imageUrl, response.code());
                return null;
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                log.error("下载图片失败, 响应体为空: {}", imageUrl);
                return null;
            }

            long contentLength = responseBody.contentLength();
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                log.error("图片超过大小上限，跳过上传, url={}, contentLength={}, limit={}",
                        imageUrl, contentLength, MAX_DOWNLOAD_BYTES);
                return null;
            }

            byte[] imageBytes = responseBody.bytes();
            if (imageBytes.length > MAX_DOWNLOAD_BYTES) {
                log.error("图片超过大小上限，跳过上传, url={}, size={}, limit={}",
                        imageUrl, imageBytes.length, MAX_DOWNLOAD_BYTES);
                return null;
            }

            String contentType = response.header("Content-Type");
            if (contentType == null || contentType.isBlank()) {
                log.warn("响应未返回 Content-Type，按默认类型处理: {}", imageUrl);
            } else if (!isImageContentType(contentType)) {
                log.error("响应不是图片类型，跳过上传, url={}, contentType={}", imageUrl, contentType);
                return null;
            }

            return uploadBytes(imageBytes, contentType, folder);
        } catch (IOException e) {
            // 统一由 uploadImageData 记录堆栈
            throw new UncheckedIOException("从 URL 下载图片失败: " + imageUrl, e);
        }
    }

    /**
     * 从 base64 data URL 解码并上传到 OSS
     *
     * @param imageData 图片数据对象
     * @param folder    已规范化的文件夹
     * @return OSS 图片 URL，解码失败返回 null
     */
    private String uploadFromDataUrl(ImageData imageData, String folder) {
        byte[] bytes = imageData.getImageBytes();
        if (bytes == null || bytes.length == 0) {
            log.warn("解码 data URL 失败，无法上传");
            return null;
        }

        return uploadBytes(bytes, imageData.getMimeType(), folder);
    }

    /**
     * 构建对象的对外访问 URL
     *
     * @param objectKey 对象名
     * @return 完整的访问 URL
     */
    private String buildOssUrl(String objectKey) {
        // 格式：https://{bucket}.{endpoint}/{objectKey}
        return "https://" + ossConfig.getBucket() + "." + resolveEndpoint() + "/" + objectKey;
    }

    /**
     * 解析 Bucket 的对外访问域名
     * <p>
     * region 是 SDK 需要的区域 ID（如 cn-beijing），而访问域名必须带 oss- 前缀
     * （oss-cn-beijing.aliyuncs.com），两者语义不同，不能直接拿 region 拼域名。
     * 因此优先使用配置项 aliyun.oss.endpoint，未配置时按规则兜底推导。
     *
     * @return 不含协议的访问域名
     */
    private String resolveEndpoint() {
        String endpoint = ossConfig.getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            return stripScheme(endpoint);
        }

        String region = ossConfig.getRegion();
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("OSS 配置缺失：aliyun.oss.endpoint 与 aliyun.oss.region 至少需要配置一个");
        }
        return region.startsWith("oss-") ? region + ".aliyuncs.com" : "oss-" + region + ".aliyuncs.com";
    }

    /**
     * 去掉 endpoint 上的协议前缀与结尾斜杠
     */
    private String stripScheme(String endpoint) {
        String result = endpoint.trim();
        if (result.startsWith("https://")) {
            result = result.substring("https://".length());
        } else if (result.startsWith("http://")) {
            result = result.substring("http://".length());
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 规范化 folder，避免出现空目录名或首尾多余的斜杠（如 images//xxx.png）
     */
    private String normalizeFolder(String folder) {
        if (folder == null || folder.isBlank()) {
            return DEFAULT_FOLDER;
        }

        String normalized = folder.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? DEFAULT_FOLDER : normalized;
    }

    /**
     * 解析对象的 Content-Type，非图片类型时退回默认值
     */
    private String resolveImageContentType(String mimeType) {
        String normalized = ImageData.normalizeMimeType(mimeType);
        return normalized.startsWith("image/") ? normalized : DEFAULT_IMAGE_MIME_TYPE;
    }

    /**
     * 判断 Content-Type 是否为图片
     */
    private boolean isImageContentType(String contentType) {
        return ImageData.normalizeMimeType(contentType).startsWith("image/");
    }

    /**
     * 只允许 http/https，避免把 file: 等协议交给 OkHttp 处理
     */
    private boolean isHttpUrl(String url) {
        return url.regionMatches(true, 0, "http://", 0, "http://".length())
                || url.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    /**
     * 凭据来源：优先使用配置文件中的 AccessKey，未配置时回退到环境变量
     * （OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET）
     */
    private CredentialsProvider resolveCredentialsProvider() {
        String accessKeyId = ossConfig.getAccessKeyId();
        String accessKeySecret = ossConfig.getAccessKeySecret();

        if (accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank()) {
            log.info("OSS 凭据来源：配置文件 aliyun.oss.access-key-id");
            return new StaticCredentialsProvider(accessKeyId, accessKeySecret);
        }

        log.warn("OSS 未配置 access-key-id / access-key-secret，回退使用环境变量 "
                + "OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET");
        return new EnvironmentVariableCredentialsProvider();
    }

    /**
     * 直接使用图片 URL（不上传到 OSS）
     * <p>
     * 说明：图文主流程目前走的是本方法，即图库图片以直链方式使用，不经过 OSS；
     * 若需要把图片落库自存，应改为调用 {@link #uploadImageData(ImageData, String)}。
     *
     * @param imageUrl 图片 URL
     * @return 原样返回的图片 URL
     */
    public String useDirectUrl(String imageUrl) {
        return imageUrl;
    }
}
