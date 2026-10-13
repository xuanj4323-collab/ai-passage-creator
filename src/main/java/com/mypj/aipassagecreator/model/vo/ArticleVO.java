package com.mypj.aipassagecreator.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mypj.aipassagecreator.model.entity.Article;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章视图
 *
 */
@Data
public class ArticleVO implements Serializable {

    private static final Gson GSON = new Gson();

    /**
     * id（雪花 ID 为 19 位，超出 JS 安全整数范围，序列化为字符串避免前端丢精度）
     *
     * 两个注解缺一不可：@JsonSerialize 决定运行时输出字符串，@Schema 让文档类型同步；
     * springdoc 不识别 @JsonSerialize，少了 @Schema 会生成 number 的 typings。
     */
    @Schema(type = "string")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 用户ID（同为雪花 ID，同样需要字符串化）
     */
    @Schema(type = "string")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 选题
     */
    private String topic;

    /**
     * 主标题
     */
    private String mainTitle;

    /**
     * 副标题
     */
    private String subTitle;

    /**
     * 大纲
     */
    private List<OutlineItem> outline;

    /**
     * 正文
     */
    private String content;

    /**
     * 配图列表
     */
    private List<ImageItem> images;

    /**
     * 状态
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 完成时间
     */
    private LocalDateTime completedTime;

    /**
     * 大纲项
     */
    @Data
    public static class OutlineItem implements Serializable {
        private Integer section;
        private String title;
        private List<String> points;
    }

    /**
     * 配图项
     */
    @Data
    public static class ImageItem implements Serializable {
        private Integer position;
        private String url;
        private String method;
        private String keywords;
        private String description;
    }

    /**
     * 对象转包装类
     *
     * @param article 文章
     * @return 文章视图
     */
    public static ArticleVO objToVo(Article article) {
        if (article == null) {
            return null;
        }
        ArticleVO articleVO = new ArticleVO();
        BeanUtils.copyProperties(article, articleVO);

        // 转换 JSON 字段
        if (article.getOutline() != null) {
            articleVO.setOutline(GSON.fromJson(article.getOutline(),
                    new TypeToken<List<OutlineItem>>(){}.getType()));
        }
        if (article.getImages() != null) {
            articleVO.setImages(GSON.fromJson(article.getImages(),
                    new TypeToken<List<ImageItem>>(){}.getType()));
        }

        return articleVO;
    }

    private static final long serialVersionUID = 1L;
}
