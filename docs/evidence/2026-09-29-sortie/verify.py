#!/usr/bin/env python3
"""소티 완료·2인 운용 스모크 시험 뒤 앱 DB와 알림음 로그를 대조한다.

사용: verify.py <inspection.db> <sound.log>
"""
import sqlite3
import sys
from collections import Counter

db = sqlite3.connect(sys.argv[1])
sounds = Counter(l.split(": ", 1)[1].strip() for l in open(sys.argv[2], encoding="utf-8") if ": " in l)
q = lambda sql: db.execute(sql).fetchall()

(mission_status,), = q("SELECT status FROM mission")
visits = q("SELECT v.id, v.status FROM visit v")
events = q("SELECT fromState, toState, reason FROM flight_event ORDER BY id")
reviews = q("SELECT visitId, verdict, tags FROM review")
detected = {r[0] for r in q("SELECT DISTINCT c.visitId FROM detection d JOIN capture c ON c.id = d.captureId WHERE d.alert")}
# 첫 소티에서 검출된 전주: 모의 탐지기는 5번째 사진에 검출을 낸다
cap_visits = [r[0] for r in q("SELECT visitId FROM capture ORDER BY id")]
to = [e[1] for e in events]
i_done = to.index("SortieDone")
chain_ok = all(events[i][0] == events[i - 1][1] for i in range(1, len(events)))
# 운용자 RTH 전에 도착한 전주 수(첫 소티에서 처리한 전주)
first_arrivals = sum(1 for e in events[:i_done] if e[1] == "Arrived")

checks = [
    ("미션 상태", "DONE", mission_status),
    ("전주 5개 모두 완료", ["DONE"] * 5, [v[1] for v in visits]),
    ("운용자 RTH(길게) 기록", 1, sum(e[1] == "ReturningHome" and e[2] == "운용자 RTH" for e in events)),
    ("RTH 전에 처리한 전주 3개", 3, first_arrivals),
    ("소티 완료 1번 → 같은 실행기로 다음 소티(비행 전 점검)", ("SortieDone", "PreflightCheck"), (events[i_done][1], events[i_done + 1][1])),
    ("미션 완료로 끝남", "MissionDone", to[-1]),
    ("상태 기록이 끊김 없이 이어짐(판독 화면에 다녀와도 실행기 유지)", True, chain_ok),
    ("검출 전주 2개(사진 5·10번째)", {cap_visits[4], cap_visits[9]}, detected),
    ("1차 판독 1건: 첫 소티 검출 전주 = 검출 맞음, 대상 칩 bird_nest", [(cap_visits[4], "CONFIRMED", "bird_nest")], sorted(reviews)),
    ("도착음 5 · 검출음 2 · 점검 완료음 3 · 오류음 0", (5, 2, 3, 0),
     (sounds["ARRIVED"], sounds["ANOMALY"], sounds["INSPECTION_OK"], sounds["ERROR"])),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name}" + ("" if want == got else f" 기대 {want}, 앱 {got}"))
print(f"상태 전이 {len(events)}건")
sys.exit(0 if ok else 1)
