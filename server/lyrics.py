"""주제(프롬프트) → 노래 제목 · 스타일 태그 · 가사. 이 PC 의 Ollama(gemma3:12b, CHAJAK_LYRICS_MODEL)로 만든다(외부 API 없음).

가사 줄 수·줄마다 음절 목표는 2분 곡 계획(PleosSongForm plan)에서 온다: 2마디가 가사 한 줄, 그 2마디 음 개수가 음절 목표.
한국어는 한 음에 한 음절이 기본이라 음절 수를 맞추면 노래가 멜로디에 잘 붙는다.
"""

from __future__ import annotations

import json
import re
import os
import random

import aiohttp

OLLAMA = os.environ.get("CHAJAK_OLLAMA", "http://127.0.0.1:11434")
# 2026-11-06 비교: gemma3:12b 가 한국어 가사·말로 바꾸기 모두 qwen3:14b·exaone3.5 보다 자연스러움
MODEL = os.environ.get("CHAJAK_LYRICS_MODEL", "gemma3:12b")

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
    """가사 한 줄 정리. 모델이 프롬프트의 글자 수 표시 '(9)'·나눔 표시 '/'·'⁄' 를 따라 적는 경우가 있어 지운다(gemma3, 2026-11-06)."""
    t = str(x.get("t", "") if isinstance(x, dict) else x)
    t = re.sub(r"\(\s*\d+\s*\)|[/⁄|]", " ", t)
    return re.sub(r"\s+", " ", t).strip()


async def _ask(session: aiohttp.ClientSession, prompt: str, seed: int, keep: str | int = 0, temperature: float = 0.8) -> dict:
    """keep: 모델을 메모리에 둘 시간. 여러 번 연달아 부를 때는 "2m", 마지막엔 0(노래 엔진에 GPU 를 돌려준다)."""
    body = {"model": MODEL, "stream": False, "format": "json", "think": False, "keep_alive": keep,
            "options": {"temperature": temperature, "seed": seed}, "messages": [{"role": "user", "content": prompt}]}
    async with session.post(OLLAMA + "/api/chat", json=body, timeout=aiohttp.ClientTimeout(total=300)) as r:
        r.raise_for_status()
        return json.loads((await r.json())["message"]["content"])


# 글자 수 허용 범위: 목표보다 1 적게 ~ 2 많게. 노래는 한 음에 두 글자를 붙여 부를 수 있어서,
# 정확히 맞추느라 말을 토막 내는 것보다 자연스러운 문장이 낫다(2026-11-06 "가사가 너무 별로" 진단).
SLACK_UNDER, SLACK_OVER = 1, 2


def _off(got: int, w: int) -> int:
    """허용 범위를 벗어난 글자 수(범위 안이면 0)."""
    return max(0, (w - SLACK_UNDER) - got, got - (w + SLACK_OVER))


def _miss(out: dict, targets: dict) -> float:
    """음절 목표에서 벗어난 정도(줄마다 차이의 평균 비율)."""
    diffs = []
    for k, want in targets.items():
        lines = [_line(x) for x in out.get(k) or []]
        for i, w in enumerate(want):
            got = _syllables(lines[i]) if i < len(lines) else 0
            diffs.append(_off(got, w) / max(1, w))
    return sum(diffs) / max(1, len(diffs))


async def write_v1(theme: str, plan: dict, seed: int) -> dict:
    """(옛 방식, 비교용) 반환: {"title", "style", "cover", "sections": {이름: [줄]}, "miss": 음절 어긋남}. 두 번 써 보고 음절이 더 맞는 쪽,
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
    """글자 수가 허용 범위(목표-1 ~ 목표+2) 밖인 줄만 모아 한 번에 고쳐 쓰게 하고, 줄마다 범위에 더 가까워진 것만 받는다."""
    todo = []
    for k, want in targets.items():
        lines = sections.get(k) or []
        for i, w in enumerate(want):
            got = _syllables(lines[i]) if i < len(lines) else 0
            if _off(got, w) > 0:
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
        if new and _off(_syllables(new), w) < _off(_syllables(old), w):
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
        '"new_lyrics": 가사를 새로 써야 하면 true, "new_theme": 주제를 바꾸라고 했을 때만 새 주제(짧은 한국어 구, 예: "바닷가 여행") 아니면 null, '
        '"summary": 이번에 바꾼 것을 사용자에게 알려 줄 한국어 한 문장(~했어요)}\n'
        "요청에 없는 것은 null 로 둬(지금 값을 다시 적지 마). 요청에 없는 분위기 태그도 더하지 마. '멜로디는 살려'면 melody_weight 를 80 이상으로.\n"
        "'내 멜로디가 잘 안 들려'·'내가 부른 거랑 달라'처럼 멜로디가 약하다는 말이면 melody_weight 를 지금보다 15 이상 올려(최대 100).\n"
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
    said_words = any(w in low for w in ("가사", "주제", "이야기", "내용", "노랫말", "lyric"))
    voice = out.get("voice") if said_voice and out.get("voice") in ("auto", "female", "male", "none") and out.get("voice") != current.get("voice") else None
    seconds = out.get("seconds") if said_len and out.get("seconds") in (60, 120, 180) and out.get("seconds") != current.get("seconds") else None
    theme = str(out.get("new_theme") or "").strip() or None
    if theme and (theme == current.get("theme") or len(theme) > 40 or theme.endswith(("습니다.", "했어요.", "어요.", "요."))):
        theme = None if theme == current.get("theme") else re.sub(r"(으로|로)? ?(가사를 )?(바꿨|변경했|바꾸었)\S*$", "", theme.rstrip(".")).strip() or None
    same = lambda k: (lambda v: None if v is None or v == current.get(k) else v)(clamp(out.get(k), 0, 100))
    return {"style_add": str(out.get("style_add") or "").strip(), "style_remove": str(out.get("style_remove") or "").strip(),
            "style_weight": same("style_weight"), "melody_weight": same("melody_weight"),
            "voice": voice, "seconds": seconds, "new_lyrics": said_words and (bool(out.get("new_lyrics")) or theme is not None), "new_theme": theme if said_words else None,
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


# ---------------------------------------------------------------- 작사 v2 (2026-11-06 "가사가 너무 별로" → 단계를 나눔)
#
# 옛 방식은 주제만 주고 한 번에 줄을 쓰게 해서 ① 이야기·훅이 없고 ② 글자 수를 맞추느라 말이 토막 나고 ③ 흔한 말이 많았다.
# v2: 이야기 설계(장면·감정 흐름·훅 한 줄) → 초안 2개(설계를 따라, 글자 수 ±1) → 심사(더 나은 쪽) → 어긋난 줄만 고쳐 쓰기.

CLICHES = "눈물, 영원, 그대, 사랑해, 운명, 별빛, 가슴이 아파, 너 없이, 추억 속에"

LYRIC_CRAFT = (
    "잘 쓴 가사의 조건:\n"
    "- 차 안에서 보이고 들리는 구체적인 것(와이퍼, 신호등, 라디오, 창밖 불빛, 핸들 쥔 손)으로 장면을 그린다. 추상어만 늘어놓지 않는다.\n"
    "- 줄마다 완결된 말로 끝낸다(~해, ~네, ~요, ~지, 명사). '떠올리', '지나가' 처럼 끊긴 말 금지.\n"
    "- 말하듯 자연스러운 한국어 문장으로 쓴다. 조사(이·가·을·를·에·은·는·도)를 빼고 낱말만 늘어놓지 않는다('핸들 바람 가볍게' 같은 줄 금지 → '핸들 위로 바람이 가벼워').\n"
    "- 후렴 첫 줄은 훅이다. 후렴 안에서 한 번 더 되풀이해도 좋다. 짧고 입에 붙게.\n"
    "- 같은 구간의 줄 끝소리를 비슷하게 맞추면(~해/~해, ~네/~네) 부르기 좋다.\n"
    f"- 흔한 말은 피한다: {CLICHES}.\n"
    "- 문장부호·영어·숫자 금지. 기존 노래 가사를 베끼지 않는다.\n"
    "예(형식만 참고. 이 예의 낱말·문장은 절대 쓰지 마): 절 '와이퍼가 박자 맞춰'(8) / '빨간불에 멈춘 숨'(7)."
)


def _story_prompt(theme: str, targets: dict) -> str:
    hook_len = (targets.get("chorus") or [6])[0]
    parts = ", ".join(f'"{k}": 이 구간에서 일어나는 장면·감정 한 문장' for k in targets)
    return (
        f"주제: {theme}\n"
        "이 주제로 차 안에서 듣는 한국어 노래를 기획한다. 가사를 쓰기 전에 이야기를 먼저 짠다. JSON 으로만 답해.\n"
        f'{{"title": 짧은 한국어 제목, "speaker": 노래하는 사람과 상황 한 문장, "story": {{{parts}}}, '
        f'"hook": 후렴 첫 줄이 될 훅(한글 {hook_len}글자 안팎, 완결된 말), '
        '"images": 가사에 쓸 구체적인 소리·장면 낱말 6개(배열), '
        '"style": 영어 음악 스타일 태그 6~8개(장르·악기·분위기, male vocal 또는 female vocal 하나 포함)를 쉼표로, '
        '"cover": 앨범 표지 그림 설명(영어 한 문장, 장면·색·빛만. 간판·표지판·책·화면처럼 글자가 생길 만한 물건은 넣지 마)}\n'
        "절은 장면에서 시작해 후렴에서 감정이 터지고, 2절은 한 걸음 나아가고, 브리지는 한 번 뒤집는 흐름으로.\n" + LYRIC_CRAFT
    )


def _draft_prompt(theme: str, story: dict, targets: dict) -> str:
    spec = "\n".join(f'- "{k}": {len(v)}줄, 줄마다 한글 글자 수 목표 {v}(목표보다 1 적게 ~ 2 많게까지 괜찮음, 말이 자연스러운 쪽). 장면: {story.get("story", {}).get(k, "")}'
                     for k, v in targets.items())
    keys = ", ".join(f'"{k}": [줄, ...]' for k in targets)
    return (
        f"주제: {theme}\n노래하는 사람: {story.get('speaker', '')}\n훅(후렴 첫 줄): {story.get('hook', '')}\n"
        f"쓸 수 있는 낱말: {', '.join(map(str, story.get('images') or []))}\n"
        f"이 기획대로 가사를 쓴다. JSON 으로만 답해: {{{keys}}}\n"
        f"구간별 줄 수·글자 수(띄어쓰기 빼고 센 한글 글자):\n{spec}\n"
        "글자 수가 적은 줄은 조사·어미를 살려 짧은 완결된 말로(예: 4글자 '비가 와요', '집에 가자'). 쓰고 나서 글자를 세어 맞춰.\n"
        + LYRIC_CRAFT
    )


# 멜로디 한 줄이 짧으면(평균 7음 미만) 한국어가 토막 난다("핸들 손 잠기", "라디오 익숙" — 2026-11-06).
# 짧은 줄 두 개를 한 문장으로 쓰게 하고, 띄어쓰기 자리에서 두 줄로 나눈다(노래에선 문장이 줄을 넘어가는 게 자연스럽다).
PAIR_UNDER = 7


def _pair(targets: dict) -> tuple[dict, dict]:
    """줄 목표 → 문장 목표. 반환: (문장 목표 {구간: [글자 수]}, 나누는 법 {구간: [[줄 목표, ...], ...]})."""
    sent, groups = {}, {}
    for k, want in targets.items():
        if not want or sum(want) / len(want) >= PAIR_UNDER:
            g = [[w] for w in want]
        else:
            g = [want[i:i + 2] for i in range(0, len(want), 2)]
        groups[k] = g
        sent[k] = [sum(x) for x in g]
    return sent, groups


def _split(line: str, want: list) -> list:
    """한 문장을 줄 목표대로 나눈다. 띄어쓰기 자리 중 앞쪽 글자 수가 목표에 가장 가까운 곳에서 자른다."""
    if len(want) == 1:
        return [line]
    words = line.split()
    if len(words) < 2:
        # 띄어쓰기가 없으면 글자 수로 자른다(드묾)
        cut, n = 0, 0
        for cut, c in enumerate(line):
            if "가" <= c <= "힣":
                n += 1
            if n >= want[0]:
                break
        return [line[:cut + 1], line[cut + 1:].strip() or line[:cut + 1]]
    best = min(range(1, len(words)), key=lambda i: abs(_syllables(" ".join(words[:i])) - want[0]))
    return [" ".join(words[:best]), " ".join(words[best:])]


def _unpair(sections: dict, groups: dict) -> dict:
    out = {}
    for k, g in groups.items():
        lines = sections.get(k) or []
        out[k] = [part for i, want in enumerate(g) for part in _split(lines[i] if i < len(lines) else "", want)]
    return out


def _polish_prompt(theme: str, sections: dict) -> str:
    shown = "\n".join(f'{k}: ' + " / ".join(f"{l}({_syllables(l)})" for l in v) for k, v in sections.items())
    keys = ", ".join(f'"{k}": [줄, ...]' for k in sections)
    return (
        f"주제: {theme}\n아래 노래 가사를 한국어 작사가처럼 다듬어. 괄호 안은 줄마다 한글 글자 수다.\n{shown}\n\n"
        "낱말만 늘어놓은 줄, 조사가 빠진 줄, 말이 끊긴 줄, 뜻이 안 통하는 줄만 자연스러운 문장으로 고치고 좋은 줄은 그대로 둬. "
        "줄마다 글자 수는 지금과 같게(1 적게 ~ 2 많게까지). 줄 수·구간은 그대로. 문장부호·영어·숫자 금지.\n"
        f"JSON 으로만: {{{keys}}}"
    )


async def _polish(session: aiohttp.ClientSession, theme: str, sections: dict, targets: dict, seed: int) -> dict:
    """어색한 줄 다듬기. 글자 수 허용 범위에서 더 멀어지지 않은 줄만 받는다."""
    try:
        out = await _ask(session, _polish_prompt(theme, sections), seed, keep="2m", temperature=0.5)
    except Exception:
        return sections
    result = {k: list(v) for k, v in sections.items()}
    for k, want in targets.items():
        new = [_line(x) for x in (out.get(k) or [])]
        for i, w in enumerate(want):
            if i < len(new) and i < len(result.get(k, [])) and new[i] and _off(_syllables(new[i]), w) <= _off(_syllables(result[k][i]), w):
                result[k][i] = new[i]
    return result


def _judge_prompt(theme: str, drafts: list) -> str:
    shown = "\n\n".join(f"[{i}]\n" + "\n".join(f"{k}: " + " / ".join(v) for k, v in d.items()) for i, d in enumerate(drafts))
    return (
        f"주제: {theme}\n아래 노래 가사 후보 중 더 좋은 하나를 골라. JSON 으로만: "
        '{"best": 번호, "scores": [[자연스러움, 장면, 훅, 주제] 후보마다 1~10], "why": 한 문장}\n'
        "기준: 말이 토막 나지 않고 자연스러운가, 구체적인 장면이 보이는가, 후렴 훅이 기억에 남는가, 주제와 이어지는가. "
        f"흔한 말({CLICHES})이 많으면 감점.\n\n{shown}"
    )


async def write(theme: str, plan: dict, seed: int) -> dict:
    """작사 v2. 반환: {"title", "style", "cover", "sections", "miss", "hook", "judge"}. 약 40~60초(옛 방식 약 20초)."""
    lines = _targets(plan)
    targets, groups = _pair(lines)   # 아래는 모두 문장 단위로 쓰고, 끝에 줄로 나눈다
    async with aiohttp.ClientSession() as session:
        try:
            story = await _ask(session, _story_prompt(theme, targets), seed, keep="2m", temperature=0.9)
            drafts = []
            for i in range(2):
                out = await _ask(session, _draft_prompt(theme, story, targets), seed + 1 + i, keep="2m")
                drafts.append({k: [_line(l) for l in (out.get(k) or [])][:len(v)] for k, v in targets.items()})
            judge = await _ask(session, _judge_prompt(theme, drafts), seed + 5, keep="2m", temperature=0.2)
            best = judge.get("best") if isinstance(judge.get("best"), int) and 0 <= judge.get("best") < len(drafts) else \
                min(range(len(drafts)), key=lambda i: _miss(drafts[i], targets))
            sections = drafts[best]
            for round_ in range(2):
                if _miss(sections, targets) < 0.2:
                    break
                sections = await _fix_lines(session, theme, sections, targets, seed + 10 + round_)
            sections = await _polish(session, theme, sections, targets, seed + 20)
        finally:
            # 노래 엔진(YuE2)이 GPU 를 쓰도록 작사 모델을 내린다
            try:
                async with session.post(OLLAMA + "/api/generate", json={"model": MODEL, "keep_alive": 0},
                                        timeout=aiohttp.ClientTimeout(total=30)) as r:
                    await r.read()
            except Exception:
                pass
    return {"title": str(story.get("title", "")).strip() or theme.split(",")[0],
            "style": str(story.get("style", "")).strip(), "cover": str(story.get("cover", "")).strip(),
            "sections": _unpair(sections, groups), "miss": round(_miss(sections, targets), 2),
            "hook": str(story.get("hook", "")).strip(), "judge": str(judge.get("why", "")).strip()}
