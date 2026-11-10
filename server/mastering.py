"""완성곡 다듬기(2026-11-11 사용자: 노래 처음이 지지직거린다).

원인: YuE2 가 만든 소리가 ① 첫 샘플부터 크게 시작하는 경우가 있어(최대의 79%) 재생 시작에 '틱·지직' 소리가 나고
② 디코더 출력이 1.0 을 넘어 FLAC 로 저장될 때 잘렸다(한 곡에 747샘플).
고침: 생성 그래프에서 저장 전에 음량을 낮추고(comfy.final_graph, GEN_HEADROOM_DB), 여기서 앞 25ms 페이드 인 · 끝 1초 페이드 아웃 ·
최대 음량을 -1dBFS 로 맞춘다(낮춘 만큼 되돌리되 넘치지 않게).
"""
from __future__ import annotations

import io

import numpy as np
import soundfile as sf

FADE_IN = 0.025      # 초
FADE_OUT = 1.0       # 초
PEAK = 10 ** (-1 / 20)  # -1dBFS
MAX_GAIN = 10 ** (6 / 20)  # 조용한 곡도 6dB 까지만 올린다


def master(flac: bytes) -> tuple[bytes, dict]:
    y, sr = sf.read(io.BytesIO(flac), dtype="float32", always_2d=True)
    n = len(y)
    fi, fo = min(int(FADE_IN * sr), n // 4), min(int(FADE_OUT * sr), n // 4)
    if fi:
        y[:fi] *= np.linspace(0, 1, fi, dtype=np.float32)[:, None]
    if fo:
        y[-fo:] *= np.linspace(1, 0, fo, dtype=np.float32)[:, None] ** 2
    peak = float(np.abs(y).max()) or 1.0
    gain = min(PEAK / peak, MAX_GAIN)
    y *= gain
    out = io.BytesIO()
    sf.write(out, y, sr, format="FLAC", subtype="PCM_16")
    return out.getvalue(), {"gain_db": round(20 * np.log10(gain), 1), "peak_in": round(peak, 3)}
