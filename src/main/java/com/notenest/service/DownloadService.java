package com.notenest.service;

import com.notenest.domain.MediaObject;
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

    /**
     * 다운로드 파일명 — "제목(부제).확장자". 확장자는 전체 데모의 실제 형식을 따른다(판별 불가하면 mp3).
     * 원본 파일명의 확장자를 우선하고, 없으면 판별한 Content-Type 으로 정한다.
     */
    public static String downloadFileName(Music music) {
        String name = music.getTitle();
        if (music.getSubtitle() != null && !music.getSubtitle().isEmpty()) {
            name += "(" + music.getSubtitle() + ")";
        }
        return name + "." + extensionOf(music.getFullDemo());
    }

    private static String extensionOf(MediaObject fullDemo) {
        if (fullDemo == null) {
            return "mp3";
        }
        String original = fullDemo.getOriginalName();
        if (original != null) {
            int dot = original.lastIndexOf('.');
            String ext = dot >= 0 ? original.substring(dot + 1).toLowerCase() : "";
            if (ext.equals("mp3") || ext.equals("wav")) {
                return ext;
            }
        }
        return "audio/wav".equals(fullDemo.getContentType()) ? "wav" : "mp3";
    }
}
