package com.mypj.aipassagecreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mypj.aipassagecreator.model.entity.Article;
import com.mypj.aipassagecreator.model.enums.ArticleStatusEnum;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
    * 根据taskId和fromStatus更新文章状态为toStatus
     * @param taskId 任务ID
     * @param fromStatus 从状态
     * @param toStatus 到状态
     * @param errorMessage 错误信息
     * @return 受影响的行数
    */
    @Update("UPDATE article SET status = #{toStatus}, errorMessage = #{errorMessage} WHERE taskId = #{taskId} AND status = #{fromStatus}")
    int updateArticleStatus(String taskId, ArticleStatusEnum fromStatus, ArticleStatusEnum toStatus, String errorMessage);

    @Update("UPDATE article SET status = 'FAILED', errorMessage = #{errorMessage} " +
            "WHERE taskId = #{taskId} AND status NOT IN ('COMPLETED', 'FAILED')")
    int markFailedIfNotTerminal(@Param("taskId") String taskId,
                                @Param("errorMessage") String errorMessage);
}
