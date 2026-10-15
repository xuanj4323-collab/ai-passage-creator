package com.mypj.aipassagecreator.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 创建文章请求
 *
 */
@Data
public class ArticleCreateRequest implements Serializable {

    /**
     * 选题
     */
    private String topic;

    /**
     * 文章风格（可选）
     */
    private String style;

    /**
     * 允许的配图方式列表（为空或 null 表示支持所有方式）
     * 可选值：UNSPLASH, NANO_BANANA, MERMAID, ICONIFY, EMOJI_PACK, SVG_DIAGRAM
     */
    private List<String> enabledImageMethods;

    @Serial
    private static final long serialVersionUID = 1L;
}
