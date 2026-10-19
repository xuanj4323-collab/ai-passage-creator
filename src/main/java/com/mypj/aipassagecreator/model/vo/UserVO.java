package com.mypj.aipassagecreator.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 脱敏后的用户信息
 */
@Data
public class UserVO implements Serializable {

    /**
     * id（雪花 ID 为 19 位，超出 JS 安全整数范围，序列化为字符串避免前端丢精度）
     *
     * 两个注解缺一不可：@JsonSerialize 决定运行时真的输出字符串，
     * @Schema 决定 OpenAPI 文档也标成 string；springdoc 不识别 @JsonSerialize，
     * 少了 @Schema 会生成 number 类型的 typings，与运行时矛盾。
     */
    @Schema(type = "string")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 账号
     */
    private String userAccount;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    private String userRole;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    @Serial
    private static final long serialVersionUID = 1L;
}
