"""대화로 가사 만들기(저니맵 02): 차 안 대화 녹음 → 받아쓰기(faster-whisper) → 노래 이야기로 정리(로컬 LLM).

- 녹음 파일은 받아쓴 뒤 저장하지 않는다(메모리에서만 다룬다).
- 사람 이름·회사·학교·전화번호 같은 고유한 정보는 정리할 때 '그 사람'·'회사' 같은 말로 바꾼다.
- 사용자가 고른 방향(느낀 그대로·통쾌하게·담담하게·유쾌하게)을 지키고, 위로·교훈·긍정적인 결말로 바꾸지 않는다.
"""
from __future__ import annotations

import asyncio
import io
import json
import os
import re
import threading

import aiohttp
import numpy as np
import soundfile as sf

import lyrics

STT_MODEL = os.environ.get("CHAJAK_STT_MODEL", "mobiuslabsgmbh/faster-whisper-large-v3-turbo")
# auto: GPU 여유(3GB 이상)가 있으면 GPU, 아니면 CPU. 노래 엔진(YuE2·표지)이 GPU 를 쓰는 중일 수 있어 CPU 도 허용한다
STT_DEVICE = os.environ.get("CHAJAK_STT_DEVICE", "auto")
MAX_TALK_SEC = 600

FEELINGS = {
    "그대로": "대화에서 드러난 감정을 그대로 살린다. 바꾸거나 누그러뜨리지 않는다",
    "통쾌하게": "억울하거나 답답했던 마음을 시원하게 받아치는 통쾌한 노래로. 풍자와 유머는 좋지만 위로나 교훈으로 끝내지 않는다",
    "담담하게": "감정을 과장하지 않고 담담하게 이야기하듯",
    "유쾌하게": "웃음이 나는 가볍고 신나는 노래로",
}

_model = None
_model_lock = threading.Lock()


def _device() -> tuple[str, str]:
    if STT_DEVICE in ("cpu", "cuda"):
        return (STT_DEVICE, "int8_float16" if STT_DEVICE == "cuda" else "int8")
    try:
        import torch
        if torch.cuda.is_available() and torch.cuda.mem_get_info()[0] > 3 * 1024 ** 3:
            return ("cuda", "int8_float16")
    except Exception:
        pass
    return ("cpu", "int8")


def _transcribe_sync(data: bytes) -> dict:
    global _model
    from faster_whisper import WhisperModel
    x, sr = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    if sr != 16000:  # whisper 는 16kHz. 간단한 선형 보간으로 맞춘다(말소리에는 충분)
        n = int(len(x) * 16000 / sr)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    seconds = len(x) / 16000
    with _model_lock:
        device, compute = _device()
        if _model is None or getattr(_model, "_chajak_device", None) != device:
            _model = WhisperModel(STT_MODEL, device=device, compute_type=compute, local_files_only=True)
            _model._chajak_device = device
        segs, info = _model.transcribe(x, language="ko", vad_filter=True, beam_size=1,
                                       condition_on_previous_text=False)
        lines = [s.text.strip() for s in segs if s.text.strip()]
        used = device
        if device == "cuda":  # 노래 엔진에 GPU 를 돌려준다
            _model = None
    return {"text": " ".join(lines), "lines": lines, "seconds": round(seconds, 1), "device": used}


async def transcribe(data: bytes) -> dict:
    return await asyncio.get_running_loop().run_in_executor(None, _transcribe_sync, data)


def _digest_prompt(transcript: str, feeling: str) -> str:
    return (
        "아래는 차 안에서 나눈 대화를 받아쓴 글이다(받아쓰기라 틀린 낱말이 있을 수 있다). 이 대화로 노래를 만들려고 한다.\n"
        f"방향: {feeling} — {FEELINGS[feeling]}.\n"
        "규칙:\n"
        "- 대화에 없는 사건을 지어내지 않는다.\n"
        "- 받아쓰기가 잘못 알아들은 것 같은 낱말(문맥에 안 맞는 낱말)은 문맥에 맞는 말로 바로잡아 쓴다.\n"
        "- 사람 이름·회사·학교·가게 이름·전화번호·주소는 '그 사람', '팀장님', '회사'처럼 바꾼다.\n"
        "- 욕설은 순화하되 감정의 세기는 줄이지 않는다.\n"
        "- 위로·교훈·'힘내자' 같은 긍정적인 결말을 덧붙이지 않는다.\n"
        "JSON 으로만 답해: "
        '{"enough": 노래로 만들 이야기가 있으면 true(인사만 하거나 거의 말이 없으면 false), '
        '"theme": 노래 주제 한 줄(언제·누가·무슨 일·어떤 마음, 40자 안팎), '
        '"story": 무슨 일이 있었는지 2~3문장, '
        '"feeling": 이 노래의 감정 낱말 1~2개, '
        '"phrases": 대화에서 실제로 나온 말 중 가사에 살리면 좋을 짧은 표현 3~5개(배열, 고유한 정보는 바꿔서), '
        '"title": 노래 제목 후보(짧게), '
        '"names": 대화에 나온 사람 이름·회사·가게 이름을 받아쓴 그대로(배열, 없으면 [])}\n'
        f"대화:\n{transcript[:6000]}"
    )


# 이름 가리기는 LLM 에만 맡기지 않는다(2026-11-10: 같은 대화에서 한 번은 가리고 한 번은 '김 팀장님'을 그대로 썼다).
# ① 성(+이름) + 직함 → 직함만  ② LLM 이 찾아 준 이름 → '그 사람'
TITLES = "팀장|부장|과장|차장|대리|사장|실장|본부장|이사|상무|전무|주임|선배|후배|선생|교수|원장|반장|매니저|대표"
_TITLE_NAME = re.compile(rf"(?<![가-힣])[가-힣]{{1,3}}\s?(?=(?:{TITLES})(?:님)?(?![가-힣]{{2}}))")
_KEEP_BEFORE = {"우리", "그", "새", "옆", "윗", "전", "다른", "저", "이"}


def _mask(text: str, names: list[str]) -> str:
    for n in sorted({n for n in names if len(n) >= 2}, key=len, reverse=True):
        text = text.replace(n, "그 사람")
    return _TITLE_NAME.sub(lambda m: m.group(0) if m.group(0).strip() in _KEEP_BEFORE else "", text)


def _words(v) -> str:
    """LLM 이 낱말을 배열로 줄 때가 있다(['분노', '통쾌함']) — '분노·통쾌함' 으로."""
    if isinstance(v, (list, tuple)):
        return "·".join(str(x).strip() for x in v if str(x).strip())
    return str(v or "").strip()


def _clean(out: dict, feeling: str) -> dict:
    names = [str(n).strip() for n in (out.get("names") or []) if isinstance(n, (str, int)) and str(n).strip()]
    m = lambda s: _mask(str(s or "").strip(), names)
    phrases = [m(p) for p in (out.get("phrases") or []) if str(p).strip()][:5]
    return {"enough": out.get("enough") is not False,
            "theme": m(out.get("theme"))[:80],
            "story": m(out.get("story"))[:400],
            "feeling": _words(out.get("feeling"))[:30],
            "phrases": [p[:40] for p in phrases],
            "title": m(out.get("title"))[:30],
            "direction": feeling}


async def digest(transcript: str, feeling: str, seed: int = 7) -> dict:
    async with aiohttp.ClientSession() as session:
        try:
            out = await lyrics._ask(session, _digest_prompt(transcript, feeling), seed, keep=0, temperature=0.4)
        finally:
            pass
    return _clean(out, feeling)


def talk_lines(talk: dict | None) -> str:
    """가사 기획·초안 프롬프트에 넣을 대화 카드(lyrics.write 의 talk)."""
    if not talk:
        return ""
    phrases = ", ".join(f"'{p}'" for p in talk.get("phrases") or [])
    direction = talk.get("direction", "그대로")
    return ("이 노래는 차 안에서 나눈 대화로 만든다.\n"
            f"대화 요약: {talk.get('story', '')}\n"
            f"감정: {talk.get('feeling', '')} · 방향: {direction} — {FEELINGS.get(direction, FEELINGS['그대로'])}. "
            "위로·교훈·긍정적인 결말로 바꾸지 마.\n"
            + (f"대화에서 실제로 나온 표현(한두 개를 가사에 살리면 좋다, 그대로 길게 옮기지는 마): {phrases}\n" if phrases else "")
            + "사람 이름·회사 같은 고유한 정보는 쓰지 마.\n")


def sanitize(talk) -> dict | None:
    """앱이 보낸 talk 옵션을 검사해 길이를 자른다."""
    if not isinstance(talk, dict):
        return None
    direction = talk.get("direction") if talk.get("direction") in FEELINGS else "그대로"
    return {"story": str(talk.get("story", ""))[:400], "feeling": str(talk.get("feeling", ""))[:30],
            "phrases": [str(p)[:40] for p in (talk.get("phrases") or [])[:5] if isinstance(p, (str, int, float))],
            "direction": direction}
