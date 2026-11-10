"""세밀한 허밍 악보(2026-11-11 사용자: 음표가 너무 단순해서 퀄리티가 낮아 보인다 — 더 잘게).

SheetSage2 는 모델이 한 박을 4칸(16분음표)으로만 읽어 리드시트처럼 단순하게 뽑는다. 그래서 같은 허밍(전처리 뒤 소리)을
pYIN 으로 약 12ms 마다 음높이를 따라가 음을 나누고, SheetSage2 악보와 시간을 맞춘 뒤 32분음표 칸에 놓는다.

- 화면 악보·트랙 연주에만 쓴다. 노래 만들기(곡 구성·가사 글자 수)는 기존 SheetSage2 악보(track["abc"])를 그대로 쓴다
  — 음표가 잘게 늘면 가사 글자 수 목표가 흔들리기 때문.
- 실패하면(무음·너무 짧음 등) None → 서버는 SheetSage2 악보로 연주한다.
"""
from __future__ import annotations

import io
import re

import numpy as np
import soundfile as sf

import melody_notes

abc_events = melody_notes.abc_events

SR = 22050
HOP = 256                  # 약 11.6ms
GRID = 32                  # 32분음표 칸
MIN_NOTE_SEC = 0.06        # 이보다 짧은 음은 잡음으로 본다(꾸밈음은 60ms 넘게 남는다)
SPLIT_SEMITONE = 0.75      # 음높이가 이만큼 바뀌어 유지되면 새 음
SPLIT_HOLD = 3             # 그 상태가 몇 프레임 이어져야 하나(약 35ms)
GAP_SEC = 0.045            # 무성 구간이 이보다 길면 음이 끊긴다
REATTACK_DB = 6.0          # 같은 음이라도 소리가 다시 세지면(다시 부름) 새 음

NAMES = ["C", "^C", "D", "^D", "E", "F", "^F", "G", "^G", "A", "^A", "B"]


def _track_notes(wav: bytes) -> list[tuple[float, float, int]]:
    """전처리된 허밍 → [(시작초, 끝초, MIDI)]."""
    import librosa
    y, sr = sf.read(io.BytesIO(wav), dtype="float32", always_2d=True)
    y = librosa.resample(y.mean(axis=1), orig_sr=sr, target_sr=SR)
    if len(y) < SR:
        return []
    f0, voiced, _ = librosa.pyin(y, fmin=65.0, fmax=1050.0, sr=SR, frame_length=2048, hop_length=HOP)
    rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=HOP)[0]
    db = 20 * np.log10(np.maximum(rms, 1e-6))
    n = min(len(f0), len(db))
    midi = np.where(voiced[:n] & np.isfinite(f0[:n]), librosa.hz_to_midi(np.nan_to_num(f0[:n], nan=1.0)), np.nan)
    dt = HOP / SR
    notes, cur, gap, drift = [], None, 0, 0
    for i in range(n):
        m = midi[i]
        if np.isnan(m) or db[i] < -55:
            gap += 1
            if cur and gap * dt > GAP_SEC:
                notes.append(cur); cur = None
            continue
        gap = 0
        if cur is None:
            cur = {"s": i, "e": i + 1, "p": [m], "db": db[i]}
            continue
        center = float(np.median(cur["p"][-8:]))
        if abs(m - center) >= SPLIT_SEMITONE:
            drift += 1
        else:
            drift = 0
        reattack = db[i] - min(db[max(cur["s"], i - 4):i]) > REATTACK_DB and i - cur["s"] > 4
        if drift >= SPLIT_HOLD or reattack:
            cut = i - (drift - 1 if drift >= SPLIT_HOLD else 0)
            cur["e"] = cut
            cur["p"] = cur["p"][:max(1, cut - cur["s"])]
            notes.append(cur)
            cur = {"s": cut, "e": i + 1, "p": list(midi[cut:i + 1]), "db": db[i]}
            drift = 0
        else:
            cur["e"] = i + 1
            cur["p"].append(m)
    if cur:
        notes.append(cur)
    out = []
    for c in notes:
        s, e = c["s"] * dt, c["e"] * dt
        p = [x for x in c["p"] if not np.isnan(x)]
        if e - s >= MIN_NOTE_SEC and p:
            out.append((s, e, int(round(float(np.median(p))))))
    # 같은 음이 아주 짧게 끊긴 것은 잇는다
    merged = []
    for s, e, p in out:
        if merged and merged[-1][2] == p and s - merged[-1][1] < 0.03:
            merged[-1] = (merged[-1][0], e, p)
        else:
            merged.append((s, e, p))
    return merged


def _coarse(abc: str):
    """SheetSage2 악보 → (음표 [(시작초, 끝초, MIDI)], 템포, 박자, 조성, 머리말 줄)."""
    _, parsed = abc_events.best_voice(abc_events.parse_by_voice(abc))
    if parsed is None or not parsed.notes:
        return None
    whole = 4 * 60 / parsed.tempo
    notes = [(n.start * whole, (n.start + n.dur) * whole, n.midi) for n in parsed.notes]
    return notes, parsed.tempo, parsed.meter, parsed.key


def _align(fine, coarse) -> tuple[float, int]:
    """SheetSage2 악보의 0초가 허밍 소리의 몇 초인지 — 두 악보의 음이름이 가장 많이 겹치는 어긋남(±6초, 10ms)과
    옥타브 차이(반음, 12의 배수). SheetSage2 는 높은음자리표 음역으로 옥타브를 올려 적어서 음높이를 그대로 비교하면 늘 어긋났다."""
    step = 0.01
    end = max(e for _, e, _ in fine) + 3
    grid = np.arange(0, end, step)

    def roll(notes, shift=0.0):
        r = np.full(len(grid), -1)
        for s, e, p in notes:
            a, b = int((s + shift) / step), int((e + shift) / step)
            r[max(a, 0):max(min(b, len(grid)), 0)] = p
        return r

    f = roll(fine)
    base = roll(coarse)  # 한 번만 그리고 칸을 밀어 비교한다(어긋남마다 다시 그리면 수 초 걸렸다)

    def shifted(k):
        c = np.full(len(grid), -1)
        if k >= 0:
            c[k:] = base[:len(grid) - k]
        else:
            c[:k] = base[-k:]
        return c

    best, best_shift, best_c = -1, 0.0, None
    for k in range(-600, 601, 2):
        c = shifted(k)
        both = (c >= 0) & (f >= 0)
        score = int(np.sum(both & np.isin((c - f) % 12, (0, 1, 11))))
        if score > best:
            best, best_shift, best_c = score, k * step, c
    both = (best_c >= 0) & (f >= 0)
    octave = int(round(float(np.median(best_c[both] - f[both])) / 12)) * 12 if both.any() else 0
    return best_shift, octave


def _fix_octaves(fine, coarse):
    """pYIN 은 약한 소리에서 가끔 한 옥타브 아래를 잡는다. 같은 때 SheetSage2 음이 있으면 그 음에 가장 가까운 옥타브로,
    없으면 앞 음과 6반음 안쪽이 되는 옥타브로 옮긴다(음이름은 그대로)."""
    out = []
    for s, e, p in fine:
        mid = (s + e) / 2
        ref = next((cp for cs, ce, cp in coarse if cs <= mid < ce), None)
        if ref is None and out:
            ref = out[-1][2] if abs(p - out[-1][2]) > 7 else None
        if ref is not None:
            p = min((p + 12 * k for k in (-2, -1, 0, 1, 2)), key=lambda x: abs(x - ref))
        out.append((s, e, p))
    return out


def _abc_note(m: int) -> str:
    """MIDI → ABC 음이름(임시표는 늘 적는다 — 같은 마디 임시표가 이어지는 규칙에 흔들리지 않게)."""
    name = NAMES[m % 12]
    acc, letter = (name[0], name[1]) if name.startswith("^") else ("=", name)
    octave = m // 12 - 1  # MIDI 60 = C4
    if octave >= 5:
        s = letter.lower() + "'" * (octave - 5)
    else:
        s = letter + "," * (4 - octave)
    return acc + s


def _dur(units: int) -> str:
    return "" if units == 1 else str(units)


def fine_abc(prep_wav: bytes, coarse_abc: str) -> tuple[str | None, str]:
    """반환: (세밀한 ABC 또는 None, 설명 한 줄)."""
    co = _coarse(coarse_abc)
    if co is None:
        return None, "기준 악보 없음"
    coarse_notes, tempo, meter, key = co
    fine = _track_notes(prep_wav)
    if len(fine) < 3:
        return None, f"음을 거의 못 찾음({len(fine)}개)"
    shift, octave = _align(fine, coarse_notes)   # coarse 0초 = 소리 shift 초 앞(+)/뒤(-)
    fine = _fix_octaves([(s, e, p + octave) for s, e, p in fine], [(a - shift, b - shift, p) for a, b, p in coarse_notes])
    origin = -shift
    unit = 4 * 60 / tempo / GRID          # 32분음표 길이(초)
    per_bar = int(round(GRID * meter[0] / meter[1]))
    q = []
    for s, e, p in fine:
        a = int(round((s - origin) / unit))
        b = max(a + 1, int(round((e - origin) / unit)))
        q.append([a, b, p])
    # 맨 앞이 0 보다 앞이면 마디 단위로 당겨 맞춘다
    lead = min(a for a, _, _ in q)
    if lead < 0:
        bars = -(-(-lead) // per_bar)
        for n in q:
            n[0] += bars * per_bar; n[1] += bars * per_bar
    q.sort()
    for x, y in zip(q, q[1:]):           # 겹치면 앞 음을 자른다
        if x[1] > y[0]:
            x[1] = max(x[0] + 1, y[0])
    q = [n for n in q if n[1] > n[0]]
    # 마디를 넘는 음은 붙임줄(-)로 나눈다
    tokens, pos = [], 0
    for a, b, p in q:
        while pos < a:                   # 쉼표
            step = min(a - pos, per_bar - pos % per_bar)
            tokens.append(f"z{_dur(step)}"); pos += step
            if pos % per_bar == 0:
                tokens.append("|")
        while pos < b:
            step = min(b - pos, per_bar - pos % per_bar)
            tie = "-" if pos + step < b else ""
            tokens.append(f"{_abc_note(p)}{_dur(step)}{tie}"); pos += step
            if pos % per_bar == 0:
                tokens.append("|")
    if pos % per_bar:
        tokens.append(f"z{_dur(per_bar - pos % per_bar)}|")
    head = [l for l in coarse_abc.splitlines() if re.match(r"^(X|T|M|Q):", l)]
    body = "".join(tokens)
    abc = "\n".join(head + ["L:1/32", 'V: Ins clef=treble name="Ins Melody" snm="Inst."', f"K:{key}", "V: Ins", body]) + "\n"
    info = f"세밀 악보 {len(q)}음(SheetSage2 {len(coarse_notes)}음) · 32분음표 칸 · 맞춤 {shift:+.2f}초 · 옥타브 {octave:+d}"
    return abc, info
