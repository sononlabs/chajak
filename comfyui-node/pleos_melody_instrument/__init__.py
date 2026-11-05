"""Pleos 멜로디 악기 렌더 커스텀 노드.

SheetSage2 로 전사한 ABC 멜로디를 사운드폰트(GM) 샘플 악기 음색으로 즉시 렌더링한다.
- 피아노 / 첼로 / 바이올린 / 리코더 등 128개 GM 프리셋 드롭다운
- SheetSage2 출력(리스트 문자열)을 그대로 연결 가능
- 결정론적 렌더링: 같은 입력 → 같은 소리 (반복 다듬기에 적합)
"""

from __future__ import annotations

import os
import traceback

import numpy as np
import torch

from comfy_api.latest import io

from .abc_events import best_voice, parse_abc, parse_by_voice, summarize
from .arrange import render_arrangement
from .hum_prepare import prepare_hum
from .melody_freedom import apply_freedom, estimate_seconds, route_melody
from .melody_variation import VARIATIONS, vary
from .song_form import song_form
from .soundfont_render import (
    SoundFontBank,
    list_soundfonts,
    render_abc,
    resolve_soundfont,
)

DEFAULT_ABC = """X:1
T:
M:4/4
L:1/16
Q:1/4=110
K:C
V: Vocal
C6E6G4-|G4z2E6D4-|D2F6A8|G8C6E2-|
"""

BANK_CACHE: dict = {}


def _bank(path: str, samplerate: int) -> SoundFontBank:
    key = (path, samplerate)
    if key not in BANK_CACHE:
        BANK_CACHE[key] = SoundFontBank(path, samplerate=samplerate)
    return BANK_CACHE[key]


def _font_options():
    fonts = list_soundfonts()
    return fonts if fonts else ["(설치된 사운드폰트 없음)"]


def _instrument_options():
    """기본 폰트의 GM 프리셋 목록 (GM 번호 포함 -> 실행 시 다른 폰트에서도 동일 번호로 해석)."""
    fonts = list_soundfonts()
    if not fonts:
        return ["GM 0 Grand Piano"]
    try:
        bank = SoundFontBank(resolve_soundfont(fonts[0]), samplerate=44100)
        return bank.preset_labels()
    except Exception:
        return ["GM 0 Grand Piano"]


def _as_text(value) -> str:
    """링크 입력은 리스트로 들어올 수 있다(SheetSage2 의 is_output_list)."""
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        parts = [str(v) for v in value if v]
        return "\n".join(parts)
    return str(value)


class PleosSoundFontRender(io.ComfyNode):
    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosSoundFontRender",
            display_name="Pleos SoundFont Render",
            category="pleos/melody",
            description=(
                "ABC 멜로디를 사운드폰트 악기 음색(피아노·첼로·바이올린·리코더 등)으로 "
                "즉시 렌더링합니다. SheetSage2 Audio to ABC 의 abc 출력을 연결하세요."
            ),
            inputs=[
                io.String.Input(
                    "abc_text",
                    multiline=True,
                    default=DEFAULT_ABC,
                    tooltip="ABC 표기 텍스트. SheetSage2 Audio to ABC 출력에 연결하면 자동으로 채워집니다.",
                ),
                io.Combo.Input(
                    "soundfont",
                    options=_font_options(),
                    tooltip="models/soundfonts 안의 사운드폰트 파일",
                ),
                io.Combo.Input(
                    "instrument",
                    options=_instrument_options(),
                    tooltip="악기 (GM 프로그램). 리버브/음량 등은 아래 슬라이더로 조절",
                ),
                io.Combo.Input(
                    "voice",
                    options=["auto", "Vocal", "Ins"],
                    default="auto",
                    tooltip=(
                        "ABC 보이스 선택. auto 는 '음표가 가장 많은 보이스'를 고릅니다 "
                        "(SheetSage2 는 허밍 멜로디를 보통 Ins 보이스에 넣습니다)."
                    ),
                ),
                io.Int.Input(
                    "tempo", default=0, min=0, max=400,
                    tooltip="0 이면 ABC 에 적힌 템포 사용",
                ),
                io.Int.Input(
                    "transpose", default=0, min=-24, max=24,
                    tooltip="반음 단위 전조. 음역이 낮으면 +12 로 올려보세요",
                ),
                io.Int.Input("velocity", default=100, min=1, max=127, tooltip="칠 강도"),
                io.Float.Input("gain_db", default=-3.0, min=-40.0, max=12.0, tooltip="출력 음량(dB)"),
                io.Int.Input(
                    "loop_count", default=1, min=1, max=16,
                    tooltip="멜로디 반복 횟수. 여러 번 들어보고 고칠 때 사용",
                ),
                io.Float.Input("reverb_mix", default=0.18, min=0.0, max=1.0, tooltip="리버브 혼합비"),
                io.Float.Input("reverb_room", default=0.9, min=0.1, max=3.0, tooltip="리버브 공간 크기(초)"),
                io.Int.Input(
                    "samplerate", default=44100, min=22050, max=48000,
                    tooltip="출력 샘플레이트",
                ),
            ],
            outputs=[
                io.Audio.Output(display_name="audio"),
                io.String.Output(display_name="summary"),
            ],
        )

    @classmethod
    async def execute(
        cls,
        abc_text,
        soundfont,
        instrument,
        voice="",
        tempo=0,
        transpose=0,
        velocity=100,
        gain_db=-3.0,
        loop_count=1,
        reverb_mix=0.18,
        reverb_room=0.9,
        samplerate=44100,
    ):
        text = _as_text(abc_text)
        if not text.strip():
            raise RuntimeError(
                "ABC 텍스트가 비어 있습니다. SheetSage2 Audio to ABC 를 연결하거나 "
                "abc_text 칸에 ABC 표기를 직접 붙여넣으세요."
            )

        path = resolve_soundfont(soundfont)
        if not os.path.isfile(path):
            raise RuntimeError(
                f"사운드폰트 파일을 찾을 수 없습니다: {path}\n"
                "models/soundfonts 폴더에 .sf2 파일을 넣어주세요."
            )

        bank = _bank(path, int(samplerate))
        preset = bank.resolve_preset(instrument)
        try:
            audio, sr, summary = render_abc(
                text,
                bank,
                preset_index=preset,
                tempo=float(tempo or 0),
                transpose=int(transpose),
                velocity=int(velocity),
                gain_db=float(gain_db),
                loop_count=int(loop_count),
                reverb_mix=float(reverb_mix),
                reverb_room=float(reverb_room),
                voice=str(voice or ""),
            )
        except Exception as exc:  # 사용자에게 원인을 그대로 보여준다
            traceback.print_exc()
            parsed = parse_abc(text, voice=str(voice or "") if voice else None)
            detail = summarize(parsed, limit=20)
            raise RuntimeError(f"멜로디 렌더링 실패: {exc}\n--- ABC 파싱 결과 ---\n{detail}") from exc

        wave = torch.from_numpy(np.ascontiguousarray(audio.T)).unsqueeze(0)  # [1, C, N]
        name = bank.preset_name(preset)
        info = f"{summary} · 악기 {name}(GM {preset}) · 반복 {int(loop_count)}회"
        return io.NodeOutput({"waveform": wave, "sample_rate": int(sr)}, info)


class PleosABCParse(io.ComfyNode):
    """ABC 텍스트를 파싱해 음표 목록만 확인/디버깅하는 노드."""

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosABCParse",
            display_name="Pleos ABC Parse",
            category="pleos/melody",
            description="ABC 텍스트를 파싱해 음표/템포/보이스를 텍스트로 보여줍니다.",
            inputs=[
                io.String.Input("abc_text", multiline=True, default=DEFAULT_ABC),
                io.Combo.Input("voice", options=["auto", "Vocal", "Ins"], default="auto"),
            ],
            outputs=[io.String.Output(display_name="notes")],
        )

    @classmethod
    async def execute(cls, abc_text, voice="auto"):
        text = _as_text(abc_text)
        by_voice = parse_by_voice(text)
        want = (voice or "auto").strip()
        if want.lower() in ("", "auto", "all"):
            name, parsed = best_voice(by_voice)
        else:
            name, parsed = want, by_voice.get(want)
            if parsed is None or not parsed.notes:
                name, parsed = best_voice(by_voice)
        if parsed is None:
            return io.NodeOutput("보이스 없음")
        return io.NodeOutput(summarize(parsed, limit=200))


class PleosPrepareHum(io.ComfyNode):
    """마이크 녹음을 SheetSage2 가 전사할 수 있는 허밍 오디오로 정제한다.

    잡음 제거 → 음성 구간만 추출 → 정규화 → (짧으면) 반복해 길이 확보.
    SheetSage2 는 8~10초 이상 이어지는 멜로디가 있어야 전사 결과가 나온다.
    """

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosPrepareHum",
            display_name="Pleos Prepare Hum",
            category="pleos/melody",
            description=(
                "허밍 녹음을 잡음 제거 · 음성 추출 · 정규화 · 반복 처리해 "
                "SheetSage2 가 전사할 수 있는 형태로 만듭니다."
            ),
            inputs=[
                io.Audio.Input("audio"),
                io.Combo.Input(
                    "mode",
                    options=["전처리 + 반복 (권장)", "전처리만", "반복만", "없음"],
                    default="전처리 + 반복 (권장)",
                    tooltip="허밍 정제 모드",
                ),
                io.Float.Input(
                    "min_seconds", default=12.0, min=0.0, max=60.0,
                    tooltip="전사 성숙을 위해 확보할 최소 길이(초). 0 이면 반복하지 않음",
                ),
                io.Float.Input("target_db", default=-22.0, min=-40.0, max=0.0, tooltip="목표 RMS(dB)"),
                io.Boolean.Input("denoise", default=True, tooltip="스펙트럼 감쇠로 잡음 제거"),
                io.Int.Input(
                    "vad_margin_hi", default=18, min=3, max=40,
                    tooltip="잡음 바닥 위 이만큼 dB 이상이면 음성으로 인정",
                ),
            ],
            outputs=[
                io.Audio.Output(display_name="audio"),
                io.String.Output(display_name="summary"),
            ],
        )

    @classmethod
    async def execute(cls, audio, mode="전처리 + 반복 (권장)", min_seconds=12.0,
                      target_db=-22.0, denoise=True, vad_margin_hi=18):
        wave = audio["waveform"]
        sr = int(audio["sample_rate"])
        # [B, C, N] → [N] 모노
        w = wave.detach().to("cpu").float().numpy()
        if w.ndim == 3:
            w = w[0].mean(axis=0)
        elif w.ndim == 2:
            w = w.mean(axis=0) if w.shape[0] <= 8 else w[0]
        x = w.astype(np.float64)

        pre = "전처리" in mode
        tile = ("반복" in mode) and float(min_seconds) > 0
        try:
            y, info = prepare_hum(
                x, sr,
                denoise=bool(denoise) and pre,
                target_db=float(target_db) if pre else -22.0,
                min_seconds=float(min_seconds) if tile else 0.0,
                vad_margin_hi=float(vad_margin_hi),
                do_extract=pre,
                do_normalize=pre,
            )
        except Exception as exc:
            traceback.print_exc()
            raise RuntimeError(str(exc)) from exc

        out = torch.from_numpy(np.ascontiguousarray(y)).float().unsqueeze(0).unsqueeze(0)
        vad = info.get("vad", {})
        pieces = info.get("extract", {}).get("pieces", 0)
        reps = info.get("tile", {}).get("repeats", 1)
        summary = (
            f"입력 {info.get('input_sec', 0):.1f}s → 출력 {info.get('output_sec', 0):.1f}s · "
            f"음성구간 {pieces}개 · 반복 {reps}회 · "
            f"잡음바닥 {vad.get('noise_floor_db', '?')}dB · "
            f"결과 {info.get('output_rms_db', 0):.1f}dB"
        )
        return io.NodeOutput({"waveform": out, "sample_rate": sr}, summary)


class PleosMelodyFreedom(io.ComfyNode):
    """허밍 악보를 그대로 쓸지, 다듬을지, AI 편곡(YuE2 melody 모드)에 넘길지 슬라이더 하나로 정한다."""

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosMelodyFreedom",
            display_name="Pleos Melody Freedom (차작 멜로디 자유도)",
            category="pleos/melody",
            description=(
                "0 = 내 허밍 그대로, 0.33~ = 다듬기(임시표 정리·아주 짧은 음 제거), "
                "0.67~ = AI 편곡(다듬은 멜로디로 YuE2 melody 모드). 멜로디는 항상 Ins 성부로 옮긴다."
            ),
            inputs=[
                io.String.Input("abc", force_input=True),
                io.Float.Input(
                    "freedom", default=0.0, min=0.0, max=1.0, step=0.01,
                    display_mode=io.NumberDisplay.slider,
                    tooltip="왼쪽: 내 허밍 그대로 + 자동 반주 / 오른쪽: AI가 편곡(약 30~45초, 멜로디가 조금 바뀔 수 있음)",
                ),
            ],
            outputs=[
                io.String.Output(display_name="abc"),
                io.Boolean.Output(display_name="use_ai"),
                io.String.Output(display_name="summary"),
            ],
        )

    @classmethod
    def execute(cls, abc, freedom) -> io.NodeOutput:
        abc = route_melody(_as_text(abc))
        out, stage, counts = apply_freedom(abc, freedom)
        use_ai = stage == "AI 편곡"
        music_sec, eta, eta_max = estimate_seconds(abc)
        if use_ai:
            head = (
                f"AI 편곡(YuE2) · 예상 제작 시간 약 {eta:.0f}초 · 길게 만들면 최대 {eta_max:.0f}초 "
                f"(악보 기준 곡 길이 {music_sec:.0f}초, 첫 실행이면 +5~10초)"
            )
        else:
            head = f"내 멜로디 + 자동 반주 · 예상 제작 시간 1~3초 (곡 길이 약 {music_sec:.0f}초)"
        summary = (
            f"{head}\n자유도 {freedom:.2f} · {stage} · "
            f"임시표 정리 {counts['accidental']} · 짧은 음 제거 {counts['short']}"
        )
        return io.NodeOutput(out, use_ai, summary)


class PleosMelodyVariation(io.ComfyNode):
    """허밍 악보를 음계 규칙으로 바꿔 변주 멜로디를 만든다(화음 멜로디·분위기 바꾸기·꾸밈·리듬·뒤집기 등)."""

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosMelodyVariation",
            display_name="Pleos Melody Variation (차작 멜로디 변주)",
            category="pleos/melody",
            description=" / ".join(f"{k}: {v}" for k, v in VARIATIONS.items()),
            inputs=[
                io.String.Input("abc", force_input=True),
                io.Combo.Input("variation", options=list(VARIATIONS), tooltip="화음 멜로디는 겹치기, 한 단계 위로는 이어 붙이기에 잘 어울려요"),
            ],
            outputs=[
                io.String.Output(display_name="abc"),
                io.String.Output(display_name="summary"),
            ],
        )

    @classmethod
    def execute(cls, abc, variation) -> io.NodeOutput:
        return io.NodeOutput(*vary(_as_text(abc), variation))


class PleosSongForm(io.ComfyNode):
    """흥얼거린 한 부분을 약 2분짜리 곡(전주·절·후렴·브리지·후주)으로 늘리고, 마디마다 화음을 적는다(YuE2 full 모드용)."""

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosSongForm",
            display_name="Pleos Song Form (차작 2분 곡 만들기)",
            category="pleos/melody",
            description="허밍 악보 → 곡 구성 + 화음 표기 ABC. plan 은 구간별 마디·가사 줄마다 음 개수(JSON).",
            inputs=[
                io.String.Input("abc", force_input=True),
                io.Float.Input("target_seconds", default=120.0, min=30.0, max=300.0, step=5.0),
                io.Boolean.Input("sung", default=True, label_on="노래", label_off="연주곡", tooltip="노래면 멜로디를 Vocal 성부에, 연주곡이면 Ins 성부에"),
            ],
            outputs=[
                io.String.Output(display_name="abc"),
                io.String.Output(display_name="plan"),
            ],
        )

    @classmethod
    def execute(cls, abc, target_seconds, sung) -> io.NodeOutput:
        return io.NodeOutput(*song_form(_as_text(abc), float(target_seconds), bool(sung)))


def _gm_default(program: int) -> str:
    options = _instrument_options()
    return next((o for o in options if o.startswith(f"GM {program} ")), options[0])


class PleosAutoArrange(io.ComfyNode):
    """허밍 멜로디를 음 하나 바꾸지 않고, 화음·베이스를 붙여 함께 연주한다."""

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosAutoArrange",
            display_name="Pleos Auto Arrange (차작 자동 반주)",
            category="pleos/melody",
            description="악보의 멜로디는 그대로 두고, 마디마다 어울리는 화음과 베이스를 붙여 사운드폰트로 연주합니다.",
            inputs=[
                io.String.Input("abc", force_input=True),
                io.Combo.Input("soundfont", options=_font_options()),
                io.Combo.Input("melody_instrument", options=_instrument_options(), default=_gm_default(0)),
                io.Combo.Input("chord_instrument", options=_instrument_options(), default=_gm_default(89)),
                io.Combo.Input("bass_instrument", options=_instrument_options(), default=_gm_default(33)),
                io.Float.Input("reverb_mix", default=0.2, min=0.0, max=1.0, step=0.01, tooltip="전체 트랙에 한 번만 거는 리버브"),
            ],
            outputs=[
                io.Audio.Output(display_name="audio"),
                io.String.Output(display_name="summary"),
            ],
        )

    @classmethod
    def execute(cls, abc, soundfont, melody_instrument, chord_instrument, bass_instrument, reverb_mix) -> io.NodeOutput:
        bank = _bank(resolve_soundfont(soundfont), 44100)
        audio, summary = render_arrangement(
            _as_text(abc), bank,
            bank.resolve_preset(melody_instrument),
            bank.resolve_preset(chord_instrument),
            bank.resolve_preset(bass_instrument),
            float(reverb_mix),
        )
        wave = torch.from_numpy(np.ascontiguousarray(audio.T)).unsqueeze(0)
        return io.NodeOutput({"waveform": wave, "sample_rate": 44100}, summary)


NO_VOCAL_STYLE = "instrumental only, no vocals, no singing, "


class PleosLyricsToggle(io.ComfyNode):
    """가사 있음/없음 토글.

    없음: 연주곡 지시 + 멜로디를 Ins 성부에만 둔다(2차 시험에서 보컬 섞임 6/9 → 2/9, 완전히 막지는 못한다).
    있음: 멜로디를 Vocal 성부에도 넣어 노래가 허밍 멜로디를 따라 부르게 한다.
    """

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosLyricsToggle",
            display_name="Pleos Lyrics Toggle (차작 가사 토글)",
            category="pleos/melody",
            inputs=[
                io.String.Input("abc", force_input=True),
                io.Boolean.Input("use_lyrics", default=False, label_on="가사 있음", label_off="가사 없음"),
                io.String.Input("style", multiline=True, default="warm indie pop, felt piano lead melody, warm bass, soft drums, night drive home atmosphere"),
                io.String.Input("lyrics", multiline=True, default="", tooltip="가사 있음일 때만 사용"),
            ],
            outputs=[
                io.String.Output(display_name="abc"),
                io.String.Output(display_name="style"),
                io.String.Output(display_name="lyrics"),
                io.Boolean.Output(display_name="use_lyrics"),
            ],
        )

    @classmethod
    def execute(cls, abc, use_lyrics, style, lyrics) -> io.NodeOutput:
        abc = _as_text(abc)
        if use_lyrics:
            return io.NodeOutput(route_melody(abc, ("Vocal", "Ins")), style, lyrics, True)
        return io.NodeOutput(route_melody(abc, ("Ins",)), NO_VOCAL_STYLE + style, "[Instrumental]", False)


class PleosVocalCheck(io.ComfyNode):
    """완성곡을 다시 전사한 악보의 보컬 성부로 보컬 섞임을 확인한다."""

    @classmethod
    def define_schema(cls):
        return io.Schema(
            node_id="PleosVocalCheck",
            display_name="Pleos Vocal Check (차작 보컬 확인)",
            category="pleos/melody",
            inputs=[
                io.String.Input("abc", force_input=True, tooltip="완성곡을 SheetSage2 로 다시 전사한 악보"),
                io.Boolean.Input("use_lyrics", force_input=True),
            ],
            outputs=[io.String.Output(display_name="summary")],
        )

    @classmethod
    def execute(cls, abc, use_lyrics) -> io.NodeOutput:
        by_voice = parse_by_voice(_as_text(abc))
        vocal = len(by_voice["Vocal"].notes) if "Vocal" in by_voice else 0
        if use_lyrics:
            msg = f"가사 있음 · 보컬 음 {vocal}개 감지" + ("" if vocal else " (보컬이 거의 안 들릴 수 있어요)")
        elif vocal >= 5:
            msg = f"⚠ 가사 없음인데 보컬이 섞였어요 (보컬 음 {vocal}개). 시드를 바꿔 다시 실행하세요."
        else:
            msg = f"가사 없음 · 보컬 없음 확인 (보컬 음 {vocal}개)"
        return io.NodeOutput(msg)


NODE_CLASS_MAPPINGS = {
    "PleosLyricsToggle": PleosLyricsToggle,
    "PleosVocalCheck": PleosVocalCheck,
    "PleosSoundFontRender": PleosSoundFontRender,
    "PleosABCParse": PleosABCParse,
    "PleosPrepareHum": PleosPrepareHum,
    "PleosMelodyFreedom": PleosMelodyFreedom,
    "PleosAutoArrange": PleosAutoArrange,
    "PleosMelodyVariation": PleosMelodyVariation,
    "PleosSongForm": PleosSongForm,
}

NODE_DISPLAY_NAME_MAPPINGS = {
    "PleosLyricsToggle": "Pleos Lyrics Toggle (차작 가사 토글)",
    "PleosVocalCheck": "Pleos Vocal Check (차작 보컬 확인)",
    "PleosSoundFontRender": "Pleos SoundFont Render",
    "PleosABCParse": "Pleos ABC Parse",
    "PleosPrepareHum": "Pleos Prepare Hum",
    "PleosMelodyFreedom": "Pleos Melody Freedom (차작 멜로디 자유도)",
    "PleosAutoArrange": "Pleos Auto Arrange (차작 자동 반주)",
    "PleosMelodyVariation": "Pleos Melody Variation (차작 멜로디 변주)",
    "PleosSongForm": "Pleos Song Form (차작 2분 곡 만들기)",
}

__all__ = ["NODE_CLASS_MAPPINGS", "NODE_DISPLAY_NAME_MAPPINGS"]
