#!/usr/bin/env python3
"""앱 재시작 복구 스모크 시험 뒤 앱 DB를 대조한다(강제 종료 직후 DB와 끝난 뒤 DB).

사용: verify.py <종료 직후 inspection.db> <끝난 뒤 inspection.db>
"""
import sqlite3
import sys

q = lambda db, sql: sqlite3.connect(db).execute(sql).fetchall()
killed, final = sys.argv[1], sys.argv[2]

k_sortie = q(killed, "SELECT startedAt IS NOT NULL, endedAt IS NULL FROM sortie")
k_visits = [r[0] for r in q(killed, "SELECT status FROM visit ORDER BY id")]
events = q(final, "SELECT fromState, toState, reason FROM flight_event ORDER BY id")
i = next(n for n, e in enumerate(events) if e[2] == "앱 재시작 복구")
after = events[i:]
visits = [r[0] for r in q(final, "SELECT status FROM visit ORDER BY id")]
captures = [r[0] for r in q(final, "SELECT COUNT(*) FROM capture GROUP BY visitId ORDER BY visitId")]
arrivals_before = sum(e[1] == "Arrived" for e in events[:i])
arrivals_after = sum(e[1] == "Arrived" for e in after)

checks = [
    ("종료 직후: 소티 1개가 열린 채(시작 O, 종료 X)", [(1, 1)], k_sortie),
    ("종료 직후: 완료 2 · 진행 중 1 · 대기 2", ["DONE", "DONE", "IN_PROGRESS", "PENDING", "PENDING"], k_visits),
    ("복구 전이: Idle → Paused", ("Idle", "Paused"), events[i][:2]),
    ("복구 뒤 이륙 없이 재개(Takeoff 0회)", 0, sum(e[1] == "Takeoff" for e in after)),
    ("복구 뒤 재개 1회", 1, sum(e[1] == "PreparingLeg" and e[2] == "재개" for e in after)),
    ("복구 뒤 도착 3회(남은 전주만)", 3, arrivals_after),
    ("전주 5개 모두 완료", ["DONE"] * 5, visits),
    ("전주마다 사진 2장(광각·망원, 다시 찍은 전주 없음)", [2] * 5, captures),
    ("소티 종료 기록됨", [(1,)], q(final, "SELECT endedAt IS NOT NULL FROM sortie")),
    ("미션 상태", [("DONE",)], q(final, "SELECT status FROM mission")),
    ("미션 완료로 끝남", "MissionDone", events[-1][1]),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name}" + ("" if want == got else f" 기대 {want}, 앱 {got}"))
print(f"종료 전 도착 {arrivals_before}회, 상태 전이 {len(events)}건")
sys.exit(0 if ok else 1)
