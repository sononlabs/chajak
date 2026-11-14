"""HTTP E2E for the API and ComfyUI protocol, using a small local engine stub.

Run from the repository root: .venv/bin/python tests/test_e2e_mock.py
This checks orchestration and storage, not model quality or actual ComfyUI execution.
"""

import asyncio
import io
import sys
import tempfile
import uuid
from pathlib import Path

import aiohttp
import numpy as np
import soundfile as sf
from aiohttp import web

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import server as S


ABC = "X:1\nT:E2E\nM:4/4\nL:1/8\nQ:1/4=100\nK:C\nC2 D2 E2 G2 | A2 G2 E2 C2 |\n"


def audio(fmt="FLAC"):
    out = io.BytesIO()
    t = np.arange(S.SR * 2) / S.SR
    sf.write(out, (0.1 * np.sin(2 * np.pi * 220 * t)).astype("float32"), S.SR, format=fmt)
    return out.getvalue()


def engine():
    app = web.Application()
    jobs = {}

    async def info(request):
        node = request.match_info.get("node")
        if node == "PleosAutoArrange":
            return web.json_response({"PleosAutoArrange": {}})
        if node == "PleosSoundFontRender":
            return web.json_response({"PleosSoundFontRender": {"input": {"required": {
                "instrument": ["STRING", {"options": ["GM 0 Grand Piano"]}]
            }}}})
        return web.json_response({})

    async def upload(request):
        reader = await request.multipart()
        field = await reader.next()
        await field.read()
        return web.json_response({"name": field.filename, "subfolder": "chajak_server"})

    async def prompt(request):
        graph = (await request.json())["prompt"]
        pid = uuid.uuid4().hex
        abc = graph.get("abc_in", {}).get("inputs", {}).get("value", ABC)
        outputs = {
            "abc_out": {"text": [abc]},
            "prep_info": {"text": ["테스트 허밍"]},
            "render_info": {"text": ["테스트 연주"]},
            "var_info": {"text": ["테스트 변주"]},
            "save": {"audio": [{"filename": "test.flac"}]},
            "prep_save": {"audio": [{"filename": "test.flac"}]},
        }
        jobs[pid] = {"status": {"messages": []}, "outputs": outputs}
        return web.json_response({"prompt_id": pid})

    async def history(request):
        pid = request.match_info["pid"]
        return web.json_response({pid: jobs[pid]})

    app.router.add_get("/object_info/{node}", info)
    app.router.add_get("/object_info", info)
    app.router.add_post("/upload/image", upload)
    app.router.add_post("/prompt", prompt)
    app.router.add_get("/history/{pid}", history)
    app.router.add_get("/view", lambda _: web.Response(body=audio()))
    return app


async def start(app):
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    return runner, f"http://127.0.0.1:{site._server.sockets[0].getsockname()[1]}"


async def main():
    with tempfile.TemporaryDirectory(prefix="chajak-e2e-") as tmp:
        S.STORAGE = Path(tmp)
        S.JOBS_FILE = S.STORAGE / "jobs.json"
        engine_runner, engine_url = await start(engine())
        api_runner, api_url = await start(S.make_app(engine_url))
        try:
            async with aiohttp.ClientSession(api_url) as client:
                for _ in range(50):
                    async with client.get("/health") as r:
                        if r.status == 200:
                            break
                    await asyncio.sleep(0.1)
                else:
                    raise AssertionError("engine did not connect")

                form = aiohttp.FormData()
                form.add_field("audio", audio("WAV"), filename="hum.wav", content_type="audio/wav")
                form.add_field("melody", "다듬기")
                form.add_field("accompaniment", "false")
                async with client.post("/v1/songs", data=form) as r:
                    body = await r.json()
                    assert r.status == 201, body
                sid = body["id"]
                assert len(body["tracks"]) == 1 and body["tracks"][0]["score"]

                async with client.get(f"/v1/songs/{sid}/audio") as r:
                    assert r.status == 200
                    assert sf.info(io.BytesIO(await r.read())).duration > 1

                async with client.post(f"/v1/songs/{sid}/variations", json={"variation": "finish"}) as r:
                    body = await r.json()
                    assert r.status == 201, body
                assert len(body["tracks"]) == 2 and body["duration_sec"] > 2

                async with client.get(f"/v1/songs/{sid}") as r:
                    body = await r.json()
                    assert r.status == 200 and len(body["tracks"]) == 2

                async with client.delete(f"/v1/songs/{sid}") as r:
                    assert r.status == 200, await r.text()
                async with client.get(f"/v1/songs/{sid}") as r:
                    assert r.status == 404
                print("PASS: health → upload → create → audio → variation → retrieve → delete")
        finally:
            await api_runner.cleanup()
            await engine_runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
