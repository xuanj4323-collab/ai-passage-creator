package com.mypj.aipassagecreator.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成中正文的内存缓冲。
 * <p>
 * 背景：SSE 是「实时事件流」，错过就无法回溯。阶段 3（正文+配图）要跑 40~80 秒，
 * 期间正文是逐字符推送且<b>不落库</b>（只在最后 {@code saveArticleContent} 时整体保存）。
 * 用户在这段时间刷新页面，新连接只能收到「之后」的片段，之前已生成的正文永久丢失，
 * 界面表现为正文区一片空白，且没有任何信息告诉用户还在正常跑。
 * <p>
 * 因此把阶段 3 的流式正文额外累积一份在内存里，供 {@code getProgress} 建连时补发。
 * 补发用的是与实时推送完全相同的 {@code AGENT3_STREAMING} 消息格式，
 * 前端只管往后追加，无需区分「补发」与「实时」。
 * <p>
 * 适用范围：单实例部署。若改成多实例，需要换成 Redis（各实例共享同一份进度），
 * 否则用户刷新后可能落到没有该缓冲的实例上，补发不到内容。
 */
@Component
@Slf4j
public class ArticleStreamBuffer {

    /**
     * 单个任务最多缓存的正文字符数。
     * 正常文章正文约 2000~4000 字，这里留足余量；
     * 上限的作用是防止异常情况下（流式回调狂刷、任务未正常收尾）内存无限增长。
     */
    private static final int MAX_CHARS = 30_000;

    /**
     * taskId -> 已生成的正文片段
     */
    private final Map<String, StringBuilder> buffers = new ConcurrentHashMap<>();

    /**
     * 追加一段流式正文
     *
     * @param taskId 任务ID
     * @param chunk  本次推送的片段（通常只有 1~3 个字符）
     */
    public void append(String taskId, String chunk) {
        if (taskId == null || taskId.isBlank() || chunk == null || chunk.isEmpty()) {
            return;
        }
        StringBuilder sb = buffers.computeIfAbsent(taskId, k -> new StringBuilder());
        synchronized (sb) {
            if (sb.length() >= MAX_CHARS) {
                return;
            }
            sb.append(chunk);
        }
    }

    /**
     * 取出已生成的正文
     *
     * @param taskId 任务ID
     * @return 已累积的正文；没有则返回 null
     */
    public String get(String taskId) {
        if (taskId == null) {
            return null;
        }
        StringBuilder sb = buffers.get(taskId);
        if (sb == null) {
            return null;
        }
        synchronized (sb) {
            return sb.length() == 0 ? null : sb.toString();
        }
    }

    /**
     * 清除某任务的缓冲。
     * 任务进入终态（成功/失败）后调用：此时正文已落库或已失败，
     * 再保留缓冲既没有用处，也会占内存。
     *
     * @param taskId 任务ID
     */
    public void clear(String taskId) {
        if (taskId != null && buffers.remove(taskId) != null) {
            log.debug("已清理正文缓冲, taskId={}", taskId);
        }
    }
}
