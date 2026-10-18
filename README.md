# DJS PDF

광고 없는 PDF 읽기 앱. 웹/PWA와 Android WebView 앱이 같은 읽기 코드를 사용합니다.
현재 공개 Android 버전은 **1.2.1 / code 4**입니다.

- [Android 업데이트 안내](https://ldjkorea.github.io/DJS-PDF/update.html)
- [정식 릴리스와 APK](https://github.com/ldjkorea/DJS-PDF/releases/tag/v1.2.1)
- [웹 PDF 리더](https://ldjkorea.github.io/DJS-PDF/)

**기존 앱을 삭제하거나 데이터를 초기화하지 말고 업데이트 설치하세요.**
같은 패키지·서명·파일 origin·DB를 유지해 문서와 책갈피를 이어받습니다.

## 소스와 저장 데이터

- `index.html`: 화면·스타일, 외부 스크립트 로드와 CSP.
- `app.js`: 문서 세션, 검색·렌더링·책갈피·네이티브 연동.
- `reader-core.js`: 내용 해시, 검색 오프셋, 렌더링 크기·작업 세대.
- `reader-store.js`: 기존 `DjsPdfDB_v2` 연결과 파일·메타 원자 저장.
- `pdf.min.js`, `pdf.worker.min.js`: 로컬 PDF.js. 보안 백포트는 `SECURITY.md` 참조.
- `sw.js`: 앱 전용 캐시. 다른 앱·분류가 불명확한 옛 캐시는 삭제하지 않습니다.
- `android/`: Android 앱과 합성 데이터 기반 instrumentation 시험.

문서는 SHA-256 내용 키로 구분합니다. 옛 `pdf::이름::크기` 문서를 다시 열면 실제 저장
바이트의 해시를 확인한 뒤 페이지·책갈피·고정·회전·테마를 새 키에 이어받습니다.
옛 바이트와 메타는 복구용으로 유지하고 목록의 중복만 숨깁니다. 과거 버전에서 이미
다른 파일로 덮인 원본은 이 변경으로 복구할 수 없습니다. Android의 앱 ID·파일 origin·
DB 이름·스키마는 유지합니다. 오프라인 저장 실패 시 현재 문서는 메모리에서 읽을 수
있으나 재실행 보존을 약속하지 않고 저장 공간·원본 보관 안내를 표시합니다.
최대 파일 크기는 256 MiB입니다.

## 개발과 검사

Node.js의 기본 test runner를 사용합니다. `fake-indexeddb`는 저장 transaction의
롤백·기존 자료 보존을 시험하는 개발 전용 의존성이며 앱에 포함하지 않습니다.

```powershell
npm ci --ignore-scripts
npm run sync-assets
npm test
npm run check
node tools/test-java.cjs
```

`sync-assets`는 웹 자산의 내용으로 서비스 워커 캐시 세대를 계산하고 기존 Android
자산 사본을 맞춥니다. Android 빌드는 정본 웹 소스를 generated assets에 복사하므로
수동 사본이 오래되어도 APK에 옛 UI를 넣지 않습니다.

Gradle 8.11.1, AGP 8.9.2, JDK 17 이상, Android SDK 35가 필요합니다.
`android/local.properties`의 SDK 경로는 로컬 환경에서 지정하며 커밋하지 않습니다.
Windows에서는 실제 소스의 ASCII 경로를 사용하세요.

```powershell
./android/gradlew.bat -p android --offline --no-daemon assembleDebug lintDebug
./android/gradlew.bat -p android --offline --no-daemon assembleRelease -PpreserveInstalledSigner=true
./android/gradlew.bat -p android --offline --no-daemon assembleDebugAndroidTest
```

Release는 서명 없이 빌드되지 않습니다. 기존 설치와 같은 인증서를 명시적으로
사용하거나 `DJS_PDF_STORE_FILE`, `DJS_PDF_STORE_PASSWORD`, `DJS_PDF_KEY_ALIAS`,
`DJS_PDF_KEY_PASSWORD` 환경 변수로 서명을 지정합니다. 비밀 값은 소스에 넣지 않습니다.
기존 설치의 인증서는 교체하지 않았습니다. 자세한 이유와 제한은 `SECURITY.md`에 있습니다.

## Android 회귀시험

`QaInstrumentation`의 `seed`와 `fixed`는 개인 데이터가 없는 **전용 시험 에뮬레이터**에서 실행합니다.
`seed`는 구버전의 합성 문서와 읽기 상태를 만들고 `fixed`는 업데이트 후 기존 DB의
보존·이전, 네이티브 파일 열기, 손상 파일 격리, 볼륨 이벤트, 공유 바이트·해시,
provider의 읽기 전용 경계를 검사합니다. 개인 휴대폰 자료를 초기화하는 시험이 아닙니다.

```powershell
adb -s <전용시험기기> shell am instrument -w -e phase seed com.ldj.djspdf.test/com.ldj.djspdf.QaInstrumentation
# 같은 서명의 수정 Release APK를 install -r로 업데이트한 다음 실행
adb -s <전용시험기기> shell am instrument -w -e phase fixed com.ldj.djspdf.test/com.ldj.djspdf.QaInstrumentation
```

실물 휴대폰은 별도의 `phone-before` → 업데이트 → `phone-after` → `phone-test` →
`phone-finish` 순서로 검사합니다. 설치된 APK와 업데이트 APK의 서명 일치, 앱을 중지한
상태의 암호화 복구본 및 복호화 검증을 먼저 확보해야 합니다. 앱 삭제·데이터 초기화는
하지 않습니다. 모든 ADB 명령에는 확인한 물리 기기 serial을 명시합니다.

`PhysicalPhoneChecks`는 기존 DB의 파일별 SHA-256·원시 메타·책갈피·localStorage를
읽기 전용으로 대조합니다. 기존 문서를 열어 메타를 변경하지 않으며, 이 실행의 고유
합성 PDF로 열기·책갈피·키 입력·공유를 검사한 후 해당 키만 제거하고 앱 설정을 복원합니다.
공유는 외부 전송 없는 별도 UID의 `QaShareReceiver`로 파일 해시와 읽기 전용 권한을
확인합니다. Samsung 공유창에서 시험 앱이 숨겨지면 실제 공유창 호출을 확인한 뒤 같은
준비 파일 URI를 해당 시험 앱에 명시적으로 전달하며, 이 경로는 로그로 구분합니다.
볼륨 ON은 Android 입력 이벤트 주입, OFF는 Activity의 시스템 위임과 페이지 불변 검사입니다.
사람이 물리 버튼을 누른 결과나 일반 수신 앱의 발송 결과로 표현하지 않습니다.

`phone-finish`는 앱 재시작 후 기존 데이터가 동일한지 다시 확인하고 이 시험의 임시
manifest·해시가 일치하는 합성 공유 캐시만 정리합니다. 마지막에 이번에 설치한
`com.ldj.djspdf.test`만 제거합니다. 본 앱 `com.ldj.djspdf`를 제거하면 안 됩니다.

## 배포와 공개 업데이트

루트 `version.json`은 현재 공개 APK를 가리키는 계약으로 유지합니다. 새 APK를
공개하기 전에만 검증된 새 버전·코드·URL·SHA-256을 함께 반영합니다. 아직 존재하지 않는
다운로드 주소를 최신 공개 업데이트로 알리지 않습니다. APK 설치기는 서명 불일치를
막으며 앱도 다운로드 해시·패키지·버전·서명을 추가 확인합니다.

Push·Pages 배포·GitHub Release 생성·Drive 전달은 사용자가 요청한 범위에서만 수행합니다.

1.2.1 공개 APK는 실제 휴대폰에서 확인한 파일을 그대로 게시했습니다. 서명·패키지·
크기·SHA-256과 검증 범위는 `RELEASES/1.2.1/RELEASE-NOTES.md` 및 고정 manifest인
`RELEASES/1.2.1/version.json`에 기록합니다. Git 태그 `v1.2.1`은 검증한 앱 소스이고,
이후 Pages 배포 커밋은 공개 업데이트 JSON·안내 페이지·HTTP 서비스 워커 라우팅을
갱신합니다. APK 안의 사전 공개 업데이트 JSON·SW 캐시 세대는 빌드 시점 값이며,
Android는 파일 origin에서 SW를 등록하지 않고 업데이트 JSON을 HTTPS로 확인합니다.
APK를 다시 빌드하여 이미 검증·공개한 파일을 교체하지 않습니다.

Pages는 `main` 루트에서 배포합니다. `update.html`은 기존 앱 삭제 없이 업데이트하는
방법과 APK 링크를 안내하며 개인 문서·책갈피를 서버로 전송하지 않습니다. 서비스 워커의
리더 HTML fallback은 리더 경로에만 적용해 업데이트 페이지를 가리지 않게 합니다.
