"""주제(프롬프트) → 노래 제목 · 스타일 태그 · 가사. 이 PC 의 Ollama(qwen3:14b)로 만든다(외부 API 없음).

가사 줄 수·줄마다 음절 목표는 2분 곡 계획(PleosSongForm plan)에서 온다: 2마디가 가사 한 줄, 그 2마디 음 개수가 음절 목표.
한국어는 한 음에 한 음절이 기본이라 음절 수를 맞추면 노래가 멜로디에 잘 붙는다.
"""

from __future__ import annotations

import json
import os
import random

import aiohttp

OLLAMA = os.environ.get("CHAJAK_OLLAMA", "http://127.0.0.1:11434")
MODEL = os.environ.get("CHAJAK_LYRICS_MODEL", "qwen3:14b")

# 주사위 주제(직접 쓴 문장). 차 안 상황 + 분위기 + 장르.
THEMES = [
    "비 오는 밤 퇴근길, 와이퍼 소리와 잔잔한 시티팝",
    "주말 아침 바닷가로 가는 드라이브, 상쾌한 어쿠스틱 팝",
    "첫눈 오는 날 고향 가는 길, 따뜻한 발라드",
    "새벽 고속도로, 졸음을 깨우는 신나는 신스팝",
    "벚꽃 터널을 지나는 봄날, 설레는 인디 팝",
    "아이와 함께 가는 소풍길, 밝고 통통 튀는 동요풍",
    "노을 지는 해안도로, 몽글몽글한 로파이",
    "오래된 친구를 만나러 가는 길, 그리운 포크",
    "야근 끝 텅 빈 도심, 위로가 되는 R&B",
    "단풍 든 산길 드라이브, 여유로운 재즈",
    "여름밤 창문 열고 달리는 길, 청량한 밴드 사운드",
    "출근길 막히는 도로, 힘내자는 펑키 팝",
]


def random_theme() -> str:
    return random.choice(THEMES)


def _syllables(line: str) -> int:
    return sum(1 for c in line if "가" <= c <= "힣")


def _targets(plan: dict) -> dict:
    """가사 묶음 → 줄마다 음절 목표. 같은 이름 구간은 첫 번째 것을 쓴다(후렴은 같은 가사를 다시 부른다)."""
    out = {}
    for s in plan["sections"]:
        key = s["name"]
        if s["sung"] and key not in out:
            out[key] = s["syllables"]
    return out


def _prompt(theme: str, targets: dict) -> str:
    spec = "\n".join(f'- "{k}": {len(v)}줄. 줄마다 음절 수 = {v}' for k, v in targets.items())
    keys = ", ".join(f'"{k}": [{{"t": 가사 한 줄, "n": 그 줄의 한글 글자 수}}, ...]' for k in targets)
    return (
        f"주제: {theme}\n"
        "이 주제로 차 안에서 듣는 한국어 노래를 만든다. JSON 으로만 답해.\n"
        f'{{"title": 짧은 한국어 제목, "style": 영어 음악 스타일 태그 6~8개(장르·악기·분위기, male vocal 또는 female vocal 하나 포함)를 쉼표로, '
        f'"cover": 앨범 표지 그림 설명(영어 한 문장, 장면·색·빛만. 간판·표지판·책·화면처럼 글자가 생길 만한 물건은 넣지 마), {keys}}}\n'
        f"줄 수와 음절 수:\n{spec}\n"
        "음절 수 = 한글 글자 수(띄어쓰기 빼고). 쓰고 나서 글자를 세어 n 에 적고, 목표와 다르면 단어를 바꿔 맞춰. ±1까지만 허용.\n"
        "후렴(chorus)은 기억에 남는 말로 쓰되, 같은 줄을 두 번 넘게 반복하지 마.\n"
        "기존 노래 가사를 베끼지 말고 새로 써. 가사에 문장부호·영어·숫자는 쓰지 마."
    )


def _line(x) -> str:
    return str(x.get("t", "") if isinstance(x, dict) else x).strip()


async def _ask(session: aiohttp.ClientSession, prompt: str, seed: int) -> dict:
    body = {"model": MODEL, "stream": False, "format": "json", "think": False, "keep_alive": 0,
            "options": {"temperature": 0.8, "seed": seed}, "messages": [{"role": "user", "content": prompt}]}
    async with session.post(OLLAMA + "/api/chat", json=body, timeout=aiohttp.ClientTimeout(total=300)) as r:
        r.raise_for_status()
        return json.loads((await r.json())["message"]["content"])


def _miss(out: dict, targets: dict) -> float:
    """음절 목표에서 벗어난 정도(줄마다 차이의 평균 비율)."""
    diffs = []
    for k, want in targets.items():
        lines = [_line(x) for x in out.get(k) or []]
        for i, w in enumerate(want):
            got = _syllables(lines[i]) if i < len(lines) else 0
            diffs.append(abs(got - w) / max(1, w))
    return sum(diffs) / max(1, len(diffs))


async def write(theme: str, plan: dict, seed: int) -> dict:
    """반환: {"title", "style", "cover", "sections": {이름: [줄]}, "miss": 음절 어긋남}. 두 번 써 보고 음절이 더 맞는 쪽,
    그다음 어긋난 줄만 골라 글자 수를 맞춰 다시 쓴다(짧은 줄 목표에서 LLM 이 늘 길게 써서 — 2026-11-06 측정 4글자 목표에 6~9글자)."""
    targets = _targets(plan)
    async with aiohttp.ClientSession() as session:
        best = None
        for attempt in range(2):
            out = await _ask(session, _prompt(theme, targets), seed + attempt)
            miss = _miss(out, targets)
            if best is None or miss < best["miss"]:
                best = {"title": str(out.get("title", "")).strip() or theme.split(",")[0],
                        "style": str(out.get("style", "")).strip(),
                        "cover": str(out.get("cover", "")).strip(),
                        "sections": {k: [_line(l) for l in (out.get(k) or [])][:len(v)] for k, v in targets.items()},
                        "miss": round(miss, 2)}
            if miss < 0.25:
                break
        for round_ in range(3):
            if best["miss"] < 0.2:
                break
            best["sections"] = await _fix_lines(session, theme, best["sections"], targets, seed + 10 + round_)
            best["miss"] = round(_miss(best["sections"], targets), 2)
    return best


async def _fix_lines(session: aiohttp.ClientSession, theme: str, sections: dict, targets: dict, seed: int) -> dict:
    """글자 수가 목표와 2자 이상 다른 줄만 모아 한 번에 고쳐 쓰게 하고, 줄마다 목표에 더 가까워진 것만 받는다."""
    todo = []
    for k, want in targets.items():
        lines = sections.get(k) or []
        for i, w in enumerate(want):
            got = _syllables(lines[i]) if i < len(lines) else 0
            if abs(got - w) >= 2:
                todo.append((k, i, lines[i] if i < len(lines) else "", w))
    if not todo:
        return sections
    # 그냥 같은 줄을 되돌려 주는 경우가 있어(2026-11-06) — 지금 몇 글자인지, 몇 글자 줄이거나 늘릴지 적어 준다
    def how(line, w):
        d = _syllables(line) - w
        return f"지금 {_syllables(line)}글자 → {d}글자 줄여서" if d > 0 else f"지금 {_syllables(line)}글자 → {-d}글자 늘려서"
    ask = "\n".join(f'{n + 1}. "{line}" ({how(line, w)}) 정확히 {w}글자로' for n, (_, _, line, w) in enumerate(todo))
    prompt = (f"주제: {theme}\n노래 가사 줄을 글자 수(한글 글자, 띄어쓰기 빼고)에 맞게 다시 써. 뜻과 분위기는 살리고 문장부호·영어·숫자는 쓰지 마.\n"
              "줄은 말이 중간에 끊기지 않게 완결된 말로 끝내(예: '~해', '~네', '~요', 명사). '떠올리'·'떨어지'처럼 끊긴 말은 안 돼.\n"
              "짧게 줄이는 예: '차창에 맺힌 이슬이'(8) → 4글자 '맺힌 이슬' / '비가 내리는 밤'(6) → 4글자 '비 오는 밤' / '이별을 떠올리게 해'(8) → 4글자 '널 떠올려'.\n"
              f"{ask}\n"
              '{"lines": [고친 줄, ...]} 형식 JSON 으로만, 위 순서대로.')
    try:
        out = await _ask(session, prompt, seed)
        fixed = [_line(x) for x in (out.get("lines") or [])]
    except Exception:
        return sections
    result = {k: list(v) for k, v in sections.items()}
    for (k, i, old, w), new in zip(todo, fixed):
        if new and abs(_syllables(new) - w) < abs(_syllables(old) - w):
            while len(result.setdefault(k, [])) <= i:
                result[k].append("")
            result[k][i] = new
    return result


def yue_lyrics(plan: dict, sections: dict) -> str:
    """YuE2 가사 형식: 노래 구간 순서대로 [verse] / [chorus] / [bridge] 묶음."""
    blocks = []
    for s in plan["sections"]:
        if not s["sung"]:
            continue
        lines = sections.get(s["name"]) or sections.get(s["name"].rstrip("2")) or []
        blocks.append(f"[{s['name'].rstrip('2')}]\n" + "\n".join(lines))
    return "\n\n".join(blocks)


# ---------------------------------------------------------------- 말로 고치기 · 구간 이름 정리 (2026-11-06)

# 사용자가 고친 가사 머리말 → 곡 계획의 구간 이름. 모르는 머리말은 버리고 알려 준다(별칭은 정규화, 모르는 머리말은 무시)
SECTION_ALIASES = {
    "verse": "verse", "verse1": "verse", "절": "verse", "절1": "verse", "1절": "verse",
    "verse2": "verse2", "절2": "verse2", "2절": "verse2",
    "chorus": "chorus", "hook": "chorus", "후렴": "chorus", "후렴구": "chorus", "코러스": "chorus",
    "bridge": "bridge", "브리지": "bridge", "브릿지": "bridge",
}


def normalize_sections(sections: dict, plan: dict) -> tuple[dict, list]:
    """{머리말: [줄]} → {계획 구간 이름: [줄]}. 줄에서 괄호 지시·문장부호를 걷어 낸다. 반환: (정리된 가사, 버린 머리말)."""
    wanted = {s["name"] for s in plan["sections"] if s["sung"]}
    out, dropped = {}, []
    for head, lines in (sections or {}).items():
        key = SECTION_ALIASES.get("".join(str(head).lower().split()).strip("[]"))
        if key is None or key not in wanted and key.rstrip("2") not in wanted:
            dropped.append(str(head))
            continue
        clean = []
        for line in lines if isinstance(lines, list) else str(lines).splitlines():
            t = str(line).strip()
            if not t or (t.startswith("[") and t.endswith("]")):
                continue  # [ ] 는 지시라 부르지 않는다
            clean.append(t.replace("[", "").replace("]", ""))
        if clean:
            out[key] = clean
    return out, dropped


def _edit_prompt(request: str, current: dict) -> str:
    return (
        "사용자가 방금 들은 노래를 이렇게 바꿔 달라고 했다: \"" + request + "\"\n"
        "지금 노래 설정: " + json.dumps(current, ensure_ascii=False) + "\n"
        "어떤 설정을 바꾸면 되는지 JSON 으로만 답해. 바꾸지 않을 것은 null.\n"
        '{"style_add": 더할 영어 스타일 태그(쉼표, 없으면 ""), "style_remove": 뺄 영어 태그(쉼표, 없으면 ""), '
        '"style_weight": 0~100 또는 null(장르 색을 진하게=높게), "melody_weight": 0~100 또는 null(내 멜로디를 살려=80 이상, 멜로디를 새롭게=50), '
        '"voice": "auto"|"female"|"male"|"none"(연주곡) 또는 null, "seconds": 60|120|180 또는 null, '
        '"new_lyrics": 가사를 새로 써야 하면 true, "new_theme": 주제를 바꿔야 하면 새 주제 한국어 한 문장 아니면 null, '
        '"summary": 이번에 바꾼 것을 사용자에게 알려 줄 한국어 한 문장(~했어요)}\n'
        "요청에 없는 것은 null 로 둬(지금 값을 다시 적지 마). '멜로디는 살려'면 melody_weight 를 80 이상으로.\n"
        "분위기 말(잔잔하게·신나게·어둡게·몽환적으로 등)은 영어 분위기·템포 태그(calm, mellow, upbeat, energetic, dark, dreamy 등)로 style_add 에 넣어. "
        "악기를 빼라면 그 악기 영어 이름을 style_remove 에."
    )


async def interpret_edit(request: str, current: dict, seed: int) -> dict:
    """말로 한 수정 요청 → 설정 변경(사용자는 결과만 말하고 작업 종류는 모델이 고른다). 값은 범위 안으로 자른다."""
    async with aiohttp.ClientSession() as session:
        out = await _ask(session, _edit_prompt(request, current), seed)

    def clamp(v, lo, hi):
        try:
            return None if v is None else max(lo, min(hi, int(v)))
        except (TypeError, ValueError):
            return None

    # 요청에 목소리·길이 말이 없는데 모델이 지금 값을 되적는 경우가 있다(2026-11-06 시험: "드럼 빼고…" → voice female) — 말이 있을 때만 받는다
    low = request.lower()
    said_voice = any(w in low for w in ("목소리", "보컬", "남자", "여자", "남성", "여성", "연주곡", "가사 없이", "voice", "vocal", "male", "female", "instrumental"))
    said_len = any(w in low for w in ("분", "길게", "짧게", "길이", "minute", "longer", "shorter"))
    voice = out.get("voice") if said_voice and out.get("voice") in ("auto", "female", "male", "none") and out.get("voice") != current.get("voice") else None
    seconds = out.get("seconds") if said_len and out.get("seconds") in (60, 120, 180) and out.get("seconds") != current.get("seconds") else None
    theme = str(out.get("new_theme") or "").strip() or None
    same = lambda k: (lambda v: None if v is None or v == current.get(k) else v)(clamp(out.get(k), 0, 100))
    return {"style_add": str(out.get("style_add") or "").strip(), "style_remove": str(out.get("style_remove") or "").strip(),
            "style_weight": same("style_weight"), "melody_weight": same("melody_weight"),
            "voice": voice, "seconds": seconds, "new_lyrics": bool(out.get("new_lyrics")) or theme is not None, "new_theme": theme,
            "summary": str(out.get("summary") or "").strip() or _summary(out, voice, seconds, theme)}


def _summary(out: dict, voice, seconds, theme) -> str:
    """모델이 요약을 빼먹으면 바뀐 것으로 한 줄을 만든다."""
    bits = []
    if out.get("style_add"):
        bits.append(f"스타일에 {out['style_add']} 를 더하고")
    if out.get("style_remove"):
        bits.append(f"{out['style_remove']} 를 빼고")
    if voice:
        bits.append({"male": "남성 목소리로", "female": "여성 목소리로", "none": "연주곡으로", "auto": "목소리는 자동으로"}[voice])
    if seconds:
        bits.append(f"{seconds // 60}분 길이로")
    if theme:
        bits.append(f"주제를 '{theme}'(으)로 바꿔")
    return (" ".join(bits) + " 새 버전을 만들었어요.") if bits else "말씀하신 대로 바꿔 새 버전을 만들었어요."


def edit_style(style: str, add: str, remove: str) -> str:
    drop = [t.strip().lower() for t in remove.split(",") if t.strip()]
    # "drum" 이 "drums"·"drum machine" 도 빼도록 낱말 포함으로 본다
    tags = [t.strip() for t in style.split(",") if t.strip() and not any(d in t.strip().lower() or t.strip().lower() in d for d in drop)]
    for t in add.split(","):
        if t.strip() and t.strip().lower() not in {x.lower() for x in tags}:
            tags.append(t.strip())
    return ", ".join(tags)
