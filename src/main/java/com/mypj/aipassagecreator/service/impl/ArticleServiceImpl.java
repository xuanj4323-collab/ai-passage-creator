package com.mypj.aipassagecreator.service.impl;

import cn.hutool.core.util.IdUtil;
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
import com.mypj.aipassagecreator.model.enums.ArticleStatusEnum;
import com.mypj.aipassagecreator.model.vo.ArticleVO;
import com.mypj.aipassagecreator.service.ArticleService;
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

@Service
@Slf4j
public class ArticleServiceImpl extends ServiceImpl<ArticleMapper, Article> implements ArticleService {

    @Resource
    private QuotaService quotaService;
    // 无需额外注入 ArticleMapper：父类 ServiceImpl<ArticleMapper, Article> 已提供 protected 的 mapper 字段

    @Override
    public String createArticleTask(String topic, String style, List<String> enabledImageMethods, User loginUser) {
        // 生成任务ID
        String taskId = IdUtil.simpleUUID();

        // 创建文章记录
        Article article = new Article();
        article.setTaskId(taskId);
        article.setUserId(loginUser.getId());
        article.setTopic(topic);
        article.setStyle(style);
        article.setEnabledImageMethods(GsonUtils.toJson(enabledImageMethods));
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
    public void saveArticleContent(String taskId, ArticleState state) {
        Article article = getByTaskId(taskId);

        if (article == null) {
            log.error("文章记录不存在, taskId={}", taskId);
            return;
        }

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

        this.updateById(article);
        log.info("文章保存成功, taskId={}", taskId);
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

}

