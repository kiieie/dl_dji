#!/usr/bin/env bash
# 오프라인 지도 스모크 시험: 전주 1,000개 가져오기, 지도 열기, MBTiles 추가, 네트워크를 끈 채 다시 열기.
# 사전 조건: 에뮬레이터 실행 중, 앱 설치됨(./gradlew installDebug), python3 + Pillow. 저장소 루트에서 실행한다.
# 클러스터 확대(3-cluster-zoom.png)와 전주 카드(4-pole-card.png)는 화면 좌표가 데이터마다 달라 손으로 눌러 찍었다.
set -euo pipefail
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
DIR=$(cd "$(dirname "$0")" && pwd)
DATA=$(mktemp -d)

tap() {
  local b
  b=$($ADB exec-out uiautomator dump /dev/tty | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 |
    grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [ -n "$b" ] || { echo "화면에 없음: $1" >&2; exit 1; }
  set -- $b
  $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
  sleep 2
}
shot() { $ADB exec-out screencap -p >"$DIR/$1.png"; echo "저장: $1.png"; }
has() { $ADB exec-out uiautomator dump /dev/tty | grep -q "$1" && echo "확인: $1" || { echo "실패: $1 없음" >&2; exit 1; }; }
launch() {
  $ADB shell am start -W -n com.example.myfirstapp/.MainActivity >/dev/null
  sleep 3
  # 에뮬레이터의 16 KB 페이지 경고 창(DJI 네이티브 라이브러리 때문)이 뜨면 닫는다.
  $ADB exec-out uiautomator dump /dev/tty | grep -q "Android App Compatibility" && tap "OK" || true
}

python3 tools/make_test_data.py "$DATA" 1000
$ADB shell pm clear com.example.myfirstapp >/dev/null
for f in poles-1000.csv test-grid.mbtiles; do
  $ADB push "$DATA/$f" "/sdcard/Download/$f" >/dev/null
  $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$f" >/dev/null
done
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 1
$ADB shell svc wifi enable; $ADB shell svc data enable
launch

tap "CSV 파일 선택"; tap "poles-1000.csv"; has "전주 1000개 · 제외 0행"; tap "저장"
tap "poles-1000"; sleep 3
has "전주 1000개"; has "오프라인 지도(.mbtiles)가 없습니다"; shot 1-map-no-base
tap "지도 파일 추가"; tap "test-grid.mbtiles"; sleep 3
has "지도를 추가했습니다: test-grid.mbtiles"; shot 2-map-with-base

# 네트워크를 끄고 앱을 다시 시작해도 지도(MBTiles)와 전주가 보여야 한다.
$ADB shell svc wifi disable; $ADB shell svc data disable
$ADB shell am force-stop com.example.myfirstapp
launch
tap "poles-1000"; sleep 3
has "전주 1000개 · 지도 1개"; shot 5-offline-restart
$ADB shell svc wifi enable; $ADB shell svc data enable
echo "스모크 시험 통과"
