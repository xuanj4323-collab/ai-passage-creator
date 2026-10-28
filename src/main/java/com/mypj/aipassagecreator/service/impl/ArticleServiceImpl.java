package com.mypj.aipassagecreator.service.impl;

import cn.hutool.core.util.IdUtil;
import com.google.gson.reflect.TypeToken;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.mypj.aipassagecreator.exception.BusinessException;
import com.mypj.aipassagecreator.exception.ErrorCode;
import com.mypj.aipassagecreator.exception.ThrowUtils;
import com.mypj.aipassagecreator.mapper.ArticleMapper;
import com.mypj.aipassagecreator.model.dto.article.ArticleQueryRequest;
import com.mypj.aipassagecreator.model.dto.article.ArticleState;
import com.mypj.aipassagecreator.model.entity.Article;
import com.mypj.aipassagecreator.model.entity.User;
import com.mypj.aipassagecreator.model.enums.ArticlePhaseEnum;
import com.mypj.aipassagecreator.model.enums.ArticleStatusEnum;
import com.mypj.aipassagecreator.model.enums.ImageMethodEnum;
import com.mypj.aipassagecreator.model.vo.ArticleVO;
import com.mypj.aipassagecreator.service.ArticleAgentService;
import com.mypj.aipassagecreator.service.ArticleService;
import com.mypj.aipassagecreator.service.ImageServiceStrategy;
import com.mypj.aipassagecreator.service.QuotaService;
import com.mypj.aipassagecreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static com.mypj.aipassagecreator.constant.UserConstant.ADMIN_ROLE;
import static com.mypj.aipassagecreator.constant.UserConstant.VIP_ROLE;

@Service
@Slf4j
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {


    @Resource
    private QuotaService quotaService;
    // 无需额外注入 ArticleMapper：父类 ServiceImpl<ArticleMapper, Article> 已提供 protected 的 mapper 字段

    @Resource
    private ArticleAgentService articleAgentService;

    @Resource
    private ImageServiceStrategy imageServiceStrategy;

    @Override
    public String createArticleTask(String topic, String style, List<String> enabledImageMethods, User loginUser) {
        // 处理配图方式：如果用户未选择，给普通用户设置默认的非 VIP 方式
        List<String> finalImageMethods = processImageMethods(enabledImageMethods, loginUser);

        // 校验配图方式权限（普通用户不能使用 QWEN_IMAGE 和 SVG_DIAGRAM）
        validateImageMethods(finalImageMethods, loginUser);

        // 获取已注册的所有图片生成方法
        List<String> registeredMethods = imageServiceStrategy.getRegisteredMethods().stream().map(ImageMethodEnum::getValue).toList();

        // 生成任务ID
        String taskId = IdUtil.simpleUUID();

        // 创建文章记录
        Article article = new Article();
        article.setTaskId(taskId);
        article.setUserId(loginUser.getId());
        article.setTopic(topic);
        article.setStyle(style);
        article.setEnabledImageMethods(enabledImageMethods != null && !enabledImageMethods.isEmpty()
                ? GsonUtils.toJson(enabledImageMethods) : GsonUtils.toJson(registeredMethods));
        article.setStatus(ArticleStatusEnum.PENDING.getValue());
        article.setCreateTime(LocalDateTime.now());

        this.save(article);

        log.info("文章任务已创建, taskId={}, userId={}, style={}", taskId, loginUser.getId(), style);
        return taskId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createArticleTaskWithQuotaCheck(String topic, String style, List<String> enabledImageMethods, User loginUser) {
        // 在同一事务中：先扣配额，再创建任务
        // 如果任务创建失败，配额会自动回滚
        quotaService.checkAndConsumeQuota(loginUser);
        return createArticleTask(topic, style, enabledImageMethods, loginUser);
    }


    @Override
    public Article getByTaskId(String taskId) {
        return this.getOne(
                QueryWrapper.create().eq("taskId", taskId)
        );
    }

    @Override
    public boolean updateArticleStatus(String taskId, ArticleStatusEnum fromStatus,
                                       ArticleStatusEnum toStatus, String errorMessage) {
        if (taskId == null || fromStatus == null || toStatus == null) {
            log.warn("状态更新参数不完整, taskId={}, from={}, to={}", taskId, fromStatus, toStatus);
            return false;
        }

        // 显式传枚举的 value 而不是枚举对象：避免 MyBatis EnumTypeHandler 走 name()，
        // 导致「枚举 value 与 name 不再一致」时 SQL 静默匹配不到记录
        int affectedRows = mapper.updateArticleStatus(taskId, fromStatus.getValue(),
                toStatus.getValue(), errorMessage);

        if (affectedRows > 0) {
            log.info("文章状态已更新, taskId={}, {} -> {}", taskId, fromStatus.getValue(), toStatus.getValue());
        } else {
            log.warn("文章状态未更新（当前状态不匹配）, taskId={}, 期望原状态={}", taskId, fromStatus.getValue());
        }
        return affectedRows > 0;
    }

    @Override
    public boolean markFailedIfNotTerminal(String taskId, String errorMessage) {
        if (taskId == null) {
            return false;
        }

        int affectedRows = mapper.markFailedIfNotTerminal(taskId, errorMessage);
        if (affectedRows > 0) {
            log.info("文章已标记为失败, taskId={}", taskId);
        } else {
            log.warn("文章已是终态，跳过失败标记, taskId={}", taskId);
        }
        return affectedRows > 0;
    }

    @Override
    public boolean saveArticleContent(String taskId, ArticleState state) {
        // 只构造需要写入的字段，不再「先读整行再 updateById 写回」。
        // 后者会把读到的 status 一并带回库里，一旦此刻任务已被超时回收置成 FAILED，
        // 就会留下「状态失败、内容齐全」的矛盾记录。
        Article article = new Article();
        article.setTaskId(taskId);
        article.setMainTitle(state.getTitle().getMainTitle());
        article.setSubTitle(state.getTitle().getSubTitle());
        article.setOutline(GsonUtils.toJson(state.getOutline().getSections()));
        article.setContent(state.getContent());
        article.setFullContent(state.getFullContent());

        // 保存封面图 URL（从 images 列表中提取 position=1 的 URL）
        if (state.getImages() != null && !state.getImages().isEmpty()) {
            ArticleState.ImageResult cover = state.getImages().stream()
                    .filter(img -> img.getPosition() != null && img.getPosition() == 1)
                    .findFirst()
                    .orElse(null);
            if (cover != null && cover.getUrl() != null) {
                article.setCoverImage(cover.getUrl());
            }
        }
        article.setImages(GsonUtils.toJson(state.getImages()));
        article.setCompletedTime(LocalDateTime.now());

        // 条件写入：只有任务仍在 PROCESSING 时才落库
        int affected = mapper.saveContentIfProcessing(article);
        if (affected == 0) {
            // 走到这里说明任务已被其他流程终结（典型是被超时回收置为 FAILED）。
            // 此时宁可丢弃这次生成结果，也不能把内容写进一条「失败」的记录里。
            Article current = getByTaskId(taskId);
            log.warn("任务已不在处理中，放弃写入正文, taskId={}, 当前状态={}",
                    taskId, current != null ? current.getStatus() : "记录不存在");
            return false;
        }

        log.info("文章保存成功, taskId={}", taskId);
        return true;
    }

    @Override
    public Page<ArticleVO> listArticleByPage(ArticleQueryRequest request, User loginUser) {
        long current = request.getPageNum();
        long size = request.getPageSize();

        // 构建查询条件
        QueryWrapper queryWrapper = QueryWrapper.create()
                .orderBy("createTime", false);

        // 非管理员只能查看自己的文章
        if (!ADMIN_ROLE.equals(loginUser.getUserRole())) {
            queryWrapper.eq("userId", loginUser.getId());
        } else if (request.getUserId() != null) {
            queryWrapper.eq("userId", request.getUserId());
        }

        // 按状态筛选
        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            queryWrapper.eq("status", request.getStatus());
        }

        // 分页查询
        Page<Article> articlePage = this.page(new Page<>(current, size), queryWrapper);

        // 转换为 VO
        return convertToVOPage(articlePage);
    }

    /**
     * 将文章分页结果转换为 VO 分页
     *
     * @param articlePage 文章分页
     * @return VO 分页
     */
    private Page<ArticleVO> convertToVOPage(Page<Article> articlePage) {
        Page<ArticleVO> articleVOPage = new Page<>();
        articleVOPage.setPageNumber(articlePage.getPageNumber());
        articleVOPage.setPageSize(articlePage.getPageSize());
        articleVOPage.setTotalRow(articlePage.getTotalRow());

        List<ArticleVO> articleVOList = articlePage.getRecords().stream()
                .map(ArticleVO::objToVo)
                .collect(Collectors.toList());
        articleVOPage.setRecords(articleVOList);

        return articleVOPage;
    }

    @Override
    public boolean deleteArticle(Long id, User loginUser) {
        Article article = this.getById(id);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR);

        // 校验权限：只能删除自己的文章（管理员除外）
        checkArticlePermission(article, loginUser);

        // 逻辑删除
        return this.removeById(id);
    }

    /**
     * 校验文章权限
     *
     * @param article   文章
     * @param loginUser 当前用户
     */
    private void checkArticlePermission(Article article, User loginUser) {
        if (!article.getUserId().equals(loginUser.getId()) &&
                !ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    @Override
    public ArticleVO getArticleDetail(String taskId, User loginUser) {
        Article article = getByTaskId(taskId);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR, "文章不存在");

        // 校验权限：只能查看自己的文章（管理员除外）
        checkArticlePermission(article, loginUser);

        return ArticleVO.objToVo(article);
    }


    @Override
    public void confirmTitle(String taskId, String mainTitle, String subTitle, String userDescription, User loginUser) {
        Article article = getByTaskId(taskId);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR, "文章不存在");

        // 校验权限
        checkArticlePermission(article, loginUser);

        // 阶段校验与写入放在同一条条件 UPDATE 里完成。
        // 早先是「先查 phase 再 updateById」，存在几毫秒的 read-modify-write 窗口：
        // 并发两次确认（双击、前端重试、两个标签页）会都通过校验，各自提交一次阶段2任务，
        // LLM 被调用两次，且后完成的那次会覆盖用户随后编辑的大纲。
        int affected = mapper.confirmTitleIfSelecting(taskId, mainTitle, subTitle, userDescription);
        ThrowUtils.throwIf(affected == 0,
                ErrorCode.OPERATION_ERROR, "当前阶段不允许此操作，请刷新页面查看最新状态");

        log.info("用户确认标题, taskId={}, mainTitle={}", taskId, mainTitle);
    }

    @Override
    public void confirmOutline(String taskId, List<ArticleState.OutlineSection> outline, User loginUser) {
        Article article = getByTaskId(taskId);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR, "文章不存在");

        // 校验权限
        checkArticlePermission(article, loginUser);

        // 同 confirmTitle：条件更新保证「校验 + 写入」的原子性，
        // 避免并发确认把阶段3（最昂贵的一步）提交两次。
        int affected = mapper.confirmOutlineIfEditing(taskId, GsonUtils.toJson(outline));
        ThrowUtils.throwIf(affected == 0,
                ErrorCode.OPERATION_ERROR, "当前阶段不允许此操作，请刷新页面查看最新状态");

        log.info("用户确认大纲, taskId={}, sectionsCount={}", taskId, outline.size());
    }

    @Override
    public boolean updatePhase(String taskId, ArticlePhaseEnum fromStatus, ArticlePhaseEnum toStatus) {
        if (taskId == null || fromStatus == null || toStatus == null) {
            log.warn("阶段更新参数不完整, taskId={}, from={}, to={}", taskId, fromStatus, toStatus);
            return false;
        }

        int affectedRows = mapper.updateArticlePhase(taskId, fromStatus.getValue(), toStatus.getValue());

        if (affectedRows > 0) {
            log.info("文章阶段已更新, taskId={}, {} -> {}", taskId, fromStatus.getValue(), toStatus.getValue());
        } else {
            log.warn("文章阶段未更新（当前阶段不匹配）, taskId={}, 期望原阶段={}", taskId, fromStatus.getValue());
        }
        return affectedRows > 0;
    }

    @Override
    public void saveTitleOptions(String taskId, List<ArticleState.TitleOption> titleOptions) {
        Article article = getByTaskId(taskId);
        if (article == null) {
            log.error("文章记录不存在, taskId={}", taskId);
            return;
        }

        article.setTitleOptions(GsonUtils.toJson(titleOptions));
        this.updateById(article);
        log.info("标题方案已保存, taskId={}, optionsCount={}", taskId, titleOptions.size());
    }

    @Override
    public List<ArticleState.OutlineSection> aiModifyOutline(String taskId, String modifySuggestion, User loginUser) {
        Article article = getByTaskId(taskId);
        ThrowUtils.throwIf(article == null, ErrorCode.NOT_FOUND_ERROR, "文章不存在");

        // 校验权限
        checkArticlePermission(article, loginUser);

        // 校验当前阶段（必须是 OUTLINE_EDITING）
        ArticlePhaseEnum currentPhase = ArticlePhaseEnum.getByValue(article.getPhase());
        ThrowUtils.throwIf(currentPhase != ArticlePhaseEnum.OUTLINE_EDITING,
                ErrorCode.OPERATION_ERROR, "当前阶段不允许此操作");

        // 校验用户权限：仅限 VIP 会员能够修改大纲
        ThrowUtils.throwIf(!isVipOrAdmin(loginUser), ErrorCode.NO_AUTH_ERROR, "当前功能仅限 VIP 会员使用");

        // 获取当前大纲
        List<ArticleState.OutlineSection> currentOutline = GsonUtils.fromJson(
                article.getOutline(),
                new TypeToken<List<ArticleState.OutlineSection>>(){}
        );

        // 调用 AI 修改大纲（传入 taskId 以便 @AgentExecution 切面把日志关联到任务）
        List<ArticleState.OutlineSection> modifiedOutline = articleAgentService.aiModifyOutline(
                taskId,
                article.getMainTitle(),
                article.getSubTitle(),
                currentOutline,
                modifySuggestion
        );

        // 保存修改后的大纲
        article.setOutline(GsonUtils.toJson(modifiedOutline));
        this.updateById(article);

        log.info("AI修改大纲完成, taskId={}, sectionsCount={}", taskId, modifiedOutline.size());
        return modifiedOutline;
    }

    /**
     * 处理配图方式
     * 如果用户未选择，给普通用户设置默认的非 VIP 方式，VIP 用户不限制
     */
    private List<String> processImageMethods(List<String> enabledImageMethods, User loginUser) {
        // 如果用户已选择，直接返回
        if (enabledImageMethods != null && !enabledImageMethods.isEmpty()) {
            return enabledImageMethods;
        }

        // VIP 和管理员：不限制，返回 null 表示支持所有方式
        if (isVipOrAdmin(loginUser)) {
            return null;
        }

        // 普通用户：返回默认的非 VIP 方式
        return List.of(
                ImageMethodEnum.UNSPLASH.getValue(),
                ImageMethodEnum.MERMAID.getValue(),
                ImageMethodEnum.ICONIFY.getValue(),
                ImageMethodEnum.EMOJI_PACK.getValue()
        );
    }

    /**
     * 校验配图方式权限
     * 普通用户不能使用 QWEN_IMAGE 和 SVG_DIAGRAM
     */
    private void validateImageMethods(List<String> enabledImageMethods, User loginUser) {
        if (enabledImageMethods == null || enabledImageMethods.isEmpty()) {
            return;
        }

        // VIP 和管理员无限制
        if (isVipOrAdmin(loginUser)) {
            return;
        }

        // 普通用户限制
        for (String method : enabledImageMethods) {
            if (ImageMethodEnum.QWEN_IMAGE.getValue().equals(method) ||
                    ImageMethodEnum.SVG_DIAGRAM.getValue().equals(method)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "高级配图功能（AI 生图、SVG 图表）仅限 VIP 会员使用");
            }
        }
    }

    /**
     * 判断是否为 VIP 或管理员
     */
    private boolean isVipOrAdmin(User user) {
        return ADMIN_ROLE.equals(user.getUserRole()) ||
                VIP_ROLE.equals(user.getUserRole());
    }


}

