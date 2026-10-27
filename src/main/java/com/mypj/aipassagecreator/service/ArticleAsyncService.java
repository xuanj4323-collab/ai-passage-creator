package com.mypj.aipassagecreator.service;

import com.google.gson.reflect.TypeToken;
import com.mypj.aipassagecreator.agent.ArticleAgentOrchestrator;
import com.mypj.aipassagecreator.config.AgentConfig;
import com.mypj.aipassagecreator.manager.ArticleStreamBuffer;
import com.mypj.aipassagecreator.manager.SseEmitterManager;
import com.mypj.aipassagecreator.model.dto.article.ArticleState;
import com.mypj.aipassagecreator.model.entity.Article;
import com.mypj.aipassagecreator.model.entity.User;
import com.mypj.aipassagecreator.model.enums.ArticlePhaseEnum;
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

    @Resource
    private ArticleAgentOrchestrator articleAgentOrchestrator;

    @Resource
    private AgentConfig agentConfig;

    @Resource
    private ArticleStreamBuffer articleStreamBuffer;



    /**
     * 阶段1：异步生成标题方案
     *
     * @param taskId 任务ID
     * @param topic  选题
     * @param style  文章风格（可为空）
     */
    @Async("articleExecutor")
    public void executePhase1(String taskId, User loginUser, String topic, String style) {
        // 是否启用 Orchestrator
        boolean orchestratorEnabled = agentConfig.isOrchestratorEnabled();
        log.info("阶段1异步任务开始, taskId={}, topic={}, style={}", taskId, topic, style);

        try {
            // 更新状态和阶段
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
            boolean phaseStarted = articleService.updatePhase(taskId,
                    ArticlePhaseEnum.PENDING, ArticlePhaseEnum.TITLE_GENERATING);
            if (!phaseStarted) {
                // status 已确认由本次执行推进，phase 不匹配属于异常（被其他流程改写），
                // 记录但不中断：继续生成不会损害数据，中断反而让用户白等
                log.warn("阶段推进到 TITLE_GENERATING 未生效, taskId={}", taskId);
            }

            // 创建状态对象
            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setTopic(topic);
            state.setStyle(style);

            // 执行阶段1：生成标题方案
            if (!orchestratorEnabled) {
                articleAgentService.executePhase1_GenerateTitles(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            } else {
                articleAgentOrchestrator.executePhase1_GenerateTitles(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            }

            // 保存标题方案到数据库
            articleService.saveTitleOptions(taskId, state.getTitleOptions());

            // 更新阶段为等待选择标题
            boolean toSelecting = articleService.updatePhase(taskId,
                    ArticlePhaseEnum.TITLE_GENERATING, ArticlePhaseEnum.TITLE_SELECTING);
            if (!toSelecting) {
                log.warn("阶段推进到 TITLE_SELECTING 未生效, taskId={}", taskId);
            }

            // 推送标题方案生成完成消息
            Map<String, Object> data = new HashMap<>();
            data.put("titleOptions", state.getTitleOptions());
            sendSseMessage(taskId, SseMessageTypeEnum.TITLES_GENERATED, data);

            // 刻意不在这里调用 sseEmitterManager.complete()：
            // 服务端主动关闭连接会让浏览器 EventSource 判定为「异常断开」并触发 onerror，
            // 前端会误报「连接中断」；关闭过快还可能吞掉刚到的那条消息。
            // 正确做法是发完消息即返回，由客户端识别终态消息后自行 close()。
            log.info("阶段1异步任务完成, taskId={}", taskId);
        } catch (Exception e) {
            log.error("阶段1异步任务失败, taskId={}", taskId, e);
            failedAndRefundQuota(taskId, loginUser, e);
        }
    }

    /**
     * 阶段2：异步生成大纲（用户确认标题后调用）
     *
     * @param taskId 任务ID
     */
    @Async("articleExecutor")
    public void executePhase2(String taskId, User loginUser) {
        // 是否启用 Orchestrator
        boolean orchestratorEnabled = agentConfig.isOrchestratorEnabled();
        log.info("阶段2异步任务开始, taskId={}", taskId);

        try {
            // 获取文章信息
            Article article = articleService.getByTaskId(taskId);
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }

            // 创建状态对象
            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setStyle(article.getStyle());
            state.setUserDescription(article.getUserDescription());

            // 设置标题
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);

            // 执行阶段2：生成大纲
            if (!orchestratorEnabled) {
                articleAgentService.executePhase2_GenerateOutline(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            } else {
                articleAgentOrchestrator.executePhase2_GenerateOutline(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            }

            // 保存大纲到数据库
            Article articleToUpdate = articleService.getByTaskId(taskId);
            articleToUpdate.setOutline(GsonUtils.toJson(state.getOutline().getSections()));
            articleService.updateById(articleToUpdate);

            // 更新阶段为等待编辑大纲
            boolean toEditing = articleService.updatePhase(taskId,
                    ArticlePhaseEnum.OUTLINE_GENERATING, ArticlePhaseEnum.OUTLINE_EDITING);
            if (!toEditing) {
                log.warn("阶段推进到 OUTLINE_EDITING 未生效, taskId={}", taskId);
            }

            // 推送大纲生成完成消息
            Map<String, Object> data = new HashMap<>();
            data.put("outline", state.getOutline().getSections());
            sendSseMessage(taskId, SseMessageTypeEnum.OUTLINE_GENERATED, data);

            // 同上：不主动关闭，交给客户端识别终态后 close()
            log.info("阶段2异步任务完成, taskId={}", taskId);
        } catch (Exception e) {
            log.error("阶段2异步任务失败, taskId={}", taskId, e);

            failedAndRefundQuota(taskId, loginUser, e);
        }
    }

    /**
     * 阶段3：异步生成正文+配图（用户确认大纲后调用）
     *
     * @param taskId 任务ID
     */
    @Async("articleExecutor")
    public void executePhase3(String taskId, User loginUser) {
        // 是否启用 Orchestrator
        boolean orchestratorEnabled = agentConfig.isOrchestratorEnabled();
        log.info("阶段3异步任务开始, taskId={}", taskId);

        try {
            // 获取文章信息
            Article article = articleService.getByTaskId(taskId);
            if (article == null) {
                throw new RuntimeException("文章不存在");
            }

            // 创建状态对象
            ArticleState state = new ArticleState();
            state.setTaskId(taskId);
            state.setStyle(article.getStyle());

            // 从数据库获取允许的配图方式
            List<String> enabledMethods = null;
            if (article.getEnabledImageMethods() != null) {
                enabledMethods = GsonUtils.fromJson(
                        article.getEnabledImageMethods(),
                        new TypeToken<List<String>>(){}
                );
            }
            state.setEnabledImageMethods(enabledMethods);

            // 设置标题
            ArticleState.TitleResult title = new ArticleState.TitleResult();
            title.setMainTitle(article.getMainTitle());
            title.setSubTitle(article.getSubTitle());
            state.setTitle(title);

            // 设置大纲
            List<ArticleState.OutlineSection> outlineSections = GsonUtils.fromJson(
                    article.getOutline(),
                    new TypeToken<List<ArticleState.OutlineSection>>(){}
            );
            ArticleState.OutlineResult outlineResult = new ArticleState.OutlineResult();
            outlineResult.setSections(outlineSections);
            state.setOutline(outlineResult);

            // 执行阶段3：生成正文+配图
            if (!orchestratorEnabled) {
                articleAgentService.executePhase3_GenerateContent(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            } else {
                articleAgentOrchestrator.executePhase3_GenerateContent(state, message -> {
                    handleAgentMessage(taskId, message, state);
                });
            }

            // 保存完整文章到数据库
            articleService.saveArticleContent(taskId, state);

            // 更新状态为已完成
            articleService.updateArticleStatus(taskId, ArticleStatusEnum.PROCESSING, ArticleStatusEnum.COMPLETED, null);

            // 推送完成消息
            sendSseMessage(taskId, SseMessageTypeEnum.ALL_COMPLETE, Map.of("taskId", taskId));

            // 不主动关闭：由客户端收到 ALL_COMPLETE 后自行 close()
            log.info("阶段3异步任务完成, taskId={}", taskId);
        } catch (Exception e) {
            log.error("阶段3异步任务失败, taskId={}", taskId, e);

            failedAndRefundQuota(taskId, loginUser, e);
        } finally {
            // 走到这里任务已是终态：成功时正文刚落库、失败时已标记 FAILED，
            // 缓冲不再需要，及时释放避免长期占内存。
            articleStreamBuffer.clear(taskId);
        }
    }

    /**
     * 将任务标记为失败并退配额
     * @param taskId 任务ID
     * @param loginUser 登录用户
     * @param e 异常
    */
    private void failedAndRefundQuota(String taskId, User loginUser, Exception e) {
        // 只有「本次把任务从非终态推到 FAILED」才退配额，用 affectedRows 保证幂等
        boolean transitioned = articleService.markFailedIfNotTerminal(taskId, e.getMessage());
        if (transitioned) {
            quotaService.refundQuota(loginUser);
        } else {
            log.warn("任务已处于终态，跳过退款, taskId={}", taskId);
        }
        sendSseMessage(taskId, SseMessageTypeEnum.ERROR, Map.of("message", resolveErrorMessage(e)));
        // 不主动关闭：由客户端收到 ERROR 后自行 close()
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
        if (data == null) {
            return;
        }

        // 阶段3正文流式：除实时推送外再额外累积一份。
        // 生成期间正文不落库（只在收尾 saveArticleContent 时整体保存），
        // 用户此刻刷新页面，已生成的部分就会永久丢失、界面一片空白。
        // 这份缓冲供 getProgress 建连时补发，见 ArticleController#sendPhaseSnapshot。
        if (SseMessageTypeEnum.AGENT3_STREAMING.getValue().equals(data.get("type"))) {
            articleStreamBuffer.append(taskId, (String) data.get("content"));
        }

        sseEmitterManager.send(taskId, GsonUtils.toJson(data));
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

