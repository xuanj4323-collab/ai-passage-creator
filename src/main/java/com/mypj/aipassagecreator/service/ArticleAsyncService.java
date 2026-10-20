package com.mypj.aipassagecreator.service;

import com.mypj.aipassagecreator.manager.SseEmitterManager;
import com.mypj.aipassagecreator.model.dto.article.ArticleState;
import com.mypj.aipassagecreator.model.entity.User;
import com.mypj.aipassagecreator.model.enums.ArticleStatusEnum;
import com.mypj.aipassagecreator.model.enums.SseMessageTypeEnum;
import com.mypj.aipassagecreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ArticleAsyncService {

    @Resource
    private ArticleAgentService articleAgentService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private ArticleService articleService;

    @Resource
    private QuotaService quotaService;

    /**
     * 异步执行文章生成
     *
     * @param taskId              任务ID
     * @param topic               选题
     * @param style               文章风格（可为空）
     * @param enabledImageMethods 允许的配图方式列表（为空表示支持所有方式）
     */
    @Async("articleExecutor")
    public void executeArticleGeneration(String taskId, User loginUser, String topic, String style, java.util.List<String> enabledImageMethods) {
        log.info("异步任务开始, taskId={}, topic={}, style={}, enabledImageMethods={}", taskId, topic, style, enabledImageMethods);

        try {
            // 条件更新：只有 PENDING → PROCESSING 成功，才说明本次执行被真正触发。
            // 重复提交、重试、或状态被其他流程改写时 affectedRows = 0，直接放弃，
            // 避免重复消耗 LLM 与生图费用。
            boolean started = articleService.updateArticleStatus(
                    taskId, ArticleStatusEnum.PENDING, ArticleStatusEnum.PROCESSING, null);
            if (!started) {
                log.warn("任务状态已非 PENDING，跳过本次执行, taskId={}", taskId);
                // 前端此刻正在等进度，必须明确告知「这次不会产出」，
                // 否则它只能一直空等到 SSE 超时。
                sendSseMessage(taskId, SseMessageTypeEnum.ERROR,
                        Map.of("message", "任务已被处理或状态异常，请刷新页面查看结果"));
                return;
            }

            // 创建状态对象
            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(topic);
            state.setStyle(style);
            state.setEnabledImageMethods(enabledImageMethods);

            // 执行智能体编排,并通过 SSE 推送进度
            articleAgentService.executeArticleGeneration(state, message -> {
                handleAgentMessage(taskId, message, state);
            });

            // 保存完整文章到数据库
            articleService.saveArticleContent(taskId, state);

            // 更新状态为已完成
            boolean completed = articleService.updateArticleStatus(
                    taskId, ArticleStatusEnum.PROCESSING, ArticleStatusEnum.COMPLETED, null);
            if (!completed) {
                // 内容已成功落库，但状态转换未生效（并发竞争等极端场景）。
                // 取舍：以「内容已生成、用户已等待」为准继续推送完成消息，
                // 用 error 级别留痕便于排查，不因状态机冲突让用户白等一次生成。
                log.error("状态 PROCESSING→COMPLETED 未生效，内容已落库但状态可能不一致, taskId={}", taskId);
            }

            // 推送完成消息
            sendSseMessage(taskId, SseMessageTypeEnum.ALL_COMPLETE, Map.of("taskId", taskId));

            log.info("异步任务完成, taskId={}", taskId);
        } catch (Exception e) {
            log.error("异步任务失败, taskId={}", taskId, e);

            // 只有「本次把任务从非终态推到 FAILED」才退配额，用 affectedRows 保证幂等
            boolean transitioned = articleService.markFailedIfNotTerminal(taskId, e.getMessage());
            if (transitioned) {
                quotaService.refundQuota(loginUser);
            } else {
                log.warn("任务已处于终态，跳过退款, taskId={}", taskId);
            }

            // 推送错误消息
            sendSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", resolveErrorMessage(e)));
        } finally {
            // 统一关闭出口：成功 / 失败 / 提前返回 / 意外异常 都必须经过这里。
            // 只在 try 和 catch 里各写一次 complete()，第三个分支（提前 return）就会漏，
            // emitter 会滞留在 Map 中直到 30 分钟超时才被清理，前端一直空等。
            sseEmitterManager.complete(taskId);
        }
    }

    /**
     * 提取可展示给用户的错误信息。
     * <p>
     * e.getMessage() 可能为 null（如部分 NPE、无消息异常），而 Map.of 不接受 null 值
     * 会直接抛 NPE——这会把「推送失败原因」变成「推送本身崩了」。
     */
    private String resolveErrorMessage(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    /**
     * 处理智能体消息并推送
     */
    private void handleAgentMessage(String taskId, String message, ArticleState state) {
        Map<String, Object> data = buildMessageData(message, state);
        if (data != null) {
            sseEmitterManager.send(taskId, GsonUtils.toJson(data));
        }
    }

    /**
     * 构建消息数据
     */
    private Map<String, Object> buildMessageData(String message, ArticleState state) {
        // 处理流式消息（带冒号分隔符）
        String streamingPrefix2 = SseMessageTypeEnum.AGENT2_STREAMING.getStreamingPrefix();
        String streamingPrefix3 = SseMessageTypeEnum.AGENT3_STREAMING.getStreamingPrefix();
        String imageCompletePrefix = SseMessageTypeEnum.IMAGE_COMPLETE.getStreamingPrefix();

        if (message.startsWith(streamingPrefix2)) {
            return buildStreamingData(SseMessageTypeEnum.AGENT2_STREAMING,
                    message.substring(streamingPrefix2.length()));
        }

        if (message.startsWith(streamingPrefix3)) {
            return buildStreamingData(SseMessageTypeEnum.AGENT3_STREAMING,
                    message.substring(streamingPrefix3.length()));
        }

        if (message.startsWith(imageCompletePrefix)) {
            String imageJson = message.substring(imageCompletePrefix.length());
            return buildImageCompleteData(imageJson);
        }

        // 处理完成消息（枚举值）
        return buildCompleteMessageData(message, state);
    }

    /**
     * 构建流式输出数据
     */
    private Map<String, Object> buildStreamingData(SseMessageTypeEnum type, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.getValue());
        data.put("content", content);
        return data;
    }

    /**
     * 构建图片完成数据
     */
    private Map<String, Object> buildImageCompleteData(String imageJson) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", SseMessageTypeEnum.IMAGE_COMPLETE.getValue());
        data.put("image", GsonUtils.fromJson(imageJson, ArticleState.ImageResult.class));
        return data;
    }

    /**
     * 构建完成消息数据
     */
    private Map<String, Object> buildCompleteMessageData(String message, ArticleState state) {
        Map<String, Object> data = new HashMap<>();

        if (SseMessageTypeEnum.AGENT1_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.AGENT1_COMPLETE.getValue());
            data.put("title", state.getTitle());
        } else if (SseMessageTypeEnum.AGENT2_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.AGENT2_COMPLETE.getValue());
            data.put("outline", state.getOutline().getSections());
        } else if (SseMessageTypeEnum.AGENT3_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.AGENT3_COMPLETE.getValue());
        } else if (SseMessageTypeEnum.AGENT4_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.AGENT4_COMPLETE.getValue());
            data.put("imageRequirements", state.getImageRequirements());
        } else if (SseMessageTypeEnum.AGENT5_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            data.put("images", state.getImages());
        } else if (SseMessageTypeEnum.MERGE_COMPLETE.getValue().equals(message)) {
            data.put("type", SseMessageTypeEnum.MERGE_COMPLETE.getValue());
            data.put("fullContent", state.getFullContent());
        } else {
            return null;
        }

        return data;
    }

    /**
     * 发送 SSE 消息
     */
    private void sendSseMessage(String taskId, SseMessageTypeEnum type, Map<String, Object> additionalData) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type.getValue());
        data.putAll(additionalData);
        sseEmitterManager.send(taskId, GsonUtils.toJson(data));
    }

}

