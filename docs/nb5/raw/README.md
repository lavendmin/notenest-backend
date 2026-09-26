# NB5 원시 결과 보존 정책

이 디렉터리는 측정·검증 도구의 출력을 **가공하지 않고** 보존한다. 보고서(`docs/nb5/phase*.md`)의 모든 수치는 여기의 파일로 거슬러 올라갈 수 있어야 한다.

- 대상: k6 요약(`*.txt`, `--summary-export` JSON), mariadb/EXPLAIN 출력, 앱·통합 테스트 로그 발췌, 캡처한 검색 결과(`*-results-*.json/.md`), 채점 결과(`*-quality.md`), ES 메모리 샘플(CSV)
- 원본 그대로: 후행 공백·표 테두리·콘솔 문자까지 도구 출력 그대로 둔다. 그래서 `git diff --check` 공백 검사에서 제외한다(루트 `.gitattributes`: `docs/nb5/raw/** -whitespace`). `scripts/nb5/phase2/schema-notenest_nb5.sql`(mariadb-dump 출력)도 같다.
- 고치지 않는다: 측정을 다시 하면 파일명에 단계(`phase0`~`phase5`, `review-`)를 붙여 새로 남긴다. 기존 파일은 덮어쓰지 않는다. 단, 같은 단계 안에서 잘못 실행해 다시 잰 경우는 보고서에 그 사실을 적고 덮어썼다(Phase 2 LIKE + 규칙 대조군 첫 실행).
- 넣지 않는다: 비밀값, 실제 presigned URL, ES·DB 데이터 디렉터리. 캡처 스크립트는 커버 URL 대신 존재 여부(`coverUrlPresent`)만 저장한다.
- 실제 사용자 데이터가 아니다: 모든 곡·판매자는 평가 코퍼스(`docs/nb5/eval/corpus-v0.jsonl`)와 결정적 생성기의 합성 데이터다.
