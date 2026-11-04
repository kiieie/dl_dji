"""배터리 소모 보정(5.5): 운용 로그(1 Hz 텔레메트리)와 Pilot 2 화면 기록으로 Tuning.Energy 값을 맞춘다.

    python -I tools/energy_fit.py <ops-YYYYMMDD.jsonl>

- 배터리 %는 정수라서, 1%씩 떨어진 순간 사이 구간(= 정확히 1% 소모)마다 순항·호버·상하 시간을 세어
  1 = a·t순항 + b·t호버 + c·t상하 를 최소제곱으로 푼다(구간 수가 적어 불확실성을 같이 낸다).
- 미션마다 실제 소모·시간과 옛/새 모델 예측(EnergyModel.sortieCost와 같은 식)을 비교한다.
  경로 길이는 계획 대신 실제로 난 거리(미션+복귀)를 쓴다.
"""
import json
import math
import sys

sys.stdout.reconfigure(encoding="utf-8")

# Pilot 2 화면 기록(2026-11-03 11:15·11:48, KMZ 3 m/s 순항, 약 27~33 m): (프레임 번호, 배터리 %), 프레임 5초 간격
PILOT2 = {
    "11:15 (46→24%)": [(1, 46), (13, 43), (25, 40), (37, 36), (49, 33), (61, 30), (73, 27), (85, 24)],
    "11:48 (97→45%)": [(16, 97), (31, 93), (46, 89), (61, 85), (76, 82), (91, 78), (106, 74), (121, 70),
                       (136, 66), (151, 62), (166, 58), (181, 54), (196, 50), (211, 45)],
}

# 옛 값(2026-10 초기값)과 새 값. 새 값은 아래 출력으로 정해 Tuning.Energy에 옮겼다.
OLD = dict(cruise=100 / (25 * 60), hover=100 / (28 * 60), shot=6.0, vz=3.0)
NEW = dict(cruise=100 / (30 * 60), hover=100 / (31 * 60), shot=6.0, vz=3.0)

# 미션(앱 설정값): 속도, 이동 고도, 촬영 고도, 앱이 비행 전 점검에 적은 예상(필요 − 예비 30%)
MISSIONS = {19: dict(v=5.0, transit=50.0, shotAlt=27.0, visits=4, logged=46 - 30),
            20: dict(v=8.0, transit=30.0, shotAlt=30.0, visits=15, logged=65 - 30)}


def load(path):
    tele, events = [], []
    for line in open(path, encoding="utf-8"):
        d = json.loads(line)
        (tele if d.get("type") == "tele" else events).append(d)
    return tele, events


def phase(s):
    if s["mode"] in ("TAKING_OFF", "LANDING") or abs(s["vs"]) > 0.5:
        return "vert"
    return "cruise" if s["spd"] >= 0.5 else "hover"


def airborne(s):
    return s["alt"] > 0.5 or abs(s["vs"]) > 0.2 or s["spd"] > 0.3


def windows(events):
    """미션별 (이륙 명령, 착륙) 시각과 수동 비행 구간."""
    out, start = {}, {}
    for e in events:
        if e.get("type") != "exec":
            continue
        m = e.get("mission")
        if e["what"] == "Takeoff":
            start[m] = e["t"]  # 마지막 Takeoff(재시도 포함)
        if e["what"] == "SortieDone":
            out[m] = (start[m], e["t"])
    return out


def solve(rows):
    """최소제곱 y = X·b (열 3개). 정규방정식을 가우스 소거로 푼다."""
    k = len(rows[0][0])
    a = [[sum(x[i] * x[j] for x, _ in rows) for j in range(k)] + [sum(x[i] * y for x, y in rows)] for i in range(k)]
    for i in range(k):
        p = max(range(i, k), key=lambda r: abs(a[r][i]))
        a[i], a[p] = a[p], a[i]
        for r in range(k):
            if r != i:
                f = a[r][i] / a[i][i]
                a[r] = [x - f * y for x, y in zip(a[r], a[i])]
    b = [a[i][k] / a[i][i] for i in range(k)]
    res = [y - sum(bi * xi for bi, xi in zip(b, x)) for x, y in rows]
    sd = math.sqrt(sum(r * r for r in res) / max(1, len(rows) - k))
    return b, sd


def steps(samples):
    """배터리가 1% 떨어진 순간 사이 구간마다 단계별 시간(초)."""
    rows, cur, last = [], None, None
    for s in samples:
        if last is not None and s["bat"] < last["bat"]:
            if cur is not None and last["bat"] - s["bat"] == 1:
                rows.append(cur)
            cur = {"cruise": 0.0, "hover": 0.0, "vert": 0.0}
        elif cur is not None and last is not None:
            cur[phase(s)] += (s["t"] - last["t"]) / 1000
        last = s
    return rows


def predict(p, m, dist, shots, jumps, takeoff_landing_alt):
    """EnergyModel.sortieCost/sortieDurationSec와 같은 식."""
    vert_s = 2 * takeoff_landing_alt / p["vz"] + (jumps + 1) * 2 * max(0.0, m["transit"] - m["shotAlt"]) / p["vz"]
    move_s = dist / m["v"]
    hover_s = shots * p["shot"] + vert_s
    return move_s * p["cruise"] + hover_s * p["hover"], move_s + hover_s


def main(path):
    tele, events = load(path)
    wins = windows(events)
    photos = [e for e in events if e.get("type") == "photo" and e.get("what") == "AUTO"]

    print("== 1% 구간 회귀(순항·호버·상하 %/s) ==")
    allrows = []
    groups = {"수동": [s for s in tele if s.get("what") == "manual" and airborne(s)]}
    for m, (t0, t1) in wins.items():
        groups[f"#{m}"] = [s for s in tele if t0 <= s["t"] <= t1]
    for name, ss in groups.items():
        r = steps(ss)
        allrows += r
        secs = [sum(x.values()) for x in r]
        if secs:
            print(f"{name}: 1% 구간 {len(r)}개, 평균 {sum(secs) / len(secs):.1f}s/% (최소 {min(secs):.0f}, 최대 {max(secs):.0f})")
    b, sd = solve([([x["cruise"], x["hover"], x["vert"]], 1.0) for x in allrows])
    print(f"회귀: 순항 {b[0]:.4f}, 호버 {b[1]:.4f}, 상하 {b[2]:.4f} %/s (구간 잔차 표준편차 {sd:.2f}%)")
    t = sum(sum(x.values()) for x in allrows)
    print(f"전체 평균: {len(allrows) / t:.4f} %/s ({t / len(allrows):.1f}s/%)")

    print("\n== Pilot 2 3 m/s 순항(프레임 5초) ==")
    for name, pts in PILOT2.items():
        (f0, b0), (f1, b1) = pts[0], pts[-1]
        print(f"{name}: {(b0 - b1) / ((f1 - f0) * 5):.4f} %/s ({(f1 - f0) * 5}s 동안 {b0 - b1}%, ±1%면 ±{1 / ((f1 - f0) * 5):.4f})")

    print("\n== 미션 합계: 실제 vs 옛 모델 vs 새 모델 ==")
    print("미션 | 시간 실제 | 순항/호버/상하 s | 거리 m | 사진 | 이동 고도 구간 | 배터리 실제 | 앱 기록 예상 | 옛 모델 | 새 모델 | 모델 시간")
    for m, (t0, t1) in wins.items():
        ss = [s for s in tele if t0 <= s["t"] <= t1 and airborne(s)]
        ph = {"cruise": 0.0, "hover": 0.0, "vert": 0.0}
        dist = 0.0
        for a, c in zip(ss, ss[1:]):
            dt = (c["t"] - a["t"]) / 1000
            ph[phase(c)] += dt
            dist += c["spd"] * dt
        dur = (ss[-1]["t"] - ss[0]["t"]) / 1000
        used = ss[0]["bat"] - ss[-1]["bat"]
        n = sum(1 for e in photos if t0 <= e["t"] <= t1)
        mm = MISSIONS[m]
        # 이동 고도 구간: 촬영 고도보다 15m 넘게 올라갔다가 다시 내려온 횟수(#19는 50m 두 번)
        jumps, high = 0, False
        for s in ss:
            if s["mode"] == "MISSION" and s["alt"] > mm["shotAlt"] + 15 and not high:
                jumps, high = jumps + 1, True
            elif s["alt"] < mm["shotAlt"] + 5:
                high = False
        old, old_t = predict(OLD, mm, dist, n, max(0, jumps - 1), mm["transit"])
        new, new_t = predict(NEW, mm, dist, n, max(0, jumps - 1), mm["transit"])
        print(f"#{m} | {dur:.0f}s | {ph['cruise']:.0f}/{ph['hover']:.0f}/{ph['vert']:.0f} | {dist:.0f} | {n} | "
              f"{max(0, jumps - 1)} | {used}% ({ss[0]['bat']}→{ss[-1]['bat']}) | {mm['logged']}% | {old:.1f}% | {new:.1f}% | {new_t:.0f}s")
    print("(모델 시간은 옛·새 같음: 바뀐 것은 소모율뿐. 옛 모델 = 앱 기록 예상을 같은 식으로 다시 계산한 값)")


if __name__ == "__main__":
    main(sys.argv[1])
