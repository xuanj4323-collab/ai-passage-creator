package com.mypj.aipassagecreator.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.mypj.aipassagecreator.exception.BusinessException;
import com.mypj.aipassagecreator.exception.ErrorCode;
import com.mypj.aipassagecreator.exception.ThrowUtils;
import com.mypj.aipassagecreator.mapper.UserMapper;
import com.mypj.aipassagecreator.model.dto.image.ImageData;
import com.mypj.aipassagecreator.model.dto.user.UserQueryRequest;
import com.mypj.aipassagecreator.model.dto.user.UserUpdateMyRequest;
import com.mypj.aipassagecreator.model.dto.user.UserUpdatePasswordRequest;
import com.mypj.aipassagecreator.model.entity.User;
import com.mypj.aipassagecreator.model.enums.UserRoleEnum;
import com.mypj.aipassagecreator.model.vo.LoginUserVO;
import com.mypj.aipassagecreator.model.vo.UserVO;
import com.mypj.aipassagecreator.service.OssService;
import com.mypj.aipassagecreator.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mypj.aipassagecreator.constant.UserConstant.USER_LOGIN_STATE;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    final List<String> ALLOWED_SORT_FIELDS = List.of("id", "userName", "userAccount", "userRole", "createTime", "updateTime");

    /** 头像允许的 MIME 类型 */
    private static final Set<String> ALLOWED_AVATAR_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    /** 头像大小上限：2MB */
    private static final long MAX_AVATAR_SIZE = 2L * 1024 * 1024;

    @Resource
    private OssService ossService;

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验参数 --保留基础校验，其余由DTO完成
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        // 2. 查询用户是否已存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        long count = this.mapper.selectCountByQuery(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
        // 3. 加密密码
        String encryptPassword = getEncryptPassword(userPassword);
        // 4. 创建用户，插入数据库
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "注册失败，数据库错误");
        }
        return user.getId();
    }

    @Override
    public String getEncryptPassword(String userPassword) {
        // 盐值，混淆密码
        final String SALT = "mypj";
        return DigestUtils.md5DigestAsHex((userPassword + SALT).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验参数
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        // 2. 加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 3. 查询用户是否存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = this.mapper.selectOneByQuery(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 4. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        // 5. 返回脱敏的用户信息
        return this.getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断用户是否登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 从数据库查询当前用户信息（保证数据最新）
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户未登录");
        }
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        if (StrUtil.isBlank(sortField) || !ALLOWED_SORT_FIELDS.contains(sortField)) {
            sortField = "updateTime";
        }

        return QueryWrapper.create()
                .eq("id", id) // where id = ${id}
                .eq("userRole", userRole) // and userRole = ${userRole}
                .like("userAccount", userAccount)
                .like("userName", userName)
                .like("userProfile", userProfile)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream()
                .map(this::getUserVO)
                .collect(Collectors.toList());
    }

    @Override
    public LoginUserVO updateMyUser(UserUpdateMyRequest updateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(updateRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        User loginUser = getLoginUser(request);

        User update = new User();
        update.setId(loginUser.getId());

        if (StrUtil.isNotBlank(updateRequest.getUserName())) {
            String userName = updateRequest.getUserName().trim();
            ThrowUtils.throwIf(userName.length() > 50, ErrorCode.PARAMS_ERROR, "昵称最长 50 个字符");
            update.setUserName(userName);
        }
        if (updateRequest.getUserProfile() != null) {
            String profile = updateRequest.getUserProfile().trim();
            ThrowUtils.throwIf(profile.length() > 500, ErrorCode.PARAMS_ERROR, "简介最长 500 个字符");
            update.setUserProfile(profile);
        }
        if (StrUtil.isNotBlank(updateRequest.getUserAvatar())) {
            update.setUserAvatar(updateRequest.getUserAvatar().trim());
        }
        update.setEditTime(LocalDateTime.now());

        boolean result = this.updateById(update);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新失败");
        return getLoginUserVO(this.getById(loginUser.getId()));
    }

    @Override
    public boolean updatePassword(UserUpdatePasswordRequest passwordRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(passwordRequest == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        String oldPassword = passwordRequest.getOldPassword();
        String newPassword = passwordRequest.getNewPassword();
        String checkPassword = passwordRequest.getCheckPassword();
        ThrowUtils.throwIf(StrUtil.hasBlank(oldPassword, newPassword, checkPassword),
                ErrorCode.PARAMS_ERROR, "密码不能为空");
        ThrowUtils.throwIf(!newPassword.equals(checkPassword),
                ErrorCode.PARAMS_ERROR, "两次输入的新密码不一致");
        ThrowUtils.throwIf(newPassword.length() < 8 || newPassword.length() > 20,
                ErrorCode.PARAMS_ERROR, "新密码长度需为 8-20 位");

        User loginUser = getLoginUser(request);
        ThrowUtils.throwIf(!getEncryptPassword(oldPassword).equals(loginUser.getUserPassword()),
                ErrorCode.PARAMS_ERROR, "原密码不正确");
        ThrowUtils.throwIf(oldPassword.equals(newPassword),
                ErrorCode.PARAMS_ERROR, "新密码不能与原密码相同");

        User update = new User();
        update.setId(loginUser.getId());
        update.setUserPassword(getEncryptPassword(newPassword));
        update.setEditTime(LocalDateTime.now());
        boolean result = this.updateById(update);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "密码修改失败");
        return true;
    }

    @Override
    public String uploadAvatar(MultipartFile file, HttpServletRequest request) {
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择要上传的图片");
        ThrowUtils.throwIf(file.getSize() > MAX_AVATAR_SIZE, ErrorCode.PARAMS_ERROR, "图片大小不能超过 2MB");

        String contentType = ImageData.normalizeMimeType(file.getContentType());
        if (!ALLOWED_AVATAR_TYPES.contains(contentType)) {
            // 部分客户端（脚本、旧版上传控件）不带正确 MIME，退回按扩展名判断
            contentType = mimeTypeFromFileName(file.getOriginalFilename());
        }
        ThrowUtils.throwIf(!ALLOWED_AVATAR_TYPES.contains(contentType),
                ErrorCode.PARAMS_ERROR, "仅支持 PNG / JPEG / WEBP / GIF 格式");

        User loginUser = getLoginUser(request);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取头像文件失败, userId={}", loginUser.getId(), e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "头像读取失败");
        }

        // 复用现有的 OSS 上传通道，头像统一存到 avatar/ 目录
        String avatarUrl = ossService.uploadImageData(ImageData.fromBytes(bytes, contentType), "avatar");
        ThrowUtils.throwIf(StrUtil.isBlank(avatarUrl),
                ErrorCode.OPERATION_ERROR, "头像上传失败，请稍后重试");

        User update = new User();
        update.setId(loginUser.getId());
        update.setUserAvatar(avatarUrl);
        update.setEditTime(LocalDateTime.now());
        this.updateById(update);

        log.info("用户头像已更新, userId={}, url={}", loginUser.getId(), avatarUrl);
        return avatarUrl;
    }

    /**
     * 按文件名扩展名推断图片 MIME 类型，用于客户端未正确携带 Content-Type 的场景
     */
    private static String mimeTypeFromFileName(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return "";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "";
    }
}
