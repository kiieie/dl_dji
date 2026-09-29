#!/usr/bin/env bash
# 계획 화면 스모크 시험 2: 핵심 파라미터 4개, 전주 개별 제외(F-06), 계획 저장(MISSION).
# 영역·이륙 지점은 docs/evidence/2026-09-29-plan과 같다. 저장 뒤 앱 DB를 꺼내 logcat 결과와 대조한다(verify.py).
# 사전 조건: 에뮬레이터 Pixel_8(2400×1080) 실행 중, 디버그 앱 설치됨, python3 + Pillow, sqlite3. 저장소 루트에서 bash로 실행한다.
set -euo pipefail
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
DIR=$(cd "$(dirname "$0")" && pwd)
DATA=$(mktemp -d)
PKG=com.example.myfirstapp

dump() { $ADB exec-out uiautomator dump /dev/tty; }
# 화면 문구(text) 또는 버튼 설명(content-desc)이 일치하는 요소의 가운데를 누른다.
tapk() {
  local b
  b=$(dump | grep -o "$1=\"$2\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [ -n "$b" ] || { echo "화면에 없음: $2" >&2; exit 1; }
  set -- $b
  $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
  sleep 1.5
}
tap() { tapk text "$1"; }
tapd() { tapk content-desc "$1"; }
at() { $ADB shell input tap "$1" "$2"; sleep 1; }
shot() { $ADB exec-out screencap -p >"$DIR/$1.png"; echo "저장: $1.png"; }
has() { dump | grep -q "$1" && echo "확인: $1" || { echo "실패: $1 없음" >&2; exit 1; }; }
# 버튼 문구가 비활성(enabled=false)인지 확인한다. Compose 버튼은 문구의 부모 노드에 enabled가 붙는다.
disabled() { dump | grep -o "enabled=\"false\"[^>]*>[^<]*<node[^>]*text=\"$1\"" | grep -q . && echo "확인: [$1] 비활성" || { echo "실패: [$1] 활성" >&2; exit 1; }; }

python3 tools/make_test_data.py "$DATA" 1000
$ADB shell pm clear $PKG >/dev/null
for f in poles-1000.csv test-grid.mbtiles; do
  $ADB push "$DATA/$f" "/sdcard/Download/$f" >/dev/null
  $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$f" >/dev/null
done
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 1
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 3
dump | grep -q "Android App Compatibility" && tap "OK" || true

tap "CSV 파일 선택"; tap "poles-1000.csv"; tap "저장"
tap "poles-1000"; sleep 3
tap "지도 파일 추가"; tap "test-grid.mbtiles"; sleep 3

# 1) 영역(이전 증빙과 같은 사각형)과 기본 파라미터
tap "영역 그리기"
at 1600 440; at 1790 440; at 1790 620; at 1600 620
tap "완료"; sleep 2
has "이동 고도"; has "50m"; has "영역 안 전주 48개"; shot 1-params-default

# 2) 범위 검사: 이동 고도를 25m로 내리면 오류 문구가 나오고 [경로 계산]이 막힌다.
for _ in 1 2 3 4 5; do tapd "이동 고도 낮추기"; done
has "이동 고도는 30~120m여야 합니다."; has "25m"
tap "이륙 지점"; at 1709 540
disabled "경로 계산"; shot 2-params-invalid
for _ in 1 2 3 4 5; do tapd "이동 고도 높이기"; done
# 예비율 30% → 40%: 소티당 예산이 줄어 소티가 늘어난다.
tapd "예비율 높이기"; tapd "예비율 높이기"
has "40%"

# 3) 전주 개별 제외: 영역 가운데에 가장 가까운 개별 전주 점을 눌러 [제외]한다.
$ADB exec-out screencap -p >"$DATA/map.png"
read -r PX PY < <(python3 "$DIR/find_pole.py" "$DATA/map.png" 1709 540 1100)
at "$PX" "$PY"
tap "제외"
has "경로에서 제외됨"; has "영역 안 전주 48개 · 제외 1개"; shot 3-excluded
tap "닫기"

# 4) 경로 계산 → 계획 저장
$ADB logcat -c
tap "경로 계산"; sleep 3
has "소티 "; tap "계획 저장"; sleep 2
has "계획을 저장했습니다: 미션 #1"; has "저장됨"; shot 4-saved
$ADB logcat -d -s RoutePlan:I | grep -E "vertices=|saved mission=" >"$DIR/routeplan.log"
cat "$DIR/routeplan.log"

# 5) 앱 DB(WAL 포함)를 꺼내 저장 내용을 대조한다.
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
done
python3 "$DIR/verify.py" "$DATA/poles-1000.csv" "$DIR/routeplan.log" "$DATA/inspection.db"
echo "스모크 시험 통과"
