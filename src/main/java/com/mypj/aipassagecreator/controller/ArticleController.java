package com.mypj.aipassagecreator.controller;

import com.mybatisflex.core.paginate.Page;
import com.mypj.aipassagecreator.annotation.AuthCheck;
import com.mypj.aipassagecreator.common.BaseResponse;
import com.mypj.aipassagecreator.common.DeleteRequest;
import com.mypj.aipassagecreator.common.ResultUtils;
import com.mypj.aipassagecreator.exception.ErrorCode;
import com.mypj.aipassagecreator.exception.ThrowUtils;
import com.mypj.aipassagecreator.manager.SseEmitterManager;
import com.mypj.aipassagecreator.model.dto.article.ArticleCreateRequest;
import com.mypj.aipassagecreator.model.dto.article.ArticleQueryRequest;
import com.mypj.aipassagecreator.model.entity.User;
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

        // 异步执行（传递风格和配图方式）
        articleAsyncService.executeArticleGeneration(
                taskId,
                loginUser,
                request.getTopic(),
                request.getStyle(),
                request.getEnabledImageMethods());

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
        }

        return emitter;
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

