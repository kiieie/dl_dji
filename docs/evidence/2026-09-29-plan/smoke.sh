#!/usr/bin/env bash
# 계획 화면 스모크 시험: 전주 1,000개와 격자 지도를 연 뒤 영역(사각형 다각형) 그리기 → 이륙 지점 → 경로 계산.
# 앱이 logcat에 남긴 꼭짓점·이륙 지점으로 verify.py가 영역 판정과 가시권 판정을 다시 계산해 앱 결과와 대조한다.
# 사전 조건: 에뮬레이터 Pixel_8(2400×1080) 실행 중, 앱 설치됨, python3 + Pillow. 저장소 루트에서 bash로 실행한다.
# 지도를 누르는 화면 좌표는 이 에뮬레이터와 시험 데이터(seed 42)에 맞춘 값이다.
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
at() { $ADB shell input tap "$1" "$2"; sleep 1; }
shot() { $ADB exec-out screencap -p >"$DIR/$1.png"; echo "저장: $1.png"; }
has() { $ADB exec-out uiautomator dump /dev/tty | grep -q "$1" && echo "확인: $1" || { echo "실패: $1 없음" >&2; exit 1; }; }

python3 tools/make_test_data.py "$DATA" 1000
$ADB shell pm clear com.example.myfirstapp >/dev/null
for f in poles-1000.csv test-grid.mbtiles; do
  $ADB push "$DATA/$f" "/sdcard/Download/$f" >/dev/null
  $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$f" >/dev/null
done
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 1
$ADB shell am start -W -n com.example.myfirstapp/.MainActivity >/dev/null
sleep 3
# 에뮬레이터의 16 KB 페이지 경고 창(DJI 네이티브 라이브러리 때문)이 뜨면 닫는다.
$ADB exec-out uiautomator dump /dev/tty | grep -q "Android App Compatibility" && tap "OK" || true

tap "CSV 파일 선택"; tap "poles-1000.csv"; tap "저장"
tap "poles-1000"; sleep 2; tap "+ 새 계획"; sleep 3  # 프로젝트 → 미션 목록 → 계획 화면
tap "지도 파일 추가"; tap "test-grid.mbtiles"; sleep 3
has "경로 계산"; shot 1-plan-start

# 1) 영역 그리기: 꼭짓점 5개를 찍고 마지막 하나를 되돌린다(사각형 4개가 남음).
tap "영역 그리기"
at 1600 440; at 1790 440; at 1790 620; at 1600 620; at 1400 800
has "꼭짓점을 추가하세요 (5개)"
tap "되돌리기"
has "꼭짓점을 추가하세요 (4개)"; shot 2-area-drawing
tap "완료"
has "영역 안 전주 "; has "이륙 지점을 정하면"; shot 3-area-done

# 2) 이륙 지점 → 경로 계산
# 완료 후 지도가 영역으로 확대되므로 영역 가운데는 지도 영역(패널 오른쪽)의 가운데에 온다.
tap "이륙 지점"; at 1709 540
$ADB logcat -c
tap "경로 계산"; sleep 3
has "소티 "; shot 4-route
$ADB logcat -d -s RoutePlan:I | grep "vertices=" | tail -1 >"$DIR/routeplan.log"
cat "$DIR/routeplan.log"

# 3) 앱 결과를 CSV로 다시 계산해 대조한다.
python3 "$DIR/verify.py" "$DATA/poles-1000.csv" "$DIR/routeplan.log"
echo "스모크 시험 통과"
