#!/usr/bin/env python3
"""스크린샷에서 개별 전주 점(주황 #D9480F)을 찾아, 기준점에 가장 가까운 점의 화면 좌표를 "x y"로 출력한다.

사용: find_pole.py <screen.png> <기준 x> <기준 y> <지도 왼쪽 경계 x>
"""
import sys

from PIL import Image

img = Image.open(sys.argv[1]).convert("RGB")
cx, cy, left = map(int, sys.argv[2:5])
w, h = img.size
px = img.load()
hits = [(x, y) for y in range(80, h, 2) for x in range(left, w, 2)
        if abs(px[x, y][0] - 217) < 20 and abs(px[x, y][1] - 72) < 20 and abs(px[x, y][2] - 15) < 20]
if not hits:
    sys.exit("주황 전주 점이 화면에 없음")
# 붙어 있는 픽셀을 점 하나로 묶는다(점 지름 약 40px).
blobs = []
for x, y in hits:
    for b in blobs:
        if abs(b[0] - x) < 30 and abs(b[1] - y) < 30:
            b[2].append((x, y))
            break
    else:
        blobs.append([x, y, [(x, y)]])
centers = [(sum(p[0] for p in b[2]) // len(b[2]), sum(p[1] for p in b[2]) // len(b[2])) for b in blobs]
x, y = min(centers, key=lambda c: (c[0] - cx) ** 2 + (c[1] - cy) ** 2)
print(x, y)
