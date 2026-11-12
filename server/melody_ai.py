"""허밍 멜로디를 '참고만' 해서 더 좋은 8마디 멜로디로 다시 쓴다(멜로디가 단순할 때).

이 PC 의 Ollama(qwen3:14b)에게 허밍 앞 4마디를 음계 칸(도=0, 레=1 … 한 옥타브 위 도=7)과 박 길이로 주고,
첫 동기의 리듬은 살리되 발전(반복·한 칸 위로·뒤집기)·절정(6~7마디)·으뜸음 마침이 있는 8마디를 쓰게 한다.
마디마다 4박이 맞는지, 음계 칸 범위·도약 크기를 검사해 고치고, 두 번 다 실패하면 규칙(멜로디 완성하기)으로 대신한다.
ABC 해석·쓰기는 ComfyUI 노드 패키지 모듈을 그대로 쓴다(melody_notes 와 같은 방식).
"""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import types

import aiohttp

from lyrics import OLLAMA

# 가사는 gemma3 로 바꿨지만(한국어), 음계 숫자 다루기는 지금까지 시험한 qwen3 를 그대로 쓴다
MODEL = os.environ.get("CHAJAK_MELODY_MODEL", "qwen3:14b")
from melody_notes import NODES

_pkg = types.ModuleType("pleos_nodes")
_pkg.__path__ = [str(NODES)]
sys.modules["pleos_nodes"] = _pkg
for _name in ("abc_events", "soundfont_render", "arrange", "melody_variation"):
    _spec = importlib.util.spec_from_file_location(f"pleos_nodes.{_name}", NODES / f"{_name}.py")
    _mod = importlib.util.module_from_spec(_spec)
    sys.modules[f"pleos_nodes.{_name}"] = _mod
    _spec.loader.exec_module(_mod)
ae = sys.modules["pleos_nodes.abc_events"]
ar = sys.modules["pleos_nodes.arrange"]
mv = sys.modules["pleos_nodes.melody_variation"]

BARS = 8
DURS = (0.5, 1.0, 1.5, 2.0, 3.0, 4.0)


def _motif(abc: str):
    _, parsed = ae.best_voice(ae.parse_by_voice(abc))
    per_whole = float(parsed.meter[1] or 4)
    root, minor = ar._key(parsed.key)
    scale = ar.MINOR if minor else ar.MAJOR
    notes = mv.tidy([ae.Note(n.start * per_whole, n.dur * per_whole, n.midi) for n in parsed.notes], root, scale, 0.5)
    base = mv._degree(notes[0].midi, root, scale)[0] // 7 * 7  # 첫 음 옥타브의 도 = 0
    bars = [[] for _ in range(4)]
    for n in notes:
        b = int(n.start // 4)
        if b < 4:
            bars[b].append([mv._degree(n.midi, root, scale)[0] - base, round(min(n.dur, 4 - n.start % 4) * 2) / 2])
    return parsed, root, scale, base, bars


def _prompt(bars, minor: bool) -> str:
    return (
        f"너는 작곡가다. 아래는 사용자가 흥얼거린 4마디 멜로디다({'단조' if minor else '장조'}, 4/4박).\n"
        "음은 음계 칸 번호(으뜸음=0, 1=두 번째 음 … 7=한 옥타브 위 으뜸음, -1=아래), 길이는 박.\n"
        f"허밍: {json.dumps(bars)}\n"
        "이 허밍을 참고해서 훨씬 노래다운 8마디 멜로디를 새로 써라. JSON 으로만 답해: {\"bars\": [[[칸, 박], ...], ... 8마디]}\n"
        "규칙: 마디마다 박 합이 정확히 4. 박은 0.5, 1, 1.5, 2, 3, 4 중에서. 칸은 -3~11.\n"
        "1~2마디는 허밍 첫 동기의 리듬과 윤곽을 살리고, 3~4마디는 그 동기를 한 칸 위나 아래에서 되풀이(반복·변형).\n"
        "5~6마디는 새 동기로 올라가 6~7마디에 가장 높은 음(절정), 8마디는 으뜸음(0 또는 7)으로 2박 이상 길게 끝.\n"
        "대부분 한 칸씩 움직이고 4칸 넘는 도약은 두 번까지. 같은 음 세 번 넘게 연속 금지. 기존 노래 멜로디를 베끼지 마."
    )


def _fix(bars):
    """마디마다 4박으로 맞추고 칸 범위를 자른다. 쓸 수 없으면 None."""
    if not isinstance(bars, list) or len(bars) < BARS:
        return None
    out = []
    for bar in bars[:BARS]:
        notes, t = [], 0.0
        for item in bar if isinstance(bar, list) else []:
            try:
                deg, dur = int(item[0]), float(item[1])
            except (TypeError, ValueError, IndexError):
                continue
            dur = min(DURS, key=lambda d: abs(d - dur))
            if t + dur > 4:
                dur = 4 - t
            if dur <= 0:
                break
            notes.append((max(-3, min(11, deg)), dur))
            t += dur
        if not notes:
            return None
        if t < 4:
            notes[-1] = (notes[-1][0], notes[-1][1] + 4 - t)
        out.append(notes)
    last = out[-1][-1]
    if last[0] % 7 != 0:
        out[-1][-1] = (7 if last[0] > 3 else 0, last[1])
    return out


async def rewrite(abc: str, seed: int) -> tuple[str, str]:
    """반환: (새 멜로디 ABC, 요약). 원본 머리글(박자·템포·조성)을 그대로 쓴다."""
    parsed, root, scale, base, bars = _motif(abc)
    minor = scale is ar.MINOR
    unit_beats = parsed.unit * float(parsed.meter[1] or 4)
    bar_units = round(parsed.meter[0] / unit_beats)
    key = parsed.key.split()[0] if parsed.key else "C"
    body = {"model": MODEL, "stream": False, "format": "json", "think": False, "keep_alive": 0,
            "options": {"temperature": 0.9, "seed": seed}, "messages": [{"role": "user", "content": _prompt(bars, minor)}]}
    async with aiohttp.ClientSession() as session:
        for attempt in range(2):
            body["options"]["seed"] = seed + attempt
            async with session.post(OLLAMA + "/api/chat", json=body, timeout=aiohttp.ClientTimeout(total=300)) as r:
                r.raise_for_status()
                try:
                    fixed = _fix(json.loads((await r.json())["message"]["content"]).get("bars"))
                except (json.JSONDecodeError, AttributeError):
                    fixed = None
            if fixed:
                notes, t = [], 0.0
                for bar in fixed:
                    for deg, dur in bar:
                        notes.append(ae.Note(t, dur, mv._pitch(base + deg, 0, root, scale)))
                        t += dur
                return mv.write_abc(notes, abc, key, unit_beats, bar_units), f"AI 멜로디 8마디 · 음표 {len(notes)}개 · 허밍 참고(시도 {attempt + 1})"
    out, _ = mv.vary(abc, "멜로디 완성하기")
    return out, "AI 멜로디를 못 써서 규칙으로 완성(멜로디 완성하기)"
