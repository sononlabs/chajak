"""엔진 고르기 — 단계마다 어떤 모델·API 를 쓸지(2026-11-13, 52번 문서).

설정 파일 ~/.chajak/engines.json (저장소 밖 — API 키가 들어갈 수 있다). 요청마다 다시 읽으므로 서버를 다시 켜지 않아도 바뀐다.
파일이 없거나 항목이 빠지면 지금 로컬 구성(YuE2 · Ollama gemma3/qwen3 · Z-Image)을 그대로 쓴다.

    {
      "song": "yue2",
      "song_new": null,                        # "내 멜로디: AI가 새로"(멜로디 없이) 노래만 다른 엔진으로, 예: "mureka". null 이면 song
      "accompaniment": null,                   # 노래를 만든 뒤 반주만 다시: null | "mureka"(보컬 분리 → 보컬에 맞춘 반주 → 섞기)
      "cover": "zimage",                      # zimage | qwen2512
      "llm": {"lyrics": "ollama/gemma3:12b",   # 가사·제목·스타일·말로 바꾸기
              "talk": "ollama/gemma3:12b",     # 대화 요약
              "melody": "ollama/qwen3:14b"},   # AI 로 멜로디 다시 쓰기
      "keys": {"mureka": "...", "anthropic": "..."}
    }
"""
from __future__ import annotations

import json
import os
from pathlib import Path

CONFIG = Path(os.environ.get("CHAJAK_ENGINES", Path.home() / ".chajak" / "engines.json"))

# 예전 환경변수(CHAJAK_LYRICS_MODEL 등)도 기본값으로 계속 따른다
DEFAULTS = {
    "song": "yue2",
    "song_new": None,
    "accompaniment": None,
    "cover": os.environ.get("CHAJAK_COVER_MODEL", "zimage"),
    "llm": {"lyrics": "ollama/" + os.environ.get("CHAJAK_LYRICS_MODEL", "gemma3:12b"),
            "talk": "ollama/" + os.environ.get("CHAJAK_LYRICS_MODEL", "gemma3:12b"),
            "melody": "ollama/" + os.environ.get("CHAJAK_MELODY_MODEL", "qwen3:14b")},
    "keys": {},
}


class EngineError(RuntimeError):
    """설정한 엔진이 없거나(이름 오타) 키가 없을 때."""


def config() -> dict:
    cfg = json.loads(json.dumps(DEFAULTS))
    if CONFIG.exists():
        try:
            user = json.loads(CONFIG.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(f"엔진 설정을 읽지 못함({CONFIG}) — 기본 구성으로: {e}", flush=True)
            return cfg
        for k, v in user.items():
            if isinstance(v, dict) and isinstance(cfg.get(k), dict):
                cfg[k].update(v)
            else:
                cfg[k] = v
    return cfg


def key(name: str) -> str:
    k = str(config().get("keys", {}).get(name, "")).strip()
    if not k:
        raise EngineError(f"{name} API 키가 없어요. {CONFIG} 의 keys.{name} 에 넣어 주세요.")
    return k


def summary() -> dict:
    """지금 쓰는 엔진 이름들(키는 빼고) — /health 와 완성곡 기록용."""
    c = config()
    return {"song": c["song"], "song_new": c.get("song_new"), "accompaniment": c.get("accompaniment"), "cover": c["cover"], "llm": dict(c["llm"])}
