# Nori 분석 결과 (analyzer ko: nori_tokenizer mixed + nori_part_of_speech + lowercase)

| 입력 | 토큰 |
|---|---|
| 봄비 | 봄비 · 봄 · 비 |
| 봄비가 내리면 | 봄비 · 봄 · 비 · 내리 |
| 봄비처럼 스며든 기억 | 봄비 · 봄 · 비 · 스며든 · 스며들 · 기억 |
| 여름밤 | 여름밤 · 여름 · 밤 |
| 여름 밤의 꿈 | 여름 · 밤 · 꿈 |
| 한여름밤 | 여름밤 · 여름 · 밤 |
| Blue Hour | blue · hour |
| bluehour | bluehour |
| 너의 이름을 부르면 | 너 · 이름 · 부르 |
| 잔잔한 피아노 발라드 | 잔잔 · 피아노 · 발라드 |
| 피아노가 잔잔하게 흐르는 곡 | 피아노 · 잔잔 · 흐르 · 곡 |
| 신나는 여름 댄스곡 | 신나 · 여름 · 댄스 · 곡 |
| 이별 후의 새벽 | 이별 · 후 · 새벽 |
| 새벽공방 | 새벽 · 공방 |
| 새벽공방스튜디오 | 새벽 · 공방 · 스튜디오 |
| 새벽 공방의 소리 | 새벽 · 공방 · 소리 |
| 윤슬 | 윤슬 |
| BPM 90 / Key: Am | bpm · 90 · key · am |
| 120%의 에너지 | 120 · 에너지 |
| Lovely Day | lovely · day |
| Glove | glove |
| Blue Hour (normalizer compact) | bluehour |
| 새벽 공방 (normalizer compact) | 새벽공방 |

plugins: analysis-nori 8.11.1