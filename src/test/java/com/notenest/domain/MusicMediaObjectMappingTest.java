package com.notenest.domain;

import com.notenest.config.QueryDslConfig;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Music 의 미디어 객체 참조(cover·preview·fullDemo) 매핑 의미를 고정한다.
 * 스키마 자체(컬럼 타입·길이)는 nb1-02 마이그레이션 적용 DB 에서 ddl-auto=validate 기동으로 확인했다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueryDslConfig.class)
@TestPropertySource(properties = {
        // user 는 H2 예약어라 @Table(name="user") DDL/쿼리가 깨진다 → NON_KEYWORDS 로 제외.
        "spring.datasource.url=jdbc:h2:mem:media-mapping;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class MusicMediaObjectMappingTest {

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("자산별 키·메타데이터가 각자의 컬럼으로 저장·조회되고, 없는 자산(미리듣기)은 null 로 읽힌다")
    void roundTripsEachAssetAndReadsMissingAsNull() {
        Music music = newMusic();
        music.setCover(new MediaObject("music/m/cover/c1", "image/png", 1_024L, "cover.png"));
        music.setFullDemo(new MediaObject("music/m/full-demo/f1", "audio/wav", 60_000_000L, "demo.wav"));
        Music saved = musicRepository.save(music);
        entityManager.flush();
        entityManager.clear();

        Music loaded = musicRepository.findById(saved.getMusicUuid()).orElseThrow();

        assertThat(loaded.getCover().getObjectKey()).isEqualTo("music/m/cover/c1");
        assertThat(loaded.getCover().getContentType()).isEqualTo("image/png");
        assertThat(loaded.getFullDemo().getObjectKey()).isEqualTo("music/m/full-demo/f1");
        assertThat(loaded.getFullDemo().getContentType()).isEqualTo("audio/wav");
        assertThat(loaded.getFullDemo().getSize()).isEqualTo(60_000_000L);
        assertThat(loaded.getFullDemo().getOriginalName()).isEqualTo("demo.wav");
        assertThat(loaded.getPreview()).as("미리듣기 없음 — 기존 곡·백필 전 상태").isNull();
    }

    @Test
    @DisplayName("미디어가 없는 곡은 세 자산 모두 null 로 읽힌다")
    void musicWithoutMediaReadsAllNull() {
        Music music = newMusic();
        Music saved = musicRepository.save(music);
        entityManager.flush();
        entityManager.clear();

        Music loaded = musicRepository.findById(saved.getMusicUuid()).orElseThrow();

        assertThat(loaded.getCover()).isNull();
        assertThat(loaded.getPreview()).isNull();
        assertThat(loaded.getFullDemo()).isNull();
    }

    private Music newMusic() {
        User owner = new User();
        owner.setEmail("owner@test.local");
        owner.setNickname("owner");
        owner.setName("owner");
        owner.setPassword("x");
        owner.setRole("ROLE_USER");
        owner.setEmailVerified(true);
        owner.setAgreement(true);
        userRepository.save(owner);

        Music music = new Music();
        music.setTitle("mapping-track");
        music.setUser(owner);
        music.setStartingPrice(10_000L);
        music.setStatus(0);
        music.setAuctionEndTime(LocalDateTime.now().plusDays(3));
        music.setAuctionFailureEmailSent(false);
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        return music;
    }
}
