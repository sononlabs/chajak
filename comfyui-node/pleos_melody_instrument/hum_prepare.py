"""허밍 전처리 DSP — 잡음 제거 · 음성 구간 추출 · 정규화 · 반복.

SheetSage2 는 8~10초 이상 이어지는 멜로디가 있어야 제대로 전사한다.
짧은 허밍(2~3초)은 쉼표만 나오므로, 음성만 추출·정제한 뒤 반복해 길이를 확보한다.
"""
from __future__ import annotations

import numpy as np

EPS = 1e-12


class NoVoiceFound(RuntimeError):
    """허밍 음성 구간을 하나도 찾지 못했을 때."""


# --------------------------------------------------------------------------- #
# 기본 유틸
# --------------------------------------------------------------------------- #
def db(x: float) -> float:
    return 20.0 * np.log10(max(float(x), EPS))


def rms(x) -> float:
    if x is None:
        return 0.0
    a = np.asarray(x, dtype=np.float64)
    if a.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(a ** 2)))


def estimate_f0(x, sr, fmin=60.0, fmax=600.0):
    """자기상관 기반 대략적 F0 추정 (0 이면 신뢰 불가)."""
    a = np.asarray(x, dtype=np.float64)
    if a.size < 2048:
        return 0.0
    seg = a[:8192]
    seg = seg - seg.mean()
    if rms(seg) < 1e-7:
        return 0.0
    corr = np.correlate(seg, seg, "full")[len(seg) - 1:]
    lo, hi = int(sr / fmax), int(sr / fmin)
    if hi >= len(corr) or lo >= hi:
        return 0.0
    lag = lo + int(np.argmax(corr[lo:hi]))
    return (sr / lag) if lag > 0 else 0.0


def highpass(x, sr, hz=70.0):
    """저주파 럼블 / mains hum 제거."""
    a = np.asarray(x, dtype=np.float64)
    try:
        from scipy.signal import butter, sosfiltfilt
        sos = butter(4, hz / (sr / 2.0), btype="highpass", output="sos")
        return sosfiltfilt(sos, a)
    except Exception:
        return a


def soft_limit(y, cap=0.89, knee=0.18):
    """하드 클리핑 대신 소프트 리미터 (피크가 아닌 평균 레벨을 유지)."""
    a = np.abs(y)
    thr = cap - knee
    over = a > thr
    if not np.any(over):
        return y
    out = y.copy()
    out[over] = np.sign(y[over]) * (thr + knee * np.tanh((a[over] - thr) / knee))
    return out


def normalize(x, target_db=-22.0, peak_cap=0.89, max_gain_db=40.0):
    """RMS 목표 + 퍼센타일 기준 피크 + 소프트 리미터.

    절대 피크에 묶으면 순간 트랜지언트 하나가 전체 게인을 눌러버린다.
    그래서 99.5 퍼센타일로 게인을 정하고 초과분만 소프트 리밋한다.
    """
    a = np.asarray(x, dtype=np.float64)
    if a.size == 0:
        return a, {"normalized": False}
    r = rms(a)
    if r < 1e-7:
        return a, {"normalized": False, "reason": "silence"}
    gain = min((10 ** (target_db / 20.0)) / r, 10 ** (max_gain_db / 20.0))
    y = a * gain
    p = float(np.percentile(np.abs(y), 99.5))
    if p > peak_cap:
        y = y * (peak_cap / p)
    y = soft_limit(y, cap=peak_cap)
    return y, {
        "normalized": True,
        "gain_db": round(20 * np.log10(max(gain, EPS)), 1),
        "rms_db_before": round(db(r), 1),
        "rms_db_after": round(db(rms(y)), 1),
        "peak_after": round(float(np.max(np.abs(y))), 4),
    }


# --------------------------------------------------------------------------- #
# 잡음 제거
# --------------------------------------------------------------------------- #
def spectral_subtract(x, sr, n_fft=2048, hop=512, alpha=2.0, beta=0.06, noise_pct=15.0):
    """잡음 프로파일을 빼서 음성 대 잡음비를 올린다."""
    a = np.asarray(x, dtype=np.float64)
    if a.size < n_fft:
        return a, {"denoised": False, "reason": "too short"}
    win = np.hanning(n_fft)
    n_frames = 1 + max(0, (a.size - n_fft) // hop)
    spec = np.empty((n_frames, n_fft // 2 + 1), dtype=np.complex128)
    for i in range(n_frames):
        seg = a[i * hop:i * hop + n_fft]
        if seg.size < n_fft:
            seg = np.pad(seg, (0, n_fft - seg.size))
        spec[i] = np.fft.rfft(seg * win)
    mag = np.abs(spec)
    noise = np.percentile(mag, noise_pct, axis=0)
    clean = np.maximum(mag - alpha * noise, beta * mag)
    spec2 = clean * np.exp(1j * np.angle(spec))
    out = np.zeros(n_frames * hop + n_fft)
    wsum = np.zeros_like(out)
    for i in range(n_frames):
        seg = np.fft.irfft(spec2[i], n_fft) * win
        out[i * hop:i * hop + n_fft] += seg
        wsum[i * hop:i * hop + n_fft] += win ** 2
    y = out[: a.size] / np.maximum(wsum[: a.size], 1e-6)
    return y, {"denoised": True, "alpha": alpha, "beta": beta,
               "noise_percentile": noise_pct, "frames": n_frames}


# --------------------------------------------------------------------------- #
# VAD / 구간 추출
# --------------------------------------------------------------------------- #
def detect_voiced(x, sr, frame_sec=0.02, margin_hi=18.0, margin_lo=10.0):
    """히스테리시스 VAD. (segments, meta) 반환."""
    n = int(sr * frame_sec)
    if n == 0 or len(x) < n:
        return [], {"voiced": False, "reason": "too short"}
    m = len(x) // n
    env = np.sqrt(np.mean(x[: m * n].reshape(m, n).astype(np.float64) ** 2, axis=1))
    edb = 20 * np.log10(env + EPS)
    floor_db = float(np.percentile(edb, 15))
    hi = floor_db + margin_hi
    lo = floor_db + margin_lo
    segs = []
    inside = False
    start = 0
    for i, v in enumerate(edb):
        if not inside and v > hi:
            inside = True
            start = i
        elif inside and v < lo:
            inside = False
            segs.append((start * frame_sec, i * frame_sec))
    if inside:
        segs.append((start * frame_sec, m * frame_sec))
    meta = {
        "voiced": bool(segs),
        "noise_floor_db": round(floor_db, 1),
        "thresh_hi_db": round(hi, 1),
        "thresh_lo_db": round(lo, 1),
        "raw_segments": len(segs),
        "level_db": round(db(rms(x)), 1),
    }
    return segs, meta


def merge_segments(segs, max_gap=0.30, min_dur=0.30):
    """짧은 끊김(숨) 이어붙이기 + 너무 짧은 구간 버리기."""
    if not segs:
        return []
    merged = [list(segs[0])]
    for s, e in segs[1:]:
        if s - merged[-1][1] <= max_gap:
            merged[-1][1] = e
        else:
            merged.append([s, e])
    return [(s, e) for s, e in merged if (e - s) >= min_dur]


def extract_segments(x, sr, segs, pad=0.10, gap=0.15):
    """음성 구간만 뽑아 이어 붙인다 (구간 사이의 잡음 제거)."""
    a = np.asarray(x, dtype=np.float64)
    if not segs:
        return a, {"extracted": False, "reason": "no segments"}
    pieces = []
    for s, e in segs:
        i0 = max(0, int((s - pad) * sr))
        i1 = min(a.size, int((e + pad) * sr))
        if i1 > i0:
            pieces.append(a[i0:i1])
    if not pieces:
        return a, {"extracted": False}
    gap_x = np.zeros(int(gap * sr)) if gap > 0 else np.zeros(0)
    out = []
    for i, p in enumerate(pieces):
        if i:
            out.append(gap_x)
        out.append(p)
    y = np.concatenate(out)
    return y, {"extracted": True, "pieces": len(pieces),
               "piece_sec": [round(p.size / sr, 2) for p in pieces],
               "gap_sec": gap, "total_sec": round(y.size / sr, 3)}


def segment_report(x, sr, segs, max_seg=24):
    """구간별 레벨/예상 음높이 — 목소리인지 잡음인지 판별용."""
    out = []
    for i, (s, e) in enumerate(segs[:max_seg]):
        seg = np.asarray(x, dtype=np.float64)[int(s * sr): int(e * sr)]
        if seg.size < 256:
            continue
        out.append({"i": i, "start": round(s, 2), "end": round(e, 2),
                    "dur": round(e - s, 2), "rms_db": round(db(rms(seg)), 1),
                    "f0": round(estimate_f0(seg, sr), 1)})
    return out


# --------------------------------------------------------------------------- #
# 반복
# --------------------------------------------------------------------------- #
def tile_audio(x, sr, min_seconds=12.0, max_repeats=12, crossfade=0.0):
    """짧은 허밍을 최소 길이까지 이어 붙인다."""
    a = np.asarray(x, dtype=np.float64)
    if a.size == 0:
        return a, {"tiled": False, "reason": "empty"}
    dur = a.size / sr
    if dur <= 0:
        return a, {"tiled": False, "reason": "empty"}
    need = int(min_seconds / dur) + 1
    if need <= 1:
        return a, {"tiled": False, "reason": "already long enough", "seconds": round(dur, 2)}
    n = min(need, max_repeats)
    out = np.tile(a, n)
    info = {"tiled": True, "repeats": n, "from_sec": round(dur, 2),
            "to_sec": round(out.size / sr, 2)}
    if crossfade > 0:
        cf = int(crossfade * sr)
        if 0 < cf and cf * 2 < a.size:
            fade = np.linspace(0, 1, cf)
            merged = []
            for k in range(n):
                seg = a.copy()
                if k > 0:
                    seg[:cf] = seg[:cf] * fade + merged[-1][-cf:] * (1 - fade)
                merged.append(seg)
            out = np.concatenate(merged)
            info["crossfade_sec"] = crossfade
    return out, info


# --------------------------------------------------------------------------- #
# 전체 파이프라인
# --------------------------------------------------------------------------- #
def prepare_hum(x, sr, denoise=True, target_db=-22.0, min_seconds=12.0,
                vad_margin_hi=18.0, vad_margin_lo=10.0, merge_gap=3.0,
                min_dur=0.30, pad=0.30, gap=0.50, max_repeats=12,
                do_extract=True, do_normalize=True):
    """마이크 녹음 -> 전사 가능한 허밍 오디오.

    음성 구간 찾기는 앞뒤 무음과 3초 넘는 멈춤만 잘라낸다(앞뒤 0.3초 여유, 긴 멈춤은 0.5초로).
    여유를 1초로 두면 소리가 짧은 녹음(40초 중 2초)에서 무음이 더 많아져 SheetSage2 가 음을 못 찾았다.
    전에는 0.3초 넘는 쉼을 모두 0.15초로 줄여 붙였는데, 쉼표가 사라져 박자가 망가지고 약한 음이 잘렸다
    — 합성 허밍 48개 시험(2026-11-12, 문서 48)에서 SheetSage2 음표 F1 0.73~0.80, 쉼을 그대로 두면 0.92~0.97.

    Returns (오디오 ndarray, 진단 dict)
    """
    info = {}
    a = np.asarray(x, dtype=np.float64)
    info["input_sec"] = round(a.size / sr, 3)
    info["input_rms_db"] = round(db(rms(a)), 1)

    hp = highpass(a, sr)
    if do_extract:
        segs, vm = detect_voiced(hp, sr, margin_hi=vad_margin_hi, margin_lo=vad_margin_lo)
        merged = merge_segments(segs, max_gap=merge_gap, min_dur=min_dur)
        vm["merged_segments"] = len(merged)
        vm["segments_sec"] = [[round(a_, 2), round(b_, 2)] for a_, b_ in merged]
        vm["segment_report"] = segment_report(hp, sr, merged)
        info["vad"] = vm
        if not merged:
            raise NoVoiceFound(
                "허밍 음성 구간을 찾지 못했습니다.\n"
                f"잡음 바닥 {vm.get('noise_floor_db')}dB 보다 "
                f"{vad_margin_hi}dB 이상 높은 구간이 없습니다.\n"
                "원인: 마이크가 너무 멀거나 / 너무 조용히 허밍했거나 / 재밌는 소리가 섞였습니다.\n"
                "조치: 마이크를 입 가까이 대고, 15초 이상 연속으로 크게 허밍한 뒤 다시 녹음하세요.\n"
                f"(참고: 현재 잡음 바닥 {vm.get('noise_floor_db')}dB / 전체 레벨 {vm.get('level_db')}dB)"
            )
        y, em = extract_segments(hp, sr, merged, pad=pad, gap=gap)
        info["extract"] = em
    else:
        info["vad"] = {"skipped": True}
        info["extract"] = {"extracted": False, "reason": "extract disabled"}
        y = hp

    if denoise and y.size:
        y, m = spectral_subtract(y, sr)
        info["denoise"] = m

    if do_normalize:
        y, nm = normalize(y, target_db=target_db)
        info["normalize"] = nm

    if min_seconds > 0:
        y, ti = tile_audio(y, sr, min_seconds=min_seconds, max_repeats=max_repeats)
        info["tile"] = ti

    info["output_sec"] = round(y.size / sr, 3)
    info["output_rms_db"] = round(db(rms(y)), 1)
    info["output_peak"] = round(float(np.max(np.abs(y))) if y.size else 0.0, 4)
    return y, info
