#!/usr/bin/env bash
# 개발용 범용 탐지 모델(COCO yolo11n)을 9.1 규격 폴더로 만든다.
#   결과: build/models/coco-yolo11n-fp16/{model.tflite, labels.txt, model.json}
#   기기에 넣기: adb push build/models/coco-yolo11n-fp16 /sdcard/Android/data/com.example.myfirstapp/files/models/
# Ultralytics 가중치는 AGPL-3.0이라 저장소와 배포 앱에 넣지 않는다(14장 미정 4).
# onnx2tf로 변환한 모델은 상자를 입력 픽셀 단위로 내보낸다("boxes": "pixel"). yolo_ref.py가 출력 값으로 확인한다.
# macOS 기본 python3(3.9)에서 동작을 확인했다. onnx2tf 1.20은 3.9에서 설치되는 마지막 버전이다.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/models/coco-yolo11n-fp16
WORK=build/yolo-export
mkdir -p "$WORK" "$OUT"
[ -x "$WORK/venv/bin/python" ] || python3 -m venv "$WORK/venv"
export PATH="$PWD/$WORK/venv/bin:$PATH"
pip install -q "ultralytics==8.3.200" "tensorflow==2.19.0" "tf_keras<=2.19.0" "onnx2tf==1.20.0" \
  "sng4onnx>=1.0.1" "onnx_graphsurgeon>=0.3.26" "onnx>=1.12.0,<1.18" "onnxslim>=0.1.67" onnxruntime psutil pillow

cd "$WORK"
python - <<'PY'
import numpy as np
from ultralytics import YOLO
m = YOLO("yolo11n.pt")  # 없으면 Ultralytics GitHub 릴리스에서 받는다
m.export(format="onnx", imgsz=640, opset=17, simplify=True)
open("labels.txt", "w").write("".join(m.names[i] + "\n" for i in range(len(m.names))))
# onnx2tf가 검증용 이미지를 외부에서 받지 않도록 로컬 파일을 둔다(정확도와 무관)
np.save("calibration_image_sample_data_20x128x128x3_float32.npy", np.random.rand(20, 128, 128, 3).astype(np.float32))
PY
onnx2tf -i yolo11n.onnx -o tfout -nuo -osd > onnx2tf.log 2>&1
cd - > /dev/null

cp "$WORK/tfout/yolo11n_float16.tflite" "$OUT/model.tflite"
cp "$WORK/labels.txt" "$OUT/labels.txt"
cat > "$OUT/model.json" <<'JSON'
{
  "version": "coco-yolo11n-fp16",
  "architecture": "yolo11n",
  "input": { "width": 640, "height": 640, "format": "RGB", "normalize": "0-1" },
  "output": { "layout": "1x(4+C)x8400", "nms": false, "boxes": "pixel" },
  "thresholds": { "score": 0.35, "iou": 0.5 },
  "alertLabels": ["person"]
}
JSON
ls -la "$OUT"
