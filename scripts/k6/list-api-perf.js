// ============================================================================
// 곡 목록 API 조회 성능 측정 (Phase 1 baseline)
//
// 무엇을 하나:
//   1) setup 단계에서 bidder@test.local 계정으로 로그인해 JWT를 받는다
//      (목록 API가 로그인 필수라서 — 비로그인이면 500이 떨어짐)
//   2) 10 VU(가상 사용자)가 30초 동안 곡 목록 20건 페이지를 반복 조회한다
//   3) 끝나면 k6가 avg / med / p(90) / p(95) 응답시간을 요약해준다
//
// 실행 방법 (레포 루트에서):
//   k6 run scripts/k6/list-api-perf.js
//
// 전제:
//   - 앱이 localhost:8086에 떠 있을 것
//   - bidder@test.local / test1234 계정이 있을 것 (스모크 시드)
//   - 측정 원칙: 콜드 스타트 1회는 버리고, 웜 상태의 2회차 실행을 채택한다
// ============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = 'http://localhost:8086';

export const options = {
    vus: 10,           // 동시 가상 사용자 수
    duration: '30s',   // 지속 시간
    thresholds: {
        http_req_failed: ['rate==0'],  // 실패 요청 0% 여야 통과
    },
};

// 시작 전에 1회 실행 — 로그인해서 JWT 확보
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

// 각 VU가 반복 실행하는 본문 — 곡 목록 첫 페이지(20건) 조회
export default function (data) {
    const res = http.get(`${BASE}/api/music/filter?page=0&size=20`, {
        headers: { Authorization: data.auth },
    });
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(0.1); // 요청 간 간격 (과도한 폭주 방지)
}
