#!/usr/bin/env bash
# 현장 배포(2026-11-07, README "배포"): release APK를 만들어 build/dist/dl_dji-<버전>.apk(+ .json)로 둔다.
#   --upload      NAS(WebDAV)의 $NAS/ 에도 올린다. RC 브라우저에서 출력된 주소를 열어 받고 설치한다.
#   --backup-key  배포 키(secrets/release.keystore·release.properties)를 $NAS/../signing/ 에 백업한다(처음 한 번).
# NAS 계정은 ~/.netrc(machine <NAS> login <id> password <pw>)에서 읽는다. 같은 이름 파일이 있으면 덮어쓰지 않는다.
set -euo pipefail
cd "$(dirname "$0")/.."
NAS="${NAS_DAV:-https://<NAS>/Projects/R26IA06/배포/dl_dji}"
upload=0; backup=0
for a in "$@"; do case "$a" in --upload) upload=1 ;; --backup-key) backup=1 ;; *) echo "모르는 옵션: $a"; exit 2 ;; esac; done

[ -f secrets/release.properties ] || { echo "secrets/release.properties 없음: 배포 키 없이 만든 APK는 RC에 덮어 설치되지 않습니다"; exit 1; }
if [ -f gradlew.bat ] && [ "${OS:-}" = "Windows_NT" ]; then
  export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"; ./gradlew.bat -q assembleRelease
else
  ./gradlew -q assembleRelease
fi

meta=app/build/outputs/apk/release/output-metadata.json
code=$(grep -o '"versionCode": *[0-9]*' "$meta" | grep -o '[0-9]*$')
name=$(grep -o '"versionName": *"[^"]*"' "$meta" | sed 's/.*"\([^"]*\)"$/\1/')
commit=$(git rev-parse --short HEAD)
[ -z "$(git status --porcelain -- app core gradle *.kts)" ] || commit="$commit+수정"
mkdir -p build/dist
apk="build/dist/dl_dji-$name.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"
sha=$(sha256sum "$apk" | cut -d' ' -f1)
printf '{"versionCode": %s, "versionName": "%s", "commit": "%s", "builtAt": "%s", "sha256": "%s"}\n' \
  "$code" "$name" "$commit" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$sha" > "${apk%.apk}.json"
echo "만듦: $apk ($code, $commit)"

# WebDAV 주소는 한글 폴더를 퍼센트 인코딩한다
enc() { python -c "import sys,urllib.parse as u;print(u.quote(sys.argv[1],safe=':/'))" "$1"; }
mkcol() { curl -s --netrc -o /dev/null -X MKCOL "$(enc "$1")/" || true; }  # 이미 있으면 405, 무시
put() { # 있으면 건너뜀(덮어쓰지 않음)
  local url; url=$(enc "$2")
  if [ "$(curl -s --netrc -o /dev/null -w '%{http_code}' -I "$url")" = 200 ]; then echo "이미 있음(건너뜀): $2"; return; fi
  curl -sf --netrc -o /dev/null -T "$1" "$url" && echo "올림: $2"
}
if [ $upload = 1 ] || [ $backup = 1 ]; then
  grep -q <NAS> ~/.netrc 2>/dev/null || { echo "~/.netrc에 NAS 계정이 없습니다"; exit 1; }
  mkcol "${NAS%/*}"; mkcol "$NAS"
fi
if [ $upload = 1 ]; then
  put "$apk" "$NAS/$(basename "$apk")"
  put "${apk%.apk}.json" "$NAS/$(basename "${apk%.apk}.json")"
  echo "RC 브라우저 주소: $(enc "$NAS/$(basename "$apk")")"
fi
if [ $backup = 1 ]; then
  mkcol "${NAS%/*}/signing"
  put secrets/release.keystore "${NAS%/*}/signing/release.keystore"
  put secrets/release.properties "${NAS%/*}/signing/release.properties"
fi
