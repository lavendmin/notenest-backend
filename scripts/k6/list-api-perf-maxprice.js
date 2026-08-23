// ============================================================================
// 곡 목록 API — 인증된 maxPrice 필터 요청 성능 측정 (N1 세션: 필터 경로 before/after)
//
// 왜 별도 스크립트인가:
//   list-api-perf.js 는 무필터(?page=0&size=20) 요청 — 이미 Phase 1 프로젝션을
//   타는 1차 진단 경로다. 이 스크립트는 팀 프론트 기본형과 같은 "인증된 maxPrice
//   필터" 요청을 측정한다. 이 요청은 getAllMusicByFilters(Specification+findAll)
//   경로로 들어가 LOB(audio/image) 전량 로딩 + 좋아요 N+1을 겪는다.
//
//   플랜 원 요청은 maxPrice=1000 이었으나 현재 시드 가격이 11,000~15,000 이라
//   1000 이면 결과 0건 → LOB 문제를 재현 못 함. 시드 가격대를 포함하는
//   maxPrice=100000 으로 측정한다 (before/after 동일 요청 유지).
//
// 실행 (레포 루트에서):
//   "C:/Program Files/k6/k6.exe" run scripts/k6/list-api-perf-maxprice.js
//
// 전제: 앱 localhost:8086, bidder@test.local/test1234, N=100 시드(status0=50).
// 측정 원칙: 콜드 1회 버리고 웜 2회차 채택.
// ============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = 'http://localhost:8086';
const MAX_PRICE = __ENV.MAX_PRICE || '100000';
const PATH = `/api/music/filter?searchTerm=&sortBy=latest&page=0&maxPrice=${MAX_PRICE}&minPrice=0`;

export const options = {
    vus: 10,
    duration: '30s',
    thresholds: {
        http_req_failed: ['rate==0'],
    },
};

export function setup() {
    const res = http.post(
        `${BASE}/login`,
        JSON.stringify({ email: 'bidder@test.local', password: 'test1234' }),
        { headers: { 'Content-Type': 'application/json' } },
    );
    const auth = res.headers['Authorization'];
    if (!auth) {
        throw new Error(`로그인 실패 (status=${res.status}) — bidder@test.local 계정 확인`);
    }
    return { auth };
}

export default function (data) {
    const res = http.get(`${BASE}${PATH}`, {
        headers: { Authorization: data.auth },
    });
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(0.1);
}
