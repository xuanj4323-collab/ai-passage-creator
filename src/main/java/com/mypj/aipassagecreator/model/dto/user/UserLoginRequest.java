package com.mypj.aipassagecreator.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserLoginRequest implements Serializable {
    @NotBlank(message = "用户账号不能为空")
    private String userAccount;
    @NotBlank(message = "用户密码不能为空")
    private String userPassword;

    @Serial
    private static final long serialVersionUID = 1L;
}

