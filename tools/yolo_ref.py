"""TFLite 모델을 앱과 같은 방식(레터박스 114, RGB 0-1, 클래스별 NMS)으로 돌려 기준값을 만든다(9.3).

사용: python tools/yolo_ref.py <model.tflite> <labels.txt> <이미지...> > ref.json
상자 단위(0~1 비율 / 픽셀)는 출력 값으로 판별하고, 같은 폴더 model.json의 output.boxes와 다르면 실패한다.
앱의 탐지 결과(DB)와 비교하는 기준이다. tensorflow가 필요하다(export_coco_model.sh의 가상환경).
"""
import sys, json, numpy as np, tensorflow as tf
from PIL import Image
model, labels_path = sys.argv[1], sys.argv[2]
labels = [l.strip() for l in open(labels_path) if l.strip()]
it = tf.lite.Interpreter(model_path=model); it.allocate_tensors()
i, o = it.get_input_details()[0], it.get_output_details()[0]
print("input", i["shape"], i["dtype"].__name__, "output", o["shape"], o["dtype"].__name__, file=sys.stderr)
S = i["shape"][1]
def run(path, score=0.35, iou=0.5):
    im = Image.open(path).convert("RGB"); W, H = im.size
    r = min(S / W, S / H); nw, nh = W * r, H * r; px, py = (S - nw) / 2, (S - nh) / 2
    canvas = Image.new("RGB", (S, S), (114, 114, 114))
    canvas.paste(im.resize((round(nw), round(nh)), Image.BILINEAR), (round(px), round(py)))
    x = (np.asarray(canvas, np.float32) / 255)[None]
    it.set_tensor(i["index"], x); it.invoke(); out = it.get_tensor(o["index"])[0]
    n = out.shape[1]; cls = out[4:]; best = cls.argmax(0); sc = cls.max(0)
    keep = np.where(sc >= score)[0]
    unit = 1.0 if out[:4].max() > 2 else S
    dets = []
    for k in keep:
        cx, cy, w, h = out[:4, k] * unit
        x0 = np.clip((cx - w / 2 - px) / r / W, 0, 1); y0 = np.clip((cy - h / 2 - py) / r / H, 0, 1)
        x1 = np.clip((cx + w / 2 - px) / r / W, 0, 1); y1 = np.clip((cy + h / 2 - py) / r / H, 0, 1)
        dets.append(dict(label=labels[best[k]], score=float(sc[k]), x=float(x0), y=float(y0), w=float(x1 - x0), h=float(y1 - y0)))
    dets.sort(key=lambda d: -d["score"]); kept = []
    def iou_(a, b):
        iw = max(0, min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"])) * W
        ih = max(0, min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"])) * H
        inter = iw * ih; u = (a["w"] * a["h"] + b["w"] * b["h"]) * W * H - inter
        return inter / u if u > 0 else 0
    for d in dets:
        if all(not (k["label"] == d["label"] and iou_(k, d) > iou) for k in kept): kept.append(d)
    return dict(image=path, size=[W, H], pixelBoxes=bool(unit == 1.0), detections=kept)
results = [run(p) for p in sys.argv[3:]]
import os
meta = json.load(open(os.path.join(os.path.dirname(model), "model.json")))
declared = meta.get("output", {}).get("boxes", "normalized") == "pixel"
if any(r["pixelBoxes"] != declared for r in results):
    sys.exit(f"model.json output.boxes가 모델 출력과 다릅니다: 선언 {'pixel' if declared else 'normalized'}")
print(json.dumps(results, indent=1, ensure_ascii=False))
