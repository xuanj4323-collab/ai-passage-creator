package com.mypj.aipassagecreator.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.mypj.aipassagecreator.model.dto.user.UserQueryRequest;
import com.mypj.aipassagecreator.model.dto.user.UserUpdateMyRequest;
import com.mypj.aipassagecreator.model.dto.user.UserUpdatePasswordRequest;
import com.mypj.aipassagecreator.model.entity.User;
import com.mypj.aipassagecreator.model.vo.LoginUserVO;
import com.mypj.aipassagecreator.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


public interface UserService extends IService<User> {
    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 加密
     *
     * @param userPassword 用户密码
     * @return 加密后的用户密码
     */
    String getEncryptPassword(String userPassword);

    /**
     * 获取脱敏的已登录用户信息
     *
     * @return
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 用户注销
     *
     * @param request
     * @return 是否注销成功
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 获取脱敏后的用户信息
     *
     * @param user 用户信息
     * @return
     */
    UserVO getUserVO(User user);

    /**
     * 根据查询条件构造数据查询参数
     *
     * @param userQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 获取脱敏后的用户信息（分页）
     *
     * @param userList 用户列表
     * @return
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 修改当前登录用户自己的资料（昵称 / 头像 / 简介）
     *
     * @param updateRequest 仅包含可改字段
     * @param request       请求对象
     * @return 更新后的脱敏用户信息
     */
    LoginUserVO updateMyUser(UserUpdateMyRequest updateRequest, HttpServletRequest request);

    /**
     * 修改当前登录用户的密码
     *
     * @param passwordRequest 原密码与新密码
     * @param request         请求对象
     * @return 是否修改成功
     */
    boolean updatePassword(UserUpdatePasswordRequest passwordRequest, HttpServletRequest request);

    /**
     * 上传当前登录用户的头像，返回可访问的 URL，并同步写入用户资料
     *
     * @param file    头像文件
     * @param request 请求对象
     * @return 头像 URL
     */
    String uploadAvatar(MultipartFile file, HttpServletRequest request);
}
