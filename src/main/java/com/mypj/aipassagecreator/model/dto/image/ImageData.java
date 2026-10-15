package com.mypj.aipassagecreator.model.dto.image;


import lombok.Data;
import lombok.Builder;

import java.util.Base64;

/**
 * 图片数据封装类
 * 用于统一处理不同来源的图片数据（字节、URL、base64 等）
 *
 */
@Data
@Builder
public class ImageData {

    /**
     * 图片字节数据
     */
    private byte[] bytes;

    /**
     * 图片 URL（外部 URL 或 base64 data URL）
     */
    private String url;

    /**
     * MIME 类型（如 image/png, image/jpeg, image/svg+xml）
     */
    private String mimeType;

    /**
     * 数据类型
     */
    private DataType dataType;

    /**
     * 数据类型枚举
     */
    public enum DataType {
        /**
         * 字节数据
         */
        BYTES,
        /**
         * 外部 URL
         */
        URL,
        /**
         * base64 data URL
         */
        DATA_URL
    }

    /**
     * 从外部 URL 创建 ImageData
     */
    public static ImageData fromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        // 判断是否为 base64 data URL
        if (url.startsWith("data:")) {
            return fromDataUrl(url);
        }

        return ImageData.builder()
                .url(url)
                .dataType(DataType.URL)
                .build();
    }

    /**
     * 从 base64 data URL 创建 ImageData
     */
    public static ImageData fromDataUrl(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return null;
        }

        // 解析 data URL 格式: data:image/png;base64,xxxxx
        String mimeType = "image/png";
        int mimeEnd = dataUrl.indexOf(";");
        if (mimeEnd > 5) {
            mimeType = dataUrl.substring(5, mimeEnd);
        }

        return ImageData.builder()
                .url(dataUrl)
                .mimeType(mimeType)
                .dataType(DataType.DATA_URL)
                .build();
    }

    /**
     * 从字节数据创建 ImageData
     */
    public static ImageData fromBytes(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        return ImageData.builder()
                .bytes(bytes)
                .mimeType(mimeType != null ? mimeType : "image/png")
                .dataType(DataType.BYTES)
                .build();
    }

    /**
     * 获取图片字节数据
     * 如果是 data URL，会解码 base64
     */
    public byte[] getImageBytes() {
        if (dataType == DataType.BYTES) {
            return bytes;
        }

        if (dataType == DataType.DATA_URL && url != null) {
            // 解析 base64 data URL
            int base64Start = url.indexOf(",");
            if (base64Start > 0) {
                String base64Data = url.substring(base64Start + 1);
                return Base64.getDecoder().decode(base64Data);
            }
        }

        return null;
    }

    /**
     * 判断是否有有效数据
     */
    public boolean isValid() {
        // dataType 未设置时直接判为无效，避免 switch 解引用 null 抛出 NPE
        if (dataType == null) {
            return false;
        }

        return switch (dataType) {
            case BYTES -> bytes != null && bytes.length > 0;
            case URL, DATA_URL -> url != null && !url.isEmpty();
        };
    }

    /**
     * 规范化 MIME 类型：去掉参数部分、空格，并转小写
     * 例如 "image/jpeg; charset=UTF-8" -> "image/jpeg"
     *
     * @param mimeType 原始 MIME 类型
     * @return 规范化后的 MIME 类型，入参为空时返回空字符串
     */
    public static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int semicolonIndex = mimeType.indexOf(';');
        String baseType = semicolonIndex > 0 ? mimeType.substring(0, semicolonIndex) : mimeType;
        return baseType.trim().toLowerCase();
    }

    /**
     * 根据 MIME 类型获取文件扩展名（统一实现，避免多处逻辑分叉）
     *
     * @param mimeType MIME 类型，允许带参数
     * @return 文件扩展名（含点号）
     */
    public static String getExtensionFromMimeType(String mimeType) {
        return switch (normalizeMimeType(mimeType)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "image/bmp" -> ".bmp";
            default -> ".png";
        };
    }

    /**
     * 根据 MIME 类型获取文件扩展名
     */
    public String getFileExtension() {
        return getExtensionFromMimeType(mimeType);
    }
}

