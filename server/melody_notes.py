"""트랙 악보(ABC) → 앱 악보 보기용 음표 목록(초 · MIDI).

ABC 해석은 ComfyUI 노드 패키지(pleos_melody_instrument)의 abc_events 를 그대로 쓴다.
노드 패키지 __init__ 은 ComfyUI 를 불러오므로, abc_events.py 파일만 따로 읽는다.
"""

from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

def _nodes_dir() -> Path:
    """노드 폴더: CHAJAK_NODES → 제출 저장소의 comfyui-node(서버 폴더 옆) → 서버를 돌리는 ComfyUI venv 의 custom_nodes.
    전에는 이 PC 의 절대경로만 봐서 다른 PC 에서는 서버가 아예 켜지지 않았다(44번 #8)."""
    here = Path(__file__).resolve().parent
    cands = [Path(os.environ["CHAJAK_NODES"])] if os.environ.get("CHAJAK_NODES") else []
    cands += [here.parent / "comfyui-node" / "pleos_melody_instrument",
              Path(sys.executable).resolve().parents[2] / "custom_nodes" / "pleos_melody_instrument"]
    for c in cands:
        if (c / "abc_events.py").exists():
            return c
    raise RuntimeError("pleos_melody_instrument 노드 폴더를 찾지 못했어요. CHAJAK_NODES 환경변수로 알려 주세요: " + ", ".join(map(str, cands)))


NODES = _nodes_dir()

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
