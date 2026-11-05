"""사운드폰트 기반 멜로디 렌더러.

ABC 텍스트 -> Note 목록(abc_events) -> tinysoundfont 샘플 렌더 -> numpy 스테레오.
GM 프리셋(피아노/첼로/바이올린/리코더/플루트...)을 골라 허밍 멜로디를 즉시 재생한다.
결정론적(deterministic)이라 같은 입력은 언제나 같은 소리가 난다.
"""

from __future__ import annotations

import os
from collections import defaultdict

import numpy as np

from .abc_events import Note, best_voice, parse_abc, parse_by_voice

try:
    import tinysoundfont
except Exception:  # pragma: no cover - 설치 여부에 따라
    tinysoundfont = None

BLOCK = 1024
GM_PROGRAM_COUNT = 128
FONT_EXTENSIONS = (".sf2", ".sf3")


# --------------------------------------------------------------------------- #
# 폰트 경로 / 목록
# --------------------------------------------------------------------------- #
def _comfy_root() -> str:
    try:
        import folder_paths  # type: ignore
        return folder_paths.base_path
    except Exception:
        here = os.path.dirname(os.path.abspath(__file__))
        return os.path.dirname(os.path.dirname(here))


def soundfont_dir() -> str:
    d = os.path.join(_comfy_root(), "models", "soundfonts")
    try:
        os.makedirs(d, exist_ok=True)
    except OSError:
        pass
    return d


def list_soundfonts() -> list:
    """models/soundfonts 안의 사운드폰트 파일명 목록."""
    d = soundfont_dir()
    try:
        return sorted(f for f in os.listdir(d) if f.lower().endswith(FONT_EXTENSIONS))
    except OSError:
        return []


def resolve_soundfont(name: str) -> str:
    """이름 또는 절대/상대 경로를 실제 파일 경로로."""
    if not name:
        name = ""
    if os.path.isfile(name):
        return name
    cand = os.path.join(soundfont_dir(), name)
    if os.path.isfile(cand):
        return cand
    fonts = list_soundfonts()
    if fonts:
        return os.path.join(soundfont_dir(), fonts[0])
    return cand


# --------------------------------------------------------------------------- #
# 사운드폰트 뱅크
# --------------------------------------------------------------------------- #
class SoundFontBank:
    """사운드폰트 파일 하나를 열어 프리셋(GM) 목록을 들고 있는다."""

    def __init__(self, path: str, samplerate: int = 44100):
        if tinysoundfont is None:
            raise RuntimeError(
                "tinysoundfont 가 설치되어 있지 않습니다. "
                "ComfyUI venv 에서 `pip install tinysoundfont` 를 실행하세요."
            )
        self.path = path
        self.samplerate = samplerate
        self.synth = tinysoundfont.Synth(samplerate=samplerate)
        self.sfid = self.synth.sfload(path)
        if self.sfid is None or self.sfid < 0:
            raise RuntimeError(f"사운드폰트를 열 수 없습니다: {path}")
        self.presets = self._enumerate()

    def _enumerate(self):
        out = []
        for p in range(GM_PROGRAM_COUNT):
            name = ""
            try:
                name = self.synth.sfpreset_name(self.sfid, 0, p)
            except Exception:
                name = ""
            name = (name or "").strip()
            label = f"GM {p} {name}".strip()
            out.append((p, label))
        return out

    def preset_labels(self):
        return [label for _, label in self.presets]

    def resolve_preset(self, label: str) -> int:
        """'GM 42 Cello' 같은 라벨을 프리셋 번호로."""
        if isinstance(label, int):
            return max(0, min(GM_PROGRAM_COUNT - 1, label))
        text = (label or "").strip()
        for p, lbl in self.presets:
            if lbl == text:
                return p
        if text.lower().startswith("gm "):
            tail = text[3:].strip()
            num = ""
            for ch in tail:
                if ch.isdigit():
                    num += ch
                else:
                    break
            if num.isdigit():
                return max(0, min(GM_PROGRAM_COUNT - 1, int(num)))
        # 이름만 준 경우
        low = text.lower()
        for p, lbl in self.presets:
            parts = lbl.split(" ", 2)
            if len(parts) >= 3 and parts[2].lower() == low:
                return p
        for p, lbl in self.presets:
            if lbl.lower() == low:
                return p
        return 0

    def preset_name(self, index: int) -> str:
        for p, lbl in self.presets:
            if p == index:
                parts = lbl.split(" ", 2)
                return parts[2] if len(parts) >= 3 else lbl
        return ""


# --------------------------------------------------------------------------- #
# 리버브 (간단한 합성 임펄스응답)
# --------------------------------------------------------------------------- #
def make_reverb_ir(samplerate: int, room_size: float = 0.9, decay: float = 3.2, seed: int = 0):
    n = max(64, int(samplerate * max(0.05, room_size)))
    rng = np.random.RandomState(seed)
    t = np.arange(n, dtype=np.float64) / float(samplerate)
    ir = rng.randn(n) * np.exp(-decay * t / max(room_size, 0.05))
    try:
        from scipy.signal import lfilter  # type: ignore
        a = 0.72
        ir = lfilter([1.0 - a], [1.0, -a], ir)
    except Exception:
        pass
    ir[0] = 1.0
    return ir.astype(np.float32)


def apply_reverb(dry: np.ndarray, samplerate: int, mix: float, room_size: float) -> np.ndarray:
    if mix <= 0.0:
        return dry
    ir = make_reverb_ir(samplerate, room_size=room_size)
    n = dry.shape[0]
    try:
        from scipy.signal import fftconvolve  # type: ignore
        if dry.ndim == 1:
            wet = fftconvolve(dry, ir, mode="full")[:n]
        else:
            wet = np.stack([fftconvolve(dry[:, c], ir, mode="full")[:n] for c in range(dry.shape[1])], axis=1)
    except Exception:
        wet = np.zeros_like(dry)
        for c in range(dry.shape[1] if dry.ndim == 2 else 1):
            src = dry[:, c] if dry.ndim == 2 else dry
            dst = wet[:, c] if dry.ndim == 2 else wet
            for i in range(n):
                seg = ir[: min(len(ir), n - i)]
                dst[i] = float(np.dot(src[i:i + len(seg)], seg))
    wet = np.asarray(wet, dtype=np.float32)
    if wet.shape != dry.shape:
        wet = np.resize(wet, dry.shape).astype(np.float32)
    return (dry * (1.0 - mix) + wet * mix).astype(np.float32)


# --------------------------------------------------------------------------- #
# 렌더링
# --------------------------------------------------------------------------- #
def _expand_loops(notes, loop_count: int):
    """멜로디를 loop_count 번 이어붙인다."""
    if loop_count <= 1 or not notes:
        return list(notes)
    loop_len = max(n.start + n.dur for n in notes)
    if loop_len <= 0:
        return list(notes)
    out = []
    for k in range(loop_count):
        off = k * loop_len
        for n in notes:
            out.append(Note(start=n.start + off, dur=n.dur, midi=n.midi))
    return out


def render_notes(
    notes,
    bank: SoundFontBank,
    preset_index: int = 0,
    tempo: float = 120.0,
    transpose: int = 0,
    velocity: int = 100,
    gain_db: float = -3.0,
    loop_count: int = 1,
    reverb_mix: float = 0.0,
    reverb_room: float = 0.9,
    tail: float = 2.5,
):
    """Note 목록을 사운드폰트로 렌더해 (N,2) float32 ndarray 를 반환.

    note.start / note.dur 는 '비트' 단위여야 한다(1비트 = 1/4 전체음).
    """
    if not notes:
        raise RuntimeError("렌더링할 음표가 없습니다. ABC 전사 결과가 쉼표뿐인지 확인하세요.")

    sr = bank.samplerate
    beat_dur = 60.0 / max(1e-6, float(tempo))
    events = _expand_loops(notes, int(loop_count))

    # (sample, kind, midi) 이벤트 구성
    evs = []
    max_end = 0.0
    for n in events:
        start_sec = n.start * beat_dur
        dur_sec = max(0.02, n.dur * beat_dur)
        midi = int(n.midi) + int(transpose)
        midi = max(0, min(127, midi))
        s0 = int(round(start_sec * sr))
        s1 = int(round((start_sec + dur_sec) * sr))
        evs.append((s0, "on", midi, s1))
        evs.append((s1, "off", midi, s0))
        max_end = max(max_end, s1)
    total = int(max_end + tail * sr)
    if total <= 0:
        raise RuntimeError("렌더링 길이가 0 입니다.")

    # 샘플 경계 분할: 이벤트가 있는 지점에서 블록을 자른다
    boundaries = sorted({0, total} | {e[0] for e in evs if 0 < e[0] < total})
    by_sample = defaultdict(list)
    for e in evs:
        by_sample[e[0]].append(e)

    synth = bank.synth
    try:
        synth.program_change(0, int(preset_index))
    except Exception:
        pass

    out = np.zeros((total, 2), dtype=np.float32)
    pos = 0
    bi = 0
    n_b = len(boundaries)
    while pos < total:
        # 다음 경계 찾기
        while bi < n_b and boundaries[bi] <= pos:
            bi += 1
        block_end = pos + BLOCK
        if bi < n_b and boundaries[bi] < block_end:
            block_end = boundaries[bi]
        if block_end > total:
            block_end = total

        for e in by_sample.get(pos, []):
            if e[1] == "on":
                try:
                    synth.noteon(0, e[2], int(velocity))
                except Exception:
                    pass
            else:
                try:
                    synth.noteoff(0, e[2])
                except Exception:
                    pass

        frames = block_end - pos
        if frames > 0:
            buf = synth.generate(frames)
            arr = np.frombuffer(buf, dtype=np.float32).reshape(-1, 2)
            m = min(frames, arr.shape[0])
            out[pos:pos + m] = arr[:m]
        pos = block_end

    try:
        synth.notes_off()
    except Exception:
        pass

    # 게인
    out *= float(10.0 ** (gain_db / 20.0))

    # 리버브
    if reverb_mix > 0.0:
        out = apply_reverb(out, sr, float(reverb_mix), float(reverb_room))

    # 클리핑 방지 정규화
    peak = float(np.abs(out).max()) if out.size else 0.0
    if peak > 0.999:
        out = out * (0.999 / peak)
    return np.ascontiguousarray(out, dtype=np.float32)


def render_abc(
    abc_text: str,
    bank: SoundFontBank,
    preset_index: int = 0,
    tempo: float = 0.0,
    transpose: int = 0,
    velocity: int = 100,
    gain_db: float = -3.0,
    loop_count: int = 1,
    reverb_mix: float = 0.0,
    reverb_room: float = 0.9,
    voice: str = "",
):
    """ABC 문자열 -> (오디오 ndarray, 샘플레이트, 요약 문자열)."""
    by_voice = parse_by_voice(abc_text)
    want = (voice or "").strip()
    auto = want.lower() in ("", "auto", "all")

    chosen_name = None
    parsed = None
    if not auto:
        parsed = by_voice.get(want)
        if parsed is not None and parsed.notes:
            chosen_name = want
        else:
            # 지정한 보이스가 비어 있으면 자동으로 fallback
            chosen_name, parsed = best_voice(by_voice)
    else:
        chosen_name, parsed = best_voice(by_voice)

    if parsed is None or not parsed.notes:
        counts = ", ".join(f"{k}:{len(v.notes)}" for k, v in by_voice.items())
        raise RuntimeError(
            "ABC 에서 음표를 찾지 못했습니다 (전사 결과가 쉼표뿐).\n"
            f"보이스별 음표 수 -> {counts or '(보이스 없음)'}\n"
            "─ 원인 1순위: 입력 오디오가 너무 짧음 ─\n"
            "  SheetSage2 는 8~10초 이상 이어지는 멜로디가 있어야 전사합니다.\n"
            "  2~3초짜리 허밍은 아무리 깨끗해도 쉼표만 나옵니다.\n"
            "  → 'Pleos Prepare Hum' 노드의 min_seconds 를 12 로 두거나, 15초 이상 허밍하세요.\n"
            "─ 원인 2순위: 목소리가 잡음에 묻힘 ─\n"
            "  → 마이크를 입 가까이 대고 크게 허밍하세요."
        )
    if want and not auto and chosen_name != want:
        # 지정 보이스는 비었지만 다른 보이스에 멜로디가 있음
        pass

    use_tempo = parsed.tempo if (not tempo or tempo <= 0) else float(tempo)

    # 파서는 '전체음' 단위로 길이를 쌓았으므로 비트 단위로 환산한다.
    # 1비트 = (1/박자분모) 전체음  ->  비트 = 전체음 * 분모
    scale = float(parsed.meter[1] or 4)
    beat_notes = [
        Note(start=n.start * scale, dur=n.dur * scale, midi=n.midi) for n in parsed.notes
    ]

    audio = render_notes(
        beat_notes,
        bank,
        preset_index=preset_index,
        tempo=use_tempo,
        transpose=transpose,
        velocity=velocity,
        gain_db=gain_db,
        loop_count=loop_count,
        reverb_mix=reverb_mix,
        reverb_room=reverb_room,
    )
    dur_beats = max(n.start + n.dur for n in beat_notes)
    summary = (
        f"음표 {len(beat_notes)}개 · {dur_beats:.2f}비트 · {use_tempo:.0f}BPM · "
        f"조성 {parsed.key} · 보이스 {chosen_name} · {audio.shape[0] / bank.samplerate:.1f}초"
    )
    return audio, bank.samplerate, summary
