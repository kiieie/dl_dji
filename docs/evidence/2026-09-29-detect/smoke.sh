#!/usr/bin/env bash
# AI 탐지 연결 스모크 시험: 범용 모델(COCO yolo11n)과 시험 사진을 앱 폴더에 넣고 모의 기체로 전주 5개를 점검한 뒤
# 결과 화면에서 경고 라벨(빨강)과 일반 탐지(노랑)를 확인한다. 끝나면 DB·사진·로그를 꺼내 verify.py로
# PC(TensorFlow)에서 같은 모델로 돌린 기준값(ref.json)과 대조한다.
# 사전 조건: 에뮬레이터 Pixel_8(2400×1080) 실행 중, 디버그 앱 설치됨, tools/export_coco_model.sh 실행함.
# 저장소 루트에서 bash로 실행한다.
set -euo pipefail
ADB=${ADB:-~/Library/Android/sdk/platform-tools/adb}
DIR=$(cd "$(dirname "$0")" && pwd)
DATA=$(mktemp -d)
PKG=com.example.myfirstapp
APPFILES=/sdcard/Android/data/$PKG/files
MODEL=build/models/coco-yolo11n-fp16
VENV=build/yolo-export/venv/bin/python
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
wait_for() {
  local end=$((SECONDS + ${2:-60}))
  while ((SECONDS < end)); do dump | grep -q "$1" && { echo "확인: $1"; return; }; sleep 1; done
  echo "실패: ${2:-60}초 안에 '$1' 없음" >&2; exit 1
}

# 0) 시험 사진 4장: 사람·버스(bus), 사람·넥타이(zidane), 사람이 없는 전주 그림 2장. 전주마다 2장씩 차례로 쓴다.
python3 tools/make_test_data.py "$DATA" 1000
ULTRA=$($VENV -c "import ultralytics, os; print(os.path.dirname(ultralytics.__file__))")
mkdir -p "$DATA/photos"
cp "$ULTRA/assets/bus.jpg" "$DATA/photos/1-bus.jpg"
cp "$ULTRA/assets/zidane.jpg" "$DATA/photos/2-zidane.jpg"
python3 - "$DATA/photos" <<'PY'
import sys
from PIL import Image, ImageDraw
for i, sky in enumerate([(120, 160, 200), (90, 120, 150)], start=3):
    im = Image.new("RGB", (1280, 960), sky); d = ImageDraw.Draw(im)
    d.rectangle([0, 700, 1280, 960], fill=(90, 110, 70))
    d.rectangle([610, 120, 670, 960], fill=(140, 123, 107)); d.rectangle([420, 220, 860, 250], fill=(140, 123, 107))
    im.save(f"{sys.argv[1]}/{i}-pole.jpg", quality=90)
PY
# 규격에 맞지 않는 모델(라벨 10개): 이름이 가장 뒤라 먼저 검사하고 거부해야 한다
mkdir -p "$DATA/zz-broken"
cp "$MODEL/model.tflite" "$MODEL/model.json" "$DATA/zz-broken/"
head -10 "$MODEL/labels.txt" >"$DATA/zz-broken/labels.txt"
$VENV tools/yolo_ref.py "$MODEL/model.tflite" "$MODEL/labels.txt" "$DATA"/photos/*.jpg >"$DIR/ref.json"

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
# 앱이 만든 앱 폴더에 모델과 사진을 넣는다(⑥ 설정의 모델 관리 전까지 adb로 넣는다, 9.1)
$ADB shell mkdir -p $APPFILES/models $APPFILES/fake_photos
$ADB push "$MODEL" $APPFILES/models/ >/dev/null
$ADB push "$DATA/zz-broken" $APPFILES/models/ >/dev/null
$ADB push "$DATA"/photos/. $APPFILES/fake_photos/ >/dev/null
$ADB shell ls $APPFILES/models $APPFILES/fake_photos

tap "CSV 파일 선택"; tap "poles-1000.csv"; tap "저장"
tap "poles-1000"; sleep 2; tap "+ 새 계획"; sleep 3  # 프로젝트 → 미션 목록 → 계획 화면
tap "지도 파일 추가"; tap "test-grid.mbtiles"; sleep 3

# 1) 비행 스모크와 같은 작은 영역(전주 5개) → 계획 저장 → 비행
tap "영역 그리기"
at 1675 505; at 1745 505; at 1745 575; at 1675 575
tap "완료"; sleep 2
tap "이륙 지점"; at 1709 540
tap "경로 계산"; sleep 2
tap "계획 저장"; wait_for "계획을 저장했습니다: 미션 #1" 10
$ADB logcat -c  # 모델은 비행 화면을 열 때 읽는다
tap "비행 시작"
wait_for "모의 기체" 10
tap "비행 시작"
wait_for "대기 [0-9]s" 90; shot 1-flight-analyzed
wait_for "미션 완료" 300

# 2) 결과 화면: 검출(사람) 전주 3개, 사진 위 빨강(person)·노랑(bus, tie) 상자
tap "결과 보기"
has "결과 — 미션 #1"; has "모델 coco-yolo11n-fp16"
shot 2-result-list
tap "1-1 · 완료 · 사진 2"; has "person"; has "bus"; shot 3-bus-zidane
tap "1-2 · 완료 · 사진 2"; has "T-[0-9]* · 완료"; shot 4-no-person
tap "검출 3"; shot 5-detected-filter
$ADB logcat -d -s Sound:I Detector:I | grep -o "\(Sound\|Detector\) *: .*" | sed 's/ *: /: /' >"$DIR/detect.log"

# 3) DB(WAL 포함)와 저장 사진을 꺼내 대조한다.
for f in inspection.db inspection.db-wal inspection.db-shm; do
  $ADB exec-out run-as $PKG cat "databases/$f" >"$DATA/$f" 2>/dev/null || true
done
mkdir -p "$DATA/images"
$ADB pull $APPFILES/projects/1/missions/1/images/. "$DATA/images/" >/dev/null
python3 "$DIR/verify.py" "$DATA/inspection.db" "$DATA/images" "$DATA/photos" "$DIR/ref.json" "$DIR/detect.log" | tee "$DIR/verify.txt"
echo "스모크 시험 통과"
