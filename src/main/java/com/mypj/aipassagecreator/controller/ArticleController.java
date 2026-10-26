package com.mypj.aipassagecreator.controller;

import com.mybatisflex.core.paginate.Page;
import com.mypj.aipassagecreator.annotation.AuthCheck;
import com.mypj.aipassagecreator.common.BaseResponse;
import com.mypj.aipassagecreator.common.DeleteRequest;
import com.mypj.aipassagecreator.common.ResultUtils;
import com.mypj.aipassagecreator.exception.ErrorCode;
import com.mypj.aipassagecreator.exception.ThrowUtils;
import com.mypj.aipassagecreator.manager.SseEmitterManager;
import com.mypj.aipassagecreator.model.dto.article.*;
import com.mypj.aipassagecreator.model.entity.User;
import com.mypj.aipassagecreator.model.enums.ArticlePhaseEnum;
import com.mypj.aipassagecreator.model.enums.ArticleStatusEnum;
import com.mypj.aipassagecreator.model.enums.ArticleStyleEnum;
import com.mypj.aipassagecreator.model.enums.SseMessageTypeEnum;
import com.mypj.aipassagecreator.model.vo.ArticleVO;
import com.mypj.aipassagecreator.service.ArticleAsyncService;
import com.mypj.aipassagecreator.service.ArticleService;
import com.mypj.aipassagecreator.service.UserService;
import com.mypj.aipassagecreator.utils.GsonUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/article")
@Tag(name = "ArticleController", description = "文章接口")
@Slf4j
public class ArticleController {

    @Resource
    private ArticleService articleService;

    @Resource
    private ArticleAsyncService articleAsyncService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private UserService userService;

    /**
     * 创建文章任务
     */
    @PostMapping("/create")
    @Operation(summary = "创建文章任务")
    public BaseResponse<String> createArticle(@RequestBody ArticleCreateRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTopic() == null || request.getTopic().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "选题不能为空");
        // 校验风格参数（允许为空）
        ThrowUtils.throwIf(!ArticleStyleEnum.isValid(request.getStyle()),
                ErrorCode.PARAMS_ERROR, "无效的文章风格");

        User loginUser = userService.getLoginUser(httpServletRequest);

        // 创建任务（包含风格参数）
        String taskId = articleService.createArticleTaskWithQuotaCheck(
                request.getTopic(),
                request.getStyle(),
                request.getEnabledImageMethods(),
                loginUser);

        // 异步执行阶段一：生成标题方案
        articleAsyncService.executePhase1(
                taskId,
                loginUser,
                request.getTopic(),
                request.getStyle());

        return ResultUtils.success(taskId);
    }

    /**
     * SSE 进度推送
     */
    @GetMapping("/progress/{taskId}")
    @Operation(summary = "获取文章生成进度(SSE)")
    public SseEmitter getProgress(@PathVariable String taskId, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(taskId == null || taskId.trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "任务ID不能为空");

        // 校验权限（内部会检查任务是否存在以及用户是否有权限访问）
        User loginUser = userService.getLoginUser(httpServletRequest);
        ArticleVO articleDetail = articleService.getArticleDetail(taskId, loginUser);
        ArticleStatusEnum status = ArticleStatusEnum.getByValue(articleDetail.getStatus());

        // 创建 SSE Emitter
        SseEmitter emitter = sseEmitterManager.createEmitter(taskId);
        log.info("SSE 连接已建立, taskId={}", taskId);

        // 任务已是终态时不会再有异步推送。若只建连而不补发消息，前端会一直停在「创作中」
        // 直到超时——所以立即补齐一条终态消息并关闭连接。
        if (status != null && status.isTerminal()) {
            sendTerminalState(taskId, status, articleDetail.getErrorMessage());
            log.info("任务已是终态, 已补发消息并关闭连接, taskId={}, status={}", taskId, status.getValue());
        } else {
            // 中间阶段（等待用户选择标题 / 编辑大纲）：拉取当前快照补发。
            // 这三阶段之间存在长时间的用户思考，SSE 连接可能早已超时断开，
            // 前端刷新或从列表页重进时只能依靠该快照恢复界面。
            sendPhaseSnapshot(taskId, articleDetail);
        }

        return emitter;
    }

    /**
     * 中间阶段的快照补发。
     * <p>
     * 仅对「等待用户操作」的两个阶段补发：{@code TITLE_SELECTING} 与 {@code OUTLINE_EDITING}。
     * 「生成中」阶段没有已完成的结果可补，前端应继续等待异步推送。
     * <p>
     * 消息类型与字段必须与 {@code ArticleAsyncService} 的推送完全一致，
     * 这样前端 {@code handleSSEMessage} 无需区分「实时消息」与「补发消息」。
     */
    private void sendPhaseSnapshot(String taskId, ArticleVO detail) {
        ArticlePhaseEnum phase = ArticlePhaseEnum.getByValue(detail.getPhase());
        if (phase == null) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        switch (phase) {
            case TITLE_SELECTING -> {
                payload.put("type", SseMessageTypeEnum.TITLES_GENERATED.getValue());
                payload.put("titleOptions", detail.getTitleOptions());
            }
            case OUTLINE_EDITING -> {
                payload.put("type", SseMessageTypeEnum.OUTLINE_GENERATED.getValue());
                payload.put("outline", detail.getOutline());
            }
            default -> {
                // PENDING / *_GENERATING：没有可补的完成态数据，等待真实推送即可
                return;
            }
        }

        sseEmitterManager.send(taskId, GsonUtils.toJson(payload));
        sseEmitterManager.complete(taskId);
        log.info("阶段快照已补发并关闭连接, taskId={}, phase={}", taskId, phase.getValue());
    }

    /**
     * 确认标题并输入补充描述
     */
    @PostMapping("/confirm-title")
    @Operation(summary = "确认标题并输入补充描述")
    public BaseResponse<Void> confirmTitle(@RequestBody ArticleConfirmTitleRequest request, HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "任务ID不能为空");
        ThrowUtils.throwIf(request.getSelectedMainTitle() == null || request.getSelectedMainTitle().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "主标题不能为空");
        ThrowUtils.throwIf(request.getSelectedSubTitle() == null || request.getSelectedSubTitle().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "副标题不能为空");

        User loginUser = userService.getLoginUser(httpServletRequest);

        // 确认标题
        articleService.confirmTitle(
                request.getTaskId(),
                request.getSelectedMainTitle(),
                request.getSelectedSubTitle(),
                request.getUserDescription(),
                loginUser
        );

        // 异步执行阶段2：生成大纲
        articleAsyncService.executePhase2(request.getTaskId(), loginUser);

        return ResultUtils.success(null);
    }

    /**
     * 确认大纲
     */
    @PostMapping("/confirm-outline")
    @Operation(summary = "确认大纲")
    public BaseResponse<Void> confirmOutline(@RequestBody ArticleConfirmOutlineRequest request,
                                             HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "任务ID不能为空");
        ThrowUtils.throwIf(request.getOutline() == null || request.getOutline().isEmpty(),
                ErrorCode.PARAMS_ERROR, "大纲不能为空");

        User loginUser = userService.getLoginUser(httpServletRequest);

        // 确认大纲
        articleService.confirmOutline(
                request.getTaskId(),
                request.getOutline(),
                loginUser
        );

        // 异步执行阶段3：生成正文+配图
        articleAsyncService.executePhase3(request.getTaskId(), loginUser);

        return ResultUtils.success(null);
    }

    /**
     * AI 修改大纲
     */
    @PostMapping("/ai-modify-outline")
    @Operation(summary = "AI 修改大纲")
    public BaseResponse<List<ArticleState.OutlineSection>> aiModifyOutline(
            @RequestBody ArticleAiModifyOutlineRequest request,
            HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getTaskId() == null || request.getTaskId().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "任务ID不能为空");
        ThrowUtils.throwIf(request.getModifySuggestion() == null || request.getModifySuggestion().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "修改建议不能为空");

        User loginUser = userService.getLoginUser(httpServletRequest);

        // AI 修改大纲
        List<ArticleState.OutlineSection> modifiedOutline = articleService.aiModifyOutline(
                request.getTaskId(),
                request.getModifySuggestion(),
                loginUser
        );

        return ResultUtils.success(modifiedOutline);
    }


    /**
     * 终态任务建连时的补偿推送。
     * <p>
     * 消息格式必须与 ArticleAsyncService 产出的完全一致：前端统一按 type 分派，
     * 且会对 data 做 JSON.parse，因此不能像早先那样直接下发纯文本（会导致解析失败被吞掉）。
     */
    private void sendTerminalState(String taskId, ArticleStatusEnum status, String errorMessage) {
        Map<String, Object> payload = new HashMap<>();
        if (status == ArticleStatusEnum.COMPLETED) {
            payload.put("type", SseMessageTypeEnum.ALL_COMPLETE.getValue());
            payload.put("taskId", taskId);
        } else {
            payload.put("type", SseMessageTypeEnum.ERROR.getValue());
            payload.put("message", errorMessage != null && !errorMessage.isBlank()
                    ? errorMessage : "任务已失败");
        }
        sseEmitterManager.send(taskId, GsonUtils.toJson(payload));
        sseEmitterManager.complete(taskId);
    }

    /**
     * 获取文章详情
     */
    @GetMapping("/{taskId}")
    @Operation(summary = "获取文章详情")
    @AuthCheck(mustRole = "user")
    public BaseResponse<ArticleVO> getArticle(@PathVariable String taskId,
                                              HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(taskId == null || taskId.trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "任务ID不能为空");

        User loginUser = userService.getLoginUser(httpServletRequest);
        ArticleVO articleVO = articleService.getArticleDetail(taskId, loginUser);

        return ResultUtils.success(articleVO);
    }

    /**
     * 分页查询文章列表
     */
    @PostMapping("/list")
    @Operation(summary = "分页查询文章列表")
    @AuthCheck(mustRole = "user")
    public BaseResponse<Page<ArticleVO>> listArticle(@RequestBody ArticleQueryRequest request,
                                                     HttpServletRequest httpServletRequest) {
        User loginUser = userService.getLoginUser(httpServletRequest);
        Page<ArticleVO> articleVOPage = articleService.listArticleByPage(request, loginUser);

        return ResultUtils.success(articleVOPage);
    }

    /**
     * 删除文章
     */
    @PostMapping("/delete")
    @Operation(summary = "删除文章")
    @AuthCheck(mustRole = "user")
    public BaseResponse<Boolean> deleteArticle(@RequestBody DeleteRequest deleteRequest,
                                               HttpServletRequest httpServletRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);

        User loginUser = userService.getLoginUser(httpServletRequest);
        boolean result = articleService.deleteArticle(deleteRequest.getId(), loginUser);

        return ResultUtils.success(result);
    }
}

