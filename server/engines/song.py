"""노래 엔진 — 곡 악보·가사·스타일 → 노래 소리.

엔진마다 되는 것이 다르다(52번 문서 2장). caps 로 알려 주면 서버가 안 되는 입력을 넣지 않고, 완성곡 기록에 엔진 이름을 남긴다.
  melody: "full"(2분 악보 전체를 따른다) | "60s"(앞 60초 멜로디만, MIDI) | None(멜로디를 받지 않는다 — 주제로 만든 노래)
  style: 스타일·장르 글을 받는지 · seed: 같은 시드로 다시 만들 수 있는지 · gpu: 이 PC 의 GPU 를 쓰는지(쓰면 표지 모델을 먼저 내린다)
새 엔진은 SongEngine 을 이어받아 generate 하나를 쓰고 _ENGINES 에 더한다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import comfy as C

from . import EngineError, config


@dataclass
class SongRequest:
    abc: str             # 곡 악보(멜로디·화음). 엔진이 멜로디를 쓰지 않거나 '내 멜로디' 가 꺼져 있으면 ""
    style: str           # 스타일 태그(영어, 쉼표)
    lyrics: str          # YuE 형식 가사 "[verse]\n…" 또는 "[Instrumental]"
    sections: dict       # 구간별 가사 줄 {"verse": [...], "chorus": [...]} — 엔진이 자기 형식으로 다시 쓸 때
    plan: dict           # 곡 구성(PleosSongForm) — 구간 순서·길이
    seconds: float       # 만들 길이(끝 여운 포함)
    seed: int
    temperature: float   # YuE2 전용(멜로디 반영 가중치에서)
    cfg_scale: float     # YuE2 전용(스타일 강도에서)
    prefix: str          # ComfyUI 저장 이름


@dataclass
class SongResult:
    audio: bytes
    ext: str                      # "flac" | "mp3" | "wav"
    info: dict = field(default_factory=dict)


class SongEngine:
    name = ""
    caps = {"melody": None, "style": True, "seed": False, "gpu": False}

    def eta(self, seconds: float) -> int:
        """노래 만들기 전체(곡 구성·가사·표지·노래) 예상 초 — 진행 막대용."""
        return round(60 + seconds * 0.5)

    async def generate(self, cf: C.Comfy, req: SongRequest) -> SongResult:
        raise NotImplementedError


class Yue2(SongEngine):
    """이 PC 의 ComfyUI YuE2 3B int8. 가중치 CC BY-NC 4.0(비상업)."""
    name = "yue2"
    caps = {"melody": "full", "style": True, "seed": True, "gpu": True}

    def eta(self, seconds: float) -> int:
        # 2026-11-05 실측(RTX 5070 Ti): 멜로디 1초 · 가사 15초 · 표지 23초 · 노래 205초(곡 114초+8초) → 곡 1초당 약 1.7초
        return round(40 + (seconds + 8) * 1.7)

    async def generate(self, cf, req):
        out = await cf.run(C.final_graph(req.abc, req.style, req.lyrics, req.seed, req.seconds, req.prefix, req.temperature, req.cfg_scale))
        return SongResult(await cf.fetch_audio(out, "save"), "flac", {"model": "yue2_3b_int8_convrot"})


_ENGINES: dict[str, type[SongEngine]] = {"yue2": Yue2}


def names() -> list[str]:
    return list(_ENGINES)


def get(name: str | None = None) -> SongEngine:
    """name 이 없으면 설정 파일의 song. 이름이 없으면 EngineError."""
    name = name or config()["song"]
    if name not in _ENGINES:
        raise EngineError(f"노래 엔진 '{name}' 은 아직 없어요({', '.join(_ENGINES)} 중 하나).")
    return _ENGINES[name]()
