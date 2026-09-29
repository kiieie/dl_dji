// 토큰만 있는 디자인 시스템 묶음(ds-bundle/)을 만든다. 앱이 Kotlin/Compose라 design-sync 변환기(React dist 대상)를 쓸 수 없어
// 같은 출력 형식을 직접 만든다(off-script layout). 기준은 앱 코드이며, 여기 값을 손으로 고치지 않는다.
//   node .design-sync/build.mjs <design-sync 스킬 폴더>
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const SKILL = process.argv[2];
if (!SKILL) throw new Error('usage: node .design-sync/build.mjs <design-sync skill dir>');
const THEME = 'app/src/main/java/com/example/myfirstapp/ui/theme';
const OUT = 'ds-bundle';
const NAMESPACE = 'InspectionDS';

// 1. 색: Color.kt의 상수 값을 Theme.kt의 MD3 역할 이름으로 묶는다(surface = Background처럼 한 값이 여러 역할에 쓰인다)
const hex = Object.fromEntries(
  [...readFileSync(`${THEME}/Color.kt`, 'utf8').matchAll(/val (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)/g)].map((m) => [m[1], `#${m[2].toUpperCase()}`]),
);
const roles = [...readFileSync(`${THEME}/Theme.kt`, 'utf8').matchAll(/^\s+(\w+) = (\w+),$/gm)].map((m) => {
  if (!hex[m[2]]) throw new Error(`Theme.kt ${m[1]} = ${m[2]}: Color.kt에 없음`);
  return [m[1].replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`), hex[m[2]]];
});
if (roles.length < 30) throw new Error(`MD3 색 역할이 ${roles.length}개뿐: Theme.kt 형식이 바뀌었는지 확인`);

// 2. 글자: Compose Material3 기본 글자 체계(Type.kt는 bodyLarge만 같은 값으로 다시 적음). [크기, 줄 높이, 굵기, 자간] (sp)
const TYPE = {
  'display-large': [57, 64, 400, -0.25], 'display-medium': [45, 52, 400, 0], 'display-small': [36, 44, 400, 0],
  'headline-large': [32, 40, 400, 0], 'headline-medium': [28, 36, 400, 0], 'headline-small': [24, 32, 400, 0],
  'title-large': [22, 28, 400, 0], 'title-medium': [16, 24, 500, 0.15], 'title-small': [14, 20, 500, 0.1],
  'body-large': [16, 24, 400, 0.5], 'body-medium': [14, 20, 400, 0.25], 'body-small': [12, 16, 400, 0.4],
  'label-large': [14, 20, 500, 0.1], 'label-medium': [12, 16, 500, 0.5], 'label-small': [11, 16, 500, 0.5],
};

// 3. 데이터 시각화 색(10.1 예외): 화면 코드에 직접 쓰는 값. 원본 파일에 그 값이 아직 있는지 확인해 어긋남을 막는다
const DATAVIZ = [
  ['pole', '#D9480F', 'ui/MapScreen.kt', '지도 전주 점'],
  ['pole-excluded', '#868E96', 'ui/MapScreen.kt', '지도 제외 전주'],
  ['area', '#1864AB', 'ui/MapScreen.kt', '점검 영역 선·면(면은 불투명도 0.12)'],
  ['home', '#212529', 'ui/MapScreen.kt', '이륙 지점(흰 테두리)'],
  ['map-background', '#E6EAE7', 'ui/MapScreen.kt', '지도 파일이 없을 때 바탕'],
  ['sortie-1', '#9C36B5', 'ui/MapScreen.kt', '소티 경로 색(순서대로 돌려 씀)'],
  ['sortie-2', '#0C8599', 'ui/MapScreen.kt', ''], ['sortie-3', '#C2255C', 'ui/MapScreen.kt', ''],
  ['sortie-4', '#F59F00', 'ui/MapScreen.kt', ''], ['sortie-5', '#3B5BDB', 'ui/MapScreen.kt', ''],
  ['pole-done', '#4CD08A', 'ui/flight/FlightScreen.kt', '비행 지도: 완료'],
  ['pole-active', '#5B9BFF', 'ui/flight/FlightScreen.kt', '비행 지도: 지금 가는 전주'],
  ['pole-pending', '#FF9F43', 'ui/flight/FlightScreen.kt', '비행 지도: 대기'],
  ['pole-skipped', '#80858E', 'ui/flight/FlightScreen.kt', '비행 지도: 건너뜀'],
  ['pole-failed', '#FF5449', 'ui/flight/FlightScreen.kt', '비행 지도: 실패'],
  ['aircraft', '#FFC53D', 'ui/flight/FlightScreen.kt', '비행 지도: 기체 위치'],
  ['box-detected', '#FF5449', 'ui/result/ResultScreen.kt', '사진 위 검출 대상(조류 둥지, 이물질) 상자'],
  ['box-other', '#FFC53D', 'ui/result/ResultScreen.kt', '사진 위 그 밖의 탐지 상자'],
];
for (const [name, v, file] of DATAVIZ) {
  const src = readFileSync(`app/src/main/java/com/example/myfirstapp/${file}`, 'utf8').toUpperCase();
  if (!src.includes(v.slice(1))) throw new Error(`dataviz ${name} ${v}: ${file}에 없음(앱 값이 바뀜)`);
}

rmSync(OUT, { recursive: true, force: true });
mkdirSync(`${OUT}/tokens`, { recursive: true });
const w = (p, s) => writeFileSync(join(OUT, p), s);
const HEAD = '/* 앱 코드(ui/theme, 화면 코드)에서 생성. 직접 고치지 말 것: .design-sync/build.mjs */\n';

w('tokens/colors.css', HEAD + ':root {\n' + roles.map(([r, v]) => `  --md-sys-color-${r}: ${v};`).join('\n') + '\n}\n');
w('tokens/typography.css', HEAD + `:root {
  --md-ref-typeface-plain: Roboto, "Noto Sans KR", system-ui, sans-serif;
${Object.entries(TYPE).map(([k, [s, lh, wt, ls]]) =>
    `  --md-sys-typescale-${k}-size: ${s}px;\n  --md-sys-typescale-${k}-line-height: ${lh}px;\n  --md-sys-typescale-${k}-weight: ${wt};\n  --md-sys-typescale-${k}-tracking: ${ls}px;`).join('\n')}
}
`);
w('tokens/shape.css', HEAD + `:root {
  /* MD3 기본 모양(Compose Material3 Shapes 기본값) */
  --md-sys-shape-corner-extra-small: 4px;
  --md-sys-shape-corner-small: 8px;
  --md-sys-shape-corner-medium: 12px;
  --md-sys-shape-corner-large: 16px;
  --md-sys-shape-corner-extra-large: 28px;
  --md-sys-shape-corner-full: 9999px;
  /* 장갑 조작(10.1): 모든 터치 영역 48dp 이상, 비행 중 주요 버튼 56dp 이상 */
  --app-touch-min: 48px;
  --app-touch-flight: 56px;
  /* 화면 여백: 목록 화면 24dp, 카드 안 16dp, 요소 사이 8dp */
  --app-space-screen: 24px;
  --app-space-card: 16px;
  --app-space-gap: 8px;
  /* 화면: DJI RC Pro Enterprise 가로 1920x1080 */
  --app-screen-width: 1920px;
  --app-screen-height: 1080px;
}
`);
w('tokens/dataviz.css', HEAD + ':root {\n  /* 데이터를 그리는 색만 직접 값(10.1 예외). UI 요소에는 쓰지 않는다 */\n' +
  DATAVIZ.map(([n, v, f, d]) => `  --app-dataviz-${n}: ${v};${d ? ` /* ${d} (${f}) */` : ''}`).join('\n') + '\n}\n');
w('styles.css', `@import url("https://fonts.googleapis.com/css2?family=Noto+Sans+KR:wght@400;500;700&family=Roboto:wght@400;500;700&display=swap");
@import "./tokens/colors.css";
@import "./tokens/typography.css";
@import "./tokens/shape.css";
@import "./tokens/dataviz.css";

/* 앱은 다크 테마 하나(10.1). 바탕 background, 글자 on-background */
:root { color-scheme: dark; }
body {
  margin: 0;
  background: var(--md-sys-color-background);
  color: var(--md-sys-color-on-background);
  font-family: var(--md-ref-typeface-plain);
  font-size: var(--md-sys-typescale-body-large-size);
  line-height: var(--md-sys-typescale-body-large-line-height);
  letter-spacing: var(--md-sys-typescale-body-large-tracking);
}
`);

// 컴포넌트는 없다(Compose 컴포넌트는 React로 올릴 수 없음). 빈 전역 이름만 둔다
const header = { namespace: NAMESPACE, components: [], sourceHashes: {}, inlinedExternals: [], builtBy: 'cc-design-sync' };
w('_ds_bundle.js', `/* @ds-bundle: ${JSON.stringify(header)} */\nwindow.${NAMESPACE} = window.${NAMESPACE} || {};\n`);

const conventions = readFileSync('.design-sync/conventions.md', 'utf8');
w('README.md', conventions + `\n## 토큰 목록 (생성)\n\n### 색 역할\n\n| 토큰 | 값 |\n|---|---|\n` +
  roles.map(([r, v]) => `| \`--md-sys-color-${r}\` | ${v} |`).join('\n') +
  `\n\n### 글자 체계\n\n| 역할 | 크기/줄 높이 | 굵기 |\n|---|---|---|\n` +
  Object.entries(TYPE).map(([k, [s, lh, wt]]) => `| \`--md-sys-typescale-${k}-*\` | ${s}/${lh}px | ${wt} |`).join('\n') +
  `\n\n### 데이터 시각화 색\n\n| 토큰 | 값 | 용도 |\n|---|---|---|\n` +
  DATAVIZ.map(([n, v, f, d]) => `| \`--app-dataviz-${n}\` | ${v} | ${d || '소티 경로'} |`).join('\n') + '\n');

w('.ds-build-meta.json', JSON.stringify({ shape: 'package', componentCount: 0, tokensOnly: true }, null, 2) + '\n');

// 검증 기준점(_ds_sync.json): 변환기와 같은 해시 함수로 만든다. 컴포넌트가 없어 renderHashes는 비어 있다
const { styleShaFor, auxShaFor, KEY_RECIPE, scriptsShaFor } = await import(join(SKILL, 'lib/sync-hashes.mjs'));
const bundleSha12 = createHash('sha256').update(readFileSync(join(OUT, '_ds_bundle.js'))).digest('hex').slice(0, 12);
w('_ds_sync.json', JSON.stringify({
  shape: 'package', styleSha: styleShaFor(OUT, { includeBundleBody: true }), renderHashes: {}, keyRecipe: KEY_RECIPE,
  scriptsSha: scriptsShaFor(), sourceHashes: {}, auxSha: auxShaFor(OUT), bundleSha12,
}, null, 2) + '\n');
console.log(`ds-bundle: 색 역할 ${roles.length}, 글자 ${Object.keys(TYPE).length}, 데이터 색 ${DATAVIZ.length}`);
