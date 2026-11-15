"""차작 서버 API 시험: 실제 허밍 녹음으로 곡 만들기 → 오류 → 변주(화음·다듬기). 시험 곡은 끝에 지운다.

python tests/test_api.py --hum /path/to/hum.wav [--server http://127.0.0.1:8790]
허밍 파일은 5초 이상의 실제 녹음. 토큰은 ~/.chajak/token(있으면).
"""

import argparse
import asyncio
import io
import sys
import time
from pathlib import Path

import aiohttp
import numpy as np
import soundfile as sf

OUT = Path(__file__).parent / "test_output"
TOKEN_FILE = Path.home() / ".chajak" / "token"


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


async def main(server: str, hum: Path):
    OUT.mkdir(exist_ok=True)
    ok = True
    made = []

    def check(name, cond, detail=""):
        nonlocal ok
        ok &= bool(cond)
        print(f"[{'PASS' if cond else 'FAIL'}] {name} {detail}")

    headers = {"X-Chajak-Token": TOKEN_FILE.read_text().strip()} if TOKEN_FILE.exists() else {}
    async with aiohttp.ClientSession(server, headers=headers) as s:
        async with s.get("/health") as r:
            check("health", r.status == 200, await r.text())
        async with s.get("/v1/instruments") as r:
            inst = (await r.json())["instruments"]
            check("instruments", len(inst) >= 100, f"{len(inst)}개")

        t = time.perf_counter()
        async with s.post("/v1/songs", data=form(hum, instrument="GM 0 Grand Piano", melody="그대로", accompaniment="true")) as r:
            song = await r.json()
            dt = time.perf_counter() - t
            check("곡 만들기(반주)", r.status == 201, f"{dt:.1f}s · 템포 {song.get('tempo')} · 조성 {song.get('key')} · 화음 {song['tracks'][0]['chords'] if r.status == 201 else song}")
        sid = song["id"]
        made.append(sid)
        async with s.get(f"/v1/songs/{sid}/audio") as r:
            data = await r.read()
            (OUT / f"{sid}_mix.flac").write_bytes(data)
            x, sr = sf.read(io.BytesIO(data))
            check("믹스 음원", r.status == 200 and abs(len(x) / sr - song["duration_sec"]) < 0.05, f"{len(x) / sr:.2f}s · {sr}Hz")
        async with s.get(f"/v1/songs/{sid}/tracks/1/audio") as r:
            check("트랙 1 음원", r.status == 200)

        async with s.post("/v1/songs", data=form(silence_wav())) as r:
            j = await r.json()
            check("무음 → NO_VOICE", r.status == 422 and j["error"]["code"] == "NO_VOICE", j["error"]["message"])
        rng = np.random.default_rng(0)
        noise = io.BytesIO()
        sf.write(noise, (rng.standard_normal(48000 * 2) * 0.3).astype(np.float32), 48000, format="WAV")
        async with s.post("/v1/songs", data=form(noise.getvalue())) as r:
            j = await r.json()
            check("짧은 잡음 → 422", r.status == 422, f"{j['error']['code']} · {j['error']['message']}")
        async with s.get("/v1/songs/000000000000") as r:
            check("없는 곡 → 404", r.status == 404)

        # 변주: 새 곡에 변주를 하나씩 쌓아 데모 믹스로.
        async with s.get("/v1/variations") as r:
            variations = (await r.json())["variations"]
            check("변주 목록", len(variations) == 4, " · ".join(v["name"] for v in variations))
        async with s.post("/v1/songs", data=form(hum)) as r:
            vid = (await r.json())["id"]
            made.append(vid)
        for v in variations:
            t = time.perf_counter()
            async with s.post(f"/v1/songs/{vid}/variations", json={"variation": v["id"]}) as r:
                j = await r.json()
                tr = j["tracks"][-1] if r.status == 201 else {}
                check(f"변주 {v['name']}", r.status == 201, f"{time.perf_counter() - t:.1f}s · {tr.get('mode')} · {tr.get('instrument')} · {tr.get('variation_info', j)}")
            async with s.get(f"/v1/songs/{vid}/tracks/{tr.get('n')}/audio") as r:
                (OUT / f"{vid}_var_{v['id']}.flac").write_bytes(await r.read())
        async with s.get(f"/v1/songs/{vid}/audio") as r:
            (OUT / f"{vid}_variation_mix.flac").write_bytes(await r.read())
        async with s.post(f"/v1/songs/{vid}/variations", json={"variation": "retrograde"}) as r:
            check("지운 변주 → 400", r.status == 400, (await r.json())["error"]["message"])

        for i in made:
            async with s.delete(f"/v1/songs/{i}") as r:
                check(f"시험 곡 지우기 {i}", r.status == 200)

    print("\n전체", "PASS" if ok else "FAIL", "· 결과 음원:", OUT)
    return ok


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8790")
    ap.add_argument("--hum", type=Path, required=True, help="5초 이상의 실제 허밍 WAV 파일")
    args = ap.parse_args()
    sys.exit(0 if asyncio.run(main(args.server, args.hum)) else 1)
