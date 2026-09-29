#!/usr/bin/env python3
"""앱 logcat(RoutePlan)의 꼭짓점·이륙 지점으로 영역 안 전주 수와 가시권(500m) 초과 수를 다시 계산해 대조한다.

앱과 같은 공식(ray casting, 등거리 근사)을 쓰되 코드는 공유하지 않는다. 사용: verify.py <poles.csv> <routeplan.log>
"""
import csv
import math
import re
import sys

R = 6_371_008.8
VLOS_M = 500.0

log = open(sys.argv[2], encoding="utf-8").read()
m = {k: v for k, v in re.findall(r"(\w+)=(\S+)", log)}
verts = [tuple(map(float, p.split(","))) for p in m["vertices"].split(";")]
home = tuple(map(float, m["home"].split(",")))


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
sel = [r for r in rows if inside(float(r["위도"]), float(r["경도"]))]
vlos = sum(dist(float(r["위도"]), float(r["경도"])) > VLOS_M for r in sel)
visited = sum(map(int, re.findall(r"\d+", re.search(r"sorties=\[([^\]]*)\]", log).group(1))))

checks = [
    ("영역 안 전주", len(sel), int(m["inArea"])),
    ("가시권 초과", vlos, int(m["vlos"])),
    ("방문 + 도달 불가 = 영역 안", len(sel), visited + int(m["vlos"]) + int(m["battery"])),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name} 기대 {want}, 앱 {got}")
print(f"계산 시간 {m['ms']}ms (N-04 기준 300개 3초)")
sys.exit(0 if ok else 1)
