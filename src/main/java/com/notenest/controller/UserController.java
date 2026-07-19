package com.notenest.controller;

import com.notenest.dto.UserDTO;
import com.notenest.service.UserService;
import com.notenest.validator.CheckEmailValidator;
import com.notenest.validator.CheckNicknameValidator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {
    private final UserService userService;
    private final CheckEmailValidator checkEmailValidator;
    private final CheckNicknameValidator checkNicknameValidator;

    public UserController(UserService userService, CheckEmailValidator checkEmailValidator, CheckNicknameValidator checkNicknameValidator) {
        this.userService = userService;
        this.checkEmailValidator = checkEmailValidator;
        this.checkNicknameValidator = checkNicknameValidator;
    }

    @InitBinder
    public void validatorBinder(WebDataBinder binder) {
        binder.addValidators(checkEmailValidator);
        binder.addValidators(checkNicknameValidator);
    }

    // 회원가입
    @PostMapping("/signUp")
    public ResponseEntity<?> signUp(@Valid @RequestBody UserDTO userDTO, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            // 유효성 검사를 통과하지 못한 필드와 메시지 핸들링
            Map<String, String> errorMap = new HashMap<>();
            for (FieldError error : bindingResult.getFieldErrors()) {
                errorMap.put(error.getField(), error.getDefaultMessage());
            }
            // 오류 응답 반환
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorMap);
        }

        try {
            userService.signUp(userDTO);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }

        return ResponseEntity.ok(Map.of("message", "회원가입이 완료되었습니다."));

    }

    // 이메일 인증 요청
    @PostMapping("/verifyEmail")
    public ResponseEntity<?> verifyEmail(@RequestParam String email) {
        userService.sendVerificationEmail(email);
        return ResponseEntity.ok(Map.of("message", "인증 코드가 발송되었습니다."));
    }

    // 이메일 인증 코드 확인
    @PostMapping("/checkCode")
    public ResponseEntity<?> checkCode(@RequestParam String email, @RequestParam String code) {
        boolean isVerified = userService.verifyEmailCode(email, code);
        if (isVerified) {
            return ResponseEntity.ok(Map.of("message", "이메일 인증이 완료되었습니다.", "emailVerified", true));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "이메일 인증 코드가 올바르지 않습니다.", "emailVerified", false));
        }
    }

    // 로그아웃
    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        SecurityContextHolder.clearContext();

        // JSON 형태의 응답 반환
        Map<String, String> responseMap = new HashMap<>();
        responseMap.put("message", "로그아웃이 완료되었습니다.");
        return ResponseEntity.ok(responseMap);
    }

}
