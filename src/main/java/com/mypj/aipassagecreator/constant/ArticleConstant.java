package com.mypj.aipassagecreator.constant;

public interface ArticleConstant {

    /**
     * SSE 连接超时时间（毫秒）：30分钟
     */
    long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * SSE 重连时间（毫秒）：3秒
     */
    long SSE_RECONNECT_TIME_MS = 3000L;

    // region Unsplash 相关常量

    /**
     * UNSPLASH API 地址
     */
    String UNSPLASH_API_URL = "https://api.unsplash.com/search/photos";

    /**
     * Unsplash 每页返回数量
     */
    int UNSPLASH_PER_PAGE = 1;

    /**
     * Unsplash 图片方向：横向
     */
    String UNSPLASH_ORIENTATION_LANDSCAPE = "landscape";

    // endregion

    // region Picsum 相关常量

    /**
     * Picsum 随机图片 URL 模板
     */
    String PICSUM_URL_TEMPLATE = "https://picsum.photos/800/600?random=%d";

    // endregion
}

