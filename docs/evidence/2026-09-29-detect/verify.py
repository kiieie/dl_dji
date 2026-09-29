#!/usr/bin/env python3
"""탐지 스모크 시험 뒤 앱 DB·저장 사진·로그를 PC 기준값(ref.json)과 대조한다.

사용: verify.py <inspection.db> <images 폴더> <시험 사진 폴더> <ref.json> <detect.log>
기준값은 tools/yolo_ref.py가 같은 모델을 TensorFlow로 돌려 만든다. 리사이즈 방식(Android Canvas와 PIL)이
조금 달라 점수가 흔들리므로, 점수 0.45 이상 탐지만 양쪽에 모두 있어야 하고 상자 IoU 0.9 이상, 점수 차 0.05 이하를 요구한다.
"""
import hashlib
import json
import os
import re
import sqlite3
import statistics
import sys
from collections import Counter

db = sqlite3.connect(sys.argv[1])
images_dir, photos_dir = sys.argv[2], sys.argv[3]
ref = {os.path.basename(r["image"]): r for r in json.load(open(sys.argv[4], encoding="utf-8"))}
log = open(sys.argv[5], encoding="utf-8").read().splitlines()
sounds = Counter(line.split(": ", 1)[1].strip() for line in log if line.startswith("Sound: "))
md5 = lambda p: hashlib.md5(open(p, "rb").read()).hexdigest()
samples = sorted(ref)  # 앱은 fake_photos를 이름순으로 차례로 쓴다
sample_md5 = {md5(os.path.join(photos_dir, s)): s for s in samples}

q = lambda sql, *a: db.execute(sql, a).fetchall()
(mission_status, model_version), = q("SELECT status, modelVersion FROM mission")
visits = q("SELECT v.id, v.status FROM visit v JOIN sortie s ON s.id = v.sortieId ORDER BY s.seq, v.`order`")
caps = q("SELECT id, visitId, filePath FROM capture ORDER BY id")


def iou(a, b, w, h):
    iw = max(0, min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"])) * w
    ih = max(0, min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"])) * h
    inter = iw * ih
    union = (a["w"] * a["h"] + b["w"] * b["h"]) * w * h - inter
    return inter / union if union > 0 else 0


def match(xs, ys, w, h):
    """xs의 점수 0.45 이상 탐지마다 ys에서 같은 라벨·가장 많이 겹치는 상자를 찾는다. (짝 목록, 짝 없는 수)"""
    pairs, missing = [], 0
    for a in (d for d in xs if d["score"] >= 0.45):
        best = max((b for b in ys if b["label"] == a["label"]), key=lambda b: iou(a, b, w, h), default=None)
        if best is None or iou(a, best, w, h) < 0.9:
            missing += 1
        else:
            pairs.append((a, best, iou(a, best, w, h)))
    return pairs, missing


used, file_ok, missing, pairs, alert_ok = [], True, 0, [], True
alert_visits = set()
for i, (cid, vid, path) in enumerate(caps):
    f = os.path.join(images_dir, os.path.basename(path or ""))
    sample = sample_md5.get(md5(f)) if os.path.isfile(f) else None
    file_ok &= sample == samples[i % len(samples)]
    used.append(sample)
    r = ref[samples[i % len(samples)]]
    w, h = r["size"]
    app = [dict(label=l, score=s, x=x, y=y, w=bw, h=bh, alert=a)
           for l, s, x, y, bw, bh, a in q("SELECT label, score, x, y, w, h, alert FROM detection WHERE captureId = ?", cid)]
    p1, m1 = match(r["detections"], app, w, h)
    p2, m2 = match(app, r["detections"], w, h)
    missing += m1 + m2
    pairs += p1
    alert_ok &= all(bool(d["alert"]) == (d["label"] == "person") for d in app)
    if any(d["alert"] for d in app):
        alert_visits.add(vid)

rows = q("SELECT v.id, (SELECT COUNT(*) FROM detection d JOIN capture c ON c.id = d.captureId WHERE c.visitId = v.id AND d.alert) "
         "FROM visit v")
want_alert = {caps[i][1] for i in range(len(caps)) if any(d["label"] == "person" for d in ref[samples[i % len(samples)]]["detections"])}
timing = [re.search(r"pre=(\d+)ms infer=(\d+)ms post=(\d+)ms", l) for l in log if l.startswith("Detector: pre=")]
infer = [int(t.group(2)) for t in timing]

checks = [
    ("미션 상태", "DONE", mission_status),
    ("모든 전주 완료", {"DONE"}, {v[1] for v in visits}),
    ("규격에 맞지 않는 모델(zz-broken) 거부", True, any("모델 거부 zz-broken: 출력 텐서" in l for l in log)),
    ("사용한 모델", "coco-yolo11n-fp16", model_version),
    ("모델 로드 로그", True, any("model=coco-yolo11n-fp16 dir=coco-yolo11n-fp16" in l for l in log)),
    ("저장 사진 = 시험 사진을 이름순으로 차례로", True, file_ok),
    ("점수 0.45 이상 탐지가 양쪽에 모두 있음(짝 없는 수)", 0, missing),
    ("짝지은 탐지 수 > 0", True, len(pairs) > 0),
    ("상자 IoU 최소 ≥ 0.9", True, min(p[2] for p in pairs) >= 0.9),
    ("점수 차 최대 ≤ 0.05", True, max(abs(a["score"] - b["score"]) for a, b, _ in pairs) <= 0.05),
    ("alert = (라벨이 person)", True, alert_ok),
    ("이상 전주 = 기준값에서 사람이 있는 전주", sorted(want_alert), sorted(alert_visits)),
    ("결과 화면 이상 수(쿼리)도 같음", sorted(want_alert), sorted(v for v, n in rows if n > 0)),
    ("이상음 = 이상 전주, 점검 완료음 = 나머지", (len(want_alert), len(visits) - len(want_alert)),
     (sounds["ANOMALY"], sounds["INSPECTION_OK"])),
    ("분석 오류음 없음", 0, sounds["ERROR"]),
    ("추론 시간 로그 = 사진 수", len(caps), len(infer)),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name} 기대 {want}, 앱 {got}")
print(f"전주 {len(visits)}개, 사진 {len(caps)}장, 사용 사진 {used}")
print(f"짝지은 탐지 {len(pairs)}개, 최소 IoU {min(p[2] for p in pairs):.3f}, "
      f"최대 점수 차 {max(abs(a['score'] - b['score']) for a, b, _ in pairs):.3f}")
print(f"에뮬레이터 추론 시간(ms) 중앙값 {statistics.median(infer)}, 최대 {max(infer)} — RC Pro 측정은 PoC ⑤")
sys.exit(0 if ok else 1)
