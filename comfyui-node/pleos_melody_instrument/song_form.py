"""흥얼거린 한 부분 → 약 2분짜리 곡 멜로디(전주·절·후렴·브리지·후주) + 마디마다 화음.

결과 ABC 는 YuE2 full 모드(화음 표기 ABC) 모양을 따른다: 화음은 Vocal 성부에 "D" 처럼 마디 앞에 적고,
노래하는 구간은 Vocal 에 멜로디, 연주 구간(전주·후주)은 Ins 에 멜로디를 둔다. 구간마다 "% verse" 같은 주석.
모델 없이 음계 규칙으로 만든다(결정론적). 계획(JSON)에는 구간별 마디·가사 줄마다 음 개수(음절 목표)가 들어간다.
"""

from __future__ import annotations

import json
import math
import re
import statistics

from .abc_events import Note, _key_accidentals, best_voice, parse_by_voice
from .arrange import MAJOR, MINOR, _key, choose_chords
from .melody_variation import FULL_CADENCE, HALF_CADENCE, _cadence, _degree, _pitch, _spell, _step, tidy

PHRASE_BARS = 4
LINE_BARS = 2
# 곡 구성 후보(길이 순). 목표 길이에 가장 가까운 것을 고른다 — 빠른 곡은 길게, 느린 곡은 짧게.
FORMS = [
    ["intro", "verse", "chorus", "outro"],
    ["intro", "verse", "chorus", "bridge", "chorus", "outro"],
    ["intro", "verse", "chorus", "verse2", "chorus", "outro"],
    ["intro", "verse", "chorus", "verse2", "chorus", "bridge", "chorus", "outro"],
    ["intro", "verse", "chorus", "verse2", "chorus", "bridge", "chorus", "chorus", "outro"],
    ["intro", "verse", "chorus", "verse2", "chorus", "bridge", "verse", "chorus", "chorus", "outro"],
]


def _shift(notes, beats):
    return [Note(n.start + beats, n.dur, n.midi) for n in notes]


def _window(notes, lo, hi):
    """[lo, hi) 박 사이의 음을 0박 기준으로 잘라 온다."""
    return [Note(n.start - lo, min(n.dur, hi - n.start), n.midi) for n in notes if lo <= n.start < hi]


def _fill(notes, bars, bar):
    """통째로 빈 마디는 앞 마디를 한 번 더 부른다."""
    notes = list(notes)
    for b in range(1, bars):
        lo, hi = b * bar, (b + 1) * bar
        if not any(n.start < hi and n.start + n.dur > lo for n in notes):
            notes += [Note(n.start + bar, min(n.dur, hi - n.start - bar), n.midi) for n in notes if lo - bar <= n.start < lo]
            notes.sort(key=lambda n: n.start)
    return notes


def _phrases(melody, bar):
    """허밍을 4마디 프레이즈 2개(A, A2)로. 2마디 이하면 두 번 불러 4마디."""
    used = math.ceil((melody[-1].start + melody[-1].dur) / bar - 1e-6)
    if used <= 2:
        a = _window(melody, 0, 2 * bar)
        a = _fill(a + _shift(a, 2 * bar), PHRASE_BARS, bar)
        return a, a
    a = _fill(_window(melody, 0, PHRASE_BARS * bar), PHRASE_BARS, bar)
    rest = _window(melody, PHRASE_BARS * bar, 2 * PHRASE_BARS * bar)
    return a, (_fill(rest, PHRASE_BARS, bar) if rest and used > 5 else a)


def _close(notes, bars, bar, root, scale, degrees):
    return _cadence([Note(n.start, n.dur, n.midi) for n in notes], bars * bar, bar / 2, bar, root, scale, degrees)


def _chorus(a, bar, root, scale):
    """후렴: 같은 리듬을 음계 세 칸(3도) 위에서 — 절보다 높게 들리되 가사 음절 수는 절과 비슷하게.
    (처음엔 앞 2마디를 두 배 길이로 늘렸더니 줄마다 4음뿐이라 가사가 '비가 내린다'만 반복됐다, 2026-11-05)"""
    return [Note(n.start, n.dur, _step(n.midi, 2, root, scale)) for n in a]


def _bridge(a, root, scale):
    """브리지: 첫 음을 축으로 위아래를 뒤집어 분위기를 바꾼다."""
    axis = _degree(a[0].midi, root, scale)[0]
    return [Note(n.start, n.dur, _pitch(2 * axis - _degree(n.midi, root, scale)[0] + 2, 0, root, scale)) for n in a]


def _sections(a, a2, bar, root, scale):
    """구간 이름 → (마디 수, 노래 여부, 음표). 노래 구간은 4마디 둘(반마침 → 온마침)."""
    c = _chorus(a, bar, root, scale)
    verse = _close(a, 4, bar, root, scale, HALF_CADENCE) + _shift(_close(a2, 4, bar, root, scale, FULL_CADENCE), 4 * bar)
    chorus = _close(c, 4, bar, root, scale, HALF_CADENCE) + _shift(_close(c, 4, bar, root, scale, FULL_CADENCE), 4 * bar)
    bridge = _close(_bridge(a, root, scale), 4, bar, root, scale, HALF_CADENCE)
    intro = _close(a, 4, bar, root, scale, HALF_CADENCE)
    outro = _close(_fill(_window(c, 0, 2 * bar), 4, bar), 4, bar, root, scale, FULL_CADENCE)
    return {"intro": (4, False, intro), "verse": (8, True, verse), "verse2": (8, True, verse),
            "chorus": (8, True, chorus), "bridge": (4, True, bridge), "outro": (4, False, outro)}


def _fit_form(seconds_per_bar, parts, target):
    return min(FORMS, key=lambda f: abs(sum(parts[s][0] for s in f) * seconds_per_bar - target))


def _bar_tokens(notes, bars, unit_beats, bar_units, keyacc, sharp):
    """음표 → 마디별 ABC 토큰 문자열(마디선 없이). 마디를 넘는 음은 붙임줄로 잇는다."""
    events, t = [], 0
    for n in sorted(notes, key=lambda n: n.start):
        s = max(t, round(n.start / unit_beats))
        e = max(s + 1, round((n.start + n.dur) / unit_beats))
        e = min(e, bars * bar_units)
        if s >= bars * bar_units:
            break
        if s > t:
            events.append((t, s, None))
        events.append((s, e, n.midi))
        t = e
    if t < bars * bar_units:
        events.append((t, bars * bar_units, None))
    out = [[] for _ in range(bars)]
    accs = [{} for _ in range(bars)]
    for s, e, midi in events:
        while s < e:
            b = s // bar_units
            cut = min(e, (b + 1) * bar_units)
            length = "" if cut - s == 1 else str(cut - s)
            out[b].append(f"z{length}" if midi is None else _spell(midi, keyacc, accs[b], sharp) + length + ("-" if cut < e else ""))
            s = cut
    return ["".join(b) for b in out]


def _chord_name(name):
    return name.replace("°", "dim")


def song_form(abc: str, target_seconds: float = 120.0, sung: bool = True) -> tuple[str, str]:
    """반환: (곡 ABC, 계획 JSON)."""
    _, parsed = best_voice(parse_by_voice(abc))
    if parsed is None:
        raise RuntimeError("악보에서 멜로디 음표를 찾지 못했습니다. 더 길게, 크게 허밍해보세요.")
    per_whole = float(parsed.meter[1] or 4)
    bar = float(parsed.meter[0])
    grid = 0.5 if parsed.meter[1] == 4 else 1.0
    root, minor = _key(parsed.key)
    scale = MINOR if minor else MAJOR
    melody = tidy([Note(n.start * per_whole, n.dur * per_whole, n.midi) for n in parsed.notes], root, scale, grid)
    a, a2 = _phrases(melody, bar)
    parts = _sections(a, a2, bar, root, scale)
    seconds_per_bar = bar * 60 / parsed.tempo * (4 / per_whole)
    form = _fit_form(seconds_per_bar, parts, target_seconds)

    whole, start, layout = [], 0.0, []
    for name in form:
        bars, is_sung, notes = parts[name]
        whole += _shift(notes, start)
        layout.append((name, bars, is_sung and sung, start))
        start += bars * bar
    total_bars = int(round(start / bar))
    chords = [_chord_name(c[2]) for c in choose_chords(whole, bar, parsed.key)]
    chords += [chords[-1]] * (total_bars - len(chords))

    lines = abc.splitlines()
    k = next(i for i, l in enumerate(lines) if l.startswith("K:"))
    header = [l for l in lines[:k] if not l.startswith("V:")]
    header += ['V: Vocal clef=treble name="Vocal Melody" snm="Vocal"', 'V: Ins clef=treble name="Ins Melody" snm="Inst."', lines[k]]
    key = re.match(r"K:\s*(\S+)", lines[k]).group(1)
    keyacc = _key_accidentals(key)
    sharp = "b" not in keyacc.values()
    unit_beats = parsed.unit * per_whole
    bar_units = round(bar / unit_beats)

    body, plan = [], []
    for name, bars, is_sung, at in layout:
        first = int(round(at / bar))
        notes = _window(whole, at, at + bars * bar)
        tokens = _bar_tokens(notes, bars, unit_beats, bar_units, keyacc, sharp)
        rest = f"z{bar_units}"
        body.append(f"% {name.rstrip('2')}")
        for g in range(0, bars, PHRASE_BARS):
            idx = range(g, min(bars, g + PHRASE_BARS))
            mel = [tokens[i] for i in idx]
            sym = [f'"{chords[first + i]}"' for i in idx]
            vocal = [s + m for s, m in zip(sym, mel)] if is_sung else [s + rest for s in sym]
            body += ["V: Vocal", "|".join(vocal) + "|", "V: Ins", ("|".join(mel) + "|") if not is_sung else f"Z{len(mel)}|"]
        onsets = [sum(1 for n in notes if l * LINE_BARS * bar <= n.start < (l + 1) * LINE_BARS * bar) for l in range(bars // LINE_BARS)]
        plan.append({"name": name, "bars": bars, "sung": is_sung, "start_sec": round(first * seconds_per_bar, 2),
                     "syllables": onsets if is_sung else []})
    info = {"tempo": parsed.tempo, "key": key, "bars": total_bars, "seconds": round(total_bars * seconds_per_bar, 1),
            "chords": chords, "sections": plan}
    return "\n".join(header + body) + "\n", json.dumps(info, ensure_ascii=False)
