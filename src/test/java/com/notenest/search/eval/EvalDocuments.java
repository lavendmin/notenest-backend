package com.notenest.search.eval;

import com.notenest.domain.MusicalKey;
import com.notenest.search.MusicSearchDocument;
import com.notenest.search.MusicSearchSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 평가 코퍼스(corpus-v0.jsonl)를 검색 문서로 만든다 — scripts/nb5/nb5_corpus.py 와 같은 곡 UUID(v5)·등록 시각(해시 순서).
 * 같은 값을 써야 Phase 2 스파이크와 tie-break(created_at·music_id)까지 같은 조건으로 비교된다.
 */
public final class EvalDocuments {

    private static final UUID NAMESPACE = UUID.fromString("5b1d6c3e-0e0a-4b5e-9f3a-6e6b0c0d0b05");
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final String COVER_KEY = "nb5/seed/cover";

    private EvalDocuments() {
    }

    public static UUID musicUuid(String songId) {
        return uuid5(NAMESPACE, "music:" + songId);
    }

    /** 곡 id → 등록 시각. 곡 id 의 SHA-256 순서로 7일 구간에 고르게 배정(관련도와 무관). */
    public static Map<String, LocalDateTime> createdAt(List<EvalData.Song> songs) {
        List<EvalData.Song> ranked = new ArrayList<>(songs);
        ranked.sort(Comparator.comparing(s -> sha256Hex("created:" + s.id())));
        long step = Math.max(1, (7L * 24 * 3600) / ranked.size());
        Map<String, LocalDateTime> created = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            created.put(ranked.get(i).id(), BASE_TIME.plusSeconds(step * i));
        }
        return created;
    }

    public static List<MusicSearchDocument> documents(List<EvalData.Song> songs) {
        Map<String, LocalDateTime> created = createdAt(songs);
        return songs.stream().map(s -> MusicSearchDocument.from(new MusicSearchSource(
                musicUuid(s.id()), created.get(s.id()), s.title(), s.subtitle(), emptyToNull(s.details()), s.hashtag(),
                s.genre(), s.seller(), s.status(), s.start(), s.bid(),
                s.ongoing() ? LocalDateTime.of(2030, 12, 31, 0, 0) : LocalDateTime.of(2026, 9, 1, 0, 0),
                s.likes(), s.bpm(), MusicalKey.parseOrNull(s.key()), COVER_KEY))).toList();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static UUID uuid5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array());
            byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            ByteBuffer b = ByteBuffer.wrap(h, 0, 16);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
