#!/usr/bin/env python3
"""비행 스모크 시험 뒤 앱 DB·이미지 목록·알림음 로그를 대조한다.

사용: verify.py <inspection.db> <images.txt> <sound.log>
"""
import sqlite3
import sys
from collections import Counter

db = sqlite3.connect(sys.argv[1])
images = set(open(sys.argv[2], encoding="utf-8").read().split())
sounds = Counter(line.split(": ", 1)[1].strip() for line in open(sys.argv[3], encoding="utf-8") if ": " in line)

q = lambda sql: db.execute(sql).fetchall()
(mission_status,), = q("SELECT status FROM mission")
visits = q("SELECT v.id, p.poleId, v.status, v.skipReason, v.arrivedAt FROM visit v JOIN pole p ON p.id = v.poleRowId")
done = [v for v in visits if v[2] == "DONE"]
skipped = [v for v in visits if v[2] == "SKIPPED"]
caps = q("SELECT c.visitId, c.lens, c.source, c.filePath, c.originalDownloaded, p.poleId, c.takenAt "
         "FROM capture c JOIN visit v ON v.id = c.visitId JOIN pole p ON p.id = v.poleRowId")
sorties = q("SELECT status, startedAt, endedAt FROM sortie")
events = q("SELECT fromState, toState, reason FROM flight_event ORDER BY id")
(model_version,), = q("SELECT modelVersion FROM mission")
# 사진 순서(id)에서 몇 번째 사진에 탐지가 있는지, 그리고 이상 탐지 전주
cap_ids = [r[0] for r in q("SELECT id FROM capture ORDER BY id")]
det_caps = {cap_ids.index(r[0]) + 1 for r in q("SELECT DISTINCT captureId FROM detection")}
anomalous = {r[0] for r in q("SELECT DISTINCT c.visitId FROM detection d JOIN capture c ON c.id = d.captureId")}
chain_ok = all(events[i][0] == events[i - 1][1] for i in range(1, len(events)))

checks = [
    ("미션 상태", "DONE", mission_status),
    ("건너뛴 전주 1개(운용자 건너뛰기)", [("운용자 건너뛰기", None)], [(v[3], v[4]) for v in skipped]),
    ("나머지 전주는 모두 완료", len(visits) - 1, len(done)),
    ("완료 전주는 도착 시각이 있음", True, all(v[4] for v in done)),
    ("사진 = 완료 전주 × 렌즈 2개(광각, 망원)", sorted((v[0], l) for v in done for l in ("WIDE", "ZOOM")), sorted((c[0], c[1]) for c in caps)),
    ("사진 출처는 모두 AUTO", {"AUTO"}, {c[2] for c in caps}),
    ("미리보기라 원본 아님", {0}, {c[4] for c in caps}),
    ("이미지 파일 = DB 사진(4.3 이름 규칙)", {f"{c[5]}_{c[6]}_{c[1]}.jpg" for c in caps}, images),
    ("DB 파일 경로 = 이미지 파일", {f"{c[5]}_{c[6]}_{c[1]}.jpg" for c in caps}, {c[3].rsplit("/", 1)[-1] for c in caps if c[3]}),
    ("소티 완료, 시작 ≤ 종료", True, all(s[0] == "DONE" and s[1] and s[1] <= s[2] for s in sorties)),
    ("상태 기록이 끊김 없이 이어짐", True, chain_ok),
    ("첫 전이", ("Idle", "PreflightCheck"), events[0][:2]),
    ("마지막 전이", "MissionDone", events[-1][1]),
    ("RC 일시정지 기록", 1, sum(e[1] == "Paused" and e[2] == "RC 일시정지 버튼" for e in events)),
    ("재개 기록", 1, sum(e[0] == "Paused" and e[2] == "재개" for e in events)),
    ("도착음 = 완료 전주 수", len(done), sounds["ARRIVED"]),
    ("일시정지음 1, 재개음 1", (1, 1), (sounds["PAUSED"], sounds["RESUMED"])),
    ("모의 탐지기 모델 버전", "fake-1", model_version),
    ("이상음 = 이상 탐지 전주 수, 점검 완료음 = 나머지 완료 전주", (len(anomalous), len(done) - len(anomalous)),
     (sounds["ANOMALY"], sounds["INSPECTION_OK"])),
    ("분석 오류음 없음", 0, sounds["ERROR"]),
    ("모의 탐지: 사진 5장마다 1장", [i for i in range(1, len(caps) + 1) if i % 5 == 0], sorted(det_caps)),
]
ok = True
for name, want, got in checks:
    ok &= want == got
    print(f"{'일치' if want == got else '불일치'}: {name} 기대 {want}, 앱 {got}")
print(f"전주 {len(visits)}개, 상태 전이 {len(events)}건, 사진 {len(caps)}장")
sys.exit(0 if ok else 1)
