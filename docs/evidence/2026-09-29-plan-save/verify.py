#!/usr/bin/env python3
"""앱 logcat(RoutePlan)과 앱 DB를 CSV로 다시 계산한 값과 대조한다.

영역 판정(ray casting)과 가시권 판정(등거리 근사)은 앱 코드를 쓰지 않고 따로 구현했다.
사용: verify.py <poles.csv> <routeplan.log> <inspection.db>
"""
import csv
import json
import math
import re
import sqlite3
import sys

R = 6_371_008.8
VLOS_M = 500.0

log = open(sys.argv[2], encoding="utf-8").read()
m = {k: v for k, v in re.findall(r"(\w+)=(\S+)", log)}
verts = [tuple(map(float, p.split(","))) for p in m["vertices"].split(";")]
home = tuple(map(float, m["home"].split(",")))
excluded = set(filter(None, m.get("excluded", "").split(";"))) if not m.get("excluded", "").startswith("params") else set()
params = list(map(float, m["params"].split(",")))
sorties = list(map(int, re.findall(r"\d+", re.search(r"sorties=\[([^\]]*)\]", log).group(1))))


def inside(lat, lon):
    hit, j = False, len(verts) - 1
    for i, (alat, alon) in enumerate(verts):
        blat, blon = verts[j]
        if (alat > lat) != (blat > lat) and lon < (blon - alon) * (lat - alat) / (blat - alat) + alon:
            hit = not hit
        j = i
    return hit


def dist(lat, lon):
    x = math.radians(lon - home[1]) * R * math.cos(math.radians(home[0]))
    y = math.radians(lat - home[0]) * R
    return math.hypot(x, y)


rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
sel = {r["전주번호"]: r for r in rows if inside(float(r["위도"]), float(r["경도"]))}
targets = {k: r for k, r in sel.items() if k not in excluded}
vlos = sum(dist(float(r["위도"]), float(r["경도"])) > VLOS_M for r in targets.values())

db = sqlite3.connect(sys.argv[3])
missions = db.execute("SELECT id, homeLat, homeLon, flightParams, areaGeoJson, status FROM mission").fetchall()
mid, hlat, hlon, fp, area, status = missions[0]
fp = json.loads(fp)
ring = json.loads(area)["coordinates"][0]
db_sorties = [n for (n,) in db.execute(
    "SELECT COUNT(v.id) FROM sortie s JOIN visit v ON v.sortieId = s.id WHERE s.missionId = ? GROUP BY s.id ORDER BY s.seq", (mid,))]
visited = [p for (p,) in db.execute(
    "SELECT p.poleId FROM visit v JOIN sortie s ON v.sortieId = s.id JOIN pole p ON p.id = v.poleRowId WHERE s.missionId = ?", (mid,))]

checks = [
    ("영역 안 전주", len(sel), int(m["inArea"])),
    ("제외 전주 수", 1, len(excluded)),
    ("제외 전주가 영역 안에 있음", True, excluded <= sel.keys()),
    ("가시권 초과(제외 전주 뺀 뒤)", vlos, int(m["vlos"])),
    ("방문 + 도달 불가 = 영역 안 − 제외", len(targets), sum(sorties) + int(m["vlos"]) + int(m["battery"])),
    ("로그 파라미터(이동, 점검, 속도, 예비율)", [50.0, 30.0, 8.0, 40.0], params),
    ("DB 미션 수", 1, len(missions)),
    ("DB 미션 번호 = 로그", int(m["mission"]), mid),
    ("DB 상태", "PLANNED", status),
    ("DB 이륙 지점 = 로그", home, (hlat, hlon)),
    ("DB 파라미터 = 로그", params, [fp["transitAltitudeM"], fp["inspectAltitudeM"], fp["speedMps"], fp["batteryReservePct"]]),
    # MapLibre GeoJSON은 좌표를 소수 7자리(약 1cm)로 쓴다. 1e-6°(약 10cm) 안이면 같은 점으로 본다.
    ("DB 영역 꼭짓점 = 로그(닫힌 고리, 1e-6° 이내)", True, len(ring) == len(verts) + 1 and all(
        abs(a - lat) < 1e-6 and abs(b - lon) < 1e-6 for (a, b), (lon, lat) in zip(verts + verts[:1], ring))),
    ("DB 소티별 방문 수 = 로그", sorties, db_sorties),
    ("DB 방문 전주 중복 없음", len(visited), len(set(visited))),
    ("DB 방문 전주는 영역 안, 제외 전주 아님", True, set(visited) <= targets.keys()),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name} 기대 {want}, 앱 {got}")
print(f"제외 전주 {sorted(excluded)}, 계산 시간 {m['ms']}ms")
sys.exit(0 if ok else 1)
