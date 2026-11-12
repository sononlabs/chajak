"""표지 엔진 — 영어 장면 문장(글자 금지 지시 포함) → 정사각형 PNG.

지금은 이 PC 의 ComfyUI 두 가지(Z-Image Turbo · Qwen-Image-2512). API 엔진은 CoverEngine 을 이어받아 draw 를 쓰고 _ENGINES 에 더한다.
"""
from __future__ import annotations

import comfy as C

from . import EngineError, config


class CoverEngine:
    name = ""
    gpu = False   # 이 PC 의 GPU 를 쓰면 그린 뒤 메모리를 비워 노래 엔진에 넘긴다

    async def draw(self, cf: C.Comfy, prompt: str, seed: int, prefix: str) -> bytes:
        raise NotImplementedError


class ComfyCover(CoverEngine):
    gpu = True

    def __init__(self, model: str):
        self.name = model

    async def draw(self, cf, prompt, seed, prefix):
        out = await cf.run(C.cover_graph(prompt, seed, prefix, self.name))
        return await cf.fetch_image(out, "save")


_ENGINES = {"zimage": lambda: ComfyCover("zimage"), "qwen2512": lambda: ComfyCover("qwen2512")}


def get(name: str | None = None) -> CoverEngine:
    name = name or config()["cover"]
    if name not in _ENGINES:
        raise EngineError(f"표지 엔진 '{name}' 은 아직 없어요({', '.join(_ENGINES)} 중 하나).")
    return _ENGINES[name]()
