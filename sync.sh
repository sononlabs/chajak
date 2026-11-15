#!/usr/bin/env bash
# 작업 폴더(앱·서버·ComfyUI 노드)에서 이 저장소로 필요한 파일만 가져온다.
# 빌드 산출물·로컬 설정·사용자 곡과 녹음(storage)·시험 결과는 가져오지 않는다.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
APP="${APP:-$here/../chajak-app}"
SERVER="${SERVER:-$here/../chajak-server}"
NODE="${NODE:-/f/StabilityMatrix-win-x64/Data/Packages/ComfyUI/custom_nodes/pleos_melody_instrument}"

copy() {  # copy <원본> <대상> <제외…>
  local src="$1" dst="$2"; shift 2
  local ex=(); for e in "$@"; do ex+=(--exclude="$e"); done
  rm -rf "$dst"; mkdir -p "$dst"
  tar -C "$src" "${ex[@]}" -cf - . | tar -C "$dst" -xf -
}

copy "$APP" "$here/app" ./README.md build .gradle .kotlin local.properties '*.iml' .idea .probe './chajak_*.png' './chajak_테스트_패키지*' ./apk
copy "$SERVER" "$here/server" ./README.md storage test_output __pycache__ '*.pyc' '*.log' 'test_*.py' 'requirements*.txt' .gitignore
copy "$NODE" "$here/comfyui-node/pleos_melody_instrument" __pycache__ '*.pyc'
# 제출 APK 는 저장소 맨 위 apk/ 에 손으로 넣는다(공식 규칙: apk/팀명_앱이름_release.apk, release 빌드).
# 작업 폴더의 apk/·디버그 APK 에는 서버 토큰이 들어 있을 수 있어 가져오지 않는다.
echo "동기화 끝: app · server · comfyui-node"
