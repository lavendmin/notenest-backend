// ============================================================================
// NB5 Phase 0 — 현재 LIKE 검색 기준선 (GET /api/music/filter)
//
// MODE=search   : docs/nb5/eval/queries-v0.jsonl 의 25개 질의를 VU·반복마다 순환 호출(균등 분포)
// MODE=nosearch : 같은 부하로 검색어 없는 최신순 1페이지(기존 QueryDSL 경로 대조군)
//
// 비로그인 호출(목록은 공개 API). 부하 형태는 기존 list-api-perf.js 와 같다(10 VU × 30s, 반복 사이 0.1s).
// 그래서 처리량은 스크립트 상한(≈ 10 ÷ (0.1s + 지연))에 묶인다 — 처리량은 참고값으로만 본다.
//
// 실행 (레포 루트, 앱이 BASE 에 떠 있고 notenest_nb5 에 코퍼스가 적재된 상태):
//   k6 run -e MODE=search   -e BASE=http://localhost:8096 scripts/k6/nb5-search-baseline.js
//   k6 run -e MODE=nosearch -e BASE=http://localhost:8096 scripts/k6/nb5-search-baseline.js
// ============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8096';
const MODE = __ENV.MODE || 'search';
const QUERIES = open('../../docs/nb5/eval/queries-v0.jsonl')
    .split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));

const TYPES = ['T1_TITLE', 'T2_CONDITION', 'T3_MOOD', 'T4_SELLER'];
const thresholds = { http_req_failed: ['rate==0'] };
// 유형별 요약을 출력하려고 태그별 임계값을 둔다(값 자체는 판정용이 아니다).
for (const t of TYPES) thresholds[`http_req_duration{qtype:${t}}`] = ['p(90)<60000'];

export const options = {
    vus: 10,
    duration: '30s',
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max'],
    thresholds,
};

function toQuery(params) {
    return Object.entries(params).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
}

export default function () {
    let url;
    let tags;
    if (MODE === 'nosearch') {
        url = `${BASE}/api/music/filter?page=0&size=10`;
        tags = { qtype: 'NOSEARCH' };
    } else {
        const q = QUERIES[(__VU * 7 + __ITER) % QUERIES.length];
        url = `${BASE}/api/music/filter?` + toQuery({ searchTerm: q.searchTerm, page: 0, size: 10, ...q.params });
        tags = { qtype: q.type, qid: q.qid };
    }
    const res = http.get(url, { tags });
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(0.1);
}
