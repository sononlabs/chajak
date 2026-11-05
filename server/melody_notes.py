"""트랙 악보(ABC) → 앱 악보 보기용 음표 목록(초 · MIDI).

ABC 해석은 ComfyUI 노드 패키지(pleos_melody_instrument)의 abc_events 를 그대로 쓴다.
노드 패키지 __init__ 은 ComfyUI 를 불러오므로, abc_events.py 파일만 따로 읽는다.
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

NODES = Path(os.environ.get("CHAJAK_NODES", r"F:\StabilityMatrix-win-x64\Data\Packages\ComfyUI\custom_nodes\pleos_melody_instrument"))

_spec = importlib.util.spec_from_file_location("pleos_abc_events", NODES / "abc_events.py")
abc_events = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = abc_events  # dataclass 가 모듈을 찾는다
_spec.loader.exec_module(abc_events)


def notes(track: dict) -> list[dict]:
    """[{"start", "dur", "midi"}] — 트랙 음원 기준 초. 실제 연주 템포·조성 이동을 반영한다."""
    _, parsed = abc_events.best_voice(abc_events.parse_by_voice(track["abc"]))
    if parsed is None:
        return []
    sec = 4 * 60 / track["tempo"]  # 온음표 길이(초) — 템포는 4분음표 기준
    return [{"start": round(n.start * sec, 3), "dur": round(n.dur * sec, 3), "midi": n.midi + track.get("transpose", 0)}
            for n in parsed.notes]
