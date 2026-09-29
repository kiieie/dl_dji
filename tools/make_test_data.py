#!/usr/bin/env python3
"""개발용 시험 데이터 생성: 전주 CSV(격자 배치)와 합성 래스터 MBTiles(z/x/y 격자 타일).

실제 지도(VWorld)는 약관 확인 전이라 쓰지 않는다(설계서 미정 2). 타일에는 좌표만 그린다.
사용: python3 tools/make_test_data.py <출력 폴더> [전주 수]
"""
import io
import math
import random
import sqlite3
import sys
from pathlib import Path

from PIL import Image, ImageDraw

# 서울 남부 일대(시험용). 전주는 이 범위 안에 무작위로 둔다.
WEST, SOUTH, EAST, NORTH = 126.98, 37.49, 127.04, 37.53
ZOOMS = range(11, 17)


def tile_range(z):
    def x(lon):
        return int((lon + 180) / 360 * 2**z)

    def y(lat):
        r = math.radians(lat)
        return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * 2**z)

    return range(x(WEST), x(EAST) + 1), range(y(NORTH), y(SOUTH) + 1)


def tile_png(z, x, y):
    img = Image.new("RGB", (256, 256), (236, 240, 233) if (x + y) % 2 else (226, 232, 224))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 255, 255], outline=(150, 160, 150))
    d.text((8, 8), f"{z}/{x}/{y}", fill=(80, 90, 80))
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def make_mbtiles(path: Path):
    path.unlink(missing_ok=True)
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    db.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    db.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
    meta = {"name": "시험 격자", "format": "png", "type": "baselayer", "version": "1",
            "minzoom": str(min(ZOOMS)), "maxzoom": str(max(ZOOMS)), "bounds": f"{WEST},{SOUTH},{EAST},{NORTH}"}
    db.executemany("INSERT INTO metadata VALUES (?, ?)", meta.items())
    count = 0
    for z in ZOOMS:
        xs, ys = tile_range(z)
        for x in xs:
            for y in ys:
                # MBTiles는 TMS 규칙이라 y를 뒤집어 저장한다.
                db.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (z, x, 2**z - 1 - y, tile_png(z, x, y)))
                count += 1
    db.commit()
    db.close()
    return count


def make_csv(path: Path, n: int):
    rnd = random.Random(42)
    lines = ["전주번호,위도,경도,선로명"]
    for i in range(1, n + 1):
        lat = rnd.uniform(SOUTH + 0.002, NORTH - 0.002)
        lon = rnd.uniform(WEST + 0.002, EAST - 0.002)
        lines.append(f"T-{i:04d},{lat:.6f},{lon:.6f},시험{(i - 1) // 100 + 1}선")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    out = Path(sys.argv[1])
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 1000
    out.mkdir(parents=True, exist_ok=True)
    tiles = make_mbtiles(out / "test-grid.mbtiles")
    make_csv(out / f"poles-{n}.csv", n)
    print(f"test-grid.mbtiles: 타일 {tiles}개, poles-{n}.csv: 전주 {n}개")
