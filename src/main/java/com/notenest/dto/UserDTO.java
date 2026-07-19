package com.notenest.dto;

import com.notenest.domain.User;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class UserDTO {
    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Pattern(regexp = "^(?=.*\\d)(?=.*[a-zA-Z])[0-9a-zA-Z@#$%^&+=!]{8,}$|^$", message = "비밀번호는 영문과 숫자의 조합으로 8자 이상 가능합니다.")
    private String password;

    @NotBlank(message = "비밀번호 확인을 위해 비밀번호를 입력해주세요.")
    private String passwordCheck;

    @NotBlank(message = "이름을 입력해주세요.")
    private String name;

    @NotBlank(message = "닉네임을 입력해주세요.")
    private String nickname;

    @NotBlank(message = "휴대폰 번호를 입력해주세요.")
    @Pattern(regexp = "^\\d{3}-\\d{3,4}-\\d{4}$|^$", message = "휴대폰 번호 형식이 올바르지 않습니다.")
    private String phoneNo;

    @NotNull(message = "저작물 이용 허락 동의서에 동의해주세요.")
    private Boolean agreement;

    // DTO -> Entity
    public User toEntity() {
        User user = new User();
        user.setEmail(email);
        user.setPassword(password);
        user.setPasswordCheck(passwordCheck);
        user.setName(name);
        user.setNickname(nickname);
        user.setPhoneNo(phoneNo);
        user.setAgreement(agreement != null && agreement);

        return user;
    }

    // 암호화된 비밀번호
    public void encryptPassword(String BCryptpassword) {
        this.password = BCryptpassword;
    }
}
