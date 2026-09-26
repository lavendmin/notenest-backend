"""NB5 검색 평가·성능용 코퍼스 SQL 생성기 (결정적).

- 평가 코퍼스: docs/nb5/eval/corpus-v0.jsonl — 사람이 설계한 표적 곡과 혼동 후보.
- 성능용 곡: 고정 시드 난수로 만든 filler. --total 로 전체 곡 수(평가 + filler)를 정한다.

출력 SQL 은 항상 `notenest_nb5` 스키마만 대상으로 한다(USE 고정). 기존 `notenest` DB 는 건드리지 않는다.
[Phase 1] bpm·musical_key 를 함께 적재한다(nb5-01 스키마). 평가 곡은 설계 속성, filler 는 별도 시드의 결정적 값이다.
filler 의 제목·가격 등은 Phase 0 과 같은 난수열을 쓰므로 Phase 0 filler 와 동일하다(속성만 추가).

사용법 (레포 루트):
  python scripts/nb5/nb5_corpus.py --total 57    > build/nb5/eval-only.sql
  python scripts/nb5/nb5_corpus.py --total 10000 > build/nb5/n10000.sql
  docker exec -i notenest-db mariadb -uroot -plocal-only < build/nb5/eval-only.sql
"""
import argparse
import hashlib
import json
import random
import sys
import uuid
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "docs" / "nb5" / "eval" / "corpus-v0.jsonl"
TARGET_DB = "notenest_nb5"
NS = uuid.UUID("5b1d6c3e-0e0a-4b5e-9f3a-6e6b0c0d0b05")  # NB5 코퍼스 전용 네임스페이스
FILLER_SEED = 20260926
ATTR_SEED = 20260927  # filler BPM·키 — FILLER_SEED 난수열을 건드리지 않도록 분리
BASE_TIME = datetime(2026, 9, 1, 0, 0, 0)
ONGOING_END = "2030-12-31 00:00:00"
ENDED_END = "2026-09-01 00:00:00"
COVER_KEY = "nb5/seed/cover"  # URL 서명만 하고 GET 하지 않는다

GENRES = ["pop", "balad", "hiphop", "trot"]  # 원 프론트엔드의 majorGenre 값
TAGS = ["#comic", "#bright", "#fresh", "#groovy", "#hope", "#sad",
        "#pop", "#dance", "#electronic", "#jazz", "#indie", "#rock"]  # 원 프론트엔드의 고정 태그
KO_WORDS = ["바람", "하늘", "별", "밤", "노래", "사랑", "기억", "시간", "거리", "꿈",
            "새벽", "바다", "달", "눈", "빛", "길", "마음", "여행", "편지", "계절"]
EN_WORDS = ["Love", "Night", "Dream", "Blue", "Light", "Road", "Heart", "Summer", "Rain", "Star",
            "Fire", "Wave", "Moon", "City", "Home"]
KO_SUB = ["감성 발라드", "미디엄 템포 팝", "그루브한 비트", "밝은 댄스곡", "쓸쓸한 어쿠스틱",
          "트로트 메들리", "몽환적인 신스", "잔잔한 연주곡"]


# com.notenest.domain.MusicalKey 와 같은 순서(음높이 0~11)
MAJOR_CODES = ["C_MAJOR", "D_FLAT_MAJOR", "D_MAJOR", "E_FLAT_MAJOR", "E_MAJOR", "F_MAJOR",
               "F_SHARP_MAJOR", "G_MAJOR", "A_FLAT_MAJOR", "A_MAJOR", "B_FLAT_MAJOR", "B_MAJOR"]
MINOR_CODES = ["C_MINOR", "C_SHARP_MINOR", "D_MINOR", "E_FLAT_MINOR", "E_MINOR", "F_MINOR",
               "F_SHARP_MINOR", "G_MINOR", "G_SHARP_MINOR", "A_MINOR", "B_FLAT_MINOR", "B_MINOR"]
PITCH = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6,
         "G": 7, "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11}


def key_code(short):
    """코퍼스의 짧은 표기(Am, F#m, Eb …)를 enum 이름으로. 코퍼스 표기는 QrelsIntegrityTest 가 Java 파서로도 검증한다."""
    if short is None:
        return None
    minor = short.endswith("m")
    return (MINOR_CODES if minor else MAJOR_CODES)[PITCH[short[:-1] if minor else short]]


def filler_attributes(count: int):
    rng = random.Random(ATTR_SEED)
    attrs = []
    for _ in range(count):
        bpm = rng.randint(60, 180) if rng.random() >= 0.15 else None  # 15% 는 값 없는 기존 곡
        key = rng.choice(MAJOR_CODES + MINOR_CODES) if rng.random() >= 0.2 else None
        attrs.append((bpm, key))
    return attrs


def music_uuid(song_id: str) -> str:
    return str(uuid.uuid5(NS, "music:" + song_id))


def user_uuid(nickname: str) -> str:
    return str(uuid.uuid5(NS, "user:" + nickname))


def hash_rank_key(song_id: str) -> str:
    # 등록 시각을 관련도와 무관한 해시 순서로 배정한다(표적 곡을 일부러 앞/뒤에 두지 않기 위해).
    return hashlib.sha256(("created:" + song_id).encode()).hexdigest()


def load_corpus():
    with CORPUS.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def filler_rows(count: int):
    rng = random.Random(FILLER_SEED)
    sellers = [f"filler_seller_{i:03d}" for i in range(1, 201)]
    rows = []
    for i in range(1, count + 1):
        if rng.random() < 0.5:
            title = f"{rng.choice(KO_WORDS)}의 {rng.choice(KO_WORDS)}"
        else:
            title = f"{rng.choice(EN_WORDS)} {rng.choice(EN_WORDS)}"
        start = rng.randrange(10, 60) * 10000
        rows.append({
            "id": f"F{i:05d}",
            "title": title,
            "subtitle": rng.choice(KO_SUB),
            "genre": rng.choice(GENRES),
            "hashtag": " ".join(rng.sample(TAGS, rng.randint(1, 3))),
            "details": f"성능 측정용 곡 {i}",
            "seller": rng.choice(sellers),
            "start": start,
            "bid": start + rng.randrange(1, 20) * 10000 if rng.random() < 0.3 else None,
            "likes": rng.randrange(0, 21),
            "status": 1 if rng.random() < 0.2 else 0,
        })
    return rows


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int,)):
        return str(v)
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--total", type=int, required=True, help="전체 곡 수(평가 코퍼스 + filler)")
    args = ap.parse_args()

    corpus = load_corpus()
    if args.total < len(corpus):
        sys.exit(f"--total 은 평가 코퍼스 수({len(corpus)}) 이상이어야 합니다")
    fillers = filler_rows(args.total - len(corpus))
    for s, (bpm, key) in zip(fillers, filler_attributes(len(fillers))):
        s["bpm"], s["key_code"] = bpm, key
    for s in corpus:
        s["key_code"] = key_code(s["key"])
    songs = corpus + fillers

    ranked = sorted(songs, key=lambda s: hash_rank_key(s["id"]))
    step = timedelta(seconds=max(1, (7 * 24 * 3600) // len(ranked)))
    created = {s["id"]: BASE_TIME + step * idx for idx, s in enumerate(ranked)}

    sys.stdout.reconfigure(encoding="utf-8")  # Windows 기본 콘솔 인코딩(cp949) 대신 UTF-8 로 출력
    out = sys.stdout
    out.write("-- generated by scripts/nb5/nb5_corpus.py --total %d (corpus-v0, filler seed %d)\n"
              % (args.total, FILLER_SEED))
    out.write(f"USE {TARGET_DB};\nSET NAMES utf8mb4;\nSTART TRANSACTION;\n")
    out.write("DELETE FROM likes;\nDELETE FROM music;\nDELETE FROM user WHERE email LIKE '%@nb5.local';\n")

    sellers = sorted({s["seller"] for s in songs})
    for chunk in range(0, len(sellers), 500):
        vals = [f"({q(user_uuid(n))}, 1, {q('seller-' + user_uuid(n)[:8] + '@nb5.local')}, 1, {q(n)}, {q(n)}, NULL, NULL, 'ROLE_USER')"
                for n in sellers[chunk:chunk + 500]]
        out.write("INSERT INTO user (user_uuid, agreement, email, email_verified, name, nickname, password, phone_no, role) VALUES\n")
        out.write(",\n".join(vals) + ";\n")

    cols = ("music_uuid, auction_end_time, auction_failure_email_sent, created_at, current_highest_bid, details, hashtag, "
            "hit_song_composer, like_count, major_genre, music_period, popular_composer, show_all_bids, starting_price, "
            "status, steady_work_composer, subtitle, title, user_uuid, cover_object_key, cover_content_type, bpm, musical_key")
    for chunk in range(0, len(songs), 500):
        vals = []
        for s in songs[chunk:chunk + 500]:
            vals.append("(" + ", ".join([
                q(music_uuid(s["id"])),
                q(ONGOING_END if s["status"] == 0 else ENDED_END),
                "0",
                q(created[s["id"]].strftime("%Y-%m-%d %H:%M:%S")),
                q(s["bid"]),
                q(s["details"] or None),
                q(s["hashtag"]),
                "0", q(s["likes"]), q(s["genre"]), "7", "0", "1", q(s["start"]),
                q(s["status"]), "0",
                q(s["subtitle"]), q(s["title"]), q(user_uuid(s["seller"])),
                q(COVER_KEY), q("image/jpeg"),
                q(s["bpm"]), q(s["key_code"]),
            ]) + ")")
        out.write(f"INSERT INTO music ({cols}) VALUES\n" + ",\n".join(vals) + ";\n")
    out.write("COMMIT;\n")


if __name__ == "__main__":
    main()
