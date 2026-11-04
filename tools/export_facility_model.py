"""설비 탐지 모델(facility_v1_coco, YOLO11l)을 9.1 규격 폴더로 만든다(2026-11-04).

두 단계로 나눈다. 이미 깔린 Ultralytics 환경을 다시 설치하지 않으려고(torch가 크다):
  1) ONNX: Ultralytics가 있는 파이썬(예: D:/kiie/projects/inspection/.venv)
       python tools/export_facility_model.py onnx [imgsz ...]
  2) tflite: tensorflow·onnx2tf가 있는 파이썬(build/yolo-export/venv, D: 드라이브. C:가 꽉 차서 TMP·PIP_CACHE_DIR도 D:)
       build/yolo-export/venv/Scripts/python.exe tools/export_facility_model.py tflite [imgsz ...]
  기본 imgsz: 640 960 1280. 결과: build/models/facility-v1-coco-<imgsz>-fp16/{model.tflite, labels.txt, model.json}
  기기에 넣기: adb push build/models/facility-v1-coco-960-fp16 /sdcard/Android/data/kr.co.dl_dji/files/models/

원본 가중치는 models_src/(git 제외)에 있다. Ultralytics 가중치는 AGPL-3.0이고 사내 데이터 산출물이라 저장소에 넣지 않는다.
변환은 export_coco_model.sh와 같다: ONNX(opset 17) → onnx2tf → float16 tflite. onnx2tf 변환이라 상자는 픽셀 단위다.
"""
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "models_src/dl_inspection_model/facility_detection/facility_v1_coco"
WORK = ROOT / "build/yolo-export/facility"
OUT = ROOT / "build/models"
# 학습 클래스 순서(facility_v1_coco_xany.yaml)와 X-AnyLabeling 임계값
NAMES = ["COS", "LP", "SUS", "LA"]
SCORE, IOU = 0.25, 0.45
DISPLAY = {"COS": "컷아웃 스위치", "LP": "라인포스트 애자", "SUS": "현수 애자", "LA": "피뢰기"}
# 비행 중 실시간 표시는 작은 입력, 착륙 뒤 정밀 탐지는 학습 크기(9.x 두 단계 탐지)
ROLE = {640: "live", 960: "live", 1280: "full"}


def onnx(imgsz: int) -> None:
    from ultralytics import YOLO

    work = WORK / str(imgsz)
    work.mkdir(parents=True, exist_ok=True)
    pt = work / "best.pt"
    shutil.copy(SRC / "weights/best.pt", pt)
    m = YOLO(str(pt))
    assert [m.names[i] for i in range(len(m.names))] == NAMES, m.names
    print(m.export(format="onnx", imgsz=imgsz, opset=17, simplify=True))


def tflite(imgsz: int) -> None:
    import numpy as np

    work = WORK / str(imgsz)
    src = work / "best.onnx"
    assert src.exists(), f"{src} 없음: 먼저 onnx 단계를 돌린다"
    out_tf = work / "tfout"
    if out_tf.exists():
        shutil.rmtree(out_tf)
    # onnx2tf가 검증용 이미지를 외부에서 받지 않도록 로컬 파일을 둔다(정확도와 무관)
    np.save(work / "calibration_image_sample_data_20x128x128x3_float32.npy", np.random.rand(20, 128, 128, 3).astype(np.float32))
    exe = Path(sys.executable).with_name("onnx2tf.exe")
    with open(work / "onnx2tf.log", "w", encoding="utf-8") as log:
        subprocess.run([str(exe if exe.exists() else "onnx2tf"), "-i", str(src), "-o", str(out_tf), "-nuo", "-osd"], cwd=work, check=True, stdout=log, stderr=subprocess.STDOUT)
    tfl = next(out_tf.glob("*_float16.tflite"))

    version = f"facility-v1-coco-{imgsz}-fp16"
    out = OUT / version
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    shutil.copy(tfl, out / "model.tflite")
    (out / "labels.txt").write_text("".join(n + "\n" for n in NAMES), encoding="utf-8")
    anchors = sum((imgsz // s) ** 2 for s in (8, 16, 32))
    meta = {
        "version": version,
        "architecture": "yolo11l",
        "role": ROLE.get(imgsz, "live"),
        "input": {"width": imgsz, "height": imgsz, "format": "RGB", "normalize": "0-1"},
        "output": {"layout": f"1x(4+C)x{anchors}", "nms": False, "boxes": "pixel"},
        "thresholds": {"score": SCORE, "iou": IOU},
        # 설비는 이상이 아니므로 검출로 세지 않는다(2026-11-04 사용자 결정)
        "alertLabels": [],
        "displayNames": {n: DISPLAY[n] for n in NAMES},
        "source": "facility_v1_coco (YOLO11l, test mAP50 0.737, 2026-10-31)",
    }
    (out / "model.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(version, (out / "model.tflite").stat().st_size // 1024, "KB")


if __name__ == "__main__":
    stage, sizes = sys.argv[1], [int(a) for a in sys.argv[2:]] or [640, 960, 1280]
    for s in sizes:
        {"onnx": onnx, "tflite": tflite}[stage](s)
