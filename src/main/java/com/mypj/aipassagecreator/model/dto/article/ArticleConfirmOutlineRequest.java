package com.mypj.aipassagecreator.model.dto.article;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class ArticleConfirmOutlineRequest implements Serializable {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 用户编辑后的大纲
     */
    private List<ArticleState.OutlineSection> outline;

    @Serial
    private static final long serialVersionUID = 1L;
}

