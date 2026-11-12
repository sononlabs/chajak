"""LLM 엔진 — JSON 하나를 돌려받는 대화 한 번.

역할(role): lyrics(가사·제목·스타일·말로 바꾸기) · talk(대화 요약) · melody(AI 로 멜로디 다시 쓰기).
엔진 이름은 "백엔드/모델" — 지금은 ollama 만 있다. 새 백엔드(예: claude)는 _BACKENDS 에 함수 하나를 더하면 된다.
"""
from __future__ import annotations

import json
import os

import aiohttp

from . import EngineError, config

OLLAMA = os.environ.get("CHAJAK_OLLAMA", "http://127.0.0.1:11434")


def spec(role: str) -> tuple[str, str]:
    name = config()["llm"].get(role)
    if not name or "/" not in name:
        raise EngineError(f"LLM 엔진 이름이 이상해요({role}: {name!r}). '백엔드/모델' 꼴로 적어 주세요.")
    backend, model = name.split("/", 1)
    if backend not in _BACKENDS:
        raise EngineError(f"LLM 백엔드 '{backend}' 는 아직 없어요({', '.join(_BACKENDS)} 중 하나).")
    return backend, model


def describe(role: str) -> str:
    return config()["llm"].get(role, "")


async def chat_json(session: aiohttp.ClientSession, role: str, prompt: str, *, seed: int, temperature: float,
                    keep_alive: str | int = 0, num_ctx: int | None = None) -> dict:
    """프롬프트 한 번 → JSON. 깨진 JSON 이면 json.JSONDecodeError 를 그대로 올린다(부르는 쪽이 다시 시도).
    keep_alive·num_ctx 는 로컬(ollama) 전용 — 연달아 부를 때 모델을 메모리에 둘 시간, 문맥 길이."""
    backend, model = spec(role)
    return await _BACKENDS[backend](session, model, prompt, seed=seed, temperature=temperature, keep_alive=keep_alive, num_ctx=num_ctx)


async def release(session: aiohttp.ClientSession, role: str):
    """로컬 모델을 메모리에서 내린다(노래 엔진에 GPU 를 돌려준다). API 백엔드는 할 일 없음."""
    backend, model = spec(role)
    if backend != "ollama":
        return
    try:
        async with session.post(OLLAMA + "/api/generate", json={"model": model, "keep_alive": 0}, timeout=aiohttp.ClientTimeout(total=30)) as r:
            await r.read()
    except Exception:
        pass


async def _ollama(session, model, prompt, *, seed, temperature, keep_alive, num_ctx):
    options = {"temperature": temperature, "seed": seed}
    if num_ctx:
        options["num_ctx"] = num_ctx
    body = {"model": model, "stream": False, "format": "json", "think": False, "keep_alive": keep_alive,
            "options": options, "messages": [{"role": "user", "content": prompt}]}
    async with session.post(OLLAMA + "/api/chat", json=body, timeout=aiohttp.ClientTimeout(total=300)) as r:
        r.raise_for_status()
        return json.loads((await r.json())["message"]["content"])


_BACKENDS = {"ollama": _ollama}
