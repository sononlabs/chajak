"""허밍 악보 하나로 여러 변주 멜로디를 만든다.

모두 음계 규칙으로 바꾸는 결정론적 변환이라 바로 끝나고, 마디 수와 박자는 거의 그대로라
원래 멜로디에 겹치거나(화음 멜로디) 뒤에 이어 붙이기(한 단계 위로)에 바로 쓸 수 있다.
"""

from __future__ import annotations

import math
import re
import statistics

from .abc_events import LETTER_SEMITONE, Note, _key_accidentals, best_voice, parse_by_voice
from .arrange import MAJOR, MINOR, _key

# 이름 → 설명. 노드 드롭다운과 서버 API 가 같은 이름을 쓴다.
VARIATIONS = {
    "화음 멜로디": "마디 화음에 맞춰 내 멜로디 위에서 함께 부르는 둘째 목소리 (겹치기용)",
    "아래 화음 멜로디": "마디 화음에 맞춰 내 멜로디 아래에서 받쳐 주는 둘째 목소리 (겹치기용)",
    "멜로디 완성하기": "음계·박자를 맞추고 4마디마다 프레이즈를 닫아 한 곡처럼. 짧으면 두 번 부르고 으뜸음으로 끝냄",
}
# 완성하기: 프레이즈 끝음 — 중간은 레·솔(반마침), 마지막은 도(온마침)
HALF_CADENCE = (1, 4)
FULL_CADENCE = (0,)
MAX_BARS = 16


def _degree(midi: int, root: int, scale) -> tuple[int, int]:
    """(음계 칸 번호, 반음 어긋남). 칸 번호 = 옥타브*7 + 도수. 음계 밖 음은 바로 아래 칸 + 어긋남."""
    octave, pc = divmod(midi - root, 12)
    d = max(i for i, s in enumerate(scale) if s <= pc)
    return octave * 7 + d, pc - scale[d]


def _pitch(index: int, off: int, root: int, scale) -> int:
    octave, d = divmod(index, 7)
    return root + 12 * octave + scale[d] + off


def _step(midi: int, steps: int, root: int, scale) -> int:
    index, off = _degree(midi, root, scale)
    return _pitch(index + steps, off, root, scale)


# 화음 줄(2026-11-12): 멜로디와의 간격(반음) → 감점. 3도·6도가 가장 자연스럽고, 4·5도는 비어 들리며, 증4도·2도 이하는 피한다
HARMONY_INTERVAL = {3: 0.0, 4: 0.0, 8: 0.25, 9: 0.25, 5: 0.7, 7: 0.7, 6: 2.5}
HARMONY_NON_CHORD = (1.6, 0.25)  # 마디 화음 밖 음: (긴 음·센박, 짧은 여린박)
HARMONY_LEAP = 0.15  # 앞 화음 음에서 반음 하나 움직일 때마다(86곡 시험: 0.06→0.15 로 움직임 3.4→2.8반음, 화음 맞음 95% 그대로)
HARMONY_PARALLEL = 0.8  # 완전5도가 연달아(병행 5도)


def _harmony(notes, root, scale, minor=False, key_field="C", bar_beats=4.0, tempo=100.0, below=False):
    """마디 화음에 맞춘 둘째 목소리. 음마다 음계 안에서 멜로디 위(below=True 면 아래) 3~9반음 후보를 놓고,
    간격의 자연스러움 + 그 마디 화음에 속하는지(길거나 센박인 음일수록 중요) + 앞 음에서 덜 움직이기로 고른다.
    화음은 자동 반주와 같은 choose_chords 라서 반주를 켜도 부딪치지 않는다. 멜로디가 음계 밖 음이면 3도 평행."""
    from .arrange import SLOW_TEMPO, choose_chords

    if not notes:
        return []
    per_bar = 2 if tempo < SLOW_TEMPO and bar_beats >= 4 else 1
    seg = bar_beats / per_bar
    chords = choose_chords(notes, seg, key_field)
    scale_pcs = {(root + s) % 12 for s in scale}
    sign = -1 if below else 1
    out, prev, prev_iv = [], None, None
    for n in notes:
        _, off = _degree(n.midi, root, scale)
        if off:
            h = _step(n.midi, 2 * sign, root, scale)
            out.append(Note(n.start, n.dur, h))
            prev, prev_iv = h, abs(h - n.midi)
            continue
        pcs = chords[min(len(chords) - 1, int(n.start // seg))][0]
        important = n.dur >= 1.0 or abs(n.start % seg) < 1e-6 or abs(n.start % seg - seg / 2) < 1e-6
        best = None
        for iv, cost in HARMONY_INTERVAL.items():
            h = n.midi + sign * iv
            if h % 12 not in scale_pcs:
                continue
            score = cost + (0.0 if h % 12 in pcs else HARMONY_NON_CHORD[0 if important else 1])
            if prev is not None:
                score += HARMONY_LEAP * abs(h - prev)
                if iv == 7 and prev_iv == 7 and h != prev:
                    score += HARMONY_PARALLEL
            if best is None or score < best[0]:
                best = (score, h, iv)
        if best is None:
            h, iv = _step(n.midi, 2 * sign, root, scale), None
        else:
            _, h, iv = best
        out.append(Note(n.start, n.dur, h))
        prev, prev_iv = h, iv
    return out


def _nearest(midi: int, root: int, scale, degrees) -> int:
    cands = [root + 12 * o + scale[d] for o in range(midi // 12 - 2, midi // 12 + 2) for d in degrees]
    return min(cands, key=lambda c: (abs(c - midi), c > midi))


def _cadence(notes, end: float, length: float, bar: float, root, scale, degrees):
    """프레이즈 끝을 마침음 하나로 닫는다. 마침음은 바로 앞 음에서 가장 가까운 degrees 음이고,
    앞 음이 일찍 끝났으면 쉼 없이 이어 길게(최대 한 마디) 끈다."""
    cut = end - length
    body = [n for n in notes if n.start < cut]
    for n in body:
        n.dur = min(n.dur, cut - n.start)
    prev = body[-1].midi if body else notes[-1].midi
    start = max(body[-1].start + body[-1].dur, end - bar) if body else cut
    return body + [Note(start, end - start, _nearest(prev, root, scale, degrees))]


def tidy(notes, root, scale, grid: float):
    """허밍 음표 정리: 짧은 음은 앞 음에 붙이고, 옥타브 튐을 접고, 음계에 맞추고, grid 칸에 맞춘다(첫 음이 0박)."""
    kept = []
    for n in notes:
        if kept and n.dur < grid / 2 and n.start - (kept[-1].start + kept[-1].dur) < grid:
            kept[-1].dur = n.start + n.dur - kept[-1].start
            continue
        kept.append(Note(n.start, n.dur, n.midi))
    center = statistics.median(n.midi for n in kept)
    for n in kept:
        n.midi += 12 * round((center - n.midi) / 12) if abs(n.midi - center) > 9 else 0
        n.midi = _nearest(n.midi, root, scale, range(7))

    lead = kept[0].start
    snapped = []
    for n in kept:
        s = round((n.start - lead) / grid) * grid
        if snapped and s <= snapped[-1].start:
            continue
        snapped.append(Note(s, max(grid, round(n.dur / grid) * grid), n.midi))
    for a, b in zip(snapped, snapped[1:]):
        a.dur = min(a.dur, b.start - a.start)
    merged = []
    for n in snapped:
        p = merged[-1] if merged else None
        if p and p.midi == n.midi and abs(p.start + p.dur - n.start) < 1e-6 and p.dur + n.dur <= 2:
            p.dur += n.dur
        else:
            merged.append(n)
    # 한 박보다 짧은 틈은 앞 음을 늘려 잇는다
    for a, b in zip(merged, merged[1:]):
        if b.start - (a.start + a.dur) < 1:
            a.dur = b.start - a.start
    return merged


def _finish(notes, root, scale, bar: float, grid: float):
    """허밍 음표를 '완성된 멜로디'로: tidy → 4마디 프레이즈와 마침."""
    merged = tidy(notes, root, scale, grid)
    used = math.ceil((merged[-1].start + merged[-1].dur) / bar - 1e-6)
    per = 2 if used <= 2 else 4
    total = per if used <= 4 else min(MAX_BARS, math.ceil(used / 4) * 4)
    merged = [Note(n.start, min(n.dur, total * bar - n.start), n.midi) for n in merged if n.start < total * bar]
    # 통째로 빈 마디는 앞 마디(프레이즈 첫 마디면 앞 프레이즈의 첫 마디) 동기를 한 번 더 부른다. 프레이즈 끝 마디는 마침음이 채운다
    for b in range(1, total):
        lo, hi = b * bar, (b + 1) * bar
        if b % per == per - 1 or any(n.start < hi and n.start + n.dur > lo for n in merged):
            continue
        src = (b - per if b % per == 0 else b - 1) * bar
        merged += [Note(n.start - src + lo, min(n.dur, src + bar - n.start), n.midi) for n in merged if src <= n.start < src + bar]
        merged.sort(key=lambda n: n.start)

    final = bar / 2
    if used <= 4:
        # 짧으면 같은 프레이즈를 두 번: 처음은 반마침, 두 번째는 온마침
        phrase = per * bar
        first = _cadence(merged, phrase, final, bar, root, scale, HALF_CADENCE)
        second = _cadence([Note(n.start + phrase, n.dur, n.midi) for n in merged], 2 * phrase, final, bar, root, scale, FULL_CADENCE)
        return first + second
    out = []
    for i in range(total // 4):
        lo, hi = i * 4 * bar, (i + 1) * 4 * bar
        ph = [Note(n.start, min(n.dur, hi - n.start), n.midi) for n in merged if lo <= n.start < hi]
        if not ph:
            ph = [Note(lo, bar, out[-1].midi)]
        out += _cadence(ph, hi, final, bar, root, scale, FULL_CADENCE if i == total // 4 - 1 or i % 2 else HALF_CADENCE)
    return out


def _spell(midi: int, keyacc: dict, bar_acc: dict, sharp: bool) -> str:
    """조표·같은 마디 임시표를 따져 필요한 때만 ^ _ = 를 붙인다(abc_events 파서와 같은 규칙)."""
    default = lambda letter: {"#": 1, "b": -1}.get(keyacc.get(letter), 0)
    cands = [(l, a) for l, s in LETTER_SEMITONE.items() for a in (-1, 0, 1) if (s + a - midi) % 12 == 0]
    letter, acc = min(cands, key=lambda c: (c[1] != default(c[0]), c[1] != 0, c[1] != (1 if sharp else -1)))
    octave = (midi - acc - LETTER_SEMITONE[letter] - 60) // 12
    prefix = ""
    if bar_acc.get((letter, octave), default(letter)) != acc:
        prefix = {1: "^", -1: "_", 0: "="}[acc]
        bar_acc[(letter, octave)] = acc
    name = letter.lower() + "'" * (octave - 1) if octave >= 1 else letter + "," * -octave
    return prefix + name


def write_abc(notes, source_abc: str, key: str, unit_beats: float, bar_units: int) -> str:
    """비트 단위 음표 → 원본 머리글을 쓰는 ABC(멜로디는 Ins, Vocal 은 쉼표). 4마디씩 한 줄."""
    lines = source_abc.splitlines()
    k = next(i for i, l in enumerate(lines) if l.startswith("K:"))
    header = lines[:k] + [f"K:{key}"]
    keyacc = _key_accidentals(key)
    sharp = "b" not in keyacc.values()

    events, t = [], 0
    for n in sorted(notes, key=lambda n: n.start):
        s = max(t, round(n.start / unit_beats))
        e = max(s + 1, round((n.start + n.dur) / unit_beats))
        if s > t:
            events.append((t, s, None))
        events.append((s, e, n.midi))
        t = e
    total = math.ceil(t / bar_units) * bar_units
    if total > t:
        events.append((t, total, None))

    bars = [[] for _ in range(total // bar_units)]
    bar_accs = [{} for _ in bars]
    for s, e, midi in events:
        while s < e:
            b = s // bar_units
            cut = min(e, (b + 1) * bar_units)
            length = "" if cut - s == 1 else str(cut - s)
            if midi is None:
                bars[b].append(f"z{length}")
            else:
                bars[b].append(_spell(midi, keyacc, bar_accs[b], sharp) + length + ("-" if cut < e else ""))
            s = cut

    body = []
    for i in range(0, len(bars), 4):
        group = bars[i:i + 4]
        body += ["V: Vocal", f"Z{len(group)}|", "V: Ins", "|".join("".join(b) for b in group) + "|"]
    return "\n".join(header + body) + "\n"


def vary(abc: str, name: str) -> tuple[str, str]:
    """반환: (변주 ABC, 요약)."""
    _, parsed = best_voice(parse_by_voice(abc))
    if parsed is None:
        raise RuntimeError("악보에서 멜로디 음표를 찾지 못했습니다. 더 길게, 크게 허밍해보세요.")
    per_whole = float(parsed.meter[1] or 4)
    notes = [Note(n.start * per_whole, n.dur * per_whole, n.midi) for n in parsed.notes]
    root, minor = _key(parsed.key)
    scale = MINOR if minor else MAJOR
    key = re.match(r"\s*(\S+)", parsed.key or "C").group(1)

    if name in ("화음 멜로디", "아래 화음 멜로디"):
        out = _harmony(notes, root, scale, minor, parsed.key, float(parsed.meter[0]), float(parsed.tempo or 100),
                       below=name == "아래 화음 멜로디")
    elif name == "멜로디 완성하기":
        out = _finish(notes, root, scale, parsed.meter[0], 0.5 if parsed.meter[1] == 4 else 1.0)
    else:
        raise ValueError(f"알 수 없는 변주: {name}")

    unit_beats = parsed.unit * per_whole
    bar_units = round(parsed.meter[0] / unit_beats)
    changed = sum(1 for a, b in zip(notes, out) if a.midi != b.midi or abs(a.start - b.start) > 1e-6)
    summary = (f"변주 {name} · 음표 {len(notes)} → {len(out)}개 · 바뀐 음 {changed + abs(len(out) - len(notes))}개 · "
               f"조성 {parsed.key} → {key}")
    return write_abc(out, abc, key, unit_beats, bar_units), summary
