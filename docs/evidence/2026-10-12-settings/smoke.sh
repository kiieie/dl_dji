#!/usr/bin/env bash
# ⑥ 설정 화면 스모크 시험: 모델 폴더 가져오기(규격 불일치 거부 포함)와 모델 고르기, 비행 기본값 저장(범위 오류 확인),
# 지도 파일 추가, 앱 재시작 뒤 값 유지, 새 계획과 비행 화면에 설정이 적용되는지, 삭제 확인 대화상자를 확인한다.
# 끝나면 앱 DB와 앱 폴더를 꺼내 verify.py로 대조한다.
# 사전 조건: 에뮬레이터 Pixel_8(2400×1080) 실행 중, 디버그 앱 설치됨, tools/export_coco_model.sh 실행함, python3 + Pillow.
# 저장소 루트에서 bash로 실행한다.
set -euo pipefail
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
DIR=$(cd "$(dirname "$0")" && pwd)
DATA=$(mktemp -d)
PKG=com.example.myfirstapp
APPFILES=/sdcard/Android/data/$PKG/files
MODEL=build/models/coco-yolo11n-fp16
[ -f "$MODEL/model.tflite" ] || { echo "모델 없음: tools/export_coco_model.sh를 먼저 실행하세요" >&2; exit 1; }

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

lacks() { dump | grep -q "$1" && { echo "실패: $1 있음" >&2; exit 1; } || echo "확인: $1 없음"; }
# 왼쪽 칸(비행 기본값)을 아래로 넘긴다.
scroll_left() { $ADB shell input swipe 600 950 600 350 400; sleep 1; }
# 오른쪽 칸(모델·지도·상태)을 아래로 넘긴다.
# 화면에 있을 때만 누른다(고르기 창의 보기 방식·위치는 지난번 상태를 따른다).
# 고르기 창 목록에서 이름을 찾을 때까지 넘긴 뒤 누른다(가로 화면에서는 아래 버튼 줄이 목록 끝을 가린다).
find_tap() {
  # 고르기 창(DocumentsUI)은 세로로 열리기도 하고 지난번 스크롤 위치를 기억한다. 좌표는 두 방향에서 모두 화면 안인 값을 쓴다
  $ADB shell input swipe 500 400 500 900 200; $ADB shell input swipe 500 400 500 900 200; sleep 1
  local b bar x
  for _ in 1 2 3 4 5 6 7 8; do
    x=$(dump)
    bar=$(echo "$x" | grep -o 'text="USE THIS FOLDER"[^>]*bounds="\[[0-9]*,[0-9]*' | grep -o '[0-9]*$' || true)
    b=$(echo "$x" | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ' || true)
    if [ -n "$b" ]; then
      set -- "$1" $b
      # 아래 [USE THIS FOLDER] 줄에 가려 있으면 조금 더 올린다
      if (( ($3 + $5) / 2 < ${bar:-99999} - 20 )); then $ADB shell input tap $((($2 + $4) / 2)) $((($3 + $5) / 2)); sleep 1.5; return; fi
    fi
    $ADB shell input swipe 500 800 500 500 300; sleep 1
  done
  echo "화면에 없음: $1" >&2; exit 1
}
opt() { if dump | grep -q "$1=\"$2\""; then tapk "$1" "$2"; fi; }
scroll_left_up() { $ADB shell input swipe 600 350 600 950 400; sleep 1; }
scroll_right() { $ADB shell input swipe 1800 950 1800 350 400; sleep 1; }
# DocumentsUI 폴더 고르기: Download/models-in/<폴더> → [USE THIS FOLDER] → [ALLOW]
pick_folder() {
  tap "모델 폴더 가져오기"; sleep 1
  # 가로 화면에서는 아래 [USE THIS FOLDER] 줄이 목록을 가리므로 목록을 올린 뒤 누른다
  opt text "Download"; sleep 1
  find_tap "models-in"; sleep 1
  find_tap "$1"
  tap "USE THIS FOLDER"; tap "ALLOW"; sleep 3
}

# 시험 데이터: 전주 CSV·지도, 모델 폴더 3개(정상, 같은 모델의 다른 버전, 라벨 10개라 규격 불일치)
python3 tools/make_test_data.py "$DATA" 1000
M=$DATA/models-in
mkdir -p "$M/coco-yolo11n-fp16" "$M/coco-old" "$M/broken"
cp "$MODEL"/* "$M/coco-yolo11n-fp16/"
cp "$MODEL"/* "$M/coco-old/"; sed -i '' 's/"version": "coco-yolo11n-fp16"/"version": "coco-old"/' "$M/coco-old/model.json"
cp "$MODEL/model.tflite" "$MODEL/model.json" "$M/broken/"; head -10 "$MODEL/labels.txt" >"$M/broken/labels.txt"
sed -i '' 's/"version": "coco-yolo11n-fp16"/"version": "broken"/' "$M/broken/model.json"

$ADB shell pm clear $PKG >/dev/null
$ADB shell rm -rf /sdcard/Download/models-in
$ADB push "$M" /sdcard/Download/ >/dev/null
for f in poles-1000.csv test-grid.mbtiles; do
  $ADB push "$DATA/$f" "/sdcard/Download/$f" >/dev/null
  $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$f" >/dev/null
done
$ADB shell settings put system accelerometer_rotation 0
$ADB shell settings put system user_rotation 1
start_app() {
  $ADB shell am start -W -n $PKG/.MainActivity >/dev/null
  sleep 3
  dump | grep -q "Android App Compatibility" && tap "OK" || true
}
start_app

# 1) 처음 설정 화면: 설계 기본값, 모델·지도 없음
tap "설정"
has "50m"; has "8m/s"; has "모델이 없습니다. 모의 탐지기(fake-1)로 비행합니다."
shot 1-first-open

# 2) 모델 가져오기: 규격 불일치는 거부, 정상 2개는 가져옴. 기본은 이름이 가장 뒤인 모델, 줄을 눌러 다른 모델을 고름
pick_folder broken
wait_for "실패: 출력 텐서" 15
pick_folder coco-yolo11n-fp16; wait_for "모델을 가져왔습니다: coco-yolo11n-fp16" 15
pick_folder coco-old; wait_for "모델을 가져왔습니다: coco-old" 15
has "coco-yolo11n-fp16 · 사용 중"
tap "coco-old"; wait_for "다음 비행부터 모델 coco-old" 5; has "coco-old · 사용 중"
shot 2-models

# 3) 지도 파일 추가
scroll_right
tap "지도 파일 추가"; sleep 1; opt text "Download"; opt content-desc "List view"; find_tap "test-grid.mbtiles";  # 고르기 창은 마지막 폴더(models-in/…)에서 열린다
 wait_for "지도를 추가했습니다: test-grid.mbtiles" 15
has "492KB"
shot 3-map-added

# 4) 비행 기본값: 범위 오류면 저장 막힘 → 고친 뒤 저장
for i in 1 2 3 4 5; do tapk content-desc "점검 고도 높이기"; done   # 30 → 55m > 이동 고도 50m
has "점검 고도는 0m 초과, 이동 고도 이하여야 합니다."
shot 4-param-error
tap "기본값 저장"; lacks "비행 기본값을 저장했습니다"   # 버튼이 꺼져 있어 저장되지 않는다
for i in 1 2 3 4 5; do tapk content-desc "점검 고도 낮추기"; done
tapk content-desc "이동 고도 높이기"; tapk content-desc "이동 고도 높이기"       # 60m
tapk content-desc "속도 낮추기"                                                  # 7m/s
tapk content-desc "예비율 높이기"                                                # 35%
scroll_left
for i in 1 2 3; do tapk content-desc "대기 시간 높이기"; done                    # 8초
tap "망원"; tap "원본"
scroll_left_up
tap "기본값 저장"; wait_for "비행 기본값을 저장했습니다. 새 계획부터 적용됩니다." 5
shot 5-defaults-saved

# 5) 앱을 다시 켜도 값이 남는다(DataStore)
$ADB shell am force-stop $PKG
start_app
tap "설정"
has "60m"; has "7m/s"; has "35%"; has "coco-old · 사용 중"
scroll_left; has "8초"
shot 6-after-restart
$ADB shell input keyevent BACK; sleep 1

# 6) 새 계획은 설정의 기본값으로 시작하고, 비행 화면은 고른 모델을 쓴다
tap "CSV 파일 선택"; tap "poles-1000.csv"; tap "저장"
tap "poles-1000"; sleep 2; tap "+ 새 계획"; sleep 4
has "60m"; has "7m/s"; has "35%"
$ADB logcat -c
tap "영역 그리기"
at 1675 505; at 1745 505; at 1745 575; at 1675 575
tap "완료"; sleep 2
tap "이륙 지점"; at 1709 540
tap "경로 계산"; sleep 2
shot 7-plan-defaults
tap "계획 저장"; wait_for "계획을 저장했습니다: 미션 #1" 10
tap "비행 시작"
wait_for "모의 기체" 10; sleep 3
$ADB logcat -d -s RoutePlan:I Detector:I | grep -o "\(RoutePlan\|Detector\) *: .*" | sed 's/ *: /: /' >"$DIR/app.log"
cat "$DIR/app.log"
# 비행 → 계획 → 미션 목록 → 프로젝트 목록
$ADB shell input keyevent BACK; sleep 1; $ADB shell input keyevent BACK; sleep 1; $ADB shell input keyevent BACK; sleep 2

# 7) 삭제는 확인 대화상자를 거친다. 고른 모델을 지우면 이름이 가장 뒤인 모델로 돌아간다
tap "설정"
tap "삭제"; has "모델을 삭제할까요?"; has "coco-yolo11n-fp16 을(를) 앱 폴더에서 지웁니다. 되돌릴 수 없습니다."
tap "취소"; has "coco-yolo11n-fp16"
b=$(dump | grep -o 'text="삭제"[^>]*bounds="[^"]*"' | sed -n 2p | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
set -- $b; $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2)); sleep 1.5   # 두 번째 줄(coco-old)
has "coco-old 을(를) 앱 폴더에서 지웁니다."
tap "삭제"; wait_for "삭제했습니다: coco-old" 5; has "coco-yolo11n-fp16 · 사용 중"
scroll_right
b=$(dump | grep -o 'text="삭제"[^>]*bounds="[^"]*"' | tail -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
set -- $b; $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2)); sleep 1.5   # 지도
has "지도 파일을 삭제할까요?"
tap "삭제"; wait_for "삭제했습니다: test-grid.mbtiles" 5; has "지도 파일이 없습니다."
shot 8-deleted

# 8) DB·앱 폴더 대조
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
done
$ADB shell ls $APPFILES/models $APPFILES/maps >"$DATA/files.txt" 2>&1 || true
python3 "$DIR/verify.py" "$DATA/inspection.db" "$DIR/app.log" "$DATA/files.txt" | tee "$DIR/verify.txt"
echo "스모크 시험 통과"
