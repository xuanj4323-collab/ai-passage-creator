package com.mypj.aipassagecreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mypj.aipassagecreator.model.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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

    /**
     * 条件确认标题：仅当任务处于 TITLE_SELECTING 阶段时写入标题并推进到 OUTLINE_GENERATING。
     * <p>
     * 为什么不能写成「先 getByTaskId 校验 phase，再 updateById」：
     * 那是典型的 read-modify-write，中间有几毫秒窗口。用户双击、前端重试、两个标签页
     * 同时操作时，两个请求都会读到 TITLE_SELECTING 并通过校验，各自提交一次阶段2任务——
     * LLM 被调用两次、费用翻倍；更糟的是后完成的那次会用 updateById 覆盖整行，
     * 把用户后来编辑的大纲冲掉。
     * <p>
     * 把阶段校验写进 WHERE 之后，MySQL 会对该行加行锁，两个请求排队执行：
     * 第一个把 phase 改走并提交，第二个的 WHERE 不再匹配，affectedRows = 0。
     *
     * @param taskId          任务ID
     * @param mainTitle       用户选定的主标题
     * @param subTitle        用户选定的副标题
     * @param userDescription 用户补充描述（可为 null）
     * @return 受影响行数：1 表示本次确认生效，0 表示阶段不匹配（重复提交或已被处理）
     */
    @Update("UPDATE article SET mainTitle = #{mainTitle}, subTitle = #{subTitle}, " +
            "userDescription = #{userDescription}, phase = 'OUTLINE_GENERATING' " +
            "WHERE taskId = #{taskId} AND phase = 'TITLE_SELECTING' AND isDelete = 0")
    int confirmTitleIfSelecting(@Param("taskId") String taskId,
                                @Param("mainTitle") String mainTitle,
                                @Param("subTitle") String subTitle,
                                @Param("userDescription") String userDescription);

    /**
     * 条件确认大纲：仅当任务处于 OUTLINE_EDITING 阶段时写入大纲并推进到 CONTENT_GENERATING。
     * <p>
     * 与 confirmTitleIfSelecting 同理：避免并发确认导致阶段3任务被提交两次
     * （正文与配图是整个流程里最贵的一步）。
     *
     * @param taskId  任务ID
     * @param outline 用户确认后的大纲（JSON 字符串）
     * @return 受影响行数：1 表示本次确认生效，0 表示阶段不匹配
     */
    @Update("UPDATE article SET outline = #{outline}, phase = 'CONTENT_GENERATING' " +
            "WHERE taskId = #{taskId} AND phase = 'OUTLINE_EDITING' AND isDelete = 0")
    int confirmOutlineIfEditing(@Param("taskId") String taskId,
                                @Param("outline") String outline);

    /**
     * 条件保存正文与配图：仅当任务仍处于 PROCESSING 状态时才写入。
     * <p>
     * 存在的意义是防「超时回收」与「正常完成」打架：
     * 阶段3要跑 40~80 秒，若引入了超时清理机制，任务可能在跑到第 4 分钟时被判失败并退款，
     * 而它第 5 分钟才真正跑完。此时若无条件写入，数据库会留下
     * 「status=FAILED，但 content/fullContent/images 齐全」的矛盾记录，
     * 排查时无法判断到底成功还是失败。
     * <p>
     * 加上 status = 'PROCESSING' 之后，被回收的任务在收尾时 affectedRows = 0，
     * 调用方据此放弃写入并跳过后续的完成推送，数据只保留 FAILED 一种事实。
     * <p>
     * 说明：这几列在阶段3收尾时都是首次写入（此前为空），因此直接覆盖是安全的，
     * 不需要像 updateById 那样做 null 忽略。
     *
     * @param article 只填 taskId 与需要写入的字段，其余留空
     * @return 受影响行数：1 表示写入成功，0 表示任务已不在处理中（被回收或已终态）
     */
    @Update("UPDATE article SET mainTitle = #{article.mainTitle}, subTitle = #{article.subTitle}, " +
            "outline = #{article.outline}, content = #{article.content}, " +
            "fullContent = #{article.fullContent}, coverImage = #{article.coverImage}, " +
            "images = #{article.images}, completedTime = #{article.completedTime} " +
            "WHERE taskId = #{article.taskId} AND status = 'PROCESSING' AND isDelete = 0")
    int saveContentIfProcessing(@Param("article") Article article);

    /**
     * 统计指定时间点之后有创作行为的去重用户数（活跃用户）。
     * <p>
     * 用 SQL 聚合而非「查全部文章再 stream().distinct()」：后者会随数据量线性吃内存。
     * 手写 SQL 不会自动追加逻辑删除条件，故显式写 isDelete = 0。
     *
     * @param start 起始时间
     * @return 去重用户数
     */
    @Select("SELECT COUNT(DISTINCT userId) FROM article " +
            "WHERE createTime >= #{start} AND isDelete = 0")
    Long countDistinctUsersSince(@Param("start") LocalDateTime start);

    /**
     * 已完成文章的平均生成耗时（毫秒）。
     * <p>
     * TIMESTAMPDIFF 以微秒为单位求差，AVG 后除以 1000 转成毫秒；
     * 无数据时 AVG 返回 NULL，映射为 Java null，由调用方兜底。
     *
     * @return 平均耗时（毫秒），无数据时返回 null
     */
    @Select("SELECT AVG(TIMESTAMPDIFF(MICROSECOND, createTime, completedTime)) / 1000 " +
            "FROM article WHERE status = 'COMPLETED' AND completedTime IS NOT NULL AND isDelete = 0")
    Double avgCompletedDurationMs();
}
