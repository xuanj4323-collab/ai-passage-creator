package com.mypj.aipassagecreator.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 请求封装类
 */
@Data
public class PageRequest {

    /**
     * 当前页号
     */
    @Max(value = 10000, message = "页号不能超过10000")
    private int pageNum = 1;

    /**
     * 页面大小
     */
    private int pageSize = 10;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序顺序（默认降序）
     */
    private String sortOrder = "descend";

    public int getPageNum() {
        return Math.max(pageNum, 1);
    }

    public int getPageSize() {
        return Math.max(10, Math.min(pageSize, 50));
    }
}
