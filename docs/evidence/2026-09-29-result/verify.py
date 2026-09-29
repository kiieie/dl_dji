#!/usr/bin/env python3
"""결과 화면 스모크 시험 뒤 내보낸 ZIP을 앱 DB·기기 사진과 대조한다.

사용: verify.py <inspection.db> <saved.zip> <app.zip> <images 폴더> <export.log>
"""
import csv
import hashlib
import io
import json
import os
import sqlite3
import sys
import zipfile

from PIL import Image

db_path, saved, app_zip, images_dir, log = sys.argv[1:6]
db = sqlite3.connect(db_path)
q = lambda sql: db.execute(sql).fetchall()
sha = lambda b: hashlib.sha256(b).hexdigest()

rows = q("SELECT s.seq, v.`order`, p.poleId, v.status, v.skipReason, v.id FROM sortie s JOIN visit v ON v.sortieId = s.id "
         "JOIN pole p ON p.id = v.poleRowId ORDER BY s.seq, v.`order`")
caps = q("SELECT id, visitId, filePath, lens FROM capture ORDER BY id")
dets = q("SELECT captureId, label, score FROM detection ORDER BY id")
reviews = {r[0]: r[1:] for r in q("SELECT visitId, verdict, tags, memo FROM review r WHERE id = (SELECT MAX(id) FROM review WHERE visitId = r.visitId)")}
VERDICT = {"CONFIRMED": "검출 맞음", "NONE": "검출 없음", "RETAKE": "재촬영"}
TITLE = {"bird_nest": "조류 둥지", "foreign_object": "이물질"}
(project,), = q("SELECT name FROM project")
mission = q("SELECT id, status, modelVersion, flightParams FROM mission")[0]
photos = {v: sum(c[1] == v for c in caps) for *_, v in rows}
anomalies = {v: sum(d[0] in {c[0] for c in caps if c[1] == v} for d in dets) for *_, v in rows}

z = zipfile.ZipFile(saved)
names = set(z.namelist())
raw_csv = z.read("result.csv").decode("utf-8")
table = list(csv.reader(io.StringIO(raw_csv.lstrip("﻿"))))
header, body = table[0], table[1:]
mj = json.loads(z.read("mission.json"))
device = {f: open(os.path.join(images_dir, f), "rb").read() for f in os.listdir(images_dir)}
zip_images = {n.split("/", 1)[1]: z.read(n) for n in names if n.startswith("images/")}
log_text = open(log, encoding="utf-8").read()
json_caps = [c for p in mj["poles"] for c in p["captures"]]

checks = [
    ("SAF로 저장한 ZIP = 앱 폴더 ZIP(export/1.zip)", sha(open(app_zip, "rb").read()), sha(open(saved, "rb").read())),
    ("ZIP 항목 = result.csv + mission.json + 사진", {"result.csv", "mission.json"} | {f"images/{f}" for f in device}, names),
    ("ZIP 사진 = 기기 사진(바이트 동일)", {k: sha(v) for k, v in device.items()}, {k: sha(v) for k, v in zip_images.items()}),
    ("사진은 640×480 JPEG", {("JPEG", (640, 480))}, {(im.format, im.size) for im in (Image.open(io.BytesIO(b)) for b in zip_images.values())}),
    ("CSV는 UTF-8 BOM으로 시작", True, raw_csv.startswith("﻿")),
    ("CSV 머리글", "소티,순서,전주번호,위도,경도,선로명,상태,건너뛴 이유,도착 시각,사진 수,검출 수,검출 대상,1차 판정,판정 대상,메모", ",".join(header)),
    ("CSV 줄 = DB 방문(소티, 순서, 전주, 상태)", [(str(r[0]), str(r[1]), r[2], r[3]) for r in rows], [tuple(b[:3]) + (b[6],) for b in body]),
    ("CSV 건너뛴 이유 = DB", [r[4] or "" for r in rows], [b[7] for b in body]),
    ("CSV 사진 수·검출 수 = DB", [(str(photos[r[5]]), str(anomalies[r[5]])) for r in rows], [(b[9], b[10]) for b in body]),
    ("CSV 검출 대상", [("조류 둥지" if anomalies[r[5]] else "") for r in rows], [b[11] for b in body]),
    ("판정 3개: 검출 전주 = 검출 맞음(대상 칩 미리 켜짐, 메모), 나머지 재촬영 1·검출 없음 1",
     [("CONFIRMED", "bird_nest", "nest ok")] + sorted([("NONE", "", None), ("RETAKE", "", None)]),
     [reviews[v] for *_, v in rows if anomalies[v] and v in reviews] + sorted(reviews[v] for *_, v in rows if not anomalies[v] and v in reviews)),
    ("CSV 1차 판정·대상·메모 = DB 최신 판정",
     [(VERDICT[reviews[v][0]], ";".join(TITLE.get(t, t) for t in reviews[v][1].split(";") if t), reviews[v][2] or "") if v in reviews else ("", "", "") for *_, v in rows],
     [tuple(b[12:15]) for b in body]),
    ("JSON 판정 = DB", [reviews[v][0] if v in reviews else None for *_, v in rows], [p["review"]["verdict"] if p["review"] else None for p in mj["poles"]]),
    ("완료 전주는 도착 시각이 있음", True, all(b[8] for b in body if b[6] == "DONE")),
    ("JSON 미션 번호·상태·모델", (mission[0], mission[1], mission[2]), (mj["missionId"], mj["status"], mj["modelVersion"])),
    ("JSON 프로젝트 이름", project, mj["project"]),
    ("JSON 비행 파라미터 = DB", json.loads(mission[3]), mj["flightParams"]),
    ("JSON 전주·사진 수 = DB", (len(rows), len(caps)), (len(mj["poles"]), len(json_caps))),
    ("JSON 사진 파일은 ZIP 안에 있음", True, all(c["file"] in names for c in json_caps)),
    ("JSON 탐지 = DB 탐지", [(d[1], round(d[2], 3)) for d in dets], [(t["label"], round(t["score"], 3)) for c in json_caps for t in c["detections"]]),
    ("앱 로그 전주·사진 수", True, "poles=5 images=8" in log_text and "saved uri=content://" in log_text),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name}" + ("" if want == got else f" 기대 {want}, 앱 {got}"))
print(f"전주 {len(rows)}개, 사진 {len(caps)}장, 탐지 {len(dets)}개, ZIP {os.path.getsize(saved):,}바이트")
sys.exit(0 if ok else 1)
