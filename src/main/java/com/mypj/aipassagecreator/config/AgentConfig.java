package com.mypj.aipassagecreator.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.serializer.std.SpringAIStateSerializer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@Getter
@Slf4j
public class AgentConfig {

    /**
     * 是否启用多智能体编排器
     * true: 使用新的 Spring AI Alibaba 多智能体编排
     * false: 使用原有的 ArticleAgentService
     */
    @Value("${article.agent.orchestrator.enabled:true}")
    private boolean orchestratorEnabled;

    /**
     * Agent 最大迭代次数（同时作为 StateGraph 的 recursionLimit）
     */
    @Value("${article.agent.max-iterations:10}")
    private int maxIterations;

    /**
     * 检查点建表策略：CREATE_NONE / CREATE_IF_NOT_EXISTS / CREATE_OR_REPLACE
     * 默认 CREATE_IF_NOT_EXISTS，表不存在时自动建，已存在则跳过。
     */
    @Value("${article.agent.checkpoint.create-option:CREATE_IF_NOT_EXISTS}")
    private String checkpointCreateOption;

    /**
     * 图执行状态的持久化保存器（MySQL 实现）。
     * <p>
     * 为什么用 MysqlSaver 而不是 MemorySaver：
     * MemorySaver 把检查点存在进程堆内存里，进程一重启就全丢——而本项目的三阶段流程中，
     * 用户在「选标题」「改大纲」处可能停留几分钟甚至跨天，这期间服务重启、发版都是常态。
     * MemorySaver 会导致重启后点确认直接找不到执行位置。
     * <p>
     * 为什么不用 RedisSaver：它依赖 Redisson 客户端，项目现在用的是 Lettuce，
     * 为一个检查点功能再引入一套 Redis 客户端和配置不划算。
     * MysqlSaver 直接复用已有的 HikariCP DataSource，且会自动建表，零新增依赖。
     * <p>
     * 序列化器选 SpringAIStateSerializer（基于 JDK 对象流），因为 ArticleState
     * 及其内部类都已实现 Serializable，不需要额外维护 Jackson 的类型映射。
     */
    @Bean
    public MysqlSaver mysqlSaver(DataSource dataSource) {
        log.info("初始化图检查点保存器: MysqlSaver, createOption={}", checkpointCreateOption);
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .stateSerializer(new SpringAIStateSerializer())
                .createOption(CreateOption.valueOf(checkpointCreateOption))
                .build();
    }
}
