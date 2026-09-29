#!/usr/bin/env bash
# 결과 화면 ⑤와 1차 판독(F-19) 스모크 시험: 비행 스모크(모의 기체, 전주 5개)로 미션을 끝낸 뒤 [결과 보기] → [검출] 필터
# → 검출 전주 [검출 맞음]·메모 저장 → [미판독] 필터에서 [재촬영]·[검출 없음] 저장 → [재촬영] 필터 → [내보내기] → SAF 저장
# → 프로젝트 목록의 [결과]로 다시 열기. 끝나면 ZIP·DB·기기 사진을 verify.py로 대조한다.
# 사전 조건: 에뮬레이터 Pixel_8(2400×1080) 실행 중, 디버그 앱 설치됨, python3 + Pillow. 저장소 루트에서 bash로 실행한다.
set -euo pipefail
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
DIR=$(cd "$(dirname "$0")" && pwd)
DATA=$(mktemp -d)
PKG=com.example.myfirstapp
FILES=/sdcard/Android/data/$PKG/files/projects/1/missions/1

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
shot() { $ADB exec-out screencap -p >"$DIR/$1.png"; echo "저장: $1.png"; }
has() { dump | grep -q "$1" && echo "확인: $1" || { echo "실패: $1 없음" >&2; exit 1; }; }
# 문구가 나타날 때까지 기다린다(스낵바는 4초 뒤 사라지므로 바로 확인한다).
wait_for() {
  local end=$((SECONDS + ${2:-10}))
  while ((SECONDS < end)); do dump | grep -q "$1" && { echo "확인: $1"; return; }; done
  echo "실패: ${2:-10}초 안에 '$1' 없음" >&2; exit 1
}

# 0) 비행 스모크로 미션 #1을 끝낸다(앱 데이터를 지우고 새로 시작한다).
$ADB shell rm -f /sdcard/Download/poles-1000_mission1*.zip
bash "$DIR/../2026-09-29-flight/smoke.sh"
$ADB logcat -c

# 1) 결과 목록
rows() { dump | grep -o 'text="[0-9]-[0-9] · [^"]*"' | wc -l | tr -d ' '; }
tap "결과 보기"
has "결과 — 미션 #1"; has "완료 4 · 건너뜀 1 · 실패 0 · 미점검 0 · 검출 1 · 판독 0/4 · 모델 fake-1"
shot 1-result-list

# 2) [검출] 필터 → 검출 전주 → 사진과 탐지 박스, 대상 칩은 AI 결과로 미리 켜짐 → [검출 맞음] + 메모 → 저장
tap "검출 1"
[ "$(rows)" = 1 ] && echo "확인: 검출 필터에 전주 1개" || { echo "실패: 검출 필터" >&2; exit 1; }
tap "[0-9]-[0-9] · 완료 · 사진 2"
has "조류 둥지 90%"; has "광각 · AUTO"; has "망원 · AUTO"; has "1차 판독 저장 후 다음"
tap "검출 맞음"
tapk class "android.widget.EditText"; $ADB shell input text "nest%sok"; $ADB shell input keyevent BACK; sleep 1
shot 2-review-detected
tap "1차 판독 저장 후 다음"; wait_for "판정 저장: 검출 맞음"

# 3) [미판독] 필터(완료 전주 중 판정 없는 3개) → 첫 전주 [재촬영] → 다음 전주 [검출 없음] → [재촬영] 필터
tap "미판독 3"
[ "$(rows)" = 3 ] && echo "확인: 미판독 필터에 전주 3개" || { echo "실패: 미판독 필터" >&2; exit 1; }
tap "[0-9]-[0-9] · 완료 · 사진 2"
tap "재촬영"; tap "1차 판독 저장 후 다음"; wait_for "판정 저장: 재촬영"
tap "검출 없음"; tap "1차 판독 저장 후 다음"; wait_for "판정 저장: 검출 없음"
tap "재촬영 1"
[ "$(rows)" = 1 ] && echo "확인: 재촬영 필터에 전주 1개" || { echo "실패: 재촬영 필터" >&2; exit 1; }
shot 3-retake-filter

# 4) 내보내기 → SAF 저장 대화상자(기본 폴더 Downloads) → 저장
tap "내보내기"; sleep 1
has "poles-1000_mission1.zip"; shot 4-save-dialog
tapk text "SAVE" 2>/dev/null || tap "저장"
wait_for "내보냈습니다: 전주 5개, 사진 8장"; shot 5-exported

# 5) 나가기 → 계획 화면 → 프로젝트 목록의 [결과]로 다시 열면 판정이 남아 있다.
tapk content-desc "나가기"; $ADB shell input keyevent BACK; sleep 2
tap "결과"; has "결과 — 미션 #1"; has "판독 3/4"; shot 6-reopen-from-list

# 6) 꺼내서 대조: SAF로 저장한 ZIP, 앱 폴더 ZIP, 기기 사진, DB(WAL 포함)
$ADB pull /sdcard/Download/poles-1000_mission1.zip "$DATA/saved.zip" >/dev/null
$ADB pull "$FILES/export/1.zip" "$DATA/app.zip" >/dev/null
$ADB pull "$FILES/images" "$DATA/" >/dev/null
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
done
$ADB logcat -d -s Export:I | grep -o "Export *: .*" | sed 's/Export *: /Export: /' >"$DIR/export.log"
unzip -p "$DATA/saved.zip" result.csv >"$DIR/result-sample.csv"
python3 "$DIR/verify.py" "$DATA/inspection.db" "$DATA/saved.zip" "$DATA/app.zip" "$DATA/images" "$DIR/export.log"
echo "스모크 시험 통과"
