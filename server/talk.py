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
MAX_TALK_SEC = 1800  # 30분. 받아쓰기는 GPU 로 15분 대화가 약 14초(2026-11-10 실측)

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


def decode(data: bytes) -> np.ndarray:
    """녹음 파일(wav·m4a·mp3·ogg…) → 16kHz 모노. PyAV(받아쓰기 엔진에 딸린 디코더)로 푼다 — soundfile 은 m4a 를 못 읽었다(2026-11-10 실측 파일)."""
    from faster_whisper.audio import decode_audio
    return decode_audio(io.BytesIO(data), sampling_rate=16000)


def _hallucinated(text: str) -> bool:
    """받아쓰기가 허밍·음악·잡음을 말로 착각해 지어낸 줄(2026-11-10 실측 허밍: '공공 김 김 김 김…', '꽁꽁 낀 낀 낀')."""
    words = text.replace(".", " ").replace(",", " ").split()
    if len(words) >= 6 and len(set(words)) / len(words) < 0.35:
        return True
    return False


def _transcribe_sync(data: bytes) -> dict:
    global _model
    from faster_whisper import WhisperModel
    x = decode(data)
    seconds = len(x) / 16000
    with _model_lock:
        device, compute = _device()
        if _model is None or getattr(_model, "_chajak_device", None) != device:
            _model = WhisperModel(STT_MODEL, device=device, compute_type=compute, local_files_only=True)
            _model._chajak_device = device
        segs, info = _model.transcribe(x, language="ko", vad_filter=True, beam_size=1,
                                       condition_on_previous_text=False)
        lines, dropped = [], 0
        for s in segs:
            t = s.text.strip()
            if not t:
                continue
            # 말소리가 아닐 확률이 높고 자신도 없는 줄, 같은 말만 되풀이하는 줄은 버린다
            if (s.no_speech_prob > 0.6 and s.avg_logprob < -1.0) or _hallucinated(t):
                dropped += 1
                continue
            lines.append(t)
        used = device
        if device == "cuda":  # 노래 엔진에 GPU 를 돌려준다
            _model = None
    return {"text": " ".join(lines), "lines": lines, "seconds": round(seconds, 1), "device": used, "dropped": dropped}


def _sample(lines: list[str], limit: int = 6000) -> str:
    """긴 대화는 앞부분만 자르지 않고 처음·중간·끝에서 고르게 뽑는다(15분 실측 대화가 앞 6천 자에서 잘렸다)."""
    text = "\n".join(lines)
    if len(text) <= limit:
        return text
    keep, total, step = [], 0, len(text) / limit
    i = 0.0
    while int(i) < len(lines) and total < limit:
        keep.append(lines[int(i)])
        total += len(lines[int(i)]) + 1
        i += step
    return "\n".join(keep)


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
        "- 대화가 길면 정보(숫자·전문 용어)보다 마음이 움직인 순간 — 기대·걱정·웃음·다짐·소중한 사람 — 을 중심으로 고른다.\n"
        "- 살릴 말은 노래로 부를 수 있게 짧게(띄어쓰기 빼고 12자 이내), 그 사람 말투 그대로. 숫자·전문 용어·받아쓰기 오류로 보이는 말은 고르지 않는다.\n"
        "JSON 으로만 답해: "
        '{"enough": 노래로 만들 이야기가 있으면 true(인사만 하거나 거의 말이 없으면 false), '
        '"theme": 노래 주제 한 줄(언제·누가·무슨 일·어떤 마음, 40자 안팎), '
        '"story": 무슨 일이 있었는지 2~3문장, '
        '"feeling": 이 노래의 감정 낱말 1~2개, '
        '"phrases": 대화에서 실제로 나온 말 중 가사에 살리면 좋을 짧은 표현 3~5개(배열, 고유한 정보는 바꿔서), '
        '"title": 노래 제목 후보(짧게), '
        '"names": 대화에 나온 사람 이름·회사·가게 이름을 받아쓴 그대로(배열, 없으면 [])}\n'
        f"대화:\n{transcript}"
    )


# 이름 가리기는 LLM 에만 맡기지 않는다(2026-11-10: 같은 대화에서 한 번은 가리고 한 번은 '김 팀장님'을 그대로 썼다).
# ① 성(+이름) + 직함 → 직함만  ② LLM 이 찾아 준 이름 → '그 사람'
TITLES = "팀장|부장|과장|차장|대리|사장|실장|본부장|이사|상무|전무|주임|선배|후배|선생|교수|원장|반장|매니저|대표"
_TITLE_NAME = re.compile(rf"(?<![가-힣])[가-힣]{{1,3}}\s?(?=(?:{TITLES})(?:님)?(?![가-힣]{{2}}))")
_KEEP_BEFORE = {"우리", "그", "새", "옆", "윗", "전", "다른", "저", "이"}


_GENERIC = re.compile(rf"^(?:{TITLES}|사장|신랑|신부|남편|아내|엄마|아빠|선생|기사|고객|손님)(?:님)?$")


def _mask(text: str, names: list[str]) -> str:
    # '사장님'·'신랑' 같은 부르는 말은 이름이 아니다(15분 실측 대화에서 '사장님'이 '그 사람'으로 바뀌었다)
    for n in sorted({n for n in names if len(n) >= 2 and not _GENERIC.match(n.replace(" ", ""))}, key=len, reverse=True):
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
    # 부르기 어려운 긴 문장은 뺀다(띄어쓰기 빼고 16자 넘게)
    phrases = [m(p) for p in (out.get("phrases") or []) if str(p).strip() and len(str(p).replace(" ", "")) <= 16][:5]
    return {"enough": out.get("enough") is not False,
            "theme": m(out.get("theme"))[:80],
            "story": m(out.get("story"))[:400],
            "feeling": _words(out.get("feeling"))[:30],
            "phrases": [p[:40] for p in phrases],
            "title": m(out.get("title"))[:30],
            "direction": feeling,
            "_names": names}  # 서버가 받아쓴 글을 가릴 때만 쓰고 앱에는 보내지 않는다


class DigestError(Exception):
    pass


async def _ask_long(session: aiohttp.ClientSession, prompt: str, seed: int) -> dict:
    """긴 대화용: 문맥 8천 토큰(기본 약 4천이면 15분 대화가 잘려 깨진 JSON 이 왔다 — 2026-11-10 실측). 마지막엔 모델을 내려 GPU 를 돌려준다."""
    body = {"model": lyrics.MODEL, "stream": False, "format": "json", "think": False, "keep_alive": 0,
            "options": {"temperature": 0.4, "seed": seed, "num_ctx": 8192}, "messages": [{"role": "user", "content": prompt}]}
    async with session.post(lyrics.OLLAMA + "/api/chat", json=body, timeout=aiohttp.ClientTimeout(total=300)) as r:
        r.raise_for_status()
        return json.loads((await r.json())["message"]["content"])


async def digest(transcript: str, feeling: str, seed: int = 7, lines: list[str] | None = None) -> dict:
    transcript = _sample(lines or transcript.split("\n"))
    async with aiohttp.ClientSession() as session:
        for attempt in range(2):  # 깨진 JSON 이면 한 번 더
            try:
                out = await _ask_long(session, _digest_prompt(transcript, feeling), seed + attempt)
                if isinstance(out, dict):
                    return _clean(out, feeling)
            except (json.JSONDecodeError, KeyError):
                pass
    raise DigestError("대화를 노래 이야기로 정리하지 못했어요.")


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
