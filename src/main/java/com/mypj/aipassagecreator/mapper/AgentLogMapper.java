package com.mypj.aipassagecreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mypj.aipassagecreator.model.entity.AgentLog;
import org.apache.ibatis.annotations.Select;

/**
 * 智能体日志 Mapper
 *
 */
public interface AgentLogMapper extends BaseMapper<AgentLog> {

    /**
     * 已完成文章的平均 AI 处理耗时（毫秒）。
     * <p>
     * 为什么不用 article 的 createTime → completedTime：
     * 那段墙钟时间包含了用户选标题、编辑大纲的停留时间，同一个人改得慢一点数字就能翻几倍。
     * 它衡量的是「用户走完全程用了多久」，不是「系统生成了多久」，不能当性能指标。
     * <p>
     * 这里改为把每个任务下各智能体的执行耗时求和，再对所有已完成任务求平均；
     * 只统计 SUCCESS 的记录，确保反映的是真实成功的处理链路。
     * 用子查询一次性完成聚合，避免把明细拉到内存再算。
     *
     * @return 平均耗时（毫秒），无数据时返回 null
     */
    @Select("SELECT AVG(t.totalMs) FROM (" +
            "  SELECT al.taskId, SUM(al.durationMs) AS totalMs" +
            "  FROM agent_log al" +
            "  INNER JOIN article a ON a.taskId = al.taskId" +
            "  WHERE a.status = 'COMPLETED' AND a.isDelete = 0" +
            "    AND al.status = 'SUCCESS' AND al.isDelete = 0" +
            "    AND al.durationMs IS NOT NULL" +
            "  GROUP BY al.taskId" +
            ") t")
    Double avgAiDurationMs();
}
