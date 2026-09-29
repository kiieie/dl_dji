#!/usr/bin/env bash
# 가져오기 화면 스모크 시험: 에뮬레이터에서 CSV 선택 → 저장 → 삭제를 누르고 단계별 화면을 저장한다.
# 사전 조건: 에뮬레이터 실행 중, 앱 설치됨(./gradlew installDebug). 저장소 루트에서 실행한다.
set -euo pipefail
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
DIR=$(cd "$(dirname "$0")" && pwd)

# 화면에서 text가 일치하는 요소의 가운데를 누른다. 두 번째 인자가 last면 마지막 요소를 누른다.
tap() {
  local b pick=head
  [ "${2:-}" = last ] && pick=tail
  b=$($ADB exec-out uiautomator dump /dev/tty | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | $pick -1 |
    grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [ -n "$b" ] || { echo "화면에 없음: $1" >&2; exit 1; }
  set -- $b
  $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
  sleep 2
}
shot() { $ADB exec-out screencap -p >"$DIR/$1.png"; echo "저장: $1.png"; }
has() { $ADB exec-out uiautomator dump /dev/tty | grep -q "$1" && echo "확인: $1" || { echo "실패: $1 없음" >&2; exit 1; }; }

$ADB shell pm clear com.example.myfirstapp >/dev/null
$ADB push "$DIR/poles.csv" /sdcard/Download/poles.csv >/dev/null
$ADB shell touch /sdcard/Download/poles.csv  # 수정 시각을 새로 해야 미디어 색인이 다시 잡는다
$ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/poles.csv >/dev/null
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 1
$ADB shell am start -W -n com.example.myfirstapp/.MainActivity >/dev/null
sleep 3
# 에뮬레이터의 16 KB 페이지 경고 창(DJI 네이티브 라이브러리 때문)이 뜨면 닫는다.
$ADB exec-out uiautomator dump /dev/tty | grep -q "Android App Compatibility" && tap "OK"

has "프로젝트가 없습니다"; shot 1-empty
tap "CSV 파일 선택"; tap "poles.csv"
has "전주 2개 · 제외 2행"; has "위도와 경도가 바뀐 것으로 보임"; has "중복 전주번호"; shot 2-parsed
tap "저장"
has "저장했습니다: poles (전주 2개)"; has "전주 2개 · "; shot 3-saved
tap "삭제"
has "poles 프로젝트와 전주 2개를 삭제합니다"; shot 4-delete-confirm
tap "삭제" last  # 확인 창의 [삭제]
has "프로젝트가 없습니다"; shot 5-deleted
echo "스모크 시험 통과"
