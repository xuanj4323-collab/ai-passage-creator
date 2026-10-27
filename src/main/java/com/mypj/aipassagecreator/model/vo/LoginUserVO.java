package com.mypj.aipassagecreator.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class LoginUserVO implements Serializable {

    /** 雪花 ID，序列化为字符串避免前端丢精度（@Schema 让文档类型同步为 string） */
    @Schema(type = "string")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String userAccount;
    private String userName;
    private String userAvatar;
    private String userProfile;
    private String userRole;
    private LocalDateTime vipTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    /**
     * 剩余创作额度（管理员与 VIP 不消耗额度，前端据此展示「不限」）
     */
    private Integer quota;
}

