#!/usr/bin/env bash
# 비행 화면 ④ 스모크 시험(모의 기체): 작은 영역 계획 → 저장 → 비행 시작 → 첫 전주 점검 → RC 일시정지 → 건너뛰기 → 재개
# → 나머지 점검 → 자동 복귀·착륙 → 미션 완료. 끝나면 앱 DB와 이미지 폴더를 꺼내 verify.py로 대조한다.
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
wait_for "모의 기체" 10; has "비행 대기"; shot 1-flight-idle

# 2) 비행 시작 → 첫 전주 점검 후 대기
$ADB logcat -c
tap "비행 시작"
wait_for "이동 중" 30; has "RTH (길게)"; shot 2-flying
wait_for "대기 [0-9]s" 60; shot 3-waiting

# 3) 다음 구간 비행 중 RC 일시정지 → 건너뛰기 → 재개
wait_for "이동 중" 30
tap "RC 일시정지"
wait_for "일시정지됨 — 수동 조종 가능" 10; has "▶ 재개"; has "건너뛰기"
tap "건너뛰기"; wait_for " 건너뜀" 5; shot 4-paused-skip
tap "▶ 재개"
wait_for "이동 중\|구간 준비" 15

# 4) 나머지 전주 점검 → 자동 복귀 → 착륙 → 미션 완료
wait_for "미션 완료" 300; shot 5-mission-done
$ADB logcat -d -s Sound:I | grep -o "Sound *: .*" | sed 's/Sound *: /Sound: /' >"$DIR/sound.log"

# 5) DB(WAL 포함)와 이미지 목록을 꺼내 대조한다.
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
done
$ADB shell ls /sdcard/Android/data/$PKG/files/projects/1/missions/1/images/ >"$DATA/images.txt"
python3 "$DIR/verify.py" "$DATA/inspection.db" "$DATA/images.txt" "$DIR/sound.log"
echo "스모크 시험 통과"
