"""ABC notation 파서 → 멜로디 이벤트.

SheetSage2 가 출력하는 ABC 를 파싱해 (시작비트, 길이비트, MIDI음높이) 목록으로 바꾼다.
지원: 음표/쉼표, 올림표(^)·내림표(_)·강음(^^)·약음(~~), 옥타브(' ,), 길이(숫자, n/m, /),
      음결(,), 타인(-), 화음((...)), 음표부, 보이스(V:), K: 조성, M: 박자, L: 단위음길이, Q: 템포.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# 조성 기본 임시표는 5도권 위치로 계산한다 (C=0, G=+1 … F=-1). 단조는 나란한조(-3).
LETTER_FIFTHS = {"F": -1, "C": 0, "G": 1, "D": 2, "A": 3, "E": 4, "B": 5}
SHARP_ORDER = "FCGDAEB"
FLAT_ORDER = "BEADGCF"
LETTER_SEMITONE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


@dataclass
class Note:
    start: float          # 시작 (비트)
    dur: float            # 길이 (비트)
    midi: int             # MIDI 음높이
    tied_from_prev: bool = False


@dataclass
class ParsedABC:
    notes: list = field(default_factory=list)
    tempo: float = 120.0          # BPM
    unit: float = 0.25            # 단위음길이 (전체음 기준, L:1/16 -> 0.0625)
    meter: tuple = (4, 4)
    key: str = "C"
    voices: list = field(default_factory=list)
    voice_names: dict = field(default_factory=dict)


def _key_accidentals(key_field: str) -> dict:
    """예: 'D#m' → 올림표 6개(F C G D A E), 'Eb' → 내림표 3개(B E A). SheetSage2 는 D#m 같은 조성도 쓴다."""
    m = re.match(r"\s*([A-Ga-g])([#b]?)\s*(m(?:in)?(?![a-z]))?", key_field or "", re.IGNORECASE)
    if not m:
        return {}
    letter, acc, minor = m.group(1).upper(), m.group(2), m.group(3)
    fifths = LETTER_FIFTHS[letter] + {"#": 7, "b": -7, "": 0}[acc] - (3 if minor else 0)
    if fifths >= 0:
        return {l: "#" for l in SHARP_ORDER[:fifths]}
    return {l: "b" for l in FLAT_ORDER[:-fifths]}


def _read_duration(line: str, i: int, prev: float):
    """(길이 in whole-note 단위, 다음 인덱스) 를 반환. 길이가 없으면 None."""
    n = len(line)
    start = i
    num = ""
    while i < n and line[i].isdigit():
        num += line[i]
        i += 1
    if num and i < n and line[i] == "/":
        i += 1
        den = ""
        while i < n and line[i].isdigit():
            den += line[i]
            i += 1
        if den:
            return int(num) / int(den), i
        return None, start
    if num:
        return ("int", int(num)), i
    if i < n and line[i] == "/" and prev is not None:
        return prev / 2.0, i + 1
    return None, i


def _duration_to_whole(dur, unit: float, prev_whole: float):
    """ABC 길이 표기를 whole-note 기준 길이로 변환."""
    if dur is None:
        return unit, unit
    if isinstance(dur, tuple) and dur[0] == "int":
        # L: 필드가 있으면 숫자는 단위음길이의 배수
        return dur[1] * unit, dur[1] * unit
    return dur, dur


def _parse_music_line(line: str, out: list, state: dict, keyacc: dict):
    """한 줄의 음악 토큰을 파싱해 out 에 Note 를 추가."""
    unit = state["unit"]
    i = 0
    n = len(line)
    last_note = None          # 마지막 발행 음 (타인 대상)
    pending_tie = False       # 직전 음이 tying 상태
    prev_whole = unit         # '/' 단독 길이 계산용
    bar_acc = state.setdefault("bar_acc", {})

    def emit_dur(whole):
        nonlocal prev_whole
        prev_whole = whole
        return whole

    def add_whole(whole):
        state["beats"] += whole

    while i < n:
        c = line[i]
        if c in " \t":
            i += 1
            continue
        if c == '"':                       # 지시문
            j = line.find('"', i + 1)
            i = (j + 1) if j >= 0 else n
            continue
        if c == "|" or c == "[" or c == "]" or c == ":":   # 마디선 등
            if c == "|":
                bar_acc.clear()
            i += 1
            continue
        if c == "w" and i + 1 < n and line[i + 1] == ":":  # 인라인 필드
            i += 2
            while i < n and line[i] not in " \t]":
                i += 1
            continue
        if c in "Zz":                      # 쉼표 (z=음길이 지정, Z=마디 전체)
            i += 1
            dur, i = _read_duration(line, i, prev_whole)
            whole, _ = _duration_to_whole(dur, unit, prev_whole)
            if c == "Z" and dur is None:
                whole = state["measure_whole"]
            emit_dur(whole)
            add_whole(whole)
            pending_tie = False
            continue
        if c == "x":                       # 마디 쉼표
            i += 1
            dur, i = _read_duration(line, i, prev_whole)
            whole, _ = _duration_to_whole(dur, unit, prev_whole)
            if dur is None:
                whole = state["measure_whole"]
            emit_dur(whole)
            add_whole(whole)
            pending_tie = False
            continue
        if c == "(":                       # 화음
            i += 1
            chord: list = []
            while i < n and line[i] != ")":
                sub, i = _parse_single_note(line, i, unit, keyacc, prev_whole, bar_acc)
                if sub is not None:
                    chord.append(sub)
                else:
                    i += 1
            if i < n:
                i += 1
            if chord:
                longest = max(x[1] for x in chord)
                for midi, whole in chord:
                    _append_note(out, state, midi, whole, longest, pending_tie)
                    last_note = out[-1]
                add_whole(longest)
                emit_dur(longest)
                pending_tie = False
            continue
        if c in "^_=" or (c.isalpha() and c.upper() in "ABCDEFG"):
            res, i = _parse_single_note(line, i, unit, keyacc, prev_whole, bar_acc)
            if res is None:
                i += 1
                continue
            midi, whole = res
            _append_note(out, state, midi, whole, whole, pending_tie)
            last_note = out[-1]
            emit_dur(whole)
            add_whole(whole)
            pending_tie = False
            continue
        if c == "-":                       # 타인
            i += 1
            if last_note is not None:
                pending_tie = True
                state["tie_target"] = last_note
            continue
        i += 1


def _parse_single_note(line: str, i: int, unit: float, keyacc: dict, prev_whole: float, bar_acc: dict):
    """(midi, whole_note 길이) 또는 None. i 는 다음 인덱스로 갱신됨.

    ABC 임시표(^ _ =)는 음이름 앞에 오고, 같은 마디의 같은 음에 계속 적용된다(bar_acc).
    """
    start = i
    acc = None
    while i < len(line) and line[i] in "^_=":
        acc = 0 if line[i] == "=" else (acc or 0) + (1 if line[i] == "^" else -1)
        i += 1
    if i >= len(line) or line[i].upper() not in LETTER_SEMITONE:
        return None, start
    c = line[i]
    letter = c.upper()
    i += 1
    octave = 1 if c.islower() else 0
    while i < len(line) and line[i] in "',":
        octave += 1 if line[i] == "'" else -1
        i += 1
    dur, i = _read_duration(line, i, prev_whole)
    whole, _ = _duration_to_whole(dur, unit, prev_whole)

    if acc is not None:
        bar_acc[(letter, octave)] = acc
    else:
        acc = bar_acc.get((letter, octave), {"#": 1, "b": -1}.get(keyacc.get(letter), 0))
    # ABC: 트리플 clef 에서 C 는 MIDI 60 (C4), 소문자 c 는 72
    midi = 60 + LETTER_SEMITONE[letter] + acc + 12 * octave
    return (midi, whole), i


def _append_note(out: list, state: dict, midi: int, whole: float, dur_whole: float, pending_tie: bool):
    target = state.get("tie_target")
    if pending_tie and target is not None:
        target.dur += dur_whole
        state["tie_target"] = None
        return
    out.append(Note(start=state["beats"], dur=dur_whole, midi=midi))


def parse_abc(text: str, voice: str | None = None) -> ParsedABC:
    """ABC 텍스트를 파싱한다. voice 를 주면 해당 보이스만, None 이면 전 보이스 합치기."""
    result = ParsedABC()
    header: dict = {}
    voice_defs: list = []
    voice_music: dict = {}
    current_voice = None
    unit = 0.25
    tempo = 120.0
    meter = (4, 4)

    lines = text.splitlines()
    # 1차 패스: 헤더와 보이스 정의
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line.startswith("%"):
            continue
        m = re.match(r"^([A-Za-z]):\s*(.*)$", line)
        if not m:
            continue
        key, val = m.group(1).upper(), m.group(2).strip()
        if key in ("X", "T", "M", "L", "Q", "K", "N", "R", "C", "D", "S", "B", "E", "P"):
            header[key] = val
        elif key == "V":
            vid = val.split()[0] if val.split() else ""
            rest = val[len(vid):].strip()
            if rest:
                voice_defs.append((vid, rest))
            else:
                current_voice = vid

    if "L" in header:
        try:
            frac = header["L"].split("/")
            unit = (int(frac[0]) / int(frac[1])) if len(frac) == 2 else 1.0 / int(frac[0])
        except Exception:
            unit = 0.25
    if "M" in header:
        try:
            mm = header["M"].split("/")
            meter = (int(mm[0]), int(mm[1]))
        except Exception:
            meter = (4, 4)
    if "Q" in header:
        mq = re.search(r"=\s*([0-9.]+)", header["Q"])
        if mq:
            try:
                tempo = float(mq.group(1))
            except Exception:
                pass
    key_field = header.get("K", "C")
    keyacc = _key_accidentals(key_field)

    # 2차 패스: 보이스별 음악 수집
    cur = None
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("%"):
            continue
        m = re.match(r"^V:\s*(\S+)\s*$", line)
        if m:
            cur = m.group(1)
            voice_music.setdefault(cur, [])
            continue
        if re.match(r"^[A-Za-z]:", line):
            continue
        if cur is None:
            cur = voice_defs[0][0] if voice_defs else "1"
        voice_music.setdefault(cur, []).append(line)

    # 보이스 선택
    if voice and voice in voice_music:
        selected = [voice]
    elif voice in ("", "all"):
        selected = list(voice_music.keys())
    else:
        selected = list(voice_music.keys())

    result.tempo = tempo
    result.unit = unit
    result.meter = meter
    result.key = key_field
    result.voices = list(voice_music.keys())
    result.voice_names = {v: r for v, r in voice_defs}

    # 마디 한 개 길이(전체음 기준): 4/4 -> 1.0, 3/4 -> 0.75, 6/8 -> 0.75
    measure_whole = meter[0] / float(meter[1] or 4)
    for vname in selected:
        state = {"unit": unit, "beats": 0.0, "measure_whole": measure_whole, "tie_target": None}
        for line in voice_music.get(vname, []):
            _parse_music_line(line, result.notes, state, keyacc)
        # 보이스가 바뀌면 시작 비트 이어붙이기
        if len(selected) > 1:
            shift = result.notes and 0.0
    # 전체길이 정규화(비트가 음수일 수 있음 방지)
    if result.notes:
        base = min(n.start for n in result.notes)
        if base < 0:
            for n in result.notes:
                n.start -= base
    return result


def whole_to_beats(parsed: ParsedABC, whole: float) -> float:
    """전체음(whole note) 길이를 비트 수로 바꾼다. 4/4 에서 1.0 -> 4비트."""
    return whole * float(parsed.meter[1] or 4)


def parse_by_voice(text: str) -> dict:
    """보이스 이름 -> ParsedABC 매핑. 보이스가 하나뿐이면 {"1": ...} 형태."""
    result = parse_abc(text, voice=None)
    out = {}
    for v in result.voices:
        out[v] = parse_abc(text, voice=v)
    return out


def best_voice(by_voice: dict):
    """음표가 가장 많은 보이스 (이름, ParsedABC). 전부 비어 있으면 (None, None)."""
    if not by_voice:
        return None, None
    name, parsed = max(by_voice.items(), key=lambda kv: len(kv[1].notes))
    if not parsed.notes:
        return None, None
    return name, parsed


def summarize(parsed: ParsedABC, limit: int = 40) -> str:
    if not parsed.notes:
        return "음표 0개 (전사 결과가 비어 있음)"
    total_whole = max(n.start + n.dur for n in parsed.notes)
    total_beats = whole_to_beats(parsed, total_whole)
    lines = [
        f"음표 {len(parsed.notes)}개 · 총 {total_beats:.2f}비트 · {parsed.tempo:.0f}BPM · "
        f"조성 {parsed.key} · 박자 {parsed.meter[0]}/{parsed.meter[1]} · "
        f"보이스 {list(parsed.voices) or ['-']}"
    ]
    shown = parsed.notes[:limit]
    for n in shown:
        lines.append(
            f"  {whole_to_beats(parsed, n.start):7.3f}b  길이{whole_to_beats(parsed, n.dur):6.3f}b  MIDI {n.midi:3d}"
        )
    if len(parsed.notes) > limit:
        lines.append(f"  ... 외 {len(parsed.notes) - limit}개")
    return "\n".join(lines)
