package com.mypj.aipassagecreator.model.dto.article;


import com.mypj.aipassagecreator.common.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 查询文章请求
 *
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ArticleQueryRequest extends PageRequest implements Serializable {

    /**
     * 用户ID（雪花 ID，前端以字符串传递）
     */
    @Schema(type = "string")
    private Long userId;

    /**
     * 状态
     */
    private String status;

    @Serial
    private static final long serialVersionUID = 1L;
}
