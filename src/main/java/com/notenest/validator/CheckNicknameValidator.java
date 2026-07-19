
package com.notenest.validator;

import com.notenest.dto.UserDTO;
import com.notenest.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@RequiredArgsConstructor
@Component
public class CheckNicknameValidator extends AbstractValidator<UserDTO> {
    private final UserRepository userRepository;

    @Override
    protected void doValidate(UserDTO dto, Errors errors) {
        if (userRepository.existsByNickname(dto.toEntity().getNickname())) {
            errors.rejectValue("nickname", "닉네임 중복 오류", "이미 사용 중인 닉네임입니다.");
        }
    }
}
