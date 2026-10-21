package com.mypj.aipassagecreator.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class ArticleAiModifyOutlineRequest implements Serializable {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 用户的修改建议
     */
    private String modifySuggestion;

    @Serial
    private static final long serialVersionUID = 1L;
}

