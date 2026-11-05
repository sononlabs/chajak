"""허밍 악보를 '얼마나 그대로 따를지' 자유도에 맞춰 손질한다.

0 에 가까우면 원본 그대로, 중간이면 다듬기(임시표 정리 · 아주 짧은 음 제거),
높으면 다듬은 악보를 YuE2 melody 모드에 넘겨 AI 가 편곡한다.
앞 동기만 남기고 full 모드로 새로 쓰는 방식은 2026-11-03 비교 시험에서
허밍과의 유사도가 무관한 곡 수준(음 순서 0.14~0.64)이라 뺐다.
"""

from __future__ import annotations

import re

NOTE_RE = re.compile(r"([\^_=]*)([A-Ga-gz])([,']*)(\d*)(/*\d*)(-?)")

TIDY_FROM = 1 / 3
AI_FROM = 2 / 3

# RTX 5070 Ti · YuE2 INT8 실측(2026-11-02~03): 곡 1초 = 25토큰, 생성 약 19.5토큰/초,
# 샘플링(32 step)·전사·디코드 등 나머지 약 9초. 30초 상한 = 750토큰.
TOKENS_PER_SEC = 25
TOKEN_RATE = 19.5
FIXED_SEC = 9.0
MAX_MUSIC_SEC = 30.0


def _music_lines(abc: str):
    """(인덱스, 줄, 현재 성부) — 헤더 이후 본문 음표 줄만."""
    voice = None
    in_body = False
    for i, line in enumerate(abc.splitlines()):
        s = line.strip()
        if s.startswith("K:"):
            in_body = True
            continue
        if not in_body or not s or s.startswith("%"):
            continue
        if s.startswith("V:"):
            voice = s[2:].strip().split()[0]
            continue
        if re.match(r"^[A-Za-z]:", s):
            continue
        yield i, line, voice


def route_melody(abc: str, voices=("Ins",)) -> str:
    """SheetSage2 는 멜로디를 Vocal·Ins 중 한쪽에 적는다. 멜로디를 voices 성부에 두고 나머지는 쉼표로 비운다.

    YuE2 는 Vocal 성부에 음이 있으면 '연주곡' 지시가 있어도 노래하는 경우가 많다(2차 시험).
    """
    lines = abc.splitlines()
    rows = {"Vocal": [], "Ins": []}
    for i, line, voice in _music_lines(abc):
        if voice in rows:
            rows[voice].append(i)
    notes = lambda idx: sum(len(NOTE_RE.findall(lines[i].replace("z", ""))) for i in idx)
    src = "Vocal" if notes(rows["Vocal"]) > notes(rows["Ins"]) else "Ins"
    for vi, ii in zip(rows["Vocal"], rows["Ins"]):
        melody = lines[vi] if src == "Vocal" else lines[ii]
        bars = sum(1 for b in melody.split("|") if b.strip())
        lines[vi] = melody if "Vocal" in voices else f"Z{bars}|"
        lines[ii] = melody if "Ins" in voices else f"Z{bars}|"
    return "\n".join(lines)


def _tidy_line(line: str, counts: dict) -> str:
    def repl(m):
        acc, letter, octave, num, frac, tie = m.groups()
        if letter == "z":
            return m.group(0)
        if not num and not frac:
            counts["short"] += 1
            return "z"
        if acc:
            counts["accidental"] += 1
        return f"{letter}{octave}{num}{frac}{tie}"
    return NOTE_RE.sub(repl, line)


def estimate_seconds(abc: str, melody_voice: str = "Ins"):
    """반환: (악보 기준 곡 길이 초, 예상 제작 시간 초, 최대 제작 시간 초).

    YuE2 는 악보가 끝나도 상한(30초)까지 이어 만들 때가 있어 최대값을 함께 준다.
    """
    tempo = 120
    beats = 4.0
    for line in abc.splitlines():
        if line.startswith("Q:"):
            m = re.search(r"=\s*(\d+)", line)
            if m:
                tempo = int(m.group(1))
        elif line.startswith("M:"):
            m = re.match(r"M:\s*(\d+)/(\d+)", line)
            if m:
                beats = int(m.group(1)) * 4 / int(m.group(2))
    bars = 0
    for _, line, voice in _music_lines(abc):
        if voice != melody_voice:
            continue
        for bar in line.split("|"):
            bar = bar.strip()
            if not bar:
                continue
            m = re.fullmatch(r"Z(\d*)", bar)
            bars += int(m.group(1) or 1) if m else 1
    music = min(MAX_MUSIC_SEC, bars * beats * 60 / tempo)
    eta = FIXED_SEC + music * TOKENS_PER_SEC / TOKEN_RATE
    eta_max = FIXED_SEC + MAX_MUSIC_SEC * TOKENS_PER_SEC / TOKEN_RATE
    return music, eta, eta_max


def apply_freedom(abc: str, freedom: float, melody_voice: str = "Ins"):
    """반환: (손질된 abc, 단계 이름, 변경 내역 dict)."""
    lines = abc.splitlines()
    counts = {"short": 0, "accidental": 0}
    if freedom < TIDY_FROM:
        return abc, "그대로", counts

    for i, line, voice in list(_music_lines(abc)):
        if voice == melody_voice:
            lines[i] = _tidy_line(line, counts)
    return "\n".join(lines), ("다듬기" if freedom < AI_FROM else "AI 편곡"), counts
