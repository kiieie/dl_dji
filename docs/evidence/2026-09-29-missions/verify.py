#!/usr/bin/env python3
"""미션 목록·이어하기 스모크 시험 뒤 앱 DB를 대조한다.

사용: verify.py <inspection.db>
"""
import sqlite3
import sys
import time

db = sqlite3.connect(sys.argv[1])
q = lambda sql, *a: db.execute(sql, a).fetchall()
missions = dict(q("SELECT id, status FROM mission"))
visits = lambda m: q("SELECT p.poleId, v.status FROM visit v JOIN sortie s ON s.id = v.sortieId JOIN pole p ON p.id = v.poleRowId "
                     "WHERE s.missionId = ? ORDER BY s.seq, v.`order`", m)
m1, m2 = visits(1), visits(2)
remaining1 = sorted(p for p, st in m1 if st in ("PENDING", "IN_PROGRESS"))
last = q("SELECT MAX(COALESCE(endedAt, startedAt)) FROM sortie WHERE missionId = 1")[0][0]
day = time.strftime("%Y-%m-%d", time.localtime(last / 1000))
yesterday = time.strftime("%Y-%m-%d", time.localtime(time.time() - 86400))

checks = [
    ("미션 #1 부분 완료, #2 계획됨", {1: "PARTIAL", 2: "PLANNED"}, missions),
    ("미션 #1: 3개 완료, 2개 남음", (3, 2), (sum(st == "DONE" for _, st in m1), len(remaining1))),
    ("미션 #2 전주 = 미션 #1의 남은 전주", remaining1, sorted(p for p, _ in m2)),
    ("미션 #2는 모두 대기", {"PENDING"}, {st for _, st in m2}),
    ("미션 #1 마지막 비행은 어제(시각을 하루 앞당김)", yesterday, day),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name} 기대 {want}, 앱 {got}")
sys.exit(0 if ok else 1)
