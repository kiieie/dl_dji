#!/usr/bin/env bash
# 미션 목록(①-1)과 이어하기(F-20) 스모크 시험(모의 기체): 계획 저장 → 미션 목록 → 비행 3개 뒤 RTH → [미션 종료]
# → 목록에 남은 전주 2 → 같은 날 [이어하기]는 바로 비행 화면 → DB의 비행 시각을 하루 앞당긴 뒤 [이어하기]는 날짜 확인
# → [이어서 비행] / [남은 전주로 새 계획] → 새 미션 #2 저장, #1은 부분 완료. 끝나면 DB를 꺼내 대조한다.
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

rows() { dump | grep -o 'text="[0-9]-[0-9] · [^"]*"' | wc -l | tr -d ' '; }
# 길게 누르기(RTH): content-desc로 찾은 위치를 1.5초 누른다.
longpress() {
  local b
  b=$(dump | grep -o "content-desc=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  $ADB shell input swipe $((($1 + $3) / 2)) $((($2 + $4) / 2)) $((($1 + $3) / 2)) $((($2 + $4) / 2)) 1500
}

pull_db() {
  for f in inspection.db inspection.db-wal inspection.db-shm; do
    $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
  done
}

# 1) 계획 화면 [← 목록] → 미션 목록: 계획됨
tap "← 목록"
has "미션 #1 · 계획됨"; has "비행 시작"; shot 1-planned

# 2) [비행 시작] → 3개 처리 → RTH(길게) → 소티 완료 → [미션 종료] → 목록에 남은 전주 2와 [이어하기]
tap "비행 시작"; wait_for "비행 대기" 10
tap "비행 시작"
wait_for "처리 3/5" 240; wait_for "이동 중" 60
longpress "RTH 길게 누르기"
wait_for "소티 1/1 완료" 120
tap "미션 종료"; wait_for "미션 완료" 10
tap "나가기"
has "미션 #1 · 완료"; has "남은 전주 2"; has "이어하기"; shot 2-ended-with-remaining

# 3) 같은 날 [이어하기]는 묻지 않고 비행 화면(남은 전주 2개)
tap "이어하기"
wait_for "비행 대기" 10; has "전주 2개"
dump | grep -q "마지막 비행이 오늘이 아닙니다" && { echo "실패: 같은 날인데 날짜 확인이 떴다" >&2; exit 1; } || echo "확인: 같은 날은 날짜 확인 없이 비행 화면"
tap "나가기"

# 4) 앱을 멈추고 DB의 소티 시각을 하루 앞당긴다(어제 비행한 미션을 흉내 낸다)
$ADB shell am force-stop $PKG
pull_db
python3 - "$DATA/inspection.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])  # 같은 폴더의 -wal을 함께 읽는다
db.execute("UPDATE sortie SET startedAt = startedAt - 86400000, endedAt = endedAt - 86400000")
db.commit()
db.execute("PRAGMA journal_mode=DELETE")  # WAL을 합쳐 파일 하나로 만든다
db.close()
PY
$ADB exec-in run-as $PKG sh -c 'cat > databases/inspection.db' <"$DATA/inspection.db"
$ADB shell run-as $PKG rm -f databases/inspection.db-wal databases/inspection.db-shm
$ADB shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 3
dump | grep -q "Android App Compatibility" && tap "OK" || true
tap "poles-1000"; sleep 2
has "미션 #1 · 완료"; shot 3-yesterday

# 5) [이어하기] → 날짜 확인 → [이어서 비행]은 같은 미션으로 비행 화면
tap "이어하기"
has "마지막 비행이 오늘이 아닙니다"; has "남은 전주는 2개입니다"; shot 4-date-dialog
tap "이어서 비행"; wait_for "비행 대기" 10; has "전주 2개"
tap "나가기"

# 6) [이어하기] → 날짜 확인 → [남은 전주로 새 계획] → 남은 2개만 골라 열린 계획 화면 → 저장하면 미션 #2
tap "이어하기"; has "마지막 비행이 오늘이 아닙니다"
tap "남은 전주로 새 계획"; sleep 3
has "미션 #1 의 남은 전주 2개를 골랐습니다"; shot 5-replan-map
tap "이륙 지점"; at 1709 540
tap "경로 계산"; sleep 2
tap "계획 저장"; wait_for "계획을 저장했습니다: 미션 #2 (소티 1개, 전주 2개)" 10
tap "← 목록"
has "미션 #2 · 계획됨"; has "미션 #1 · 부분 완료"; shot 6-after-replan

# 7) DB 대조
pull_db
python3 "$DIR/verify.py" "$DATA/inspection.db" | tee "$DIR/verify.txt"
echo "스모크 시험 통과"
