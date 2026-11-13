"""노래 엔진 비교 시험(52번 문서 4장) — 같은 허밍·같은 가사로 엔진마다 노래를 만들어 나란히 듣고 잰다.

    <ComfyUI venv python> bench_engines.py --engines yue2,yue2+acc,mureka [--hum a.wav b.wav] [--seconds 60] [--voice female]
    ("+acc" 를 붙이면 그 엔진으로 만든 뒤 반주만 Mureka 로 다시 만든다 — engines/accomp.py)
                                           [--prompt "주제"] [--out 폴더] [--keep] [--no-metrics]

흐름(곡마다): 허밍으로 시험 곡 만들기 → 첫 엔진으로 노래(가사·표지 새로) → 나머지 엔진은 그 버전을 바탕으로
가사·표지·곡 구성을 그대로 두고(keep_lyrics·keep_cover) 엔진만 바꿔 다시 만들기 → 소리 받기 → 재기 → 시험 곡 지우기(--keep 이면 남김).
잴 것:
  걸린 시간(서버 작업 seconds) · 노래 길이
  멜로디 따르기 — 결과를 SheetSage2 로 다시 받아 적어, 엔진에 넘긴 곡 악보와 음표 F1(시작 ±0.2초, 같은 음, 시간 이동·조옮김은 가장 잘 맞게)
  가사 알아듣기 — 결과를 whisper 로 받아 적어, 가사와 한글 글자 오류율(CER, 낮을수록 좋음)
듣기용: 엔진 이름을 가린 파일(곡1_A.flac …)과 답지(답지.json)도 만든다.
서버(8790)·ComfyUI(8188)가 켜져 있어야 한다. 토큰은 ~/.chajak/token.
"""
from __future__ import annotations

import argparse
import asyncio
import io
import json
import random
import re
import shutil
import string
import time
from pathlib import Path

import aiohttp
import numpy as np
import soundfile as sf

import comfy as C
import melody_notes
import talk as T

HUMS = Path(r"F:\StabilityMatrix-win-x64\Data\Packages\ComfyUI\input")
DEFAULT_HUMS = [HUMS / "chajak_user_hum4.wav", HUMS / "chajak_user_hum5.wav"]
TOKEN_FILE = Path.home() / ".chajak" / "token"
STORAGE = Path(__file__).parent / "storage"
DEFAULT_OUT = Path(r"F:\현대자동차-앱개발-공모전\I05_차와함께작곡하는드라이브\개발\자료\샘플")


# ---------------------------------------------------------------- 서버 호출

async def _json(r):
    j = await r.json()
    if r.status >= 400:
        raise RuntimeError(f"{r.status} {j.get('error', j)}")
    return j


async def make_song(s, hum: Path) -> dict:
    f = aiohttp.FormData()
    f.add_field("audio", hum.read_bytes(), filename=hum.name, content_type="audio/wav")
    for k, v in (("instrument", "GM 0 Grand Piano"), ("melody", "그대로"), ("accompaniment", "true")):
        f.add_field(k, v)
    async with s.post("/v1/songs", data=f) as r:
        return await _json(r)


async def final(s, sid: str, body: dict) -> dict:
    async with s.post(f"/v1/songs/{sid}/final", json=body) as r:
        job = await _json(r)
    while job["status"] in ("queued", "running"):
        await asyncio.sleep(3)
        async with s.get(f"/v1/jobs/{job['id']}") as r:
            job = await _json(r)
    if job["status"] != "done":
        raise RuntimeError(f"노래 실패({body.get('engine')}): {job.get('error')}")
    async with s.get(f"/v1/songs/{sid}") as r:
        song = await _json(r)
    return next(f for f in song["finals"] if f["id"] == job["id"])


async def audio(s, url: str) -> bytes:
    async with s.get(url) as r:
        r.raise_for_status()
        return await r.read()


# ---------------------------------------------------------------- 재기

def _notes(abc: str) -> list[tuple[float, int]]:
    tempo, _ = C.abc_header(abc)
    return [(n["start"], n["midi"]) for n in melody_notes.notes({"abc": abc, "tempo": tempo})]


def melody_f1(ref: list[tuple[float, int]], est: list[tuple[float, int]], tol: float = 0.2) -> dict:
    """음표 F1: 시작 ±tol 초 안 · 같은 음(반음). 엔진이 전주를 늘이거나 조를 옮겨 부를 수 있어
    시간 이동(-10~+10초)·조옮김은 가장 잘 맞는 값(조옮김 후보: 0·±12 와 가운데 음 차이 ±1).
    2026-11-13 첫 시험: ±3초로는 전주 때문에 끝에 붙어(2.8초) 점수가 낮게 나왔다."""
    if not ref or not est:
        return {"f1": 0.0, "shift": 0.0, "transpose": 0, "n_ref": len(ref), "n_est": len(est)}
    rt = np.array([t for t, _ in ref]); rp = np.array([p for _, p in ref])
    et = np.array([t for t, _ in est]); ep = np.array([p for _, p in est])
    mid = int(round(np.median(rp) - np.median(ep)))
    best = (0.0, 0.0, 0)
    for tr in sorted({0, -12, 12, mid - 1, mid, mid + 1}):
        for sh in np.arange(-10.0, 10.0001, 0.05):
            used = np.zeros(len(et), bool); hit = 0
            for t, p in zip(rt, rp):
                cand = np.where(~used & (np.abs(et + sh - t) <= tol) & (ep + tr == p))[0]
                if cand.size:
                    used[cand[np.argmin(np.abs(et[cand] + sh - t))]] = True
                    hit += 1
            f = 2 * hit / (len(rt) + len(et))
            if f > best[0]:
                best = (f, float(sh), tr)
    return {"f1": round(best[0], 3), "shift": round(best[1], 2), "transpose": best[2], "n_ref": len(ref), "n_est": len(est)}


def _hangul(text: str) -> str:
    return re.sub(r"[^가-힣]", "", text)


def cer(ref: str, hyp: str) -> float:
    a, b = _hangul(ref), _hangul(hyp)
    if not a:
        return 0.0
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, y in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y))
        prev = cur
    return round(prev[-1] / len(a), 3)


def _score_abc(sid: str, final_id: str) -> str:
    """엔진에 넘긴 곡 악보. 앱용 응답에는 악보가 빠져 있어 서버 저장 파일에서 읽는다(이 도구는 서버 PC 에서 돈다)."""
    song = json.loads((STORAGE / sid / "song.json").read_text(encoding="utf-8"))
    return next(f for f in song["finals"] if f["id"] == final_id).get("abc", "")


_whisper = None


def _sing_text(data: bytes) -> str:
    """노래 받아쓰기. 대화용(talk.transcribe)은 말소리 아닌 구간을 걸러 노래를 대부분 버려서(2026-11-13 첫 시험: 한 줄만 남음),
    거르기를 끄고 같은 모델로 받아 적는다."""
    global _whisper
    from faster_whisper import WhisperModel
    if _whisper is None:
        device, compute = T._device()
        _whisper = WhisperModel(T.STT_MODEL, device=device, compute_type=compute, local_files_only=True)
    segs, _ = _whisper.transcribe(T.decode(data), language="ko", vad_filter=False, beam_size=5, condition_on_previous_text=False)
    return " ".join(s.text.strip() for s in segs if s.text.strip())


async def measure(cf: C.Comfy, data: bytes, name: str, sid: str, fin: dict) -> dict:
    out = {}
    if fin.get("engines", {}).get("melody_used", True):
        try:  # 멜로디: 완성곡을 '음악' 모드(정리 없이)로 SheetSage2 에 다시 넣는다
            up = await cf.upload(data, f"bench_{name}")
            got = C.text(await cf.run(C.melody_graph(up, "그대로", music=True)), "abc_out")
            out["melody"] = melody_f1(_notes(_score_abc(sid, fin["id"])), _notes(got))
        except Exception as e:
            out["melody"] = {"error": f"{type(e).__name__}: {e}"[:200]}
    else:
        out["melody"] = {"f1": None, "note": "멜로디를 넘기지 않은 엔진"}
    # 정답 가사는 실제로 부르는 순서(후렴 반복 포함) — 구간별 가사를 이어 붙이면 순서가 달라 오류율이 부풀었다
    lyr = re.sub(r"\[[^\]]*\]", " ", fin.get("lyrics_text") or "")
    if _hangul(lyr):
        try:
            await cf.free()  # 받아쓰기가 GPU 를 쓰게
            text = await asyncio.get_running_loop().run_in_executor(None, _sing_text, data)
            out["lyrics_cer"] = cer(lyr, text)
            out["heard"] = text[:300]
        except Exception as e:
            out["lyrics_cer"] = None
            out["lyrics_error"] = f"{type(e).__name__}: {e}"[:200]
    return out


# ---------------------------------------------------------------- 실행

async def main(a):
    engines_ = [e.strip() for e in a.engines.split(",") if e.strip()]
    stamp = time.strftime("%Y%m%d_%H%M")
    out_dir = Path(a.out) if a.out else DEFAULT_OUT / f"엔진비교_{stamp}"
    out_dir.mkdir(parents=True, exist_ok=True)
    headers = {"X-Chajak-Token": TOKEN_FILE.read_text().strip()} if TOKEN_FILE.exists() else {}
    cf = C.Comfy(a.comfy); await cf.start()
    rows, answer, made = [], {}, []
    letters = list(string.ascii_uppercase[:len(engines_)])
    try:
        async with aiohttp.ClientSession(a.server, headers=headers, timeout=aiohttp.ClientTimeout(total=1800)) as s:
            for i, hum in enumerate(map(Path, a.hum), 1):
                song = await make_song(s, hum)
                made.append(song["id"])
                print(f"곡{i} {hum.name} → {song['id']} (템포 {song['tempo']} · {song['key']})", flush=True)
                common = {"prompt": a.prompt, "seconds": a.seconds, "voice": a.voice, "melody_weight": 80}
                base = None
                random.shuffle(letters)
                for engine, letter in zip(engines_, letters):
                    name, _, extra = engine.partition("+")   # "yue2+acc" = YuE2 로 만들고 반주만 Mureka 로 다시
                    body = {**common, "engine": name, "accompaniment": "mureka" if extra == "acc" else "none"}
                    if base:  # 같은 가사·표지·곡 구성으로 엔진만 바꾼다
                        body |= {"from": base["id"], "keep_lyrics": True, "keep_cover": True}
                    t0 = time.time()
                    try:
                        fin = await final(s, song["id"], body)
                    except Exception as e:
                        print(f"  {engine}: 실패 — {e}", flush=True)
                        rows.append({"곡": i, "engine": engine, "error": str(e)[:200]})
                        continue
                    base = base or fin
                    data = await audio(s, fin["audio_url"])
                    ext = Path(fin.get("file", "x.flac")).suffix or ".flac"
                    (out_dir / f"곡{i}_{engine.replace('+', '_')}{ext}").write_bytes(data)
                    blind = f"곡{i}_{letter}{ext}"
                    (out_dir / blind).write_bytes(data)
                    answer[blind] = engine
                    row = {"곡": i, "hum": hum.name, "engine": engine, "wall_sec": round(time.time() - t0, 1),
                           "server_sec": fin.get("seconds"), "duration_sec": fin.get("duration_sec"), "title": fin.get("title"),
                           "engines": fin.get("engines")}
                    if not a.no_metrics:
                        row |= await measure(cf, data, f"{song['id']}_{engine}{ext}", song["id"], fin)
                    rows.append(row)
                    print(f"  {engine}: {row.get('server_sec')}초 · 길이 {row.get('duration_sec')}초 · 멜로디 F1 {row.get('melody', {}).get('f1')} · 가사 CER {row.get('lyrics_cer')}", flush=True)
            if not a.keep:
                for sid in made:
                    async with s.delete(f"/v1/songs/{sid}") as r:
                        await r.read()
    finally:
        await cf.close()
    (out_dir / "결과.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / "답지.json").write_text(json.dumps(answer, ensure_ascii=False, indent=1), encoding="utf-8")
    lines = ["| 곡 | 엔진 | 만드는 데(초) | 노래 길이(초) | 멜로디 F1 | 가사 CER |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['곡']} | {r['engine']} | {r.get('server_sec', '실패')} | {r.get('duration_sec', '')} | "
                     f"{(r.get('melody') or {}).get('f1', '')} | {r.get('lyrics_cer', '')} |")
    (out_dir / "결과.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print("결과 폴더:", out_dir)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--engines", default="yue2")
    ap.add_argument("--hum", nargs="+", default=[str(p) for p in DEFAULT_HUMS])
    ap.add_argument("--seconds", type=float, default=60)
    ap.add_argument("--voice", default="female")
    ap.add_argument("--prompt", default="비 오는 밤 퇴근길, 잔잔한 시티팝")
    ap.add_argument("--out", default="")
    ap.add_argument("--server", default="http://127.0.0.1:8790")
    ap.add_argument("--comfy", default="http://127.0.0.1:8188")
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--no-metrics", action="store_true")
    asyncio.run(main(ap.parse_args()))
