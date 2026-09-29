# 배전선로 전주 점검 드론 앱 디자인 시스템 (토큰 전용)

DJI RC Pro Enterprise(가로 1920×1080, 안드로이드)에서 도는 점검 앱의 디자인 토큰입니다. 실제 앱은 Jetpack Compose + Material Design 3(MD3)이고, 이 프로젝트에는 **컴포넌트가 없고 토큰만** 있습니다. `window.InspectionDS`는 비어 있으니 가져다 쓸 컴포넌트를 찾지 마세요. 화면은 MD3 컴포넌트 모양(Filled/Tonal/Outlined 버튼, FilterChip, Card, TopAppBar, AlertDialog, SegmentedButton, ListItem)을 HTML/CSS로 그리고, 색·글자·모양은 아래 토큰으로만 지정합니다. 그린 화면은 개발자가 Compose의 같은 MD3 컴포넌트로 옮깁니다.

## 설정

- `styles.css` 하나만 불러오면 모든 토큰과 바탕(다크)이 적용됩니다. 테마는 **다크 하나**입니다. 라이트 테마나 동적 색을 만들지 마세요.
- 화면 틀은 1920×1080 가로 고정입니다(`--app-screen-width`, `--app-screen-height`). 세로 화면과 모바일 반응형은 필요 없습니다.

## 스타일 규칙 (MD3 + DJI 조종 앱 톤)

- **색은 MD3 역할 토큰으로만**: `var(--md-sys-color-<역할>)`. 예: 바탕 `background`, 카드 `surface-container-high`, 패널 `surface-container`, 기본 글자 `on-surface`, 보조 글자 `on-surface-variant`, 강조·주요 버튼 `primary`/`on-primary`, 보조 버튼 `secondary-container`/`on-secondary-container`, 테두리 `outline`/`outline-variant`.
- 의미 색: 파랑 `primary`(강조), 초록 `tertiary`(정상·완료·재개), 빨강 `error`(위험·RTH·검출). 주황·노랑 주의색은 MD3 역할이 아직 없으니 새로 만들지 말고 필요하면 개발자에게 알리세요.
- `--app-dataviz-*`는 지도·사진 위에 데이터를 그릴 때만 씁니다(전주 점, 소티 경로, 검출 상자). 버튼·글자·카드에는 쓰지 않습니다.
- 글자는 `--md-sys-typescale-<역할>-{size,line-height,weight,tracking}`(예: `title-medium`, `body-large`, `label-large`), 글꼴은 `var(--md-ref-typeface-plain)`입니다.
- 모서리는 `--md-sys-shape-corner-*`입니다. 버튼 `full`, 카드 `medium`, 대화상자 `extra-large`를 씁니다.
- **장갑 조작**: 누르는 요소는 모두 최소 `var(--app-touch-min)`(48px), 비행 화면의 주요 버튼(재개, RTH, 다음 소티)은 `var(--app-touch-flight)`(56px) 이상입니다. 여백은 `--app-space-screen`/`card`/`gap`입니다.
- 영상·지도 위 정보는 어두운 `surface-container` 패널에 올립니다. 글자 뒤로 지도 점이 비치면 읽기 어려우니 패널은 불투명하게 둡니다(직사광 가독성). DJI 로고·아이콘·상표는 쓰지 않습니다.
- 한국어 UI입니다. 버튼 이름은 동사로 끝냅니다(예: "비행 시작", "1차 판독 저장 후 다음").

## 화면 목록 (참고)

① 프로젝트 목록 + ② CSV 가져오기 → ①-1 미션 목록(이어하기) → ③ 계획(지도 + 왼쪽 패널) → ④ 비행(조종사) / 소티 완료(조종사 점검 + 보조자 1차 판독 진입) → ⑤ 결과·1차 판독(판정: 검출 맞음 / 검출 없음 / 재촬영, 대상: 조류 둥지·이물질).

## 예시

```html
<link rel="stylesheet" href="styles.css">
<div style="background: var(--md-sys-color-surface-container-high); border-radius: var(--md-sys-shape-corner-medium);
            padding: var(--app-space-card); display: flex; gap: var(--app-space-card); align-items: center;">
  <div style="flex: 1">
    <div style="font-size: var(--md-sys-typescale-title-medium-size); font-weight: var(--md-sys-typescale-title-medium-weight);">미션 #3 · 진행 중</div>
    <div style="color: var(--md-sys-color-on-surface-variant);">소티 2 · 완료 12/30 · 남은 전주 18</div>
  </div>
  <button style="min-height: var(--app-touch-min); padding: 0 24px; border: 0; border-radius: var(--md-sys-shape-corner-full);
                 background: var(--md-sys-color-primary); color: var(--md-sys-color-on-primary);
                 font: var(--md-sys-typescale-label-large-weight) var(--md-sys-typescale-label-large-size)/1 var(--md-ref-typeface-plain);">이어하기</button>
</div>
```
