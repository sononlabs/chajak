@echo off
rem 차작 에뮬레이터 켜기(더블클릭용). Claude 데스크톱 앱 안에서 켜면 그 앱의 마이크 권한을 물려받아 PC 마이크가 무음으로 들어온다(2026-11-11).
rem 그래서 탐색기에서 이 파일을 실행한다 — Claude 에서 켤 때도 explorer.exe 로 이 파일을 연다.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start_emulator.ps1" -App
