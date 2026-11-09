"""받아쓰기만 따로 도는 프로세스(2026-11-10: faster-whisper 가 GPU 에서 비정상 종료(c0000409)하며 서버까지 끌고 내려갔다).

python talk_worker.py <녹음 파일> <결과 json>   (CHAJAK_STT_DEVICE=cuda|cpu|auto)
결과: talk._transcribe_sync 와 같은 {text, lines, seconds, device, dropped}. 실패하면 0 이 아닌 종료 코드.
"""
import json
import sys

import talk

if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    with open(src, "rb") as f:
        r = talk._transcribe_sync(f.read())
    with open(out, "w", encoding="utf-8") as f:
        json.dump(r, f, ensure_ascii=False)
