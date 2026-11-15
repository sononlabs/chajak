"""Pleos 에뮬레이터를 '주행 중'으로 바꾸는 도구 (Pleos Connect Virtual Console 사용).

에뮬레이터 안의 Virtual Console(포트 8088)에 기어·속도를 보내 실제 주행 규제(CarUxRestrictions)를 켠다.
콘솔은 브라우저 연결이 3초 넘게 끊기면 꺼지므로, 주행 중에는 1초마다 연결을 갱신한다.

    python scripts/pleos_drive.py drive [속도km/h]   # 주행 시작(Ctrl+C 로 끝내면 자동으로 P·정지)
    python scripts/pleos_drive.py park               # 정차(P·0km/h)
    python scripts/pleos_drive.py state              # 지금 주행 상태

화면으로 하고 싶으면: adb forward tcp:18088 tcp:8088 뒤 PC 브라우저에서 http://localhost:18088
(에뮬레이터 앱 목록의 'FormFactorConsole' 도 같은 콘솔). 공식 adb 명령(cmd hmg_car_service …)은 이 에뮬레이터에 없다.
"""
import os, subprocess, sys, time, urllib.request

ADB = os.path.join(os.environ.get('LOCALAPPDATA', ''), r'Android\Sdk\platform-tools\adb.exe')
ENV = dict(os.environ, ANDROID_ADB_SERVER_PORT='5038')
BASE = 'http://127.0.0.1:18088'


def adb(*a):
    return subprocess.run([ADB, '-s', 'emulator-5554', *a], env=ENV, capture_output=True, text=True,
                          encoding='utf-8', errors='replace').stdout


def req(method, path):
    r = urllib.request.Request(BASE + path, method=method, data=b'')
    with urllib.request.urlopen(r, timeout=5) as f:
        return f.read().decode('utf-8', 'replace')


def state():
    s = adb('shell', 'dumpsys car_service --services CarDrivingStateService')
    u = adb('shell', 'dumpsys car_service --services CarUxRestrictionsManagerService')
    line = next((l.strip() for l in s.splitlines() if 'Current Driving State' in l), '?')
    uxr = next((l.strip() for l in u.splitlines() if 'Display id: 0' in l), '?')
    return f'{line} (0 정차 · 1 공회전 · 2 주행) | {uxr}'


def park():
    req('POST', '/browser/session?client=chajak-drive')
    req('PUT', '/vehicle/controls?speed=0&accelerator=0&brake=30&steering=0')
    time.sleep(0.5)
    req('PUT', '/vehicle/gear?gear=Park')
    time.sleep(2)
    print('정차:', state())


def drive(kph):
    req('POST', '/browser/session?client=chajak-drive')
    req('PUT', '/vehicle/gear?gear=Drive')
    req('PUT', f'/vehicle/controls?speed={kph}&accelerator=20&brake=0&steering=0')
    time.sleep(2.5)
    print('주행:', state(), '— Ctrl+C 로 끝내면 정차로 돌아가요')
    try:
        while True:
            req('POST', '/browser/session?client=chajak-drive')
            time.sleep(1)
    except KeyboardInterrupt:
        park()


if __name__ == '__main__':
    adb('forward', 'tcp:18088', 'tcp:8088')
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'state'
    if cmd == 'drive':
        drive(int(sys.argv[2]) if len(sys.argv) > 2 else 40)
    elif cmd == 'park':
        park()
    else:
        print(state())
