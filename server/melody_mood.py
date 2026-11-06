"""음표 → 멜로디 성격(에너지 × 밝기) → 노래 프롬프트 맞춤.

사용자 요청(2026-11-06): "음표를 만들었을 때 어떤 음표인지 알고, 프롬프트도 비슷한 느낌으로 만들어 줘야 한 번에 제대로 된 멜로디를 만드니까".
지금까지 스타일 태그·가사 분위기는 주제 글만 보고 정해서, 느리고 쓸쓸한 허밍에 빠른 팝 반주가 붙곤 했다.

흐름(서버):
  허밍 → ComfyUI(SheetSage2) → 트랙 악보(ABC) → melody_notes.notes() → analyze() → 곡 응답 tracks[n].mood · song.mood
  노래 만들기: 실제로 쓰는 악보(1번 트랙 또는 AI 가 다시 쓴 8마디)를 다시 analyze()
             → card() 를 작사 LLM 의 이야기 설계 프롬프트에, style_for() 를 스타일 태그 앞쪽에, 빛 낱말을 표지 장면에.

판단은 규칙이라 같은 멜로디면 같은 성격이 나온다. 범위·가중치는 허밍 기준 시작값이고 기준 허밍으로 보정한다(기획 17번 5절).
"""

from __future__ import annotations

import re
import statistics as st

MIN_NOTES, MIN_SEC = 6, 4.0

# 특성마다 (잔잔 끝, 격정 끝, 에너지 가중치). 이 범위를 0~1 로 펴서 가중합 → 에너지 0~100
SCALE = {
    "tempo": (70.0, 160.0, 0.30),    # 허밍 템포(BPM)
    "density": (0.8, 4.0, 0.30),     # 초당 음 수(허밍은 보통 1~3개)
    "leap": (1.0, 6.0, 0.15),        # 이웃 음 간격 평균(반음)
    "range": (5.0, 19.0, 0.15),      # 가장 높은 음 - 가장 낮은 음(반음)
    "rhythm": (0.0, 1.0, 0.10),      # 음 길이 표준편차 / 평균(들쭉날쭉)
}
LEVELS = ((35, "잔잔"), (65, "중간"), (101, "격정"))
BRIGHT_MIN = 0.05  # 장조·단조 상관값 차이가 이보다 작으면 "애매"

NAMES = {
    ("잔잔", "밝음"): "포근한", ("잔잔", "어두움"): "쓸쓸한", ("잔잔", "애매"): "잔잔한",
    ("중간", "밝음"): "산뜻한", ("중간", "어두움"): "아련한", ("중간", "애매"): "부드러운",
    ("격정", "밝음"): "신나는", ("격정", "어두움"): "강렬한", ("격정", "애매"): "힘찬",
}

# 성격 → (장르·악기 태그, 목소리 창법 태그, 표지 빛). 노래 엔진(YuE2)은 스타일 태그로 반주·창법을 정한다
STYLES = {
    "포근한": ("acoustic ballad, soft piano, warm acoustic guitar", "soft vocal", "warm golden sunset light"),
    "쓸쓸한": ("piano ballad, lo-fi, melancholic strings", "breathy soft vocal", "blue evening light, light rain"),
    "잔잔한": ("calm ballad, soft piano, ambient pad", "soft vocal", "soft blue dusk light"),
    "산뜻한": ("acoustic pop, bright acoustic guitar, light percussion", "clear airy vocal", "clear bright daylight"),
    "아련한": ("indie pop, dreamy synth pad, r&b", "airy mellow vocal", "hazy dawn light, soft neon glow"),
    "부드러운": ("soft pop, electric piano, mellow groove", "smooth vocal", "soft afternoon light"),
    "신나는": ("upbeat pop, dance beat, funky bass", "bright energetic vocal", "vivid colors, strong sunlight"),
    "강렬한": ("rock, electric guitar, driving drums", "powerful belting vocal", "high contrast, red light, dramatic shadows"),
    "힘찬": ("pop rock, punchy drums, electric guitar", "strong vocal", "bold bright light"),
}
TEMPO_TAG = ((90, "slow tempo"), (125, "mid tempo"), (999, "fast tempo"))
# 앱에 보여 줄 장르 이름(STYLES 의 장르·악기를 한국어로)
GENRE_KO = {"포근한": "어쿠스틱 발라드", "쓸쓸한": "피아노 발라드", "잔잔한": "잔잔한 발라드", "산뜻한": "어쿠스틱 팝",
            "아련한": "몽환적인 인디 팝", "부드러운": "소프트 팝", "신나는": "업비트 댄스 팝", "강렬한": "록", "힘찬": "팝 록"}

# 주제에 장르를 직접 적었으면 그 말을 따른다(일부러 대비를 원했을 수 있다) — 장르 태그를 앞에 넣지 않는다
GENRE_WORDS = ("록", "락", "발라드", "힙합", "랩", "재즈", "어쿠스틱", "팝", "댄스", "트로트", "알앤비", "R&B", "EDM", "일렉", "시티팝",
               "로파이", "포크", "펑크", "메탈", "보사노바", "lo-fi", "rock", "ballad", "hip hop", "jazz", "acoustic", "pop", "dance",
               "edm", "r&b", "funk", "metal", "folk")

# Krumhansl 조성 틀(으뜸음 = 0)
_MAJOR = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
_MINOR = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
_PC = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]
_PC_SHARP = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
_PC_FLAT = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]
_NOTE = {"C": 0, "B#": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "Fb": 4, "F": 5, "E#": 5, "F#": 6, "Gb": 6,
         "G": 7, "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11, "Cb": 11}


def _major_tonic(key: str | None) -> int | None:
    """악보 조 표기(K: 의 값, 예: "C#", "Am", "Bbmin") → 그 조표의 장조 으뜸음(0~11). 모르면 None."""
    m = re.match(r"^([A-G][#b]?)(m|min|minor)?$", (key or "").strip())
    if not m or m.group(1) not in _NOTE:
        return None
    pc = _NOTE[m.group(1)]
    return (pc + 3) % 12 if m.group(2) else pc


def _corr(a: list, b: list) -> float:
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    num = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    den = (sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b)) ** 0.5
    return num / den if den else 0.0


def _key(notes: list, key: str | None = None, transpose: int = 0) -> tuple[float, str]:
    """반환: (장조 상관 - 단조 상관, 조 이름). 음 분포는 음 길이로 가중한다.
    악보 조 표기(key)가 있으면 그 조표 안에서 장조 으뜸음과 나란한조 단조 으뜸음만 비교한다 — 앱이 보여 주는 조와
    성격의 장·단조가 어긋나지 않게(2026-11-06 "C#장조인데 단조라 어두워요"). 없으면 24개 조를 다 본다."""
    pc = [0.0] * 12
    for n in notes:
        pc[n["midi"] % 12] += n["dur"]
    rot = lambda p, k: p[-k:] + p[:-k] if k else p
    major_tonic = _major_tonic(key)
    if major_tonic is not None:
        t = (major_tonic + transpose) % 12
        r = (t + 9) % 12
        names = _PC_FLAT if "b" in (key or "")[1:2] else _PC_SHARP
        maj, mnr = _corr(pc, rot(_MAJOR, t)), _corr(pc, rot(_MINOR, r))
        return maj - mnr, (f"{names[t]}장조" if maj >= mnr else f"{names[r]}단조")
    major = max((_corr(pc, rot(_MAJOR, k)), k) for k in range(12))
    minor = max((_corr(pc, rot(_MINOR, k)), k) for k in range(12))
    name = f"{_PC[major[1]]}장조" if major[0] >= minor[0] else f"{_PC[minor[1]]}단조"
    return major[0] - minor[0], name


def _unit(name: str, x: float) -> float:
    lo, hi, _ = SCALE[name]
    return max(0.0, min(1.0, (x - lo) / (hi - lo)))


def features(notes: list, tempo: float, key: str | None = None, transpose: int = 0) -> dict:
    ns = sorted(notes, key=lambda n: n["start"])
    span = max(1e-6, ns[-1]["start"] + ns[-1]["dur"] - ns[0]["start"])
    beat = 60.0 / max(1.0, tempo)
    iv = [abs(b["midi"] - a["midi"]) for a, b in zip(ns, ns[1:])]
    durs = [n["dur"] / beat for n in ns]
    midis = [n["midi"] for n in ns]
    bright, key_name = _key(ns, key, transpose)
    return {
        "tempo": round(tempo), "density": round(len(ns) / span, 2), "leap": round(st.mean(iv), 2) if iv else 0.0,
        "big_leaps": round(sum(1 for x in iv if x >= 5) / max(1, len(iv)), 2), "range": max(midis) - min(midis),
        "rhythm": round(st.pstdev(durs) / st.mean(durs), 2) if st.mean(durs) else 0.0,
        "pitch_mean": round(st.mean(midis), 1), "bright": round(bright, 3), "key": key_name,
        "notes": len(ns), "seconds": round(span, 1),
    }


# 이유 한 줄용 말(낮을 때, 높을 때)
_WHY = {
    "tempo": (lambda f: f"느린 템포({f['tempo']})", lambda f: f"빠른 템포({f['tempo']})"),
    "density": (lambda f: "음이 듬성듬성해요", lambda f: "음이 촘촘해요"),
    "leap": (lambda f: "음이 한 칸씩 이어져요", lambda f: "음이 크게 뛰어요"),
    "range": (lambda f: f"음폭이 좁아요({f['range']}반음)", lambda f: f"음폭이 넓어요({f['range']}반음)"),
    "rhythm": (lambda f: "박자가 고르게 흘러요", lambda f: "리듬이 들쭉날쭉해요"),
}


def _loud_db(hum_info: str | None) -> float | None:
    m = re.search(r"평균\s*(-?\d+(?:\.\d+)?)\s*dB", hum_info or "")
    return float(m.group(1)) if m else None


def analyze(notes: list, tempo: float, hum_info: str | None = None, key: str | None = None, transpose: int = 0) -> dict | None:
    """음 목록([{"start","dur","midi"}], 초) + 템포(+ 악보 조 표기·조 옮김) → 성격. 음이 너무 적거나 짧으면 None."""
    if len(notes) < MIN_NOTES:
        return None
    f = features(notes, tempo, key, transpose)
    if f["seconds"] < MIN_SEC:
        return None
    units = {k: _unit(k, f[k]) for k in SCALE}
    energy = round(100 * sum(units[k] * SCALE[k][2] for k in SCALE))
    level = next(name for top, name in LEVELS if energy < top)
    shade = "애매" if abs(f["bright"]) < BRIGHT_MIN else ("밝음" if f["bright"] > 0 else "어두움")
    label = NAMES[(level, shade)]
    # 이유: 두드러진(0.5 에서 0.2 넘게 먼) 특성 중 두 개 + 밝기. 다 중간이면 "중간 빠르기"
    top = [k for k in sorted(units, key=lambda k: abs(units[k] - 0.5), reverse=True) if abs(units[k] - 0.5) >= 0.2][:2]
    why = [_WHY[k][units[k] >= 0.5](f) for k in top] or [f"중간 빠르기({f['tempo']})"]
    if shade != "애매":
        why.append("장조라 밝아요" if shade == "밝음" else "단조라 어두워요")
    loud = _loud_db(hum_info)
    if loud is not None:
        f["loud_db"] = loud  # 아직 에너지에 넣지 않음 — 마이크·차마다 기준이 달라 사용자별 보정 뒤에 쓴다
    # 확신: 음이 적거나 에너지가 칸 경계 가까이면 낮다
    edge = min(abs(energy - top) for top, _ in LEVELS[:-1])
    confident = f["notes"] >= 10 and edge >= 4
    return {"energy": energy, "level": level, "shade": shade, "label": label, "genre": GENRE_KO[label], "confident": confident,
            "why": why, "features": f, "units": {k: round(v, 2) for k, v in units.items()}}


def card(mood: dict | None) -> str:
    """작사 LLM 에게 줄 멜로디 한 줄."""
    if not mood:
        return ""
    f = mood["features"]
    # 조 이름은 넣지 않는다 — 앱이 보여 주는 조(악보 머리)와 다를 수 있어 헷갈린다. 밝기만
    shade = {"밝음": "장조풍", "어두움": "단조풍", "애매": "장·단조 애매"}[mood["shade"]]
    return f"{mood['label']} 느낌(에너지 {mood['energy']}/100, {shade}) · 템포 {f['tempo']} · " + " · ".join(mood["why"])


def tempo_tag(mood: dict) -> str:
    return next(tag for top, tag in TEMPO_TAG if mood["features"]["tempo"] < top)


def user_genre(theme: str) -> bool:
    low = theme.lower()
    return any(w.lower() in low for w in GENRE_WORDS)


def style_for(mood: dict | None, theme: str = "") -> dict:
    """성격 → {"genre": 장르·악기 태그(주제에 장르가 있으면 ""), "tempo": 템포 태그, "voice": 창법 태그, "light": 표지 빛}."""
    if not mood:
        return {"genre": "", "tempo": "", "voice": "", "light": ""}
    genre, voice, light = STYLES[mood["label"]]
    return {"genre": "" if user_genre(theme) else genre, "tempo": tempo_tag(mood), "voice": voice, "light": light}


def merge_style(llm_tags: str, mood: dict | None, theme: str = "", limit: int = 10, vocal: bool = True) -> str:
    """스타일 태그 = [성격 장르·악기] + [템포] + [창법] + [LLM 분위기 태그]. 겹치는 태그는 한 번만, 앞에서부터 limit 개."""
    s = style_for(mood, theme)
    out, seen = [], set()
    for part in (s["genre"], s["tempo"], s["voice"] if vocal else "", llm_tags):
        for t in part.split(","):
            t = t.strip()
            if t and t.lower() not in seen:
                seen.add(t.lower())
                out.append(t)
    return ", ".join(out[:limit])
