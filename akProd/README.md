# akProd

CP_Colored(CP3) / CP_mono(CP14) 생산 데이터 전용 Android Kotlin 프로젝트.

## 포함 기능
- 로그인: 기존 ERP HAA010T 사번/전화번호 끝 4자리 인증 방식 유지
- 등록 / 조회 카테고리: CP_Colored, CP_mono 각각 제공
- 촬영·사진 선택, 화면 영역 검출, 한글/영문 ML Kit OCR, 숫자 영역 수정
- 0/8 오인식 보정, 0 글꼴 학습, 등록 재시도 및 중복 요청 확인
- CP_mono 사출 화면(20항목), 계량 화면(15항목)을 별도 레코드로 저장
- 조회에서 화면 유형 표시, 기존 합본 레코드 조회 호환

기존 물류·재고·바코드 업무 및 PB 5호기 소스/메뉴/Activity는 포함하지 않습니다.
앱 ID / namespace: com.bsd.akProd. 기존 앱과 별도 설치됩니다.

## 개발 환경
Android Studio에서 이 폴더를 Open합니다. Gradle JDK는 Android Studio 내장 JBR을 사용합니다.
AGP 9.2.1, Gradle 9.4.1, compileSdk 36, minSdk 24, targetSdk 34.
원본의 SDK 및 DB 접속 설정은 local.properties에 복사했습니다. 이 파일은 Git 제외 대상입니다.
DB_IP / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD / DB_CLASSES를 사용합니다.
로그인 DB는 DB_AUTH_NAME으로 변경할 수 있으며 기본값은 원본과 같은 JSAK_ERP입니다.
생산 데이터 DB는 DB_NAME을 사용합니다. 원본 akPDA 경로에 대한 빌드 의존성은 없습니다.

## DB 준비
CP_mono·CP_Colored 신규 등록은 수치만 저장하며 사진은 OCR에만 사용합니다.
기존 DB는 APK 설치 전에 scripts/cp-values-only.sql을 실행해 사진 수 0을 허용해야 합니다.
기존 사진은 유지하고 조회할 수 있습니다. 상세 배포·검증 내용은 CP_VALUES_ONLY.md를 참고하세요.
신규 DB에는 scripts/cp3-schema.sql 및 scripts/cp14-schema.sql을 확인 후 적용합니다.
구버전 테이블 이름 변경은 scripts/cp-menu-schema.sql을 검토합니다.
기존 두 메뉴의 사진 수 제약은 scripts/cp-values-only.sql로 0~5장을 허용합니다.
SQL 파일은 자동 실행되지 않습니다. 이번 분리 작업에서 DB 스키마/데이터는 변경하지 않았습니다.

## 빌드 및 검증
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```
APK: app/build/outputs/apk/debug/app-debug.apk
OcrRegressionTest는 화면별 필수 항목/혼합 금지, 표 매핑, 화면 검출 및 0 보정을 검증합니다.
실제 단말 촬영, 사내망 로그인/DB 저장/조회는 현장 연결 환경에서 확인해야 합니다.

## 공정관리일지 사진 등록·조회
공정관리일지 메뉴와 사진 OCR 보조입력/DB 저장/기간·설비별 조회를 추가했습니다.
최초 DB 준비와 사용법: [PROCESS_JOURNAL.md](PROCESS_JOURNAL.md).


CP_Colored / CP_mono: 메뉴 진입 시 새 입력창을 열고, 이전 미확인 요청은 등록 화면의 '미확인 등록 보관함'에서 재확인합니다.

