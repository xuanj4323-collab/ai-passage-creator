package com.mypj.aipassagecreator.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 删除请求包装类
 */
@Data
public class DeleteRequest implements Serializable {

    /**
     * id（雪花 ID 超出 JS 安全整数范围，前端以字符串传递；Jackson 可宽松地把字符串解析回 Long）
     */
    @Schema(type = "string")
    private Long id;

    private static final long serialVersionUID = 1L;
}
