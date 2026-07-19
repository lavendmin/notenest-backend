package com.notenest.validator;

import com.notenest.dto.UserDTO;
import com.notenest.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@RequiredArgsConstructor
@Component
public class CheckEmailValidator extends AbstractValidator<UserDTO> {
    private final UserRepository userRepository;

    @Override
    protected void doValidate(UserDTO dto, Errors errors) {
        if (userRepository.existsByEmail(dto.toEntity().getEmail())) {
            errors.rejectValue("email", "이메일 중복 오류", "이미 가입된 이메일입니다.");
        }
    }
}
