"""차작 서버 API 시험: 실제 허밍 녹음으로 곡 만들기 → 쌓기 → 오류 → AI 편곡.

python test_api.py [--server http://127.0.0.1:8790] [--no-ai]
허밍 파일: ComfyUI input 폴더의 chajak_user_hum5.wav 등
"""

import argparse
import asyncio
import io
import time
from pathlib import Path

import aiohttp
import numpy as np
import soundfile as sf

HUMS = Path(r"F:\StabilityMatrix-win-x64\Data\Packages\ComfyUI\input")
OUT = Path(__file__).parent / "test_output"


def form(path_or_bytes, **fields):
    f = aiohttp.FormData()
    data = path_or_bytes if isinstance(path_or_bytes, bytes) else Path(path_or_bytes).read_bytes()
    f.add_field("audio", data, filename="hum.wav", content_type="audio/wav")
    for k, v in fields.items():
        f.add_field(k, v)
    return f


def silence_wav(sec=6.0, sr=48000) -> bytes:
    b = io.BytesIO()
    sf.write(b, np.zeros(int(sec * sr), dtype=np.float32), sr, format="WAV")
    return b.getvalue()


async def main(server: str, ai: bool):
    OUT.mkdir(exist_ok=True)
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        ok &= bool(cond)
        print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")

    async with aiohttp.ClientSession(server) as s:
        async with s.get("/health") as r:
            check("health", r.status == 200, await r.text())
        async with s.get("/v1/instruments") as r:
            inst = (await r.json())["instruments"]
            check("instruments", len(inst) >= 100, f"{len(inst)}개")

        t = time.perf_counter()
        async with s.post("/v1/songs", data=form(HUMS / "chajak_user_hum5.wav", instrument="GM 0 Grand Piano", melody="그대로", accompaniment="true")) as r:
            song = await r.json()
            dt = time.perf_counter() - t
            check("곡 만들기(반주)", r.status == 201, f"{dt:.1f}s · 템포 {song.get('tempo')} · 조성 {song.get('key')} · 화음 {song['tracks'][0]['chords'] if r.status == 201 else song}")
        sid = song["id"]

        cello = next(i for i in inst if i.startswith("GM 42 "))
        t = time.perf_counter()
        async with s.post(f"/v1/songs/{sid}/tracks", data=form(HUMS / "chajak_user_hum4.wav", instrument=cello, mode="overlay")) as r:
            song = await r.json()
            check("겹치기 트랙(첼로)", r.status == 201, f"{time.perf_counter() - t:.1f}s · 트랙 {len(song.get('tracks', []))}개 · 연주 템포 {song['tracks'][-1]['tempo'] if r.status == 201 else song} (부른 템포 {song['tracks'][-1].get('hummed_tempo') if r.status == 201 else ''}) · 조성 이동 {song['tracks'][-1].get('transpose') if r.status == 201 else ''}반음")

        flute = next(i for i in inst if i.startswith("GM 73 "))
        t = time.perf_counter()
        async with s.post(f"/v1/songs/{sid}/tracks", data=form(HUMS / "chajak_loud5s.wav", instrument=flute, mode="append", melody="다듬기")) as r:
            song = await r.json()
            check("이어 붙이기 트랙(플루트)", r.status == 201, f"{time.perf_counter() - t:.1f}s · 곡 길이 {song.get('duration_sec')}s · 시작점 {[x['start_sec'] for x in song.get('tracks', [])]}")

        async with s.get(f"/v1/songs/{sid}/audio") as r:
            data = await r.read()
            (OUT / f"{sid}_mix.flac").write_bytes(data)
            x, sr = sf.read(io.BytesIO(data))
            check("믹스 음원", r.status == 200 and abs(len(x) / sr - song["duration_sec"]) < 0.05, f"{len(x) / sr:.2f}s · {sr}Hz")
        for n in (1, 2, 3):
            async with s.get(f"/v1/songs/{sid}/tracks/{n}/audio") as r:
                (OUT / f"{sid}_track{n}.flac").write_bytes(await r.read())
                check(f"트랙 {n} 음원", r.status == 200)

        async with s.post("/v1/songs", data=form(silence_wav())) as r:
            j = await r.json()
            check("무음 → NO_VOICE", r.status == 422 and j["error"]["code"] == "NO_VOICE", j["error"]["message"])
        rng = np.random.default_rng(0)
        noise = io.BytesIO()
        sf.write(noise, (rng.standard_normal(48000 * 2) * 0.3).astype(np.float32), 48000, format="WAV")
        async with s.post("/v1/songs", data=form(noise.getvalue())) as r:
            j = await r.json()
            check("짧은 잡음 → 422", r.status == 422, f"{j['error']['code']} · {j['error']['message']}")
        async with s.post(f"/v1/songs/{sid}/tracks", data=form(HUMS / "chajak_loud5s.wav", mode="sideways")) as r:
            check("잘못된 mode → 400", r.status == 400, (await r.json())["error"]["message"])
        async with s.get("/v1/songs/000000000000") as r:
            check("없는 곡 → 404", r.status == 404)

        # 변주: 새 곡에 변주를 하나씩 쌓아 듣고 빼 본다. 화음 멜로디(겹치기)·한 단계 위로(이어 붙이기)는 남겨 데모 믹스로.
        async with s.get("/v1/variations") as r:
            variations = (await r.json())["variations"]
            check("변주 목록", len(variations) == 9, " · ".join(v["name"] for v in variations))
        async with s.post("/v1/songs", data=form(HUMS / "chajak_user_hum5.wav")) as r:
            vid = (await r.json())["id"]
        for v in variations:
            t = time.perf_counter()
            async with s.post(f"/v1/songs/{vid}/variations", json={"variation": v["id"]}) as r:
                j = await r.json()
                tr = j["tracks"][-1] if r.status == 201 else {}
                check(f"변주 {v['name']}", r.status == 201, f"{time.perf_counter() - t:.1f}s · {tr.get('mode')} · 반주 {tr.get('accompaniment')} · {tr.get('variation_info', j)}")
            async with s.get(f"/v1/songs/{vid}/tracks/{tr.get('n')}/audio") as r:
                (OUT / f"{vid}_var_{v['id']}.flac").write_bytes(await r.read())
            if v["id"] not in ("harmony", "step_up"):
                async with s.delete(f"/v1/songs/{vid}/tracks/{tr.get('n')}") as r:
                    check(f"  빼기", r.status == 200 and len((await r.json())["tracks"]) == tr.get("n", 0) - 1)
        async with s.get(f"/v1/songs/{vid}/audio") as r:
            (OUT / f"{vid}_variation_mix.flac").write_bytes(await r.read())
        async with s.post(f"/v1/songs/{vid}/variations", json={"variation": "jazz"}) as r:
            check("없는 변주 → 400", r.status == 400, (await r.json())["error"]["message"])
        async with s.delete(f"/v1/songs/{vid}/tracks/1") as r:
            check("첫 트랙 빼기 → 400", r.status == 400)

        if ai:
            async with s.post(f"/v1/songs/{sid}/ai", json={"lyrics": False}) as r:
                job = await r.json()
                check("AI 편곡 시작", r.status == 202)
            t = time.perf_counter()
            while job["status"] in ("queued", "running"):
                await asyncio.sleep(2)
                async with s.get(f"/v1/jobs/{job['id']}") as r:
                    job = await r.json()
            check("AI 편곡 완료", job["status"] == "done",
                  f"{time.perf_counter() - t:.0f}s · 예상 {job.get('eta_sec')}~{job.get('eta_max_sec')}s · 시도 {job.get('attempts')} · 보컬 섞임 {job.get('vocal_leak')} {job.get('error', '')}")
            if job["status"] == "done":
                async with s.get(job["audio_url"]) as r:
                    (OUT / f"{sid}_ai.flac").write_bytes(await r.read())

    print("\n전체", "PASS" if ok else "FAIL", "· 결과 음원:", OUT)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8790")
    ap.add_argument("--no-ai", action="store_true")
    a = ap.parse_args()
    asyncio.run(main(a.server, not a.no_ai))
