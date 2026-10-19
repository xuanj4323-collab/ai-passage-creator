package com.mypj.aipassagecreator.model.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class UserRegisterRequest implements Serializable {
    @NotBlank(message = "用户账号不能为空")
    @Size(min=4, max=32, message = "用户账号长度在4到32个字符之间")
    private String userAccount;
    @NotBlank(message = "密码不能为空")
    @Size(min=8, max=32, message = "密码长度需要在8-32")
    private String userPassword;
    @NotBlank(message = "确认密码不能为空")
    private String checkPassword;

    @Serial
    private static final long serialVersionUID = 1L;
}

