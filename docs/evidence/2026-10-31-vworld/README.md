# 2026-10-31 VWorld 배경 지도 연계(RC Pro)

계획: 배경 지도는 ⑥ 설정에서 고른다(Bing 위성 / VWorld 위성 / VWorld 일반, 7.4). 키는 `secrets/vworld.properties`(git 제외)에 둔다.

| 파일 | 확인한 것 | 결과 |
|---|---|---|
| (PC curl) | VWorld WMTS `Satellite`(jpeg), `Hybrid`(png), `Base`(png)가 z16·z18·z19에서 200 응답 | 통과 |
| 01-settings-map.png | 설정 → 배경 지도 세 칸, VWorld 위성일 때만 "도로·지명 라벨" 스위치, 지도별 캐시 파일 목록 | 통과 |
| 02-plan-vworld-satellite.png | 계획 지도: VWorld 위성 + 하이브리드 라벨(지명·역·도로) | 통과 |
| (로그) | 계획 저장 → `map prefetch source=VWORLD_SATELLITE tiles=677` → 위성 677장(8.9MB)·라벨 677장(1.3MB), 실패 0. `maps/vworld-satellite-cache.mbtiles`, `vworld-hybrid-cache.mbtiles` 생성 | 통과 |
| 03-offline-flight-vworld.png | **Wi-Fi를 끄고** 비행 지도: VWorld 영상·라벨·전주번호·설비 기호 표시 | 통과 |
| 04-plan-vworld-base.png | VWorld 일반지도 | 통과 |

- 설정을 바꾸면 다음에 지도 화면을 열 때 적용된다.
- 캐시는 지도마다 따로이고, 지금 고른 지도의 캐시만 그린다(Bing 캐시와 VWorld 캐시가 섞이지 않음).
- 설정 "상태" 칸을 실제에 맞게 고쳤다. LDM은 앱의 실제 상태를 보이고, 외부 전송은 "배경 지도 타일 받기만"으로 보인다.
