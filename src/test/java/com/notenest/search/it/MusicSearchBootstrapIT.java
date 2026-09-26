package com.notenest.search.it;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import com.notenest.NoteNestApplication;
import com.notenest.domain.MediaObject;
import com.notenest.domain.Music;
import com.notenest.domain.MusicalKey;
import com.notenest.domain.User;
import com.notenest.dto.MusicSummaryDTO;
import com.notenest.repository.MusicListCondition;
import com.notenest.repository.MusicRepository;
import com.notenest.repository.UserRepository;
import com.notenest.search.MusicSearchPort;
import com.notenest.search.MusicSearchSynchronizer;
import com.notenest.search.SearchIndexBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * [NB5] 부트스트랩 — "기존 DB 데이터 + 비어 있는 Elasticsearch" 로 앱을 다시 띄웠을 때 검색이 원본으로 채워지는지.
 *
 * 앱 컨텍스트를 실제로 두 번 띄운다. 1차 기동에서 DB 에 곡을 저장하고 종료 → 색인을 지워 새 ES 와 같은 상태로 만든다 →
 * 2차 기동(부트스트랩 기본값)만으로 검색이 되고 색인 매핑이 Nori·strict 인지 확인한다. 곡 변경 이벤트 없이 부트스트랩만으로 채워져야 한다.
 * 추가로: 색인이 없는 상태에서 단건 동기화가 먼저 와도 기본 매핑이 아니라 올바른 매핑으로 색인을 만들고,
 * 이 ES 는 색인 자동 생성을 막아 둔다(action.auto_create_index=false).
 */
@Tag("es-integration")
class MusicSearchBootstrapIT {

    private static final String INDEX = "notenest-music-bootstrap-it";

    private static String[] properties(String ddlAuto, boolean bootstrap) {
        return new String[]{
                "server.port=0",
                "spring.jwt.secret=test-secret-key-for-notenest-builds",
                "notenest.scheduling.enabled=false",
                "spring.datasource.url=jdbc:mariadb://localhost:3311/notenest_es_boot_it?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8",
                "spring.jpa.hibernate.ddl-auto=" + ddlAuto,
                "spring.jpa.show-sql=false",
                "logging.level.org.hibernate.SQL=INFO",
                "spring.elasticsearch.uris=" + ElasticsearchTestContainer.url(),
                "notenest.search.index=" + INDEX,
                "notenest.search.bootstrap.enabled=" + bootstrap,
                "notenest.search.reindex-on-startup=false",
        };
    }

    @Test
    @DisplayName("기존 DB + 빈 ES 로 재기동하면 부트스트랩 대조만으로 검색이 채워지고 매핑은 Nori·strict 다")
    void restartWithEmptyElasticsearch() throws Exception {
        ElasticsearchClient es = ElasticsearchTestContainer.client();
        UUID rain;
        UUID night;

        // 1차 기동 — 스키마를 만들고 곡을 저장한다(부트스트랩 끔). 저장은 트랜잭션 없는 save 라 이벤트 없이 DB 만 채운다.
        try (ConfigurableApplicationContext first = start(properties("create", false))) {
            UserRepository users = first.getBean(UserRepository.class);
            MusicRepository musics = first.getBean(MusicRepository.class);
            User seller = users.save(user("seller@test.local", "윤슬"));
            rain = musics.save(music("봄비", "창밖을 적시는 첫 비", seller, 76, MusicalKey.E_FLAT_MAJOR)).getMusicUuid();
            night = musics.save(music("여름밤", "열대야의 끝에서", seller, 118, MusicalKey.F_SHARP_MINOR)).getMusicUuid();
        }

        // 새 Elasticsearch 와 같은 상태 — 색인 없음
        if (es.indices().exists(e -> e.index(INDEX)).value()) {
            es.indices().delete(d -> d.index(INDEX));
        }

        // 2차 기동 — 부트스트랩 기본값. 곡 변경은 없다.
        try (ConfigurableApplicationContext second = start(properties("validate", true))) {
            SearchIndexBootstrap bootstrap = second.getBean(SearchIndexBootstrap.class);
            MusicSearchPort search = second.getBean(MusicSearchPort.class);

            await().atMost(Duration.ofSeconds(30)).until(() -> bootstrap.state() == SearchIndexBootstrap.State.DONE);

            assertThat(ids(search, "봄비")).first().isEqualTo(rain);
            assertThat(ids(search, "여름 밤")).first().as("Nori·compact 정확 일치").isEqualTo(night);
            assertThat(ids(search, "윤슬")).containsExactlyInAnyOrder(rain, night);
            assertMappedWithNori(es);

            // 색인이 사라진 상태에서 단건 동기화가 먼저 와도 올바른 매핑으로 색인을 만든다(기본 매핑 자동 생성 아님)
            es.indices().delete(d -> d.index(INDEX));
            MusicSearchSynchronizer synchronizer = second.getBean(MusicSearchSynchronizer.class);
            synchronizer.submit(() -> synchronizer.syncOnce(rain)).get(30, TimeUnit.SECONDS);
            assertMappedWithNori(es);
            es.indices().refresh(r -> r.index(INDEX));
            assertThat(ids(search, "봄비")).containsExactly(rain);
        }
    }

    @Test
    @DisplayName("통합 테스트 ES 는 색인 자동 생성을 막는다 — 없는 색인에 문서를 넣으면 거부된다")
    void autoCreateIsDisabled() {
        ElasticsearchClient es = ElasticsearchTestContainer.client();
        assertThatThrownBy(() -> es.index(i -> i.index("nb5-auto-create-probe").id("1").document(java.util.Map.of("a", 1))))
                .isInstanceOf(ElasticsearchException.class)
                .hasMessageContaining("index_not_found");
    }

    private static void assertMappedWithNori(ElasticsearchClient es) throws Exception {
        IndexMappingRecord mapping = es.indices().getMapping(m -> m.index(INDEX)).result().get(INDEX);
        assertThat(mapping.mappings().dynamic().jsonValue()).isEqualTo("strict");
        assertThat(mapping.mappings().properties().get("title").text().analyzer()).isEqualTo("ko");
        assertThat(mapping.mappings().properties().get("source_hash").isKeyword()).isTrue();
    }

    private static List<UUID> ids(MusicSearchPort search, String term) {
        return search.search(MusicListCondition.of(null, null, null, null, null, null, null, term), null, PageRequest.of(0, 10))
                .getContent().stream().map(MusicSummaryDTO::getMusicUuid).toList();
    }

    // 설정은 반드시 커맨드라인 인자(--key=value)로 넘긴다. SpringApplicationBuilder.properties() 는 "기본값"(최하위 우선순위)이라
    // application-local.properties 의 datasource(로컬 notenest DB)·ddl-auto 에 덮인다 — 실제로 그렇게 로컬 DB 에 행이 들어간 적이 있다.
    // 기동 직후 실제 연결 대상을 확인해, 다르면 아무것도 쓰기 전에 멈춘다.
    private static ConfigurableApplicationContext start(String[] properties) {
        String[] args = java.util.Arrays.stream(properties).map(p -> "--" + p).toArray(String[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(NoteNestApplication.class).run(args);
        String url = context.getEnvironment().getProperty("spring.datasource.url");
        if (url == null || !url.contains("/notenest_es_boot_it?")) {
            context.close();
            throw new IllegalStateException("테스트 전용 DB 가 아닌 곳에 연결됨: " + url);
        }
        assertThat(context.getEnvironment().getProperty("notenest.search.index")).isEqualTo(INDEX);
        return context;
    }

    private static User user(String email, String nickname) {
        User user = new User();
        user.setEmail(email);
        user.setNickname(nickname);
        user.setName(nickname);
        user.setPassword("x");
        user.setRole("ROLE_USER");
        user.setEmailVerified(true);
        user.setAgreement(true);
        return user;
    }

    private static Music music(String title, String subtitle, User seller, int bpm, MusicalKey key) {
        Music music = new Music();
        UUID id = UUID.randomUUID();
        music.setMusicUuid(id);
        music.setTitle(title);
        music.setSubtitle(subtitle);
        music.setUser(seller);
        music.setMajorGenre("balad");
        music.setHashtag("#sad");
        music.setStartingPrice(10_000L);
        music.setStatus(0);
        music.setBpm(bpm);
        music.setMusicalKey(key);
        music.setAuctionEndTime(LocalDateTime.now().plusDays(3));
        music.setCover(new MediaObject("music/" + id + "/cover/c", "image/png", 10L, null));
        music.setAuctionFailureEmailSent(false);
        music.setShowAllBids(false);
        music.setPopularComposer(false);
        music.setSteadyWorkComposer(false);
        music.setHitSongComposer(false);
        return music;
    }
}
