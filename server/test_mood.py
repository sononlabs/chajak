"""멜로디 성격 분석 시험(서버 없이): python test_mood.py

1) 칸마다 만든 예시 멜로디가 그 칸으로 나오는지
2) 프롬프트 맞춤(스타일 태그·멜로디 카드)이 기대대로인지
3) storage 의 실제 허밍 트랙 분포(보정 참고)
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import melody_mood as M
import melody_notes

FAILS = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")
    if not ok:
        FAILS.append(name)


def mk(tempo: float, beats: list, pitches: list, legato: float = 0.9, repeat: int = 2) -> list:
    """한 소절을 repeat 번 되풀이(실제 허밍은 5~30초)."""
    b, t, out = 60 / tempo, 0.0, []
    for d, p in zip(beats * repeat, pitches * repeat):
        out.append({"start": t, "dur": d * b * legato, "midi": p})
        t += d * b
    return out


# C장조(도미솔 중심) / A단조(라도미 중심) 음 모음
C_MAJ = [60, 62, 64, 65, 67, 69, 71, 72]
A_MIN = [57, 59, 60, 62, 64, 65, 67, 69]

CASES = {
    # 이름: (기대 성격, 템포, 박 길이, 음높이)
    "포근한(자장가)": ("포근한", 68, [2, 1, 1, 2, 2, 1, 1, 2, 2, 4], [60, 62, 64, 62, 60, 64, 65, 64, 62, 60]),
    "쓸쓸한(느린 단조)": ("쓸쓸한", 70, [2, 1, 1, 2, 2, 2, 1, 1, 4], [69, 67, 65, 64, 65, 64, 62, 60, 57]),
    "산뜻한(경쾌한 장조)": ("산뜻한", 116, [1, 0.5, 0.5, 1, 1, 0.5, 0.5, 1, 1, 1, 2], [60, 64, 67, 69, 67, 64, 65, 67, 72, 71, 72]),
    "아련한(중간 단조)": ("아련한", 112, [1, 0.5, 0.5, 1, 1, 0.5, 0.5, 1, 1, 1, 2], [57, 60, 64, 65, 64, 62, 60, 59, 57, 64, 57]),
    "신나는(빠른 장조 도약)": ("신나는", 160, [0.5, 0.5, 0.25, 0.25, 0.5, 1, 0.5, 0.25, 0.25, 0.5, 1, 0.5],
                       [60, 67, 72, 76, 67, 79, 64, 72, 76, 84, 67, 84]),
    "강렬한(빠른 단조 도약)": ("강렬한", 168, [0.5, 0.5, 0.25, 0.25, 0.5, 1, 0.5, 0.25, 0.25, 0.5, 1, 0.5],
                       [57, 60, 64, 72, 65, 77, 57, 69, 72, 81, 64, 72]),  # 라 단조: 도·파(단3·단6)를 살림
}


def synthetic() -> None:
    for name, (want, tempo, beats, pitches) in CASES.items():
        m = M.analyze(mk(tempo, beats, pitches), tempo)
        got = m and m["label"]
        check(f"예시 {name}", got == want, f"→ {got} · 에너지 {m and m['energy']} · {m and m['features']['key']} · {' · '.join(m['why']) if m else ''}")
    lull = M.analyze(mk(*CASES["포근한(자장가)"][1:]), 68)
    rock = M.analyze(mk(*CASES["강렬한(빠른 단조 도약)"][1:]), 168)
    check("에너지 순서(자장가 < 록)", lull["energy"] + 50 < rock["energy"], f"{lull['energy']} < {rock['energy']}")
    check("너무 짧으면 분석 안 함", M.analyze(mk(100, [1, 1, 1], [60, 62, 64], repeat=1), 100) is None)
    check("같은 멜로디면 같은 결과", M.analyze(mk(*CASES["산뜻한(경쾌한 장조)"][1:]), 116) == M.analyze(mk(*CASES["산뜻한(경쾌한 장조)"][1:]), 116))


def prompt() -> None:
    sad = M.analyze(mk(*CASES["쓸쓸한(느린 단조)"][1:]), 70)
    tags = M.merge_style("night drive, nostalgic, male vocal", sad, "금요일 퇴근길")
    check("스타일: 성격 장르가 앞에", tags.startswith("piano ballad"), tags)
    check("스타일: 느린 템포 태그", "slow tempo" in tags)
    check("스타일: 창법 태그", "breathy soft vocal" in tags)
    tags2 = M.merge_style("upbeat, summer", sad, "주말 아침 신나는 록 드라이브")
    check("주제에 장르를 적으면 장르 태그를 넣지 않음", not tags2.startswith("piano ballad") and "slow tempo" in tags2, tags2)
    check("멜로디 카드", M.card(sad).startswith("쓸쓸한 느낌"), M.card(sad))
    check("성격 없으면 LLM 태그 그대로", M.merge_style("a, b", None) == "a, b")


def storage() -> None:
    root = Path(__file__).parent / "storage"
    rows = []
    for p in root.glob("*/song.json"):
        song = json.loads(p.read_text(encoding="utf-8"))
        t = (song.get("tracks") or [None])[0]
        if not t or t.get("variation"):
            continue
        m = M.analyze(melody_notes.notes(t), t["tempo"], t.get("hum_info"))
        if m:
            rows.append((m["energy"], m["label"], m["features"]["key"], m["confident"], p.parent.name))
    if not rows:
        print("저장된 허밍 트랙 없음")
        return
    rows.sort()
    print(f"\n저장된 허밍 1번 트랙 {len(rows)}개 · 에너지 {rows[0][0]}~{rows[-1][0]} · " +
          ", ".join(f"{k} {v}" for k, v in Counter(r[1] for r in rows).most_common()) +
          f" · 확신 {sum(r[3] for r in rows)}/{len(rows)}")


if __name__ == "__main__":
    synthetic()
    prompt()
    storage()
    print("\n전체 PASS" if not FAILS else f"\nFAIL {len(FAILS)}개: {FAILS}")
    sys.exit(1 if FAILS else 0)
