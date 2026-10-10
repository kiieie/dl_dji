#!/usr/bin/env bash
# 앱 재시작 복구 스모크 시험(6.4, 모의 기체): 전주 5개 계획 → 비행 → 2개 점검 뒤 이동 중 앱 강제 종료
# → 다시 켜면 비행 화면이 바로 열리고 일시정지로 복귀 → [재개] → 남은 3개 점검 → 미션 완료. 종료 직후와 끝의 DB를 대조한다.
# 사전 조건: 에뮬레이터 Pixel_8(2400×1080) 실행 중, 디버그 앱 설치됨, python3 + Pillow. 저장소 루트에서 bash로 실행한다.
set -euo pipefail
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
DIR=$(cd "$(dirname "$0")" && pwd)
DATA=$(mktemp -d)
PKG=com.example.myfirstapp

dump() { $ADB exec-out uiautomator dump /dev/tty; }
tapk() {
  local b
  b=$(dump | grep -o "$1=\"$2\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [ -n "$b" ] || { echo "화면에 없음: $2" >&2; exit 1; }
  set -- $b
  $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
  sleep 1.5
}
tap() { tapk text "$1"; }
at() { $ADB shell input tap "$1" "$2"; sleep 1; }
shot() { $ADB exec-out screencap -p >"$DIR/$1.png"; echo "저장: $1.png"; }
has() { dump | grep -q "$1" && echo "확인: $1" || { echo "실패: $1 없음" >&2; exit 1; }; }
# 문구가 나타날 때까지 기다린다(최대 $2초).
wait_for() {
  local end=$((SECONDS + ${2:-60}))
  while ((SECONDS < end)); do dump | grep -q "$1" && { echo "확인: $1"; return; }; sleep 1; done
  echo "실패: ${2:-60}초 안에 '$1' 없음" >&2; exit 1
}

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
tap "poles-1000"; sleep 2; tap "+ 새 계획"; sleep 3  # 프로젝트 → 미션 목록 → 계획 화면
tap "지도 파일 추가"; tap "test-grid.mbtiles"; sleep 3

# 1) 작은 영역(전주 몇 개) → 이륙 지점 → 계산 → 저장 → [비행 시작]
tap "영역 그리기"
at 1675 505; at 1745 505; at 1745 575; at 1675 575
tap "완료"; sleep 2
tap "이륙 지점"; at 1709 540
tap "경로 계산"; sleep 2
tap "계획 저장"; wait_for "계획을 저장했습니다: 미션 #1" 10
tap "비행 시작"
wait_for "모의 기체" 10; has "비행 대기"


# 2) 비행 → 전주 2개 처리 → 3번째 전주로 이동하는 중에 앱 프로세스를 강제 종료(비정상 종료 흉내)
tap "비행 시작"
wait_for "처리 2/5" 240
wait_for "이동 중" 60
shot 1-before-kill
$ADB shell am force-stop $PKG
echo "확인: 이동 중 앱 강제 종료"
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/killed-$f" 2>/dev/null || true
done

# 3) 다시 켜면 열린 소티를 감지해 비행 화면을 바로 열고 일시정지로 복귀(모의 기체는 목표 전주 상공에 떠 있음)
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null
sleep 3
dump | grep -q "Android App Compatibility" && tap "OK" || true
wait_for "일시정지됨 — 수동 조종 가능" 20
has "앱이 다시 시작되었습니다. 기체 위치를 확인하고 재개하세요."
has "소티 1/1 · 전주 3개 · 처리 0/3"  # 비행 화면은 남은 전주만 읽는다(이어하기와 같음)
shot 2-recovered-paused

# 4) [재개] → 남은 3개 점검 → 미션 완료 → 결과
tap "▶ 재개"
wait_for "미션 완료" 240; shot 3-mission-done
tap "결과 보기"; has "완료 5 · 건너뜀 0 · 실패 0 · 미점검 0"
shot 4-result

# 5) 뒤로 가면 미션 목록(복구 진입 백 스택)
$ADB shell input keyevent BACK; sleep 1; $ADB shell input keyevent BACK; sleep 1
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
done
python3 "$DIR/verify.py" "$DATA/killed-inspection.db" "$DATA/inspection.db" | tee "$DIR/verify.txt"
echo "스모크 시험 통과"
