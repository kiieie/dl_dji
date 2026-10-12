#!/usr/bin/env python3
"""⑥ 설정 스모크 시험 뒤 앱 DB, 앱 로그, 앱 폴더 목록을 대조한다.

사용: verify.py <inspection.db> <app.log> <files.txt>
"""
import json
import re
import sqlite3
import sys

db = sqlite3.connect(sys.argv[1])
log = open(sys.argv[2], encoding="utf-8").read()
files = open(sys.argv[3], encoding="utf-8").read().split()

(params_json,), = db.execute("SELECT flightParams FROM mission").fetchall()
p = json.loads(params_json)
plan = re.search(r"params=([\d.]+),([\d.]+),([\d.]+),([\d.]+)", log)
model = re.search(r"Detector: model=(\S+) dir=(\S+)", log)

checks = [
    ("미션 파라미터: 이동 고도 60 · 점검 고도 30 · 속도 7 · 예비율 35",
     (60.0, 30.0, 7.0, 35.0), (p["transitAltitudeM"], p["inspectAltitudeM"], p["speedMps"], p["batteryReservePct"])),
    ("미션 파라미터: 대기 8초 · 렌즈 광각만 · 다운로드 원본", (8, ["WIDE"], "ORIGINAL"), (p["waitSec"], p["lenses"], p["downloadMode"])),
    ("경로 계산 로그의 파라미터가 설정 기본값", ("60.0", "30.0", "7.0", "35.0"), plan.groups() if plan else None),
    ("비행 화면이 고른 모델(coco-old)을 씀", ("coco-old", "coco-old"), model.groups() if model else None),
    ("앱 폴더: 남은 모델은 coco-yolo11n-fp16 하나(거부한 broken·지운 coco-old 없음)",
     ["coco-yolo11n-fp16"], [f for f in files if f in ("coco-yolo11n-fp16", "coco-old", "broken", ".import")]),
    ("앱 폴더: 지도 삭제됨", False, "test-grid.mbtiles" in files),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name}" + ("" if want == got else f" 기대 {want}, 앱 {got}"))
sys.exit(0 if ok else 1)
