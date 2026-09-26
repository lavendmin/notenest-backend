# P1 — BPM·키 DB CHECK 제약 (P0 에서 분리, 미적용)

상태: **미해결·미적용 P1 후보** (2026-09-26 리뷰 반영으로 nb5-01 에서 분리)

## 왜 분리했나

- Phase 1 의 nb5-01 은 컬럼 추가(STEP 2)와 CHECK 제약 추가(STEP 3)를 함께 정본으로 선언했다. 그런데 CHECK 추가는 테이블 재구성 ALTER 가 필요하다. 이 작업이 로컬 `notenest-db` 컨테이너(Windows 바인드 마운트 `N1\mariadb`)의 실제 `music` 테이블에서 `ERROR 1025 … errno: 194 "Tablespace is missing for a table"` 로 실패했다. 격리 스키마·FK 없는 복사본에서는 성공해 원인을 분리하지 못했다(Phase 1 §6).
- 리뷰 결론: "정본 SQL 에는 필수 CHECK 가 있지만 실제 적용은 실패"인 상태로는 병합할 수 없다. 선택지는 (1) DB 환경을 고쳐 실제 적용을 확인하거나 (2) CHECK 를 P1 로 분리하는 것이다.
- (2)를 택했다. (1)은 기존 `notenest-db` 컨테이너의 데이터 디렉터리 구성을 바꿔야 하는데, 이는 "기존 컨테이너를 삭제·초기화하지 않는다"는 작업 조건과 부딪힌다. 또 "CHECK 는 main 에 반영하지 않고 미해결로 유지한다"는 결정과도 맞지 않는다.

## P0 에서 보장하는 것 (애플리케이션 검증)

| 경로 | 집행 | 테스트 |
|---|---|---|
| 등록·수정 | BPM 40~250 정수, 소수 JSON 거부, 키는 24개 enum 으로 정규화하고 모호(AM)·한글·해석 불가는 400. 파일 업로드 전에 검증 | `MusicAttributesFlowTest`, `MusicAttributesTest` |
| 목록 필터 | `bpmMin`·`bpmMax` 범위·순서, `musicalKey` 정규화, 위반 400 | `MusicFilterContractTest` |
| 저장 형식 | 엔티티가 `MusicalKey` enum 이름만 쓴다(`@Enumerated(STRING)`) | — |

P0 의 한계: 애플리케이션을 거치지 않는 직접 SQL(수동 수정·다른 서비스)은 범위 밖 값을 넣을 수 있다. 그런 행이 생기면 목록 필터에서는 그 값 그대로 비교되고, 상세 응답에서는 enum 변환에 실패할 수 있다.

## P1 로 적용할 SQL (초안 — 실행 스크립트가 아니다)

```sql
ALTER TABLE music
    ADD CONSTRAINT IF NOT EXISTS chk_music_bpm_range CHECK (bpm IS NULL OR bpm BETWEEN 40 AND 250),
    ADD CONSTRAINT IF NOT EXISTS chk_music_musical_key CHECK (musical_key IS NULL OR musical_key IN (
        'C_MAJOR', 'D_FLAT_MAJOR', 'D_MAJOR', 'E_FLAT_MAJOR', 'E_MAJOR', 'F_MAJOR',
        'F_SHARP_MAJOR', 'G_MAJOR', 'A_FLAT_MAJOR', 'A_MAJOR', 'B_FLAT_MAJOR', 'B_MAJOR',
        'C_MINOR', 'C_SHARP_MINOR', 'D_MINOR', 'E_FLAT_MINOR', 'E_MINOR', 'F_MINOR',
        'F_SHARP_MINOR', 'G_MINOR', 'G_SHARP_MINOR', 'A_MINOR', 'B_FLAT_MINOR', 'B_MINOR'));
-- 롤백: ALTER TABLE music DROP CONSTRAINT IF EXISTS chk_music_bpm_range, DROP CONSTRAINT IF EXISTS chk_music_musical_key;
```

이 SQL 은 격리 리허설(Phase 1 [raw](raw/phase1-nb5-01-rehearsal.txt))에서 동작을 확인했다. 정상 값은 저장되고, 위반 4건(39, 251, `Am`, `AM`)은 거부되며, `IF NOT EXISTS` 재실행은 기존 정의를 유지하고, 롤백도 된다. 실패 기록은 [raw](raw/phase1-nb5-01-apply-notenest_nb5.txt)에 있다.

## P1 착수 조건

1. 적용 대상 DB 에서 재구성 ALTER 가 성공하는 환경을 확보한다. 운영과 같은 스토리지(바인드 마운트가 아닌 볼륨·관리형 DB)이거나, 로컬 컨테이너의 데이터 디렉터리 문제를 해결한 상태여야 한다.
2. 적용 전에 기존 행이 제약을 만족하는지 확인한다(`SELECT COUNT(*) FROM music WHERE bpm NOT BETWEEN 40 AND 250 OR musical_key NOT IN (…)` = 0).
3. 별도 마이그레이션 파일(nb5-02)로 만들고, 리허설과 실제 스키마 적용 결과(`INFORMATION_SCHEMA.CHECK_CONSTRAINTS` 2건)를 함께 기록한다.
