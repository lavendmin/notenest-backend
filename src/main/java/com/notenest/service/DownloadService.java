package com.notenest.service;

import com.notenest.domain.Music;
import com.notenest.domain.User;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class DownloadService {
    private final MusicRepository musicRepository;
    private final UserRepository userRepository;

    public DownloadService(MusicRepository musicRepository, UserRepository userRepository) {
        this.musicRepository = musicRepository;
        this.userRepository = userRepository;
    }

    private Map<UUID, Integer> downloadCount = new HashMap<>();

    public byte[] downloadMusic(UUID musicUuid, String loggedInUserEmail) {
        Music music = musicRepository.findById(musicUuid)
                .orElseThrow(() -> new IllegalArgumentException("Invalid music UUID: " + musicUuid));

        User user = userRepository.findByEmail(loggedInUserEmail);
        if (user == null) {
            throw new IllegalArgumentException("로그인 후 이용 가능합니다.");
        }

        UUID key = UUID.nameUUIDFromBytes((musicUuid.toString() + loggedInUserEmail).getBytes());
        int count = downloadCount.getOrDefault(key, 0);

        if (count > 5) {
            throw new IllegalArgumentException("다운로드 횟수 초과");
        }

        downloadCount.put(key, count + 1);
        return music.getAudio();
    }

    public int getDownloadCount(UUID musicUuid, String loggedInUserEmail) {
        UUID key = UUID.nameUUIDFromBytes((musicUuid.toString() + loggedInUserEmail).getBytes());
        return downloadCount.getOrDefault(key, 0);
    }
}
