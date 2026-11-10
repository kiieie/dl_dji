"""전주 좌표 오차 재기(2026-11-10): 전주 바로 위(짐벌 -85° 아래)에서 찍은 광각 사진으로 전주 머리의 실제 위치를 구해 엑셀 좌표와 비교한다.

사용: python tools/pole_gps_error.py <설비 엑셀.xlsx> <사진 폴더> [출력 폴더] [--fix 파일명=x,y]...
- 사진: DJI 원본(XMP GPS·상대 고도·짐벌). 망원은 건너뛴다(광각만, 시야가 넓어 오차 3m여도 들어옴).
- 전주 머리 화면 위치: 설비 모델(best.pt) 상자들의 중심(애자가 완금을 따라 전주 양쪽에 놓임). 못 찾으면 화면 가운데로 두고 "추정 없음".
  그림(<출력>/<사진>.jpg)에 빨간 +로 표시하니 눈으로 보고 틀리면 --fix 파일명=x,y(원본 픽셀)로 고쳐 다시 돌린다.
- 거리: 상대 고도 - 전주 지상 높이(길이 - 근입, PoleCodes와 같은 규칙). 이륙 지점과 전주 땅 높이가 다르면(±1.5m) 오차의 약 10%가 틀린다.
- 결과: 전주별 동·북·거리(m) 표. 기체 GPS(RTK 없음) 오차 ±0.5~1m가 섞여 있다.
필요: ultralytics(D:/kiie/projects/inspection/.venv), openpyxl, Pillow. 모델 경로는 MODEL 환경 변수로 바꿀 수 있다.
"""
import glob, math, os, re, sys
import openpyxl
from PIL import Image, ImageDraw

MODEL = os.environ.get('MODEL', r'D:\kiie\projects\dl_dji\models_src\dl_inspection_model\facility_detection\facility_v1_coco\weights\best.pt')
LINK_M = 20.0  # 사진을 전주에 붙이는 최대 수평 거리(앱 SHUTTER_POLE_LINK_M과 같음)


def dms(v):
    s = str(v).strip()
    if s.count('.') >= 2:
        d, m, sec = s.split('.', 2)
        return int(d) + int(m) / 60 + float(sec) / 3600
    return float(s)


def embed(length):  # PoleCodes.embedmentM
    return length / 6 if length <= 15 else 2.5


def poles_of(xlsx):
    ws = openpyxl.load_workbook(xlsx, read_only=True).worksheets[0]
    rows = list(ws.iter_rows(values_only=True))
    h = [str(c).strip() if c else '' for c in rows[0]]
    col = lambda name: h.index(name)
    out = {}
    for r in rows[1:]:
        if not r[col('전산화번호')]:
            continue
        length = float(re.sub(r'[^0-9.]', '', str(r[col('전주규격')] or '16')) or 16)
        out[str(r[col('전산화번호')])] = (dms(r[col('위도')]), dms(r[col('경도')]), length - embed(length))
    return out


def enu(lat0, lon0, lat, lon):
    r = 6378137.0
    return math.radians(lon - lon0) * r * math.cos(math.radians(lat0)), math.radians(lat - lat0) * r


def xmp(path):
    b = open(path, 'rb').read(400000)
    s = b[b.find(b'<x:xmpmeta'):b.find(b'</x:xmpmeta>')].decode('utf-8', 'replace')
    return dict(re.findall(r'drone-dji:(\w+)="([^"]*)"', s))


def main():
    fixes = {}
    i = 0
    raw = sys.argv[1:]
    while i < len(raw):
        if raw[i] == '--fix':
            name, xy = raw[i + 1].split('=')
            fixes[name] = tuple(float(v) for v in xy.split(','))
            i += 2
            continue
        i += 1
    args = [a for j, a in enumerate(raw) if a != '--fix' and (j == 0 or raw[j - 1] != '--fix')]
    xlsx, folder = args[0], args[1]
    out_dir = args[2] if len(args) > 2 else os.path.join(folder, 'gps_error')
    os.makedirs(out_dir, exist_ok=True)
    poles = poles_of(xlsx)
    from ultralytics import YOLO
    model = YOLO(MODEL)
    results = []
    for p in sorted({os.path.normcase(q): q for q in glob.glob(os.path.join(folder, '*.jpg')) + glob.glob(os.path.join(folder, '*.JPG'))}.values()):
        x = xmp(p)
        if not x or float(x.get('GimbalPitchDegree', 0)) > -85:
            continue
        im = Image.open(p)
        w, h = im.size
        if w != 5280:  # 광각만(망원 4000×3000, 줌 사진 dz>1도 뺌)
            continue
        dz = float((im.getexif().get_ifd(0x8769).get(0xA404) or 1))
        if dz > 1.01:
            continue
        lat, lon = float(x['GpsLatitude']), float(x['GpsLongitude'])
        near = min(((pid, enu(lat, lon, v[0], v[1])) for pid, v in poles.items()), key=lambda t: math.hypot(*t[1]))
        pid, (pe, pn) = near
        if math.hypot(pe, pn) > LINK_M:
            continue
        f = float(x.get('CalibratedFocalLength', 3713))
        dist = float(x['RelativeAltitude']) - poles[pid][2]
        name = os.path.basename(p)
        if name in fixes:
            u, v, how = fixes[name][0], fixes[name][1], '수동'
        else:
            r = model.predict(p, imgsz=1280, conf=0.25, verbose=False)[0]
            cs = [((b.xyxy[0][0] + b.xyxy[0][2]) / 2, (b.xyxy[0][1] + b.xyxy[0][3]) / 2) for b in r.boxes]
            if cs:
                u, v, how = float(sum(c[0] for c in cs) / len(cs)), float(sum(c[1] for c in cs) / len(cs)), f'상자 {len(cs)}개'
            else:
                u, v, how = w / 2, h / 2, '추정 없음'
        # 화면 위 = 짐벌 요 방향. 오른쪽 +, 아래 +
        fwd = -(v - h / 2) / f * dist
        right = (u - w / 2) / f * dist
        y = math.radians(float(x['GimbalYawDegree']))
        de = fwd * math.sin(y) + right * math.cos(y)
        dn = fwd * math.cos(y) - right * math.sin(y)
        ee, en = pe - de, pn - dn  # 엑셀 좌표 - 실제(사진) 위치
        results.append((pid, name, how, dist, ee, en))
        th = im.copy()
        th.thumbnail((1400, 1400))
        s = th.width / w
        d = ImageDraw.Draw(th)
        d.line([(u * s - 30, v * s), (u * s + 30, v * s)], fill='red', width=4)
        d.line([(u * s, v * s - 30), (u * s, v * s + 30)], fill='red', width=4)
        d.text((10, 10), f'{pid} err {math.hypot(ee, en):.1f}m', fill='red')
        th.save(os.path.join(out_dir, name), quality=85)
    print('전주 | 사진 | 머리 찾기 | 머리까지 m | 엑셀-실제 동 m | 북 m | 거리 m')
    for pid, name, how, dist, ee, en in sorted(results):
        print(f'{pid} | {name} | {how} | {dist:.1f} | {ee:+.1f} | {en:+.1f} | {math.hypot(ee, en):.1f}')


if __name__ == '__main__':
    main()
