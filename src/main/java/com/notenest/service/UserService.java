package com.notenest.service;

import com.notenest.domain.User;
import com.notenest.dto.UserDTO;
import com.notenest.dto.UserMyPageDTO;
import com.notenest.dto.UserUpdatePasswordDTO;
import com.notenest.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final EmailService emailService;

    private final Map<String, String> emailVerificationCodes = new HashMap<>();
    private final Map<String, Boolean> emailVerificationStatus = new HashMap<>();

    public UserService(UserRepository userRepository, BCryptPasswordEncoder bCryptPasswordEncoder, EmailService emailService) {
        this.userRepository = userRepository;
        this.bCryptPasswordEncoder = bCryptPasswordEncoder;
        this.emailService = emailService;
    }

    /**
     * 회원가입
     * @param userDTO
     * @return
     */
    public void signUp(UserDTO userDTO) {
        if (!checkPasswordMatch(userDTO.getPassword(), userDTO.getPasswordCheck())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        Boolean isEmailVerified = emailVerificationStatus.get(userDTO.getEmail());
        if (isEmailVerified == null || !isEmailVerified) {
            throw new IllegalArgumentException("이메일 인증을 완료해주세요.");
        }

        if (userDTO.getAgreement() == null || !userDTO.getAgreement()) {
            throw new IllegalArgumentException("저작물 이용 허락 동의서에 동의해주세요.");
        }

        userDTO.encryptPassword(bCryptPasswordEncoder.encode(userDTO.getPassword()));

        User user = userDTO.toEntity();
        user.setRole("ROLE_ADMIN");
        user.setEmailVerified(true);
        userRepository.save(user);

        emailVerificationStatus.remove(userDTO.getEmail()); // 회원가입 후 인증 상태 제거
    }

    // 비밀번호 확인
    public boolean checkPasswordMatch(String password, String passwordCheck) {
        return password.equals(passwordCheck);
    }

    /**
     * 이메일 인증 요청
     * @param email
     */
    public void sendVerificationEmail(String email) {
        String code = generateVerificationCode();
        emailVerificationCodes.put(email, code); // 이메일 인증 코드 저장
        emailService.sendVerificationEmail(email, code);
    }

    /**
     * 이메일 인증코드 확인
     * @param email
     * @param code
     * @return
     */
    public boolean verifyEmailCode(String email, String code) {
        String storedCode = emailVerificationCodes.get(email);
        if (storedCode != null && storedCode.equals(code)) {
            emailVerificationStatus.put(email, true);
            emailVerificationCodes.remove(email);
            return true;
        }
        return false;
    }

    // 인증 코드 생성
    private String generateVerificationCode() {
        return UUID.randomUUID().toString().substring(0, 6);
    }


    /**
     * 마이페이지 내 정보 조회
     * @return
     */
    public UserMyPageDTO getMyPage() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        User user = userRepository.findByEmail(loggedInUserEmail);

        UserMyPageDTO myPageDTO = new UserMyPageDTO();
        myPageDTO.setEmail(user.getEmail());
        myPageDTO.setName(user.getName());
        myPageDTO.setNickname(user.getNickname());
        myPageDTO.setPhoneNo(user.getPhoneNo());

        return myPageDTO;
    }

    /**
     * 마이페이지 비밀번호 수정
     * @param updatePasswordDTO
     * @return
     */
    public User updatePassword(UserUpdatePasswordDTO updatePasswordDTO) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String loggedInUserEmail = authentication.getName();

        User user = userRepository.findByEmail(loggedInUserEmail);

        if (updatePasswordDTO.getOldPassword() == null || updatePasswordDTO.getOldPassword().isEmpty()) {
            throw new IllegalArgumentException("기존 비밀번호를 입력해주세요.");
        }

        if (!bCryptPasswordEncoder.matches(updatePasswordDTO.getOldPassword(), user.getPassword())) {
            throw new IllegalArgumentException("기존 비밀번호와 일치하지 않습니다.");
        }

        if (updatePasswordDTO.getNewPassword() == null || updatePasswordDTO.getNewPassword().isEmpty()) {
            throw new IllegalArgumentException("새로운 비밀번호를 입력해주세요.");
        }

        if (updatePasswordDTO.getOldPassword().equals(updatePasswordDTO.getNewPassword())) {
            throw new IllegalArgumentException("새로운 비밀번호가 기존 비밀번호와 동일합니다.");
        }

        if (updatePasswordDTO.getPasswordCheck() == null || updatePasswordDTO.getPasswordCheck().isEmpty()) {
            throw new IllegalArgumentException("새로운 비밀번호의 확인이 필요합니다.");
        }

        if (!updatePasswordDTO.getNewPassword().matches("^(?=.*\\d)(?=.*[a-zA-Z])[0-9a-zA-Z@#$%^&+=!]{8,}$")) {
            throw new IllegalArgumentException("비밀번호는 영문과 숫자의 조합으로 8자 이상 가능합니다.");
        }

        if (!updatePasswordDTO.getNewPassword().equals(updatePasswordDTO.getPasswordCheck())) {
            throw new IllegalArgumentException("새로운 비밀번호가 일치하지 않습니다.");
        }

        user.setPassword(bCryptPasswordEncoder.encode(updatePasswordDTO.getNewPassword()));

        return userRepository.save(user);
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
}
