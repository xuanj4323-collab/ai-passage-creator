package com.mypj.aipassagecreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mypj.aipassagecreator.model.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 条件更新文章状态：仅当当前状态等于 fromStatus 时才更新为 toStatus。
     * <p>
     * 条件更新是幂等的关键：重复执行时第二次 affectedRows = 0，
     * 调用方可据此判断「本次状态转换是否由我触发」，从而决定是否执行退配额等副作用。
     * <p>
     * 两个刻意的设计：
     * 1）状态参数使用枚举的 value 字符串而非枚举对象——MyBatis 默认 EnumTypeHandler
     * 走 name()，一旦枚举的 value 与 name 不再相同，SQL 会静默查不到记录；
     * 2）所有参数显式标注 @Param——不依赖编译期 -parameters 选项，避免换构建方式后
     * 报 BindingException。
     *
     * @param taskId       任务ID
     * @param fromStatus   期望的原状态值（ArticleStatusEnum.getValue()）
     * @param toStatus     目标状态值（ArticleStatusEnum.getValue()）
     * @param errorMessage 错误信息（成功时传 null 以清空历史错误）
     * @return 受影响行数，1 表示本次转换成功，0 表示当前状态不匹配（已被其他流程处理）
     */
    @Update("UPDATE article SET status = #{toStatus}, errorMessage = #{errorMessage} " +
            "WHERE taskId = #{taskId} AND status = #{fromStatus}")
    int updateArticleStatus(@Param("taskId") String taskId,
                            @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus,
                            @Param("errorMessage") String errorMessage);

    /**
     * 把非终态任务标记为失败（PENDING / PROCESSING → FAILED）。
     * <p>
     * 使用 NOT IN 而非显式列举 PENDING/PROCESSING：将来新增中间状态时无需改 SQL。
     * SQL 中的状态字面量需与 ArticleStatusEnum 的 value 保持一致。
     *
     * @param taskId       任务ID
     * @param errorMessage 失败原因
     * @return 受影响行数，1 表示本次标记成功，0 表示已是终态（无需重复处理）
     */
    @Update("UPDATE article SET status = 'FAILED', errorMessage = #{errorMessage} " +
            "WHERE taskId = #{taskId} AND status NOT IN ('COMPLETED', 'FAILED')")
    int markFailedIfNotTerminal(@Param("taskId") String taskId,
                                @Param("errorMessage") String errorMessage);

    @Update("UPDATE article SET phase = #{toPhase} " +
            "WHERE taskId = #{taskId} AND phase = #{fromPhase}")
    int updateArticlePhase(@Param("taskId") String taskId,
                            @Param("fromPhase") String fromPhase,
                            @Param("toPhase") String toPhase);
}
