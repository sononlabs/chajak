"""차작 서버 — 차량 앱이 허밍을 올리면 곡(트랙 묶음)을 만들어 돌려준다.

실행: python server.py [--port 8790] [--comfy http://127.0.0.1:8188]
에뮬레이터에서는 http://10.0.2.2:8790 으로 접속한다.
프로젝트 및 API 개요: ../README.md
"""

from __future__ import annotations

import argparse
import asyncio
import hmac
import io
import json
import os
import re
import shutil
import sys
import time
import traceback
import uuid
from pathlib import Path

import aiohttp
import numpy as np
import soundfile as sf
from aiohttp import web

import comfy as C
import fine_melody
import mastering
import lyrics
import melody_ai
import melody_mood
import melody_notes
import talk as T
import lyricfit

STORAGE = Path(__file__).parent / "storage"
SR = 44100
# 렌더 리버브 공간 0.9초 → 이어 붙일 때 앞 트랙의 잔향 길이만큼 겹친다
REVERB_TAIL_SEC = 0.9
MAX_UPLOAD = 20 * 1024 * 1024
MIN_UPLOAD_SEC = 1.0
# 받는 녹음 형식(파일 이름 끝). 그 밖의 이름은 hum.wav 로 바꿔 저장한다(Windows 에서 이상한 접미사로 저장 실패하지 않게)
AUDIO_EXTS = (".wav", ".flac", ".ogg", ".mp3", ".m4a", ".aac")
TRACK_MODES = ("overlay", "append")


class ApiError(Exception):
    """앱에 보여줄 오류. 응답: {"error": {"code", "message"}} — message 는 그대로 화면에 띄울 수 있는 문장."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


HUM_ERRORS = {
    "no_voice": ("NO_VOICE", "목소리가 들리지 않았어요. 마이크 가까이에서 조금 더 크게 흥얼거려 주세요."),
    "no_melody": ("NO_MELODY", "멜로디를 찾지 못했어요. 조금 더 길게(5초 이상), 또렷하게 흥얼거려 주세요."),
}


class Store:
    """곡 = 폴더 하나 (song.json + 트랙·믹스 음원). 서버를 다시 켜도 남는다."""

    def __init__(self, root: Path):
        self.root = root
        self._locks: dict[str, asyncio.Lock] = {}
        root.mkdir(parents=True, exist_ok=True)

    def dir(self, song_id: str) -> Path:
        if not re.fullmatch(r"[0-9a-f]{12}", song_id):
            raise ApiError(404, "NOT_FOUND", "곡을 찾을 수 없어요.")
        d = self.root / song_id
        if not (d / "song.json").exists():
            raise ApiError(404, "NOT_FOUND", "곡을 찾을 수 없어요.")
        return d

    def load(self, song_id: str) -> dict:
        return json.loads((self.dir(song_id) / "song.json").read_text(encoding="utf-8"))

    def lock(self, song_id: str) -> asyncio.Lock:
        """곡 하나를 읽고-고치고-쓰는 동안 잡는다. 노래를 만드는 3분 사이에 트랙을 쌓으면
        트랙 쪽이 먼저 읽어 둔 곡으로 저장해 새 노래(final)를 지워 버릴 수 있었다."""
        return self._locks.setdefault(song_id, asyncio.Lock())

    def save(self, song: dict):
        d = self.root / song["id"]
        d.mkdir(exist_ok=True)
        _write_atomic(d / "song.json", json.dumps(song, ensure_ascii=False, indent=2))


def _write_atomic(path: Path, text: str):
    """임시 파일에 다 쓴 뒤 바꿔 끼운다 — 쓰는 도중 서버가 꺼져도 song.json·jobs.json 이 반쯤 잘리지 않게(2026-11-11, 44번 #6)."""
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


def _read(data: bytes) -> np.ndarray:
    x, sr = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
    if x.shape[1] == 1:
        x = np.repeat(x, 2, axis=1)
    assert sr == SR, sr
    return x


def mix_song(d: Path, tracks: list[dict]) -> float:
    """트랙을 겹치기(같은 시작점)·이어 붙이기(앞 내용 끝)로 합쳐 mix.flac 를 쓴다. 반환: 길이(초)."""
    layers, end = [], 0
    for t in tracks:
        x = _read((d / t["file"]).read_bytes())
        start = 0 if t["mode"] == "overlay" else max(0, end - int(REVERB_TAIL_SEC * SR))
        t["start_sec"] = round(start / SR, 3)
        layers.append((start, x))
        end = max(end, start + len(x))
    mix = np.zeros((end, 2), dtype=np.float32)
    for start, x in layers:
        mix[start:start + len(x)] += x
    peak = float(np.abs(mix).max())
    if peak > 0:
        mix *= 0.89 / peak
    sf.write(d / "mix.flac", mix, SR, format="FLAC")
    return round(end / SR, 2)


def _chords(summary: str) -> list[str]:
    m = re.search(r"화음 \d+마디: (.*)$", summary)
    return [c for c in m.group(1).replace("|", " ").split()] if m else []


def _note_count(summary: str) -> int | None:
    m = re.search(r"(?:내 멜로디 (\d+)음|음표 (\d+)개)", summary)
    return int(m.group(1) or m.group(2)) if m else None


def _mood_view(mood: dict | None) -> dict | None:
    """앱에 보낼 성격(계산 세부는 features 에만)."""
    return None if not mood else {k: mood[k] for k in ("label", "genre", "energy", "level", "shade", "confident", "why", "features")}


def _track_view(song: dict, t: dict, base: str) -> dict:
    score = melody_notes.notes(t)
    # 음표 → 성격. 계산이 가벼워서 저장하지 않고 응답 때마다 잰다(규칙을 고치면 지난 곡에도 바로 반영)
    mood = melody_mood.analyze(score, t["tempo"], t.get("hum_info"), t.get("key"), t.get("transpose", 0), t.get("input", "hum"))
    if t.get("abc_fine"):  # 화면 악보는 세밀 악보(연주도 이것), 성격 분석은 노래 만들기와 같은 SheetSage2 악보로
        score = melody_notes.notes({**t, "abc": t["abc_fine"]})
    return {**{k: v for k, v in t.items() if k not in ("file", "abc", "abc_fine")}, "audio_url": f"{base}/tracks/{t['n']}/audio",
            "hum_url": f"{base}/tracks/{t['n']}/hum" if any((STORAGE / song["id"]).glob(f"hum{t['n']}.*")) else None,
            "score": score, "mood": _mood_view(mood),
            "duration_sec": round(sf.info(STORAGE / song["id"] / t["file"]).duration, 2)}


def song_view(song: dict) -> dict:
    base = f"/v1/songs/{song['id']}"
    out = {k: v for k, v in song.items() if k not in ("tracks", "ai", "final", "finals")}
    view = lambda f: {**{k: v for k, v in f.items() if k not in ("abc", "file", "cover")},
                      "audio_url": f"{base}/final/audio?v={f.get('id', '')}", "cover_url": f"{base}/final/cover?v={f.get('id', '')}"}
    if song.get("final"):
        out["final"] = view(song["final"])
        out["finals"] = [view(f) for f in song.get("finals", [song["final"]])]
    out["audio_url"] = base + "/audio"
    out["tracks"] = [_track_view(song, t, base) for t in song["tracks"]]
    out["mood"] = out["tracks"][0]["mood"] if out["tracks"] else None
    return out


async def _form(request: web.Request) -> tuple[bytes, str, dict]:
    if not request.content_type.startswith("multipart/"):
        raise ApiError(400, "BAD_REQUEST", "multipart/form-data 로 audio 파일을 올려 주세요.")
    data, filename, fields = None, "hum.wav", {}
    async for part in await request.multipart():
        if part.name == "audio":
            filename = part.filename or filename
            data = await part.read(decode=False)
        else:
            fields[part.name] = (await part.text()).strip()
    if not data:
        raise ApiError(400, "NO_AUDIO", "audio 파일이 없어요.")
    if len(data) > MAX_UPLOAD:
        raise ApiError(413, "TOO_LARGE", "녹음 파일이 너무 커요(20MB 이하).")
    # 엔진에 넘기기 전에 읽히는 소리인지·길이를 본다(빈 파일·깨진 파일이 엔진 오류 502 + 내부 경로로 새던 것)
    ext = Path(filename).suffix.lower()
    if ext not in AUDIO_EXTS:
        filename, ext = "hum.wav", ".wav"
    if ext in (".wav", ".flac", ".ogg"):
        try:
            info = sf.info(io.BytesIO(data))
        except Exception:
            raise ApiError(400, "BAD_AUDIO", "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요.")
        if info.duration < MIN_UPLOAD_SEC:
            raise ApiError(422, "TOO_SHORT", "너무 짧아요. 3초 이상 흥얼거려 주세요.")
    return data, filename, fields


async def _json(request: web.Request) -> dict:
    """JSON 본문(객체만). 비어 있으면 {}."""
    try:
        body = await request.json() if request.can_read_body else {}
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise ApiError(400, "BAD_JSON", "JSON 본문을 읽지 못했어요.")
    if not isinstance(body, dict):
        raise ApiError(400, "BAD_JSON", "JSON 본문은 객체({...})여야 해요.")
    return body


def _option(fields: dict, name: str, allowed, default):
    v = fields.get(name, default)
    if v not in allowed:
        raise ApiError(400, "BAD_OPTION", f"{name} 값은 {list(allowed)} 중 하나여야 해요.")
    return v


async def _render_track(app, song_id: str, n: int, data: bytes, filename: str, instrument: str,
                        melody: str, accompaniment: bool, song: dict | None, music: bool = False) -> tuple[dict, bytes]:
    """song 이 있으면 쌓기 트랙: 먼저 악보만 뽑아 부른 템포·조성을 보고 곡 박자·조성에 맞춰 연주한다."""
    cf: C.Comfy = app["comfy"]
    if app["instruments"] and instrument not in app["instruments"]:
        raise ApiError(400, "BAD_OPTION", "instrument 는 /v1/instruments 목록의 값이어야 해요.")
    t0 = time.perf_counter()
    ext = Path(filename).suffix.lower() or ".wav"
    name = await cf.upload(data, f"{song_id}_{n}{ext}")
    hummed_tempo, tempo, transpose = None, 0, 0
    try:
        if song:
            hummed_tempo, hummed_key = C.abc_header(C.text(await cf.run(C.melody_graph(name, melody, music), front=True), "abc_out"))
            tempo = C.fit_tempo(song["tempo"], hummed_tempo)
            transpose = C.fit_key(song["key"], hummed_key)
        fine, fine_info = None, None
        if melody == "그대로" and not music:
            # 세밀 악보(2026-11-11 사용자: 음표가 너무 단순하다): 정리한 허밍을 pYIN 으로 32분음표 칸까지 —
            # 화면·연주에만 쓰고, 노래 만들기는 아래 SheetSage2 악보(track["abc"])를 그대로 쓴다
            front = await cf.run(C.melody_graph(name, melody, music, prep_prefix=f"chajak_server/{song_id}_{n}_prep"), front=True)
            coarse = C.text(front, "abc_out")
            try:
                prep = await cf.fetch_audio(front, "prep_save")
                fine, fine_info = await asyncio.get_running_loop().run_in_executor(None, fine_melody.fine_abc, prep, coarse)
            except Exception as e:  # 세밀 악보가 안 되면 예전처럼
                print("세밀 악보 실패(SheetSage2 악보로 연주):", e, flush=True)
        if fine:
            outputs = await cf.run(C.abc_graph(fine, instrument, accompaniment, tempo, transpose, f"chajak_server/{song_id}_{n}"), front=True)
            outputs = {**front, **outputs}  # abc_out(SheetSage2)·prep_info 는 앞 그래프에서
        else:
            outputs = await cf.run(C.track_graph(name, instrument, melody, accompaniment, tempo,
                                                 f"chajak_server/{song_id}_{n}", transpose, music), front=True)
    except C.ComfyError as e:
        if e.kind in HUM_ERRORS:
            raise ApiError(422, *HUM_ERRORS[e.kind]) from e
        print(f"생성 엔진 오류: {e}", flush=True)
        raise ApiError(502, "ENGINE_ERROR", ENGINE_MSG) from e
    audio = await cf.fetch_audio(outputs, "save")
    abc = C.text(front if fine else outputs, "abc_out")
    info = C.text(outputs, "render_info")
    track_tempo, key = C.abc_header(abc)
    track = {
        "n": n, "instrument": instrument, "melody": melody, "accompaniment": accompaniment,
        "notes": _note_count(info), "tempo": tempo or track_tempo, "hummed_tempo": hummed_tempo or track_tempo,
        "key": key, "transpose": transpose,
        "chords": _chords(info) if accompaniment else [],
        "hum_info": C.text(outputs, "prep_info"), "render_info": info,
        "seconds": round(time.perf_counter() - t0, 2), "abc": abc, "file": f"track{n}.flac",
    }
    if fine:
        track.update(abc_fine=fine, fine_info=fine_info)
    return track, audio


# ---------------------------------------------------------------- 핸들러

async def health(request):
    alive = await request.app["comfy"].alive()
    extra = request.app.get("extra_nodes", [])
    return web.json_response({"ok": alive, "engine": "comfyui", "engine_alive": alive, "extra_custom_nodes": len(extra)},
                             status=200 if alive else 503)


async def instruments(request):
    return web.json_response({"default": C.DEFAULT_INSTRUMENT, "instruments": request.app["instruments"]})


async def create_song(request):
    """첫 트랙으로 곡을 만든다. 필드: audio, instrument, melody(그대로|다듬기), accompaniment(true|false)."""
    data, filename, f = await _form(request)
    melody = _option(f, "melody", ("그대로", "다듬기"), "그대로")
    accompaniment = _option(f, "accompaniment", ("true", "false"), "true") == "true"
    source = _option(f, "input", ("hum", "music"), "hum")
    song_id = uuid.uuid4().hex[:12]
    track, audio = await _render_track(request.app, song_id, 1, data, filename,
                                       f.get("instrument", C.DEFAULT_INSTRUMENT), melody, accompaniment, None, source == "music")
    track["mode"] = "overlay"
    track["input"] = source
    store: Store = request.app["store"]
    song = {"id": song_id, "created": _now_iso(), "tempo": track["tempo"], "key": track["key"],
            "tracks": [track]}
    store.save(song)
    d = store.dir(song_id)
    (d / track["file"]).write_bytes(audio)
    (d / "hum1").with_suffix(Path(filename).suffix or ".wav").write_bytes(data)
    song["duration_sec"] = mix_song(d, song["tracks"])
    store.save(song)
    return web.json_response(song_view(song), status=201)


def _append_track(store: Store, song: dict, track: dict, audio: bytes):
    """song 은 방금 다시 읽은 것(잠금 안)이어야 한다 — 그사이 저장된 최종 노래를 지우지 않게."""
    d = store.dir(song["id"])
    (d / track["file"]).write_bytes(audio)
    song["tracks"].append(track)
    song["duration_sec"] = mix_song(d, song["tracks"])
    store.save(song)
    return web.json_response(song_view(song), status=201)


async def variations(request):
    return web.json_response({"variations": [{"id": k, "name": name, "mode": mode, "description": desc}
                                             for k, (name, mode, desc) in C.VARIATIONS.items()]})


async def add_variation(request):
    """녹음 없이 이미 있는 트랙의 악보로 변주 트랙을 쌓는다(약 0.5초).

    본문 JSON: {"variation", "source": 1, "instrument", "mode", "accompaniment"}. instrument 를 빼면 원래 트랙 악기
    (화음 줄 harmony·harmony_low 는 다른 소리 — 위 플루트·아래 첼로, 원래가 그 악기면 바이올린·클라리넷, 조금 작게).
    mode 를 빼면 변주에 어울리는 방식,
    accompaniment 를 빼면 이어 붙이기일 때만 반주를 붙인다. 원본 트랙을 조성 이동했으면 반주 없이 연주한다.
    """
    store: Store = request.app["store"]
    song_id = request.match_info["song_id"]
    store.dir(song_id)
    body = await _json(request)
    async with store.lock(song_id):
        return await _add_variation(request, store, store.load(song_id), body)


async def _add_variation(request, store: Store, song: dict, body: dict):
    variation = _option(body, "variation", C.VARIATIONS, None)
    mode = _option(body, "mode", TRACK_MODES, C.VARIATIONS[variation][1])
    source = next((t for t in song["tracks"] if t["n"] == body.get("source", 1)), None)
    if source is None:
        raise ApiError(404, "NOT_FOUND", "변주할 트랙을 찾을 수 없어요.")
    instrument = body.get("instrument") or C.harmony_instrument(variation, source["instrument"]) or source["instrument"]
    if instrument not in request.app["instruments"]:
        raise ApiError(400, "BAD_OPTION", "instrument 는 /v1/instruments 목록의 값이어야 해요.")
    accompaniment = bool(body.get("accompaniment", mode == "append")) and source["transpose"] == 0
    # 자동 반주는 악보 템포로 연주하므로 원본 트랙이 실제로 연주된 템포를 악보에 적어 둔다
    abc = re.sub(r"^Q:.*$", f"Q:1/4={source['tempo']}", source["abc"], count=1, flags=re.M)
    n = len(song["tracks"]) + 1
    t0 = time.perf_counter()
    cf: C.Comfy = request.app["comfy"]
    try:
        if variation in C.SERVER_VARIATIONS:
            new_abc, var_info = await melody_ai.rewrite(abc, int(uuid.uuid4().int % 2**31))
            outputs = await cf.run(C.abc_graph(new_abc, instrument, accompaniment, source["tempo"], source["transpose"],
                                               f"chajak_server/{song['id']}_{n}"), front=True)
        else:
            outputs = await cf.run(C.variation_graph(abc, variation, instrument, accompaniment, source["tempo"],
                                                     source["transpose"], f"chajak_server/{song['id']}_{n}",
                                                     C.HARMONY_GAIN_DB if variation in C.HARMONY_INSTRUMENTS else -6.0), front=True)
            var_info = C.text(outputs, "var_info")
    except C.ComfyError as e:
        print(f"생성 엔진 오류: {e}", flush=True)
        raise ApiError(502, "ENGINE_ERROR", ENGINE_MSG) from e
    audio = await cf.fetch_audio(outputs, "save")
    out_abc = C.text(outputs, "abc_out")
    info = C.text(outputs, "render_info")
    track = {
        "n": n, "mode": mode, "instrument": instrument, "variation": variation, "source": source["n"],
        "accompaniment": accompaniment, "notes": _note_count(info), "tempo": source["tempo"],
        "hummed_tempo": source["hummed_tempo"], "key": C.abc_header(out_abc)[1], "transpose": source["transpose"],
        "chords": _chords(info) if accompaniment else [], "variation_info": var_info,
        "render_info": info, "seconds": round(time.perf_counter() - t0, 2), "abc": out_abc, "file": f"track{n}.flac",
    }
    return _append_track(store, store.load(song["id"]), track, audio)


# ComfyUI 폴더: 서버를 ComfyUI venv 의 python 으로 켜므로(README) 그 위 폴더. 다르면 CHAJAK_COMFY_DIR.
def _comfy_dir() -> Path | None:
    import os
    env = os.environ.get("CHAJAK_COMFY_DIR")
    for d in ([Path(env)] if env else []) + [Path(sys.executable).resolve().parents[2]]:
        if (d / "main.py").exists():
            return d
    return None


def _busy(app, song_id: str, final_id: str | None = None) -> bool:
    """이 곡(또는 이 버전을 바탕으로)으로 노래를 만드는 중인가."""
    return any(j.get("song_id") == song_id and j.get("status") not in ("done", "failed", "error", "cancelled")
               and (final_id is None or j.get("from") == final_id) for j in app["jobs"].values())


def _purge_comfy(song_id: str) -> int:
    """ComfyUI 입력(올린 허밍)·출력(연주·노래·표지) 폴더에 남은 이 곡의 사본을 지운다."""
    d, n = _comfy_dir(), 0
    if d is None:
        return 0
    for sub in ("input/chajak_server", "output/chajak_server"):
        for f in (d / sub).glob(f"{song_id}_*"):
            try:
                f.unlink(); n += 1
            except OSError:
                pass
    return n


async def delete_song(request):
    """곡 지우기: 멜로디(트랙)·노래 모든 버전·표지와 ComfyUI 에 남은 사본까지. 노래를 만드는 중이면 409."""
    store: Store = request.app["store"]
    song_id = request.match_info["song_id"]
    d = store.dir(song_id)
    if _busy(request.app, song_id):
        raise ApiError(409, "BUSY", "이 곡으로 노래를 만드는 중이에요. 다 만든 뒤에 지워 주세요.")
    async with store.lock(song_id):
        shutil.rmtree(d, ignore_errors=True)
    copies = _purge_comfy(song_id)
    for jid in [k for k, j in request.app["jobs"].items() if j.get("song_id") == song_id]:  # 작업 기록에 남은 가사·주제도
        request.app["jobs"].pop(jid, None)
    _save_jobs(request.app["jobs"])
    return web.json_response({"deleted": song_id, "comfy_copies": copies})


async def delete_final(request):
    """노래 한 버전 지우기(멜로디와 다른 버전은 남는다). 다른 버전과 같이 쓰는 표지는 남긴다."""
    store: Store = request.app["store"]
    song_id, fid = request.match_info["song_id"], request.match_info["final_id"]
    store.dir(song_id)
    if _busy(request.app, song_id, fid):
        raise ApiError(409, "BUSY", "이 버전으로 새 노래를 만드는 중이에요. 다 만든 뒤에 지워 주세요.")
    async with store.lock(song_id):
        song = store.load(song_id)
        finals = song.get("finals") or ([song["final"]] if song.get("final") else [])
        f = next((x for x in finals if x.get("id") == fid), None)
        if f is None:
            raise ApiError(404, "NOT_FOUND", "그 노래 버전을 찾을 수 없어요.")
        rest = [x for x in finals if x is not f]
        d = store.dir(song_id)
        (d / f.get("file", "final.flac")).unlink(missing_ok=True)
        cover = f.get("cover")
        if cover and all(x.get("cover") != cover for x in rest):
            (d / cover).unlink(missing_ok=True)
        song["finals"] = rest
        if rest:
            song["final"] = rest[-1]
        else:
            song.pop("final", None); song.pop("finals", None)
        store.save(song)
        return web.json_response(song_view(song))


async def list_songs(request):
    """내 노래 목록(최근 순, 최대 ?limit=20). 완성된 노래가 있으면 제목·표지·길이."""
    store: Store = request.app["store"]
    try:
        limit = max(1, min(100, int(request.query.get("limit", 20))))
    except ValueError:
        raise ApiError(400, "BAD_OPTION", "limit 는 숫자여야 해요.")
    dirs = sorted((d for d in store.root.iterdir() if (d / "song.json").exists()), key=lambda d: (d / "song.json").stat().st_mtime, reverse=True)
    out = []
    for d in dirs[:limit]:
        try:
            s = json.loads((d / "song.json").read_text(encoding="utf-8"))
            v = song_view(s) if s.get("final") else None
        except Exception as e:
            print(f"곡 목록에서 건너뜀 {d.name}: {e}", flush=True)
            continue
        first = (s.get("tracks") or [None])[0]
        try:
            m = first and melody_mood.analyze(melody_notes.notes(first), first["tempo"], None, first.get("key"), first.get("transpose", 0),
                                              first.get("input", "hum"))
        except Exception:
            m = None
        out.append({"mood": {"label": m["label"], "energy": m["energy"], "key": m["features"]["key"]} if m else None, "id": s["id"], "created": s.get("created"), "tempo": s.get("tempo"), "key": s.get("key"),
                    "tracks": len(s.get("tracks", [])), "duration_sec": s.get("duration_sec"),
                    "title": s["final"]["title"] if v else None, "cover_url": v["final"]["cover_url"] if v else None,
                    "final_sec": s["final"].get("duration_sec") if v else None})
    return web.json_response({"songs": out})


async def get_song(request):
    return web.json_response(song_view(request.app["store"].load(request.match_info["song_id"])))


_THUMBS: dict[tuple, bytes] = {}
THUMB_SIZES = range(64, 1025)


def _make_thumb(path: Path, size: int) -> bytes:
    from PIL import Image
    with Image.open(path) as im:
        im = im.convert("RGB")
        im.thumbnail((size, size), Image.LANCZOS)
        out = io.BytesIO()
        im.save(out, "JPEG", quality=82, optimize=True)
        return out.getvalue()


async def _cover_response(request, path: Path):
    """표지. ?size=N 이면 N px JPEG(목록·작은 칸용)로 줄여 준다 — 1024px PNG(약 1.3MB)를 터널로 받으면
    목록 하나에 1분이 걸리고 앱 메모리도 한 장에 4MB 씩 들었다(2026-11-09). 줄인 그림은 메모리에 기억해 둔다."""
    if not path.exists():
        raise ApiError(404, "NOT_FOUND", "표지가 아직 없어요.")
    size = request.query.get("size")
    cache = {"Cache-Control": "public, max-age=86400"}
    if not size:
        return web.FileResponse(path, headers={"Content-Type": "image/png", **cache})
    try:
        n = int(size)
    except ValueError:
        raise ApiError(400, "BAD_OPTION", "size 는 64~1024 사이 숫자여야 해요.")
    if n not in THUMB_SIZES:
        raise ApiError(400, "BAD_OPTION", "size 는 64~1024 사이 숫자여야 해요.")
    key = (str(path), path.stat().st_mtime_ns, n)
    data = _THUMBS.get(key)
    if data is None:
        data = await asyncio.get_running_loop().run_in_executor(None, _make_thumb, path, n)
        if len(_THUMBS) > 256:
            _THUMBS.clear()
        _THUMBS[key] = data
    return web.Response(body=data, content_type="image/jpeg", headers=cache)


def _now_iso() -> str:
    """만든 시각(시간대 포함, 예: 2026-11-09T23:31:28+09:00). 서버와 차의 시간대가 달라도 앱이 차 시각으로 바꿔 보여 준다
    (2026-11-09: 시간대 없이 적어서 에뮬레이터·원격 서버에서 '아침 출근길' 곡이 '밤 드라이브 앨범'에 묶였다)."""
    import datetime
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def _file_response(path: Path):
    if not path.exists():
        raise ApiError(404, "NOT_FOUND", "음원이 아직 없어요.")
    return web.FileResponse(path, headers={"Content-Type": "audio/flac"})


async def song_audio(request):
    return _file_response(request.app["store"].dir(request.match_info["song_id"]) / "mix.flac")


async def track_audio(request):
    store: Store = request.app["store"]
    song = store.load(request.match_info["song_id"])
    n = int(request.match_info["n"])
    track = next((t for t in song["tracks"] if t["n"] == n), None)
    if track is None:
        raise ApiError(404, "NOT_FOUND", "트랙을 찾을 수 없어요.")
    return _file_response(store.dir(song["id"]) / track["file"])


async def themes(request):
    return web.json_response({"themes": lyrics.THEMES, "random": lyrics.random_theme(), "examples": lyrics.theme_examples()})


FINAL_SECONDS = 120.0
# 2026-11-05 실측(RTX 5070 Ti): 멜로디 1초 · 가사 15초 · 표지 23초(첫 로드 포함) · 노래 205초(곡 114초+8초) → 곡 1초당 약 1.7초.
FINAL_ETA = lambda sec: round(40 + (sec + 8) * 1.7)


VOICES = {"auto": "", "female": "female vocal", "male": "male vocal", "none": "",
          # 랩(2026-11-10): 허밍 악보 없이 만들고 힙합 태그·랩 가사로
          "rap_male": "male rapper, rap vocal", "rap_female": "female rapper, rap vocal"}
# 앨범 표지에 글자가 절대 들어가지 않게(앱·사용자에게는 보이지 않는 고정 지시)
COVER_NO_TEXT = ("a wordless picture: absolutely no text, no letters, no words, no numbers, no captions, no title, "
                 "no typography, no signs, no signboards, no logo, no watermark, no writing of any language")

# 가사 모델이나 사용자가 쓴 장면에 간판류가 있으면 Z-Image 가 글자 같은 무늬를 그린다(2026-11-10 네온 거리 표지) → 빛으로 바꾼다
_COVER_SIGNS = re.compile(r"\b(?:neon\s+|shop\s+|street\s+|glowing\s+|illuminated\s+)?(?:signs?|signboards?|signage|billboards?|storefronts?|shopfronts?|"
                          r"posters?|banners?|license\s+plates?|menus?|books?|newspapers?|screens?|monitors?)\b", re.I)


def _cover_scene(scene: str) -> str:
    return re.sub(r"\s{2,}", " ", _COVER_SIGNS.sub("soft lights", scene)).strip()


REFERENCE_FROM = 33
KEEP_FROM = 70


def _weights(melody: int, style: int) -> dict:
    """가중치(0~100) → YuE2 설정.
    내 멜로디 반영: 70 이상 = 내 허밍(정리만) 그대로, 33~69 = 허밍은 참고만 하고 AI 가 다시 쓴 멜로디(melody_ai),
    33 미만 = 악보 없이(화음·멜로디를 YuE2 가 새로) 가사·스타일만. 높을수록 덜 흔들리게(temperature 1.0→0.6).
    스타일 강도: 스타일·가사 지시를 얼마나 세게 따를지(cfg 1.0~1.6). 높으면 장르 색이 진해지고, 너무 높으면 소리가 거칠어질 수 있다."""
    return {"use_abc": melody >= REFERENCE_FROM, "temperature": round(1.0 - 0.4 * melody / 100, 2), "cfg_scale": round(1.0 + 0.6 * style / 100, 2)}


def _style(tags: str, voice: str) -> str:
    """보컬 선택을 스타일 태그에 반영(LLM 이 고른 성별 태그는 지우고 바꾼다)."""
    parts = [t.strip() for t in tags.split(",") if t.strip() and t.strip().lower() not in ("female vocal", "male vocal")]
    if voice == "none":
        return "instrumental only, no vocals, " + ", ".join(parts)
    if VOICES[voice]:
        parts.append(VOICES[voice])
    elif not any("vocal" in t.lower() for t in tags.split(",")):
        parts.append("female vocal")
    else:
        parts += [t.strip() for t in tags.split(",") if t.strip().lower() in ("female vocal", "male vocal")][:1]
    return ", ".join(parts)


async def start_final(request):
    """최종 노래 만들기(약 3분). 본문 JSON: {"prompt": 주제(비우면 주사위), "seconds": 60~180(기본 120),
    "voice": auto|female|male|none(연주곡), "melody_weight": 0~100(기본 80), "style_weight": 0~100(기본 50),
    "follow_mood": 멜로디 느낌에 스타일·가사 말투를 맞출지(기본 true)}.
    흥얼거린 트랙 1 → 2분 멜로디(곡 구성 + 화음) → 주제로 가사 → 앨범 표지 → YuE2 로 노래. 진행은 /v1/jobs/{id} 의 stage.
    다시 만들면 이전 노래는 song["finals"](버전 목록)에 남는다."""
    store: Store = request.app["store"]
    song = store.load(request.match_info["song_id"])
    body = await _json(request)
    prompt = str(body.get("prompt", "")).strip() or _talk_theme(body) or lyrics.random_theme()
    opts = {"seconds": float(min(180, max(60, float(body.get("seconds", FINAL_SECONDS))))),
            "voice": _option(body, "voice", VOICES, "auto"),
            "melody_weight": int(min(100, max(0, int(body.get("melody_weight", 80))))),
            "style_weight": int(min(100, max(0, int(body.get("style_weight", 50))))),
            "follow_mood": body.get("follow_mood", True) is not False}
    if opts["voice"].startswith("rap"):  # 랩은 허밍 악보를 따르게 하면 노래처럼 불렀다(초당 2.2자) — 악보 없이, 멜로디 느낌 장르도 쓰지 않는다
        opts["melody_weight"] = min(opts["melody_weight"], 20)
        opts["follow_mood"] = False
    if body.get("talk"):  # 대화로 가사 만들기: /v1/talk 가 정리해 준 이야기를 가사에 넣는다
        opts["talk"] = T.sanitize(body.get("talk"))
    # 다시 만들기: 이전 버전을 바탕으로 가중치·주제·가사·스타일만 바꾼다
    base = next((f for f in song.get("finals", []) if f.get("id") == body.get("from")), None)
    if body.get("from") and base is None:
        raise ApiError(404, "NOT_FOUND", "바탕으로 할 노래 버전을 찾을 수 없어요.")
    remix = {"base": base, "keep_lyrics": bool(body.get("keep_lyrics", False)), "keep_cover": bool(body.get("keep_cover", False)),
             "lyrics": body.get("lyrics") if isinstance(body.get("lyrics"), dict) else None,
             "style": str(body.get("style", "")).strip() or None,
             "edit": str(body.get("edit", "")).strip()[:200] or None,
             "title": str(body.get("title", "")).strip()[:40] or None,
             "cover": str(body.get("cover", "")).strip()[:400] or None}
    if remix["edit"] and base is None:
        raise ApiError(400, "BAD_OPTION", "말로 고치기는 바탕이 될 노래 버전(from)이 있어야 해요.")
    job_id = uuid.uuid4().hex[:12]
    job = {"id": job_id, "song_id": song["id"], "kind": "final", "status": "queued", "stage": "melody", "prompt": prompt,
           "options": opts, "from": base["id"] if base else None, "eta_sec": FINAL_ETA(opts["seconds"]), "created": time.time(),
           "_remix": remix}
    request.app["jobs"][job_id] = job
    job["_task"] = _spawn(request.app, (_run_final(request.app, job, song)))
    return web.json_response({k: v for k, v in job.items() if not k.startswith("_")}, status=202)


def _talk_theme(body: dict) -> str:
    """주제를 비우고 대화를 넣었으면 대화 이야기를 주제로(무작위 주제가 대화와 어긋났다 — 2026-11-11)."""
    t = body.get("talk") if isinstance(body.get("talk"), dict) else None
    return str(t.get("theme") or t.get("story") or "").strip()[:80] if t else ""


def _avoid(song: dict) -> list[str]:
    """이 곡의 최근 버전 3개의 제목·후렴 첫 줄·첫 줄 — 다시 만들 때 같은 가사가 또 나오지 않게(2026-11-11)."""
    out = []
    for f in (song.get("finals") or [])[-3:]:
        ly = f.get("lyrics") or {}
        first = next((v[0] for v in ly.values() if v), "")
        hook = (ly.get("chorus") or [""])[0]
        out += [x for x in (f.get("title"), hook, first) if x]
    return list(dict.fromkeys(out))


def _melody_mood(src_abc: str, first: dict) -> dict | None:
    """노래에 실제로 쓰는 멜로디(1번 트랙 또는 AI 가 다시 쓴 8마디)의 성격. 실패해도 노래 만들기는 계속한다."""
    try:
        notes = melody_notes.notes({"abc": src_abc, "tempo": first["tempo"], "transpose": first.get("transpose", 0)})
        same = src_abc == first["abc"]
        return melody_mood.analyze(notes, first["tempo"], first.get("hum_info") if same else None,
                                   C.abc_header(src_abc)[1], first.get("transpose", 0), first.get("input", "hum") if same else "hum")
    except Exception:
        return None


async def _run_final(app, job: dict, song: dict):
    """노래 작업은 한 번에 하나씩(2026-11-10 사용자: 만드는 중에 또 누르면 대기열로). GPU 하나에 노래·표지·작사 모델이
    겹치면 메모리가 모자란다. 기다리는 동안 status=queued, stage=queue, queue_ahead=앞에 남은 수."""
    waiting: list = app["final_waiting"]
    waiting.append(job["id"])
    job.update(status="queued", stage="queue")
    try:
        while True:
            ahead = waiting.index(job["id"])
            job["queue_ahead"] = ahead + (1 if app["final_lock"].locked() else 0)
            if ahead == 0 and not app["final_lock"].locked():
                break
            await asyncio.sleep(1)
        async with app["final_lock"]:
            waiting.remove(job["id"])
            job.pop("queue_ahead", None)
            job["created"] = time.time()  # 진행 막대·남은 시간은 실제로 시작한 때부터
            job["stage"] = "melody"
            await _run_final_now(app, job, song)
    except asyncio.CancelledError:
        cover = job.get("_cover")
        if cover:
            Path(cover).unlink(missing_ok=True)
            job.pop("cover_url", None)
        print(f"노래 작업 {job['id']} 취소됨", flush=True)
    except Exception as e:
        if job.get("status") not in ("done", "failed", "cancelled"):
            job.update(status="failed", error=_fail_message(e, "노래를 만들지 못했어요."))
    finally:
        if job["id"] in waiting:
            waiting.remove(job["id"])


async def _run_final_now(app, job: dict, song: dict):
    cf: C.Comfy = app["comfy"]
    store: Store = app["store"]
    d = store.dir(song["id"])
    opts = job["options"]
    seed = int(uuid.uuid4().int % 2**31)
    remix = job.pop("_remix")
    base = remix["base"]
    job["status"] = "running"
    try:
        if remix.get("edit"):
            # 말로 고치기(작업 종류는 모델이 고른다): 요청 한 문장 → 설정 변경 → 무엇을 바꿨는지 한 줄로 알린다
            job["stage"] = "edit"
            cur = {"주제": job["prompt"], "스타일": base.get("style", ""), **{k: opts[k] for k in ("seconds", "voice", "melody_weight", "style_weight")}}
            e = await lyrics.interpret_edit(remix["edit"], cur, seed)
            for k in ("voice", "melody_weight", "style_weight"):
                if e[k] is not None:
                    opts[k] = e[k]
            if e["seconds"]:
                opts["seconds"] = float(e["seconds"])
            if e["new_theme"]:
                job["prompt"] = e["new_theme"]
            remix["keep_lyrics"] = not e["new_lyrics"]
            remix["keep_cover"] = remix["keep_cover"] and not e["new_theme"]
            remix["style"] = lyrics.edit_style(remix["style"] or base.get("style", ""), e["style_add"], e["style_remove"])
            job.update(edit_summary=e["summary"], options=opts, prompt=job["prompt"], stage="melody")
        sung = opts["voice"] != "none"
        w = _weights(opts["melody_weight"], opts["style_weight"])
        same_form = base and base.get("options", {}).get("seconds") == opts["seconds"] and (base.get("options", {}).get("voice") == "none") == (not sung)
        reference = REFERENCE_FROM <= opts["melody_weight"] < KEEP_FROM
        first = song["tracks"][0]
        src = first["abc"]
        if same_form and bool(base.get("reference")) == reference:
            abc, plan = base["abc"], base["plan"]
            src = base.get("melody_abc") or src
        else:
            if reference:  # 허밍은 참고만: AI 가 다시 쓴 8마디로 2분 곡을 만든다
                src, _ = await melody_ai.rewrite(src, seed)
            out = await cf.run(C.song_form_graph(src, opts["seconds"], sung))
            abc, plan = C.text(out, "abc_out"), json.loads(C.text(out, "plan_out"))
        if sung:  # 가사 줄 = 멜로디 프레이즈(쉼표로 나눈 구간), 줄마다 음표 수를 글자 수 목표로(2마디씩 자르던 것 대신)
            try:
                lyricfit.phrases(abc, plan)
            except Exception as e:
                print("프레이즈 나누기 실패(2마디 기준 그대로):", e)
        rap = opts["voice"].startswith("rap")
        lyr_plan = lyrics.rap_plan(plan["seconds"]) if rap else plan
        mood = _melody_mood(src, first) if opts.get("follow_mood", True) and not rap else None
        job["mood"] = _mood_view(mood)
        job.update(stage="lyrics", eta_sec=FINAL_ETA(plan["seconds"]), seconds=plan["seconds"])
        if remix["lyrics"]:
            sections, dropped = lyrics.normalize_sections(remix["lyrics"], lyr_plan)
            if dropped:
                job["lyrics_dropped"] = dropped
            words = {"title": remix.get("title") or (base["title"] if base else job["prompt"]), "style": base["style"] if base else "",
                     "cover": remix.get("cover") or (base.get("scene", "") if base else ""),
                     "sections": sections, "miss": None}
        elif remix["keep_lyrics"] and same_form:
            words = {"title": base["title"], "style": base["style"], "cover": base.get("scene", ""), "sections": base["lyrics"], "miss": base.get("syllable_miss")}
        else:
            words = await lyrics.write(job["prompt"], lyr_plan, seed, mood, T.talk_lines(opts.get("talk")), rap=rap, avoid=_avoid(song))
            # 새로 쓴 가사일 때만 스타일·표지를 멜로디 느낌에 맞춘다(가사·스타일을 지킨 다시 만들기는 그대로)
            words["style"] = melody_mood.merge_style(words["style"], mood, job["prompt"], vocal=sung)
            words["cover"] = melody_mood.cover_scene(words["cover"], mood, job["prompt"])
        if rap:  # 모델이 붙인 R&B·Lo-fi 같은 태그가 힙합 느낌을 흐려서(2026-11-10) 랩 태그만 쓴다
            words["style"] = lyrics.RAP_STYLE
            words["sections"] = lyrics.rap_clean(words["sections"])
        if sung and not rap:  # 가사가 정해지면 줄마다 음표 수 = 한글 글자 수로 맞춘다(다음 줄 글자를 당겨 부르지 않게)
            try:
                abc, job["lyric_fit"] = lyricfit.fit(abc, plan, words["sections"])
            except Exception as e:
                print("가사-음표 맞추기 실패(원래 악보로):", e)
        text = (lyrics.rap_text(words["sections"]) if rap else lyrics.yue_lyrics(plan, words["sections"])) if sung else "[Instrumental]"
        style = _style(words["style"] if rap else (remix["style"] or words["style"]), opts["voice"])
        # 만드는 동안 먼저 보여 줄 것(첫 결과가 준비되면 알린다) — 노래를 기다리는 3분이 덜 지루하게
        job.update(stage="cover", title=words["title"], lyrics=words["sections"] if sung else {}, style=style)
        cover_file, audio_file = f"cover_{job['id']}.png", f"final_{job['id']}.flac"
        if remix["keep_cover"] and base:
            cover_file = base.get("cover", "cover.png")
        else:
            # 제목·한글을 넣으면 그림에 깨진 글자가 그려졌다(2026-11-05 첫 시험) → 가사 모델이 쓴 영어 장면 설명만 쓰고,
            # 글자 금지 지시는 사용자에게 보이지 않게 앞뒤로 붙인다(사용자 요청).
            scene = _cover_scene(words["cover"] or "") or "a quiet road at night seen through a car windshield"
            cover_prompt = f"{COVER_NO_TEXT}. {scene}. album cover art, square composition, cinematic soft light, painterly illustration. {COVER_NO_TEXT}"
            out = await cf.run(C.cover_graph(cover_prompt, seed, f"chajak_server/{song['id']}_cover"))
            (d / cover_file).write_bytes(await cf.fetch_image(out, "save"))
            await cf.free()  # 표지 모델을 내리고 노래 모델을 올린다(둘 다 올라가 있으면 VRAM 이 넘쳐 노래가 몇십 분씩 걸렸다)
        job["_cover"] = str(d / cover_file)
        job["cover_url"] = f"/v1/jobs/{job['id']}/cover"
        job["stage"] = "music"
        out = await cf.run(C.final_graph(abc if w["use_abc"] else "", style, text, seed, plan["seconds"] + 8,
                                         f"chajak_server/{song['id']}_final", w["temperature"], w["cfg_scale"]))
        raw = await cf.fetch_audio(out, "save")
        try:  # 앞 25ms 페이드 인 · 끝 페이드 아웃 · 최대 음량 -1dBFS(처음 지지직·잘림 방지, 2026-11-11)
            raw, mst = await asyncio.get_running_loop().run_in_executor(None, mastering.master, raw)
            job["mastering"] = mst
        except Exception as e:
            print("다듬기 실패(원본 저장):", e, flush=True)
        (d / audio_file).write_bytes(raw)
        # 곡을 다시 읽어 버전을 붙인다 — 만드는 동안 쌓은 트랙을 지우지 않게, 잠근 채로
        async with store.lock(song["id"]):
            fresh = store.load(song["id"])
            kept = {k: v for k, v in opts.items() if k != "talk"} | ({"talk_used": True} if opts.get("talk") else {})
            final = {"id": job["id"], "parent": base["id"] if base else None, "reference": reference, "prompt": job["prompt"], "options": kept, "weights": w,
                     "title": words["title"], "style": style, "scene": words["cover"],
                     "lyrics": words["sections"] if sung else {}, "lyrics_text": text, "syllable_miss": words["miss"], "plan": plan, "seed": seed,
                     "duration_sec": round(sf.info(d / audio_file).duration, 2), "seconds": round(time.time() - job["created"], 1),
                     "file": audio_file, "cover": cover_file, "created": _now_iso(), "abc": abc,
                     "edit": remix.get("edit"), "edit_summary": job.get("edit_summary"),
                     "mood": _mood_view(mood) and {**_mood_view(mood), "genre_applied": bool(melody_mood.style_for(mood, job["prompt"])["genre"])},
                     "melody_abc": src}
            fresh["finals"] = fresh.get("finals", []) + [final]
            fresh["final"] = final
            store.save(fresh)
        job.update(status="done", stage="done", seconds=final["seconds"])
    except Exception as e:
        job.update(status="failed", error=_fail_message(e, "노래를 만들지 못했어요."))


def _final(request) -> tuple[Path, dict]:
    """?v=버전id 가 있으면 그 버전, 없으면 가장 최근 노래."""
    store: Store = request.app["store"]
    song = store.load(request.match_info["song_id"])
    v = request.query.get("v")
    f = next((x for x in song.get("finals", []) if x.get("id") == v), None) if v else None
    f = f or song.get("final")
    if f is None:
        raise ApiError(404, "NOT_FOUND", "노래가 아직 없어요.")
    return store.dir(song["id"]), f


async def final_audio(request):
    d, f = _final(request)
    return _file_response(d / f.get("file", "final.flac"))  # 버전 목록 전(2026-11-05 첫 시험) 곡은 final.flac


async def final_cover(request):
    d, f = _final(request)
    return await _cover_response(request, d / f.get("cover", "cover.png"))


TALK_TTL_SEC = 3600


def _json_ok(obj, status=200):
    return web.json_response(obj, status=status, dumps=lambda o: json.dumps(o, ensure_ascii=False))


async def talk_upload(request):
    """대화로 가사 만들기(저니맵 02) — 분석 작업 시작. multipart audio(차 안 대화, 30분까지 · wav·m4a·mp3) + direction.
    → 202 {id}. 진행·결과는 GET /v1/talk/{id}. 대화는 서버에 보관하지 않는다: 녹음 파일은 받아쓰고 버리고,
    결과(받아쓴 글·정리)는 메모리에만 1시간 두었다가 지운다(작업 파일 jobs.json 에도 안 쓴다). 보관은 앱이 한다."""
    data, filename, fields = await _form(request)
    direction = fields.get("direction") or "그대로"
    if direction not in T.FEELINGS:
        raise ApiError(400, "BAD_OPTION", f"direction 값은 {list(T.FEELINGS)} 중 하나여야 해요.")
    jobs = request.app["talk_jobs"]
    now = time.time()
    for k in [k for k, j in jobs.items() if now - j["created"] > TALK_TTL_SEC]:
        jobs.pop(k, None)
    tid = uuid.uuid4().hex[:12]
    jobs[tid] = {"id": tid, "status": "running", "stage": "decode", "direction": direction, "created": now, "eta_sec": 30}
    _spawn(request.app, (_run_talk(request.app, tid, data, direction)))
    return _json_ok({"id": tid, "status": "running", "stage": "decode"}, status=202)


async def _run_talk(app, tid: str, data: bytes, direction: str):
    job = app["talk_jobs"][tid]
    try:
        try:  # wav·m4a·mp3 모두(PyAV). 15분 대화 푸는 데 약 0.5초
            seconds = len(await asyncio.get_running_loop().run_in_executor(None, T.decode, data)) / 16000
        except Exception:
            raise ApiError(400, "BAD_AUDIO", "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요.")
        if seconds > T.MAX_TALK_SEC + 5:
            raise ApiError(413, "TOO_LONG", f"대화 녹음은 {T.MAX_TALK_SEC // 60}분까지예요.")
        # 받아쓰기는 GPU 로 15분이 약 20초, 정리 약 10초. GPU 를 노래가 쓰고 있으면 CPU 라 더 걸린다
        job.update(stage="stt", seconds=round(seconds, 1), eta_sec=round(20 + seconds / 8))
        t0 = time.perf_counter()
        try:
            heard = await T.transcribe(data)
        except Exception as e:
            if "snapshot" in str(e) or "local_files_only" in str(e) or "No such file" in str(e):
                raise ApiError(503, "STT_UNAVAILABLE", "받아쓰기 모델이 서버에 아직 없어요. 서버 PC 에서 faster-whisper 모델을 받아 주세요.")
            raise
        del data
        # 짧게 "○○ 노래 만들어 줘"라고 부탁한 녹음이면 정리 없이 바로 돌려준다 — 앱이 곧장 노래를 만든다(2026-11-11 사용자)
        ask = T.as_request(heard["text"], heard["seconds"])
        if ask:
            job["result"] = {"transcript": heard["text"], "lines": heard["lines"], "seconds": heard["seconds"], "request": ask,
                             "timing": {"stt_sec": round(time.perf_counter() - t0, 1), "stt_device": heard["device"]}}
            job.update(status="done", stage="done")
            return
        if len(heard["text"].replace(" ", "")) < 8:
            raise ApiError(422, "TALK_EMPTY", "말소리가 거의 들리지 않았어요. 마이크 가까이에서 다시 이야기해 주세요.")
        t1 = time.perf_counter()
        job["stage"] = "digest"
        try:
            story = await T.digest(heard["text"], direction, lines=heard["lines"])
        except T.DigestError as e:
            raise ApiError(502, "TALK_DIGEST_FAILED", f"{e} 잠시 후 다시 시도해 주세요.")
        lines = heard["lines"]
        job["result"] = {"transcript": " ".join(lines), "lines": lines, "seconds": heard["seconds"], **story,
                         "timing": {"stt_sec": round(t1 - t0, 1), "digest_sec": round(time.perf_counter() - t1, 1), "stt_device": heard["device"]}}
        job.update(status="done", stage="done")
    except ApiError as e:
        job.update(status="error", error_code=e.code, error=e.message)
    except Exception as e:
        job.update(status="error", error_code="SERVER_ERROR", error=_fail_message(e, "대화를 분석하지 못했어요."))


async def talk_status(request):
    job = request.app["talk_jobs"].get(request.match_info["talk_id"])
    if job is None:
        raise ApiError(404, "NOT_FOUND", "분석 작업을 찾을 수 없어요(1시간이 지나 지웠거나 서버를 다시 켰어요). 다시 녹음해 주세요.")
    return _json_ok({k: v for k, v in job.items() if k != "created"})


async def talk_digest(request):
    """보관한 대화를 다른 방향으로 다시 정리: JSON {lines: [...], direction}. 받아쓰기 없이 약 10초."""
    body = await _json(request)
    direction = body.get("direction") or "그대로"
    if direction not in T.FEELINGS:
        raise ApiError(400, "BAD_OPTION", f"direction 값은 {list(T.FEELINGS)} 중 하나여야 해요.")
    lines = [str(l)[:500] for l in (body.get("lines") or []) if str(l).strip()][:2000]
    if not lines:
        raise ApiError(400, "BAD_OPTION", "lines(받아쓴 줄 목록)가 필요해요.")
    try:
        story = await T.digest(" ".join(lines), direction, lines=lines)
    except T.DigestError as e:
        raise ApiError(502, "TALK_DIGEST_FAILED", f"{e} 잠시 후 다시 시도해 주세요.")
    return _json_ok(story)


async def write_lyrics(request):
    """가사 미리 보기(2026-11-10): 노래를 만들지 않고 가사만 쓴다. JSON {prompt, seconds, voice, follow_mood, talk}.
    → {title, style, cover, sections: {절: [줄]…}, lines: {구간: [줄마다 글자 수 목표]}, seconds, mood}. 약 1분.
    고친 가사는 POST /v1/songs/{id}/final 의 lyrics(+title·style·cover)로 넘긴다. 곡 구성은 노래 만들기와 같다(멜로디 반영 70 이상)."""
    store: Store = request.app["store"]
    song = store.load(request.match_info["song_id"])
    if not song.get("tracks"):
        raise ApiError(409, "NO_MELODY", "이 곡에는 멜로디가 없어요.")
    body = await _json(request)
    prompt = str(body.get("prompt", "")).strip() or _talk_theme(body) or lyrics.random_theme()
    seconds = float(min(180, max(60, float(body.get("seconds", FINAL_SECONDS)))))
    first = song["tracks"][0]
    src = first["abc"]
    out = await request.app["comfy"].run(C.song_form_graph(src, seconds, True), front=True)
    abc, plan = C.text(out, "abc_out"), json.loads(C.text(out, "plan_out"))
    try:
        lyricfit.phrases(abc, plan)
    except Exception as e:
        print("프레이즈 나누기 실패:", e)
    rap = str(body.get("voice", "")).startswith("rap")
    if rap:
        plan = lyrics.rap_plan(plan["seconds"])
    mood = _melody_mood(src, first) if body.get("follow_mood", True) is not False and not rap else None
    talk = T.sanitize(body.get("talk")) if body.get("talk") else None
    words = await lyrics.write(prompt, plan, int(uuid.uuid4().int % 2**31), mood, T.talk_lines(talk), rap=rap, avoid=_avoid(song))
    if rap:
        words["style"], words["sections"] = lyrics.RAP_STYLE, lyrics.rap_clean(words["sections"])
    else:
        words["style"] = melody_mood.merge_style(words["style"], mood, prompt, vocal=True)
    words["cover"] = melody_mood.cover_scene(words["cover"], mood, prompt)
    lines = {}
    for sec in plan["sections"]:
        if sec.get("sung") and sec["name"] not in lines:
            lines[sec["name"]] = sec.get("syllables", [])
    return _json_ok({"title": words["title"], "style": words["style"], "cover": words["cover"], "sections": words["sections"],
                     "lines": lines, "seconds": plan["seconds"], "prompt": prompt, "mood": _mood_view(mood)})


async def song_form(request):
    """곡 구성 미리보기(플로우 1 '가사 구간 판정 → 구간별 길이 추정'): ?seconds=60|120|180.
    → {seconds, tempo, key, sections: [{name, bars, sung, start_sec, syllables: [줄마다 음표 수 = 글자 수 목표]}]}.
    노래 만들기와 같은 곡 구성 노드를 1번 트랙 멜로디로 돌린다(멜로디 반영을 낮춰 AI 가 다시 쓰면 실제 구성은 달라질 수 있다)."""
    store: Store = request.app["store"]
    song = store.load(request.match_info["song_id"])
    try:
        seconds = float(min(180, max(60, float(request.query.get("seconds", FINAL_SECONDS)))))
    except ValueError:
        raise ApiError(400, "BAD_OPTION", "seconds 는 60~180 숫자여야 해요.")
    if not song.get("tracks"):
        raise ApiError(409, "NO_MELODY", "이 곡에는 멜로디가 없어요.")
    out = await request.app["comfy"].run(C.song_form_graph(song["tracks"][0]["abc"], seconds, True), front=True)
    plan = json.loads(C.text(out, "plan_out"))
    return _json_ok({k: plan[k] for k in ("seconds", "tempo", "key", "sections") if k in plan})


async def get_job(request):
    job = request.app["jobs"].get(request.match_info["job_id"])
    if job is None:
        raise ApiError(404, "NOT_FOUND", "작업을 찾을 수 없어요.")
    return web.json_response({k: v for k, v in job.items() if not k.startswith("_")}, dumps=lambda o: json.dumps(o, ensure_ascii=False))


async def cancel_job(request):
    """노래 만들기 취소. 대기열에 있으면 빼고, 만드는 중이면 멈춘다(작사 모델·ComfyUI 작업도 멈춤). 이미 끝났으면 409."""
    job = request.app["jobs"].get(request.match_info["job_id"])
    if job is None:
        raise ApiError(404, "NOT_FOUND", "작업을 찾을 수 없어요.")
    if job.get("status") in ("done", "failed", "cancelled", "error"):
        raise ApiError(409, "ALREADY_FINISHED", "이미 끝난 작업이에요.")
    if job.get("kind") != "final" or not job.get("_task"):
        raise ApiError(400, "NOT_CANCELLABLE", "이 작업은 취소할 수 없어요.")
    job.update(status="cancelled", stage="cancelled", error="취소했어요.")
    job.pop("queue_ahead", None)
    job["_task"].cancel()   # 대기 중이면 대기열에서, 만드는 중이면 그 자리에서 멈춘다(final_lock 은 저절로 풀린다)
    _save_jobs(request.app["jobs"])
    return web.json_response({k: v for k, v in job.items() if not k.startswith("_")}, dumps=lambda o: json.dumps(o, ensure_ascii=False))


async def job_cover(request):
    """만드는 중인 노래의 표지(그려지는 대로 먼저 보여 준다)."""
    job = request.app["jobs"].get(request.match_info["job_id"])
    path = Path(job["_cover"]) if job and job.get("_cover") else None
    if path is None:
        raise ApiError(404, "NOT_FOUND", "표지가 아직 없어요.")
    return await _cover_response(request, path)


async def hum_audio(request):
    """트랙의 허밍 원본(전/후 들어보기 — 올린 소리는 라이브러리에 남아 언제든 다시 쓴다). 변주 트랙은 원본이 없다."""
    store: Store = request.app["store"]
    d = store.dir(request.match_info["song_id"])
    n = int(request.match_info["n"])
    found = sorted(d.glob(f"hum{n}.*"))
    if not found:
        raise ApiError(404, "NOT_FOUND", "이 트랙에는 허밍 원본이 없어요.")
    kind = {".wav": "audio/wav", ".flac": "audio/flac", ".ogg": "audio/ogg", ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac"}
    return web.FileResponse(found[0], headers={"Content-Type": kind.get(found[0].suffix.lower(), "application/octet-stream")})


# 공유 토큰(2026-11-11, 44번 #1): 터널로 밖에 열면 주소만 알면 곡·허밍·대화를 보고 지우고 GPU 를 쓸 수 있었다.
# 토큰은 CHAJAK_TOKEN 환경변수 → ~/.chajak/token 파일 순으로 읽는다(저장소에 넣지 않는다). 없으면 인증 없이(로컬 개발).
# 앱은 X-Chajak-Token 헤더로, 차량 미디어 센터처럼 헤더를 못 붙이는 음원·표지 주소는 ?t= 로 보낸다. /health 는 주소 확인용이라 예외.
def _load_token() -> str:
    t = os.environ.get("CHAJAK_TOKEN", "").strip()
    if not t:
        f = Path(os.environ.get("CHAJAK_TOKEN_FILE", Path.home() / ".chajak" / "token"))
        t = f.read_text(encoding="utf-8").strip() if f.exists() else ""
    return t


TOKEN = _load_token()
OPEN_PATHS = {"/health"}
# 접속 기록(2026-11-11 협업자: 서버에 요청이 오는지 로그로 보고 싶다). 진행 조회(2~4초마다)는 성공이면 남기지 않는다.
# 주소의 ?t=(토큰)는 찍지 않도록 경로만 남긴다.
QUIET_OK = ("/v1/jobs/", "/v1/talk/", "/health")


@web.middleware
async def access_log(request, handler):
    t0 = time.perf_counter()
    status = 500
    try:
        resp = await handler(request)
        status = resp.status
        return resp
    except web.HTTPException as e:
        status = e.status
        raise
    finally:
        if not (status < 400 and request.method == "GET" and request.path.startswith(QUIET_OK)):
            who = request.headers.get("CF-Connecting-IP") or request.remote or "-"
            note = " (토큰 없음/틀림)" if status == 401 else ""
            print(f"{time.strftime('%H:%M:%S')} {who} {request.method} {request.path} → {status}{note} "
                  f"{(time.perf_counter() - t0) * 1000:.0f}ms", flush=True)


@web.middleware
async def auth(request, handler):
    if TOKEN and request.path not in OPEN_PATHS:
        got = request.headers.get("X-Chajak-Token") or request.query.get("t") or ""
        if not hmac.compare_digest(got.encode(), TOKEN.encode()):
            return _error_json(401, "UNAUTHORIZED", "이 서버를 쓸 수 있는 앱이 아니에요. 앱을 다시 설치하거나 서버 주소를 확인해 주세요.")
    return await handler(request)


ENGINE_MSG = "생성 엔진에서 문제가 생겼어요. 잠시 후 다시 시도해 주세요."
# aiohttp 가 직접 내는 오류(라우트 없음·메서드·본문 크기)도 같은 {"error": {code, message}} 형식으로(2026-11-11, 44번 #3)
HTTP_ERRORS = {400: ("BAD_REQUEST", "요청을 읽지 못했어요."), 404: ("NOT_FOUND", "찾는 주소가 없어요."),
               405: ("METHOD_NOT_ALLOWED", "이 주소에서는 쓸 수 없는 요청이에요."),
               413: ("TOO_LARGE", "보낸 파일이 너무 커요(20MB 이하).")}


def _fail_message(e: Exception, default: str) -> str:
    """작업 실패를 앱에 보일 문장으로. 앱용 오류(ApiError)만 그대로, 나머지는 기본 문장 — 원문(경로·예외)은 서버 로그에만."""
    if isinstance(e, ApiError):
        return e.message
    if isinstance(e, C.ComfyError) and e.kind in HUM_ERRORS:
        return HUM_ERRORS[e.kind][1]
    print(f"작업 실패: {type(e).__name__}: {e}", flush=True)
    traceback.print_exception(e)
    return default + " 잠시 후 다시 시도해 주세요."


def _error_json(status: int, code: str, message: str) -> web.Response:
    return web.json_response({"error": {"code": code, "message": message}}, status=status,
                             dumps=lambda o: json.dumps(o, ensure_ascii=False))


@web.middleware
async def errors(request, handler):
    try:
        return await handler(request)
    except ApiError as e:
        return _error_json(e.status, e.code, e.message)
    except web.HTTPException as h:
        if h.status < 400:  # 리다이렉트 등은 그대로
            raise
        code, message = HTTP_ERRORS.get(h.status, ("HTTP_ERROR", "요청을 처리하지 못했어요."))
        return _error_json(h.status, code, message)
    except Exception as e:  # 예상 못 한 오류: 원문은 서버 로그에만(경로·내부 값이 앱으로 나가지 않게)
        print(f"서버 오류 {request.method} {request.path}: {type(e).__name__}: {e}", flush=True)
        traceback.print_exception(e)
        return _error_json(500, "SERVER_ERROR", "서버에서 문제가 생겼어요. 잠시 후 다시 시도해 주세요.")


JOBS_FILE = STORAGE / "jobs.json"


def _save_jobs(jobs: dict):
    """작업 상태를 파일로(숨은 _ 값은 빼고). 서버를 다시 켜도 앱이 작업 결과를 물을 수 있게."""
    try:
        recent = sorted(jobs.values(), key=lambda j: j.get("created", 0))[-200:]
        _write_atomic(JOBS_FILE, json.dumps({j["id"]: {k: v for k, v in j.items() if not k.startswith("_") and k != "options"}
                                            | {"options": {k: v for k, v in j.get("options", {}).items() if k != "talk"}}
                                            for j in recent}, ensure_ascii=False))
    except Exception as e:  # 저장 실패가 서버를 멈추면 안 된다
        print("작업 저장 실패:", e)


def _load_jobs() -> dict:
    """다시 켤 때: 끝나지 않은 작업은 '서버가 다시 켜져 멈췄어요'로 실패 처리(앱이 NOT_FOUND 대신 이유를 보여 준다)."""
    try:
        jobs = json.loads(JOBS_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    for j in jobs.values():
        if j.get("status") in ("queued", "running"):
            j.update(status="failed", error="서버가 다시 켜지면서 만들던 노래가 멈췄어요. 다시 만들어 주세요.")
    return jobs


def _spawn(app, coro_tuple):
    """백그라운드 작업 시작. 끝나면 ai_tasks 에서 빠진다(44번 #17)."""
    t = asyncio.create_task(coro_tuple[0] if isinstance(coro_tuple, tuple) else coro_tuple)
    app["ai_tasks"].add(t)
    t.add_done_callback(app["ai_tasks"].discard)
    return t


async def _save_jobs_forever(app):
    last = ""
    while True:
        await asyncio.sleep(3)
        t = time.time()
        for k in [k for k, j in app["talk_jobs"].items() if t - j["created"] > TALK_TTL_SEC]:
            app["talk_jobs"].pop(k, None)
        now = json.dumps([(j.get("id"), j.get("status"), j.get("stage")) for j in app["jobs"].values()])
        if now != last:
            _save_jobs(app["jobs"])
            last = now


def make_app(comfy_url: str) -> web.Application:
    app = web.Application(middlewares=[access_log, errors, auth], client_max_size=MAX_UPLOAD + 1024 * 1024)
    app["store"] = Store(STORAGE)
    app["comfy"] = C.Comfy(comfy_url)
    app["jobs"] = _load_jobs()
    app["ai_tasks"] = set()
    app["talk_jobs"] = {}
    app["final_lock"] = asyncio.Lock()  # 노래 작업은 차례대로
    app["final_waiting"] = []  # 대화 분석 작업(메모리에만, 1시간)

    async def on_start(app):
        await app["comfy"].start()
        app["job_saver"] = asyncio.create_task(_save_jobs_forever(app))
        # 엔진(ComfyUI)이 늦게 켜져도 서버는 먼저 켠다(44번: 엔진이 꺼져 있으면 서버가 아예 시작되지 않았다).
        # 악기 목록은 뒤에서 받아 온다 — 그 전까지 /health 는 503, 트랙 만들기는 악기 확인을 건너뛴다.
        app["instruments"] = []
        app["extra_nodes"] = []
        app["engine_watch"] = asyncio.create_task(_engine_info_forever(app))

    async def on_stop(app):
        app["engine_watch"].cancel()
        app["job_saver"].cancel()
        _save_jobs(app["jobs"])
        await app["comfy"].close()

    async def _engine_info_forever(app):
        while not app["instruments"]:
            try:
                app["instruments"] = await app["comfy"].instruments()
            except Exception:
                await asyncio.sleep(5)
                continue
            try:
                app["extra_nodes"] = await app["comfy"].extra_custom_nodes()
            except Exception as e:
                print("커스텀 노드 목록을 읽지 못함:", e, flush=True)
            print(f"생성 엔진 연결됨: 악기 {len(app['instruments'])}종", flush=True)
            _warn_extra_nodes(app)

    def _warn_extra_nodes(app):
        if app["extra_nodes"]:
            print(f"경고: ComfyUI 에 차작 노드 말고 커스텀 노드 {len(app['extra_nodes'])}개가 올라와 있습니다"
                  f"({', '.join(app['extra_nodes'][:5])} 등). 모델을 VRAM 에 붙잡아 노래가 몹시 느려질 수 있으니 "
                  "--disable-all-custom-nodes --whitelist-custom-nodes pleos_melody_instrument 로 다시 켜세요.", flush=True)


    app.on_startup.append(on_start)
    app.on_cleanup.append(on_stop)
    app.add_routes([
        web.get("/health", health),
        web.get("/v1/instruments", instruments),
        web.get("/v1/songs", list_songs),
        web.post("/v1/songs", create_song),
        web.get("/v1/songs/{song_id}", get_song),
        web.get("/v1/songs/{song_id}/audio", song_audio),
        web.get("/v1/songs/{song_id}/tracks/{n:\\d+}/audio", track_audio),
        web.delete("/v1/songs/{song_id}", delete_song),
        web.delete("/v1/songs/{song_id}/finals/{final_id}", delete_final),
        web.get("/v1/variations", variations),
        web.post("/v1/songs/{song_id}/variations", add_variation),
        web.get("/v1/jobs/{job_id}", get_job),
        web.get("/v1/jobs/{job_id}/cover", job_cover),
        web.post("/v1/jobs/{job_id}/cancel", cancel_job),
        web.get("/v1/songs/{song_id}/tracks/{n:\\d+}/hum", hum_audio),
        web.get("/v1/themes", themes),
        web.post("/v1/talk", talk_upload),
        web.get("/v1/talk/{talk_id}", talk_status),
        web.post("/v1/talk/digest", talk_digest),
        web.get("/v1/songs/{song_id}/form", song_form),
        web.post("/v1/songs/{song_id}/lyrics", write_lyrics),
        web.post("/v1/songs/{song_id}/final", start_final),
        web.get("/v1/songs/{song_id}/final/audio", final_audio),
        web.get("/v1/songs/{song_id}/final/cover", final_cover),
    ])
    return app


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8790)
    ap.add_argument("--comfy", default="http://127.0.0.1:8188")
    a = ap.parse_args()
    print("인증: " + ("공유 토큰 사용(X-Chajak-Token)" if TOKEN else "꺼짐(토큰 없음 — 로컬 개발용)"), flush=True)
    web.run_app(make_app(a.comfy), host=a.host, port=a.port)
