"""가사-음표 1:1 맞추기(2026-11-10: "맘 흔들려요신 호등불 이커져요" — 띄어쓰기를 무시하고 부르던 문제).

원인: 곡 구성이 허밍을 2마디씩 잘라 한 줄로 삼고(숨 쉬는 자리와 무관), 가사 글자 수도 목표보다 -1~+2 를 허용했다.
가사 줄 글자가 음표보다 적으면 노래 엔진(YuE2)이 다음 줄 첫 글자를 끌어와 채워 낱말이 줄을 넘어 쪼개졌다.
YuE2 가 가사로 스스로 만든 악보는 줄 끝마다 쉼표가 있고 줄마다 음표 수 = 글자 수였다.

그래서
1) phrases(): 부르는 구간의 멜로디를 쉼표로 나눠 프레이즈(= 가사 한 줄)를 찾고, 프레이즈마다 음표 수를 글자 수 목표로 준다.
2) fit(): 가사가 정해진 뒤 프레이즈마다 음표를 합치거나 나눠 **음표 수 = 그 줄의 한글 글자 수** 로 맞춘다(길이 합은 그대로).
악보 형식은 곡 구성 노드(song_form)가 쓰는 것: '% 구간' 머리말, 4마디마다 'V: Vocal' 줄과 'V: Ins' 줄, 마디 앞 화음 "A".
"""
from __future__ import annotations

import re
from dataclasses import dataclass

NOTE = re.compile(r'(?P<chord>"[^"]*")|(?P<rest>[zx])(?P<rd>\d*)(?P<rf>/\d*)?|(?P<pitch>(?:\^{1,2}|_{1,2}|=)?[A-Ga-g][,\']*)(?P<nd>\d*)(?P<nf>/\d*)?(?P<tie>-)?')
MIN_PHRASE, MAX_PHRASE = 3, 16


@dataclass
class Ev:
    pitch: str | None  # None = 쉼표
    dur: float


def _dur(d: str, f: str | None) -> float:
    n = float(d) if d else 1.0
    if f:
        den = f[1:]
        n /= float(den) if den else 2.0
    return n


def _header(abc: str) -> tuple[float, int]:
    m = re.search(r"^M:\s*(\d+)/(\d+)", abc, re.M)
    l = re.search(r"^L:\s*1/(\d+)", abc, re.M)
    num, den = (int(m.group(1)), int(m.group(2))) if m else (4, 4)
    unit = int(l.group(1)) if l else 8
    return num * unit / den, unit  # 한 마디의 L 단위 수


def _parse_line(content: str) -> tuple[list[Ev], list[str]]:
    """'V: Vocal' 다음 줄 → 사건 목록(붙임줄로 이은 음은 하나) + 마디별 화음."""
    evs, chords = [], []
    tie_open = False
    for bar in content.strip().strip("|").split("|"):
        chord = ""
        for m in NOTE.finditer(bar):
            if m.group("chord"):
                chord = m.group("chord")
            elif m.group("rest"):
                evs.append(Ev(None, _dur(m.group("rd"), m.group("rf"))))
                tie_open = False
            elif m.group("pitch"):
                d = _dur(m.group("nd"), m.group("nf"))
                if tie_open and evs and evs[-1].pitch is not None:
                    evs[-1].dur += d
                else:
                    evs.append(Ev(m.group("pitch"), d))
                tie_open = bool(m.group("tie"))
        chords.append(chord)
    return evs, chords


def _fmt(d: float) -> str:
    if abs(d - round(d)) < 1e-6:
        d = int(round(d))
        return "" if d == 1 else str(d)
    # 반 단위 이하(드묾)
    for den in (2, 4, 8):
        if abs(d * den - round(d * den)) < 1e-6:
            return f"{int(round(d * den))}/{den}"
    return str(round(d, 3))


def _emit(evs: list[Ev], chords: list[str], bar_units: float) -> list[str]:
    """사건 → 마디 문자열 목록(마디를 넘는 음은 붙임줄)."""
    bars, cur, used = [], [], 0.0
    for e in evs:
        left = e.dur
        while left > 1e-6:
            room = bar_units - used
            take = min(left, room)
            if e.pitch is None:
                cur.append("z" + _fmt(take))
            else:
                cur.append(e.pitch + _fmt(take) + ("-" if left - take > 1e-6 else ""))
            used += take
            left -= take
            if bar_units - used < 1e-6:
                bars.append("".join(cur)); cur, used = [], 0.0
    if cur:
        cur.append("z" + _fmt(bar_units - used)) if bar_units - used > 1e-6 else None
        bars.append("".join(cur))
    return [(chords[i] if i < len(chords) else "") + b for i, b in enumerate(bars)]


def _sections(abc: str):
    """(구간 이름, [Vocal 내용 줄 번호]) 차례대로."""
    lines = abc.split("\n")
    out, name = [], None
    for i, l in enumerate(lines):
        if l.startswith("% "):
            name = l[2:].strip()
            out.append((name, []))
        elif l.strip() == "V: Vocal" and out and i + 1 < len(lines):
            out[-1][1].append(i + 1)
    return lines, out


def _split_phrases(evs: list[Ev]) -> list[list[int]]:
    """음 사건 번호들을 쉼표로 나눈 프레이즈. 너무 긴 건 가장 긴 음 뒤에서, 너무 짧은 건 다음과 붙인다."""
    groups, cur = [], []
    for i, e in enumerate(evs):
        if e.pitch is None:
            if cur:
                groups.append(cur); cur = []
        else:
            cur.append(i)
    if cur:
        groups.append(cur)
    # 너무 짧은 프레이즈(3음 미만)는 다음 프레이즈와 합친다
    merged = []
    for g in groups:
        if merged and len(merged[-1]) < MIN_PHRASE:
            merged[-1] = merged[-1] + g
        else:
            merged.append(g)
    if len(merged) > 1 and len(merged[-1]) < MIN_PHRASE:
        merged[-2] = merged[-2] + merged.pop()
    # 너무 긴 프레이즈(16음 넘게)는 가운데쯤 가장 긴 음 뒤에서 나눈다
    out = []
    for g in merged:
        while len(g) > MAX_PHRASE:
            mid = range(len(g) // 3, 2 * len(g) // 3)
            cut = max(mid, key=lambda k: evs[g[k]].dur) + 1
            out.append(g[:cut]); g = g[cut:]
        out.append(g)
    return out


def phrases(abc: str, plan: dict) -> dict:
    """부르는 구간마다 프레이즈별 음표 수로 plan["sections"][i]["syllables"] 를 바꾼다(가사 글자 수 목표)."""
    lines, secs = _sections(abc)
    sung = [s for s in plan.get("sections", []) if s.get("sung")]
    si = 0
    for name, rows in secs:
        if not rows:
            continue
        evs = []
        for r in rows:
            e, _ = _parse_line(lines[r])
            evs += e
        if not any(e.pitch for e in evs):
            continue
        # 같은 순서의 부르는 구간에 붙인다
        while si < len(sung) and sung[si]["name"] != name:
            si += 1
        if si >= len(sung):
            break
        sung[si]["syllables"] = [len(g) for g in _split_phrases(evs)]
        si += 1
    return plan


def hangul(line: str) -> int:
    return sum(1 for ch in line if "가" <= ch <= "힣")


def _word_ends(line: str) -> list[int]:
    """줄의 어절마다 마지막 글자 번호(한글 글자만 센다). '맘 흔들려요' → [0, 4]."""
    ends, n = [], 0
    for w in line.split():
        c = hangul(w)
        if c:
            n += c
            ends.append(n - 1)
    return ends


def _shape_words(notes: list[Ev], ends: list[int]) -> None:
    """낱말 경계를 음 길이에 맞춘다(2026-11-10 사용자: 멜로디와 가사가 따로 논다). 어절 끝 글자가 짧은 음인데
    바로 다음 어절 첫 글자가 두 배 넘게 긴 음이면 두 음의 길이를 바꾼다 — 한국어는 어절 끝을 늘이고 다음 어절을 짧게 시작해야
    '맘 흔들려요신 호등'처럼 들리지 않는다. 음 높이 차례와 줄 전체 길이는 그대로."""
    for e in ends[:-1]:
        if e + 1 >= len(notes):
            break
        a, b = notes[e], notes[e + 1]
        if a.pitch and b.pitch and b.dur >= 2 * a.dur:
            a.dur, b.dur = b.dur, a.dur


def _fit_phrase(evs: list[Ev], idx: list[int], want: int, ends: list[int] | None = None) -> None:
    """프레이즈의 음 사건(idx)을 want 개로: 많으면 가장 짧은 이웃 둘을 합치고, 적으면 가장 긴 음을 둘로 나눈다(같은 음높이)."""
    notes = [evs[i] for i in idx]
    want = max(1, want)
    while len(notes) > want:
        k = min(range(len(notes) - 1), key=lambda j: notes[j].dur + notes[j + 1].dur)
        notes[k] = Ev(notes[k].pitch, notes[k].dur + notes[k + 1].dur)
        del notes[k + 1]
    while len(notes) < want:
        k = max(range(len(notes)), key=lambda j: notes[j].dur)
        if notes[k].dur < 2 - 1e-6:  # 16분음표보다 잘게는 나누지 않는다 — 대신 끝 음에 붙여 부르게 둔다
            break
        half = notes[k].dur / 2
        h1 = round(half) if notes[k].dur >= 2 else half
        notes[k:k + 1] = [Ev(notes[k].pitch, h1), Ev(notes[k].pitch, notes[k].dur - h1)]
    if ends and len(notes) == want:
        _shape_words(notes, ends)
    # 원래 자리(첫 음 자리)에 새 음들을, 나머지 자리는 지운다
    first = idx[0]
    for i in reversed(idx):
        del evs[i]
    evs[first:first] = notes


def fit(abc: str, plan: dict, sections: dict) -> tuple[str, list]:
    """가사에 맞춰 부르는 구간의 음표 수를 줄마다 정확히 맞춘다. 반환: (새 악보, 줄별 [구간, 글자 수, 원래 음표 수])."""
    bar_units, _ = _header(abc)
    lines, secs = _sections(abc)
    report = []
    for name, rows in secs:
        lyr = sections.get(name) or sections.get(name.rstrip("2")) or []
        if not rows or not lyr:
            continue
        evs, chords = [], []
        for r in rows:
            e, c = _parse_line(lines[r])
            evs += e; chords += c
        if not any(e.pitch for e in evs):
            continue
        groups = _split_phrases(evs)
        # 뒤에서부터 맞춘다(앞 번호가 안 바뀌게)
        for j in reversed(range(len(groups))):
            if j < len(lyr):
                want = hangul(lyr[j])
                report.append([name, j, want, len(groups[j])])
                if want:
                    _fit_phrase(evs, groups[j], want, _word_ends(lyr[j]))
            else:  # 가사 줄이 모자라면 그 프레이즈는 쉰다(엔진이 다음 가사를 당겨 부르지 않게)
                for i in groups[j]:
                    evs[i] = Ev(None, evs[i].dur)
        bars = _emit(evs, chords, bar_units)
        # 원래처럼 4마디씩 'V: Vocal' 줄에 나눠 담는다
        per = len(_parse_line(lines[rows[0]])[1]) or 4
        for n, r in enumerate(rows):
            chunk = bars[n * per:(n + 1) * per]
            lines[r] = "|".join(chunk) + "|" if chunk else lines[r]
    return "\n".join(lines), list(reversed(report))
