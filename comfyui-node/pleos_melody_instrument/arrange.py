"""허밍 멜로디는 그대로 두고, 마디마다 어울리는 화음·베이스를 붙여 함께 연주한다."""

from __future__ import annotations

import math
from collections import defaultdict

import numpy as np

from .abc_events import LETTER_SEMITONE, Note, best_voice, parse_by_voice
from .soundfont_render import apply_reverb, render_notes

PC_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
MAJOR = [0, 2, 4, 5, 7, 9, 11]
MINOR = [0, 2, 3, 5, 7, 8, 10]
# 음계 도수별 선호도 (I/i 가 가장 자연스럽다)
MAJOR_PREF = [1.0, 0.7, 0.6, 0.9, 0.9, 0.8, 0.3]
MINOR_PREF = [1.0, 0.3, 0.8, 0.9, 0.8, 0.85, 0.7]
# 자연스러운 진행 (도수 → 다음 도수). 장조 I-IV-V-vi 계열, 단조 i-iv-v-VI-VII-III 계열
MAJOR_MOVES = {0: {3, 4, 5, 1}, 1: {4, 6}, 2: {5, 3}, 3: {4, 0, 1}, 4: {0, 5}, 5: {3, 1, 4}, 6: {0}}
MINOR_MOVES = {0: {3, 5, 6, 4, 2}, 1: {4}, 2: {5, 3}, 3: {4, 0, 6}, 4: {0, 5}, 5: {6, 3, 2}, 6: {2, 0}}
GOOD_MOVE = 0.15
# 같은 화음이 이어질 때 감점: 2번째는 그대로, 3번째부터 점점 크게
REPEAT_PENALTY = [0.0, 0.0, 0.3, 0.6]
SLOW_TEMPO = 100


def _key(key_field: str):
    k = (key_field or "C").strip().split()[0]
    minor = k.endswith("m") and not k.endswith("maj")
    name = k[:-1] if minor else k
    root = LETTER_SEMITONE.get(name[:1].upper(), 0)
    if name[1:2] == "#":
        root += 1
    elif name[1:2] == "b":
        root -= 1
    return root % 12, minor


def _triads(root: int, minor: bool):
    scale = MINOR if minor else MAJOR
    pref = MINOR_PREF if minor else MAJOR_PREF
    out = []
    for d in range(7):
        pcs = [(root + scale[(d + k) % 7]) % 12 for k in (0, 2, 4)]
        third = (pcs[1] - pcs[0]) % 12
        fifth = (pcs[2] - pcs[0]) % 12
        suffix = "" if third == 4 else ("°" if fifth == 6 else "m")
        out.append((pcs, pref[d], PC_NAMES[pcs[0]] + suffix))
    return out


def _bar_weights(melody, bar_beats: float, n_bars: int):
    weights = [defaultdict(float) for _ in range(n_bars)]
    for n in melody:
        end = n.start + n.dur
        b = int(n.start // bar_beats)
        while b < n_bars and b * bar_beats < end:
            lo = max(n.start, b * bar_beats)
            hi = min(end, (b + 1) * bar_beats)
            pos = lo - b * bar_beats
            strong = 1.5 if pos in (0.0, bar_beats / 2) else 1.0
            weights[b][n.midi % 12] += (hi - lo) * strong
            b += 1
    return weights


def choose_chords(melody, seg_beats: float, key_field: str):
    """구간마다 화음 하나. 멜로디 적합도 + 진행 자연스러움 + 반복 감점을 곡 전체에서 최대화(비터비)."""
    root, minor = _key(key_field)
    triads = _triads(root, minor)
    moves = MINOR_MOVES if minor else MAJOR_MOVES
    n_segs = max(1, math.ceil(max(n.start + n.dur for n in melody) / seg_beats))
    weights = _bar_weights(melody, seg_beats, n_segs)

    def fit(s, i):
        w = weights[s]
        total = sum(w.values())
        pcs, pref, _ = triads[i]
        score = 0.25 * pref
        if total:
            score += (2 * sum(v for pc, v in w.items() if pc in pcs) - total) / total
        if s == n_segs - 1 and i == 0:
            score += 0.3
        if n_segs > 2 and s == n_segs - 2 and i in (3, 4):
            score += 0.1
        return score

    # 상태 = (화음, 연속 횟수 1..len(REPEAT_PENALTY))
    max_run = len(REPEAT_PENALTY)
    best = {(i, 1): (fit(0, i) + (0.2 if i == 0 else 0.0), [i]) for i in range(7)}
    for s in range(1, n_segs):
        nxt = {}
        for (i, run), (score, path) in best.items():
            for j in range(7):
                r = min(run + 1, max_run) if j == i else 1
                gain = fit(s, j) - REPEAT_PENALTY[r - 1] + (GOOD_MOVE if j in moves[i] else 0.0)
                cand = (score + gain, path + [j])
                if cand[0] > nxt.get((j, r), (-1e9,))[0]:
                    nxt[(j, r)] = cand
        best = nxt
    path = max(best.values(), key=lambda v: v[0])[1]
    return [triads[i] for i in path]


def _voicing(pcs, top: int, prev):
    """화음 음을 top 아래에 놓되, 앞 화음과 가장 가깝게 움직이는 자리바꿈을 고른다."""
    v = sorted(top - ((top - pc) % 12) for pc in pcs)
    options = [v]
    for _ in range(2):
        v = sorted(v[:-1] + [v[-1] - 12])
        options.append(v)
    if prev is None:
        return options[0]
    return min(options, key=lambda o: sum(abs(a - b) for a, b in zip(o, prev)))


def accompaniment(chords, seg_beats: float, melody_median: int):
    top = min(64, melody_median - 5)
    chord_notes, bass_notes = [], []
    prev = None
    for s, (pcs, _, _) in enumerate(chords):
        start = s * seg_beats
        prev = _voicing(pcs, top, prev)
        for midi in prev:
            chord_notes.append(Note(start=start, dur=seg_beats * 0.95, midi=midi))
        bass = 36 + (pcs[0] - 36) % 12
        half = seg_beats / 2
        # 한 마디에 화음 하나면 근음-5음, 반 마디마다 바뀌면 근음만
        second = (bass + 7 if bass + 7 <= 52 else bass - 5) if seg_beats >= 4 else bass
        bass_notes.append(Note(start=start, dur=half * 0.9, midi=bass))
        bass_notes.append(Note(start=start + half, dur=half * 0.9, midi=second))
    return chord_notes, bass_notes


def render_arrangement(abc: str, bank, melody_preset: int, chord_preset: int, bass_preset: int, reverb_mix: float):
    """반환: (오디오 (N,2) float32, 요약 문자열)."""
    _, parsed = best_voice(parse_by_voice(abc))
    if parsed is None:
        raise RuntimeError("악보에서 멜로디 음표를 찾지 못했습니다. 더 길게, 크게 허밍해보세요.")
    beats_per_whole = float(parsed.meter[1] or 4)
    melody = [Note(start=n.start * beats_per_whole, dur=n.dur * beats_per_whole, midi=n.midi) for n in parsed.notes]
    bar_beats = float(parsed.meter[0])
    # 느린 곡은 마디가 길어 한 화음이 오래 끌리므로 반 마디마다 바꿀 수 있게 한다
    per_bar = 2 if parsed.tempo < SLOW_TEMPO and bar_beats >= 4 else 1
    seg_beats = bar_beats / per_bar

    chords = choose_chords(melody, seg_beats, parsed.key)
    median = int(np.median([n.midi for n in melody]))
    chord_notes, bass_notes = accompaniment(chords, seg_beats, median)

    tracks = [
        render_notes(melody, bank, preset_index=melody_preset, tempo=parsed.tempo, gain_db=-4.0),
        render_notes(chord_notes, bank, preset_index=chord_preset, tempo=parsed.tempo, gain_db=-16.0, velocity=80),
        render_notes(bass_notes, bank, preset_index=bass_preset, tempo=parsed.tempo, gain_db=-11.0, velocity=90),
    ]
    mix = np.zeros((max(t.shape[0] for t in tracks), 2), dtype=np.float32)
    for t in tracks:
        mix[: t.shape[0]] += t
    mix = apply_reverb(mix, bank.samplerate, reverb_mix, 0.9)
    peak = float(np.abs(mix).max())
    if peak > 0:
        mix *= 0.89 / peak
    names = " | ".join(" ".join(c[2] for c in chords[i:i + per_bar]) for i in range(0, len(chords), per_bar))
    summary = (
        f"내 멜로디 {len(melody)}음 그대로 · 템포 {parsed.tempo:g} · 조성 {parsed.key} · "
        f"화음 {math.ceil(len(chords) / per_bar)}마디: {names}"
    )
    return mix, summary
