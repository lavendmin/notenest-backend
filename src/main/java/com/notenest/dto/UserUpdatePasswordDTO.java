package com.notenest.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserUpdatePasswordDTO {
    private String oldPassword;
    private String newPassword;
    private String passwordCheck;
}
