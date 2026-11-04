"""변환한 설비 탐지 tflite(640·960·1280)가 원본(.pt, 1280)과 얼마나 같은지 사진으로 잰다(2026-11-04, 9.x).

두 단계(export_facility_model.py와 같은 이유로 환경을 나눈다):
  1) 기준: Ultralytics 환경   python tools/compare_facility_models.py ref <사진 폴더>   → build/yolo-export/facility/ref.json
  2) 비교: tensorflow 환경    build/yolo-export/venv/Scripts/python.exe tools/compare_facility_models.py eval <사진 폴더> [모델 폴더 ...]
  모델 폴더 기본값: build/models/facility-v1-coco-*-fp16
  기준: models_src/.../best.pt를 imgsz 1280, conf 0.25, iou 0.45로 돌린 상자
  같은 클래스이고 IoU 0.5 이상이면 일치로 센다. 결과는 클래스별 재현율·정밀도와 PC 1장 시간(참고용, RC 시간은 따로 잰다).
tflite 실행은 앱과 같은 방식(레터박스 114, RGB 0-1, 클래스별 NMS)이다(tools/yolo_ref.py와 같음).
"""
import json
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PT = ROOT / "models_src/dl_inspection_model/facility_detection/facility_v1_coco/weights/best.pt"
MATCH_IOU = 0.5


def iou(a, b):
    ax0, ay0, ax1, ay1 = a
    bx0, by0, bx1, by1 = b
    iw = max(0.0, min(ax1, bx1) - max(ax0, bx0))
    ih = max(0.0, min(ay1, by1) - max(ay0, by0))
    inter = iw * ih
    u = (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter
    return inter / u if u > 0 else 0.0


def nms(dets, thr):
    dets.sort(key=lambda d: -d[1])
    kept = []
    for d in dets:
        if all(not (k[0] == d[0] and iou(k[2], d[2]) > thr) for k in kept):
            kept.append(d)
    return kept


class Tflite:
    def __init__(self, folder: Path):
        self.meta = json.loads((folder / "model.json").read_text(encoding="utf-8"))
        self.labels = [l.strip() for l in (folder / "labels.txt").read_text(encoding="utf-8").splitlines() if l.strip()]
        import tensorflow as tf

        self.it = tf.lite.Interpreter(model_path=str(folder / "model.tflite"), num_threads=8)
        self.it.allocate_tensors()
        self.i, self.o = self.it.get_input_details()[0], self.it.get_output_details()[0]
        self.S = self.i["shape"][1]

    def run(self, im: Image.Image):
        W, H = im.size
        S = self.S
        r = min(S / W, S / H)
        nw, nh = W * r, H * r
        px, py = (S - nw) / 2, (S - nh) / 2
        canvas = Image.new("RGB", (S, S), (114, 114, 114))
        canvas.paste(im.resize((round(nw), round(nh)), Image.BILINEAR), (round(px), round(py)))
        self.it.set_tensor(self.i["index"], (np.asarray(canvas, np.float32) / 255)[None])
        self.it.invoke()
        out = self.it.get_tensor(self.o["index"])[0]
        cls = out[4:]
        best, sc = cls.argmax(0), cls.max(0)
        unit = 1.0 if self.meta["output"].get("boxes") == "pixel" else S
        th = self.meta["thresholds"]
        dets = []
        for k in np.where(sc >= th["score"])[0]:
            cx, cy, w, h = out[:4, k] * unit
            box = ((cx - w / 2 - px) / r, (cy - h / 2 - py) / r, (cx + w / 2 - px) / r, (cy + h / 2 - py) / r)
            dets.append((self.labels[best[k]], float(sc[k]), box))
        return nms(dets, th["iou"])


REF = ROOT / "build/yolo-export/facility/ref.json"


def photos_in(folder):
    return sorted(p for p in Path(folder).iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))


def make_ref(folder):
    from ultralytics import YOLO

    m = YOLO(str(PT))
    out, t = {}, time.time()
    photos = photos_in(folder)
    for p in photos:
        r = m.predict(str(p), imgsz=1280, conf=0.25, iou=0.45, verbose=False)[0]
        out[p.name] = [(m.names[int(c)], float(s), list(b)) for c, s, b in zip(r.boxes.cls.tolist(), r.boxes.conf.tolist(), r.boxes.xyxy.tolist())]
    REF.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"기준 .pt 1280: 사진 {len(photos)}장, 상자 {sum(map(len, out.values()))}개, PC {1000 * (time.time() - t) / max(len(photos), 1):.0f} ms/장 → {REF}")


def evaluate(folder, folders):
    ref = json.loads(REF.read_text(encoding="utf-8"))
    photos = [p for p in photos_in(folder) if p.name in ref]
    images = [Image.open(p).convert("RGB") for p in photos]
    folders = folders or sorted((ROOT / "build/models").glob("facility-v1-coco-*-fp16"))
    for f in folders:
        m = Tflite(Path(f))
        stats = {}
        t = time.time()
        for p, im in zip(photos, images):
            pd = m.run(im)
            used = set()
            for lab, _, box in ref[p.name]:
                s = stats.setdefault(lab, [0, 0, 0])  # 기준 수, 일치, 예측 수
                s[0] += 1
                j = max((j for j in range(len(pd)) if j not in used and pd[j][0] == lab), key=lambda j: iou(box, pd[j][2]), default=None)
                if j is not None and iou(box, pd[j][2]) >= MATCH_IOU:
                    used.add(j)
                    s[1] += 1
            for lab, _, _ in pd:
                stats.setdefault(lab, [0, 0, 0])[2] += 1
        ms = 1000 * (time.time() - t) / max(len(images), 1)
        tot = [sum(v[i] for v in stats.values()) for i in range(3)]
        print(f"\n{Path(f).name}: PC {ms:.0f} ms/장, 재현율 {tot[1] / max(tot[0], 1):.2f}, 정밀도 {tot[1] / max(tot[2], 1):.2f}")
        for lab, (n, hit, pred) in sorted(stats.items()):
            print(f"  {lab}: 기준 {n}, 일치 {hit}, 예측 {pred}, 재현율 {hit / max(n, 1):.2f}, 정밀도 {hit / max(pred, 1):.2f}")


if __name__ == "__main__":
    if sys.argv[1] == "ref":
        make_ref(sys.argv[2])
    else:
        evaluate(sys.argv[2], sys.argv[3:])
