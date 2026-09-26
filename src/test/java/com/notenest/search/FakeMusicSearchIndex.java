package com.notenest.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * [NB5] 기본 테스트용 메모리 색인 — Elasticsearch 없이 동기화·재시도·대조를 검증한다.
 * 실패 주입: 다음 n 번의 upsert·delete 를 {@link SearchIndexException} 으로 실패시킨다(최초 색인 실패·재시도 소진 재현).
 */
public class FakeMusicSearchIndex implements MusicSearchIndex {

    private final Map<String, MusicSearchDocument> documents = new ConcurrentHashMap<>();
    private final AtomicInteger failuresToInject = new AtomicInteger();
    private final AtomicInteger writeCalls = new AtomicInteger();

    public void failNextWrites(int times) {
        failuresToInject.set(times);
    }

    public void reset() {
        documents.clear();
        failuresToInject.set(0);
        writeCalls.set(0);
    }

    /** 테스트가 색인만 직접 바꿔 불일치·잔존을 만든다(동기화를 거치지 않는 상태 재현). */
    public void putRaw(MusicSearchDocument document) {
        documents.put(document.musicId(), document);
    }

    public void removeRaw(String musicId) {
        documents.remove(musicId);
    }

    public MusicSearchDocument get(String musicId) {
        return documents.get(musicId);
    }

    public Map<String, MusicSearchDocument> all() {
        return Map.copyOf(documents);
    }

    public int writeCalls() {
        return writeCalls.get();
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public void ensureExists() {
    }

    @Override
    public void recreate() {
        documents.clear();
    }

    @Override
    public void upsert(MusicSearchDocument document) {
        write();
        documents.put(document.musicId(), document);
    }

    @Override
    public void upsertAll(List<MusicSearchDocument> docs) {
        docs.forEach(this::upsert);
    }

    @Override
    public void delete(String musicId) {
        write();
        documents.remove(musicId);
    }

    @Override
    public void deleteAll(Collection<String> musicIds) {
        musicIds.forEach(this::delete);
    }

    @Override
    public Map<String, String> sourceHashes() {
        Map<String, String> hashes = new ConcurrentHashMap<>();
        documents.forEach((id, doc) -> hashes.put(id, doc.sourceHash()));
        return hashes;
    }

    @Override
    public void refresh() {
    }

    private void write() {
        writeCalls.incrementAndGet();
        if (failuresToInject.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new SearchIndexException("injected failure", null);
        }
    }
}
