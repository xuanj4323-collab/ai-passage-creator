package com.mypj.aipassagecreator.model.dto.user;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户修改自己资料的请求
 *
 * 只允许改昵称、头像、简介；角色、额度等敏感字段不在此列，避免越权。
 */
@Data
public class UserUpdateMyRequest implements Serializable {

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像 URL
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    @Serial
    private static final long serialVersionUID = 1L;
}
