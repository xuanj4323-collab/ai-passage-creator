package com.mypj.aipassagecreator.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mypj.aipassagecreator.constant.UserConstant;
import com.mypj.aipassagecreator.mapper.AgentLogMapper;
import com.mypj.aipassagecreator.mapper.ArticleMapper;
import com.mypj.aipassagecreator.mapper.UserMapper;
import com.mypj.aipassagecreator.model.enums.ArticleStatusEnum;
import com.mypj.aipassagecreator.model.vo.StatisticsVO;
import com.mypj.aipassagecreator.service.AgentLogService;
import com.mypj.aipassagecreator.service.StatisticsService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

/**
 * 统计服务实现
 *
 */
@Service
@Slf4j
public class StatisticsServiceImpl implements StatisticsService {

    private static final String STATISTICS_CACHE_KEY = "statistics:overview";
    private static final long CACHE_EXPIRE_HOURS = 1L;

    @Resource
    private ArticleMapper articleMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private AgentLogMapper agentLogMapper;

    @Resource
    private AgentLogService agentLogService;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public StatisticsVO getStatistics() {
        // 先从缓存获取
        StatisticsVO cachedStats = (StatisticsVO) redisTemplate.opsForValue().get(STATISTICS_CACHE_KEY);
        if (cachedStats != null) {
            log.info("从缓存获取统计数据");
            return cachedStats;
        }

        // 缓存不存在，重新计算
        // 今日创作数量
        Long todayCount = countArticlesByDateRange(getTodayStart(), LocalDateTime.now());

        // 本周创作数量
        Long weekCount = countArticlesByDateRange(getWeekStart(), LocalDateTime.now());

        // 本月创作数量
        Long monthCount = countArticlesByDateRange(getMonthStart(), LocalDateTime.now());

        // 总创作数量
        Long totalCount = countTotalArticles();

        // 成功率统计
        Double successRate = calculateSuccessRate();

        // 平均耗时统计
        Integer avgDurationMs = calculateAvgDuration();

        // 活跃用户统计（本周有创作的用户）
        Long activeUserCount = countActiveUsers(getWeekStart());

        // 总用户数
        Long totalUserCount = countTotalUsers();

        // VIP 用户数
        Long vipUserCount = countVipUsers();

        // 配额使用情况（总配额 - 剩余配额）
        Long quotaUsed = calculateQuotaUsed();

        StatisticsVO statistics = StatisticsVO.builder()
                .todayCount(todayCount)
                .weekCount(weekCount)
                .monthCount(monthCount)
                .totalCount(totalCount)
                .successRate(successRate)
                .avgDurationMs(avgDurationMs)
                .activeUserCount(activeUserCount)
                .totalUserCount(totalUserCount)
                .vipUserCount(vipUserCount)
                .quotaUsed(quotaUsed)
                .build();

        // 存入缓存，1 小时过期
        redisTemplate.opsForValue().set(STATISTICS_CACHE_KEY, statistics, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        log.info("统计数据已缓存，过期时间: {} 小时", CACHE_EXPIRE_HOURS);

        return statistics;
    }

    /**
     * 统计指定时间范围内的文章数量
     */
    private Long countArticlesByDateRange(LocalDateTime start, LocalDateTime end) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .ge("createTime", start)
                .le("createTime", end);
        return articleMapper.selectCountByQuery(queryWrapper);
    }

    /**
     * 统计总文章数量
     */
    private Long countTotalArticles() {
        return articleMapper.selectCountByQuery(QueryWrapper.create());
    }

    /**
     * 计算成功率
     */
    private Double calculateSuccessRate() {
        Long totalCount = countTotalArticles();
        if (totalCount == 0) {
            return 0.0;
        }

        QueryWrapper successWrapper = QueryWrapper.create()
                .eq("status", ArticleStatusEnum.COMPLETED.getValue());
        Long successCount = articleMapper.selectCountByQuery(successWrapper);

        return (successCount.doubleValue() / totalCount.doubleValue()) * 100;
    }

    /**
     * 计算平均 AI 处理耗时（各智能体耗时之和的均值）
     * <p>
     * 不用 article 的 createTime → completedTime：那段墙钟时间包含用户选标题、
     * 编辑大纲的停留时间，同一个人改得慢一点数字就能翻几倍，衡量的是「用户走完全程多久」，
     * 不是「系统生成多久」。改用 agent_log 里各节点的真实执行耗时，
     * 口径清晰、可复现，也不会被人工操作干扰。
     * <p>
     * 聚合交给数据库，避免把全部明细拉到内存再算。
     */
    private Integer calculateAvgDuration() {
        try {
            Double avgMs = agentLogMapper.avgAiDurationMs();
            return avgMs != null ? (int) Math.round(avgMs) : 0;
        } catch (Exception e) {
            log.warn("计算平均耗时失败", e);
            return 0;
        }
    }

    /**
     * 统计活跃用户数（本周有创作的用户）
     * <p>
     * 用 COUNT(DISTINCT userId) 让数据库去重，避免拉全量文章到内存。
     */
    private Long countActiveUsers(LocalDateTime weekStart) {
        try {
            Long count = articleMapper.countDistinctUsersSince(weekStart);
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.warn("统计活跃用户失败", e);
            return 0L;
        }
    }

    /**
     * 统计总用户数
     */
    private Long countTotalUsers() {
        return userMapper.selectCountByQuery(QueryWrapper.create());
    }

    /**
     * 统计 VIP 用户数
     */
    private Long countVipUsers() {
        Long count = userMapper.countByRole(UserConstant.VIP_ROLE);
        return count != null ? count : 0L;
    }

    /**
     * 计算配额使用量
     * <p>
     * 口径：普通用户数 × 默认配额 - 剩余配额总和。
     * 注意该口径隐含「每个普通用户初始配额都是默认值」的前提；
     * 若将来支持活动赠送/调整默认值，应改为统计实际消耗流水。
     */
    private Long calculateQuotaUsed() {
        try {
            Long normalUserCount = userMapper.countByRole(UserConstant.DEFAULT_ROLE);
            Long remainingQuota = userMapper.sumQuotaByRole(UserConstant.DEFAULT_ROLE);

            long count = normalUserCount != null ? normalUserCount : 0L;
            long remaining = remainingQuota != null ? remainingQuota : 0L;

            long used = (count * UserConstant.DEFAULT_QUOTA) - remaining;
            // 赠送配额会让 remaining 超过初始总额，兜底避免负数
            return Math.max(used, 0L);
        } catch (Exception e) {
            log.warn("计算配额使用量失败", e);
            return 0L;
        }
    }

    /**
     * 获取今天开始时间
     */
    private LocalDateTime getTodayStart() {
        return LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
    }

    /**
     * 获取本周开始时间（周一）
     */
    private LocalDateTime getWeekStart() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        return LocalDateTime.of(monday, LocalTime.MIN);
    }

    /**
     * 获取本月开始时间
     */
    private LocalDateTime getMonthStart() {
        LocalDate today = LocalDate.now();
        LocalDate firstDay = today.withDayOfMonth(1);
        return LocalDateTime.of(firstDay, LocalTime.MIN);
    }


}

