package com.notenest.service;

import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class DownloadService {
    private final MusicRepository musicRepository;
    private final UserRepository userRepository;
    private final FullDemoAccessPolicy fullDemoAccessPolicy;

    public DownloadService(MusicRepository musicRepository, UserRepository userRepository,
                           FullDemoAccessPolicy fullDemoAccessPolicy) {
        this.musicRepository = musicRepository;
        this.userRepository = userRepository;
        this.fullDemoAccessPolicy = fullDemoAccessPolicy;
    }

    // 다운로드 횟수는 제한하지 않는다 — 받은 파일은 복사할 수 있어 횟수 제한은 보호 수단이 되지 못하고,
    // 네트워크 실패·기기 변경 시 정상 사용자만 불편해진다(NB1 결정).
    public Music getDownloadableMusic(UUID musicUuid, String loggedInUserEmail) {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "곡을 찾을 수 없습니다."));

        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인 후 이용 가능합니다.");
        }

        if (!fullDemoAccessPolicy.canAccess(music, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "판매자 또는 결제 완료 낙찰자만 전체 데모를 받을 수 있습니다.");
        }
        return music;
    }
}
