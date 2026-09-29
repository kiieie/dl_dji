#!/usr/bin/env bash
# 소티 완료 화면과 2인 운용 스모크 시험(모의 기체): 전주 5개 계획 → 비행 → 3개 점검 뒤 RTH(길게) → 소티 완료 화면
# → 보조자가 [1차 판독 시작](검출 필터) → 판정 저장 → 뒤로 가면 비행 화면(실행기 유지)에 판독 수가 반영됨
# → 조종사 확인 항목 체크 → [다음 소티 시작] → 남은 2개 점검 → 미션 완료 → 결과(모의 검출은 사진 5장마다 1장이라 모두 2개). 끝나면 DB를 꺼내 대조한다.
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

rows() { dump | grep -o 'text="[0-9]-[0-9] · [^"]*"' | wc -l | tr -d ' '; }
# 길게 누르기(RTH): content-desc로 찾은 위치를 1.5초 누른다.
longpress() {
  local b
  b=$(dump | grep -o "content-desc=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  $ADB shell input swipe $((($1 + $3) / 2)) $((($2 + $4) / 2)) $((($1 + $3) / 2)) $((($2 + $4) / 2)) 1500
}

# 2) 비행 → 전주 3개 처리 → 다음 구간 이동 중 RTH(길게)
$ADB logcat -c
tap "비행 시작"
wait_for "처리 3/5" 240
wait_for "이동 중" 60
longpress "RTH 길게 누르기"
wait_for "복귀 중" 10

# 3) 소티 완료 화면: 조종사 칸(확인 항목을 체크해야 다음 소티), 보조자 칸(이번 소티 처리 3 · 검출 1)
wait_for "소티 1/1 완료" 120
has "보조자 · 1차 판독"; has "이번 소티 전주 3 · 검출 1 · 판독 0/3"; has "남은 전주 2"
tap "다음 소티 시작"; sleep 2; has "소티 1/1 완료"; echo "확인: 확인 항목 체크 전에는 [다음 소티 시작]이 동작하지 않음"
shot 1-sortie-done

# 4) 보조자: [1차 판독 시작] → 검출 필터로 열린 결과 화면 → [검출 맞음] 저장
tap "1차 판독 시작"
has "결과 — 미션 #1"
[ "$(rows)" = 1 ] && echo "확인: 검출 필터로 열림(전주 1개)" || { echo "실패: 검출 필터" >&2; exit 1; }
has "조류 둥지 90%"
tap "검출 맞음"; tap "1차 판독 저장 후 다음"; wait_for "판정 저장: 검출 맞음"
shot 2-review-from-sortie

# 5) 뒤로 → 비행 화면으로 돌아오면 실행기가 그대로이고 판독 수가 반영된다
$ADB shell input keyevent BACK
wait_for "판독 1/3" 10; has "소티 1/1 완료"
shot 3-back-to-sortie

# 6) 조종사: 확인 항목 체크 → [다음 소티 시작] → 남은 전주 → 미션 완료 → 결과
tap "배터리 교체"; tap "프로펠러·기체 확인"
tap "다음 소티 시작"
wait_for "미션 완료" 240; shot 4-mission-done
tap "결과 보기"; has "완료 5 · 건너뜀 0 · 실패 0 · 미점검 0 · 검출 2 · 판독 1/5"
shot 5-result
$ADB logcat -d -s Sound:I | grep -o "Sound *: .*" | sed 's/Sound *: /Sound: /' >"$DIR/sound.log"

# 7) DB 대조
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
done
python3 "$DIR/verify.py" "$DATA/inspection.db" "$DIR/sound.log" | tee "$DIR/verify.txt"
echo "스모크 시험 통과"
