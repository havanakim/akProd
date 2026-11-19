# AKPDA Kotlin / Android 전환

기존 C# Windows CE 코드는 유지하고 이 폴더에 Android 앱과 Kotlin JVM API 서버를 구현했습니다.

## 구성

- app: Android Kotlin / Jetpack Compose, Android 8.0(API 26) 이상. 하드웨어 스캐너의 키보드 입력 + Enter, 수동 입력 지원.
- shared: 요청/응답 계약, 소수 수량·BOX 검증.
- server: Kotlin JVM HTTP API + SQL Server JDBC. 앱에는 DB 접속 정보가 들어가지 않습니다.
- server/sql: 저장 요청 중복 실행 방지 테이블.

## 빌드

JDK 17 이상(Gradle 8.9를 지원하는 JDK), Android SDK 35가 필요합니다. Android Studio에서 이 kotlin 폴더를 Open하고 Gradle Sync합니다. Gradle JDK는 Android Studio의 Embedded JDK를 선택할 수 있습니다. local.properties는 자신의 SDK 경로에 맞추며 버전 관리에 포함하지 않습니다.

    .\build.ps1

build.ps1은 영문 경로의 전용 Gradle 캐시를 사용해 한글 Windows 사용자 경로의 테스트 실행 문제를 피합니다. Android Studio에서 테스트할 때도 Gradle User Home을 이 프로젝트의 .tools/gradle-home으로 지정할 수 있습니다.

SDK 경로에 한글이 있으면 JDK 이미지 생성 단계가 실패할 수 있습니다. 이 PC에서는 local.properties가 .tools/android-sdk의 영문 SDK 경로를 사용합니다. 다른 PC에서는 SDK 경로를 영문으로 지정하세요.

직접 빌드 명령: .\gradlew.bat -g .tools\gradle-home :shared:test :server:test :app:assembleDebug :app:lintDebug

APK 경로: app/build/outputs/apk/debug/app-debug.apk

## 즉시 실행 가능한 데모

    .\run-demo.ps1

기본값은 demo 모드, 127.0.0.1:8080입니다. 로그인은 공장 01 / 사번 demo / 비밀번호 demo입니다. 데모 데이터는 서버 메모리에만 있으며 재시작 시 초기화됩니다. 일부 투입 재고와 요청 중복 방지를 시뮬레이션하며 실제 MES 업무 전체를 재현하지는 않습니다.

Android 에뮬레이터: 설정의 API 주소 http://10.0.2.2:8080.
실물 PDA: 서버를 사내 인터페이스에 바인딩하고 접근 가능한 서버 주소를 설정해야 합니다. Debug APK만 HTTP를 허용하며 Release는 HTTPS만 허용합니다. 운영 시 신뢰 가능한 인증서가 있는 HTTPS 역방향 프록시 뒤에 API를 둡니다. 임의 인증서 신뢰나 인증서 검증 해제 코드는 없습니다.

## 전환 범위

| 기존 화면 | Android 기능 |
|---|---|
| PDA0001M1 | 로그인, 사용자 표시, 서버 세션, 메뉴 권한 |
| PDA0002M1 | API 주소·공장 코드 설정 |
| PDA1001M1 | 권한별 6개 업무 메뉴 |
| PDA2001M1 | BOX 조회·누적·중복/발주/INVOICE 검증·입고/취소 |
| PDA2002M1 | 탱크/설비 조회·BOX 조회·소수 수지 투입·취소 |
| PDA2003M1 | 분쇄재 품목·최대량 조회·실적 등록/취소 |
| PDA2004M1 | 분쇄재 투입 등록, 확인된 별도 취소 프로시저 설정 시 취소 |
| PDA2005M1 | 작업장·날짜별 지시 목록·LOT 조회·수검 실적 |
| PDA2006M1 | 투입/생산 LOT 조회·품목 일치 검증·등록/취소 |

변경점: 수량은 반올림하지 않고 decimal 값을 보존합니다. 음수와 0을 거부하며 서버도 재검증합니다. 분쇄재 처리량은 서버 조회 최대량을 초과할 수 없습니다(원본은 상한 검증이 없어 현장 규칙 확인 필요). 조회·저장은 백그라운드에서 실행하며 회전 시 ViewModel이 상태와 실행 중 요청을 유지합니다. 저장 중 조작을 차단하고 저장 전 확인창을 표시합니다.

알림은 앱이 전면에 있을 때 10초 간격으로 확인하며, 재귀 호출이나 자동 종료 대신 메시지로 표시합니다. 알림 프로시저 계약은 실제 DB에서 확인해야 합니다.

원본 Forms에는 화면 본체 55개가 있지만 csproj에 포함된 10개 화면과 실제 메뉴의 6개 업무만 전환했습니다. 미포함 PFWPDA 화면, 구형 프린터 제어, 자동 업데이트 실행기, 서버 시각으로 단말 시각 변경은 전환 범위에 포함하지 않았습니다.

## 실제 SQL Server 연결 전 필요한 확인

1. server/.env.example의 값을 실제 환경변수로 설정합니다. 이 파일은 자동으로 읽지 않습니다. 비밀번호는 로컬 환경에서만 설정합니다.
2. server/sql/001_request_ledger.sql을 검증용 MES DB에 적용합니다. 서버가 자동으로 DB 스키마를 변경하지 않습니다.
3. 호출 프로시저의 입력/출력 파라미터를 확인합니다. 서버는 sys.parameters에서 파라미터 순서와 타입을 확인하며 원본 C#에서 추출한 입력 계약과 다르면 실행하지 않습니다. 현재 어댑터는 dbo 스키마를 사용합니다.
4. BZPDALibrary.DBhelper가 처리하던 반환값/출력 메시지 규칙은 DLL 소스가 없어 확인되지 않았습니다. 실제 쓰기 프로시저마다 반환 코드와 출력 메시지 성공 조건을 검증한 뒤 write-contracts.json에 등록합니다. 예제의 @RTMSG와 returnCode 0은 예시이며 실제 계약이 아닙니다. 설정이 없는 쓰기는 차단됩니다. 성공 접두어가 필요한 경우 명확히 확인된 값만 successPrefix에 지정합니다.
5. 프로시저가 외부 트랜잭션을 직접 COMMIT하거나 ROLLBACK하지 않는지 확인합니다. 업무 변경과 요청 기록이 같은 트랜잭션에 있어야 재시작 후에도 중복 방지가 보장됩니다. DB 권한은 필요한 프로시저 EXECUTE, 메타데이터 조회, 요청 테이블 SELECT/INSERT로 제한합니다.
6. 원본 PDA2004M1은 등록/취소 모두 같은 프로시저와 인자를 사용합니다. 취소는 기본 차단합니다. 확인된 취소 프로시저를 AKPDA_RECYCLE_CANCEL_PROCEDURE로 지정하고 출력 계약도 등록해야 합니다. 설정 프로시저는 등록과 동일한 입력 파라미터 계약을 지원해야 합니다.
7. 원본 비밀번호 변경은 Common.EncryptString을 사용하지만 로그인은 PWD를 직접 비교합니다. 현재 로그인은 원본의 직접 비교 동작을 서버로 옮겼습니다. 암호문을 반환하는 환경이면 실제 암호화 규칙에 맞춘 서버 인증 어댑터가 필요합니다. 비밀번호 변경 기능은 구현하지 않았으며 기존 관리 화면을 사용합니다. PWD는 API 응답에 포함되지 않습니다.
8. HTTPS 구성, 실제 단말 바코드 Enter 설정, 공장/사용자 메뉴 권한, 등록/취소 후 재고·실적 변화를 검증용 DB에서 확인합니다.

## 저장 통신 장애 대응

저장 요청 UUID와 입력 내용은 단말에 먼저 보관하고 서버에 보냅니다. 응답을 받기 전 연결이 끊기거나 5xx가 반환되면 미확정 상태로 남깁니다. 앱 재실행 후에도 같은 서버·공장·사번으로 로그인해 '동일 저장 요청 재확인'을 사용합니다. 서버는 같은 UUID/사용자/공장/내용이면 저장을 다시 실행하지 않고 기존 응답을 돌려줍니다. 요청 내용이 다르면 거부합니다. 자동 저장 재시도나 자동 폐기 기능은 없습니다. SQL 모드는 요청 기록을 영속 저장하고 demo 모드는 서버 재시작 시 기록을 잃습니다.

인증 토큰은 메모리에만 유지합니다. 세션 만료는 8시간이며 로그아웃 시 서버 세션을 제거합니다. 서버 재시작 후에는 다시 로그인해야 합니다. 앱은 비밀번호와 DB 계정을 저장하지 않습니다. 오프라인 신규 등록 큐와 카메라 바코드 스캔은 포함하지 않았습니다.

## 운영 전 별도 통합 검증

현재 DB 정의와 실제 PDA가 없어 실제 SQL 업무 실행·물리 스캐너·프린터·장비별 레이아웃은 검증하지 않았습니다. 데모 성공을 운영 준비 완료로 판단하면 안 됩니다. 다중 API 인스턴스 운영 시 별도 중앙 세션/로그인 제한 저장소가 필요합니다. 이번 버전은 단일 API 인스턴스 기준입니다.

참고: Android AGP 8.7 공식 호환성 문서에 따라 Gradle 8.9 / SDK 35를 사용합니다.
https://developer.android.com/build/releases/agp-8-7-0-release-notes

## 확인한 결과

회귀 테스트 10개와 데모 HTTP 통합 테스트 통과, Debug APK 생성 및 서명 검증 통과, Lint 오류 0건(경고 9건). 자세한 결과는 VALIDATION.md에 있습니다. 실제 단말과 SQL DB 실행은 미검증입니다.

## Gradle Agent 시작 오류 해결

`ClassNotFoundException: org.gradle.instrumentation.agent.Agent`가 한글 Windows 사용자 경로에서 발생하면 Gradle User Home을 영문 경로로 지정합니다. 이 PC의 JDK 18에서는 동일한 Agent JAR이 한글 경로에서 실패하고 영문 경로에서 정상 로드되는 것을 재현했습니다.

Android Studio: File > Settings > Build, Execution, Deployment > Build Tools > Gradle에서 Gradle user home을 `D:/kotlin/ak2/.tools/gradle-home`으로 설정한 뒤 Sync Project with Gradle Files를 실행합니다. Gradle JDK는 Android Studio Embedded JDK를 선택할 수 있습니다. 기존 Gradle 옵션을 임의로 삭제하거나 전체 사용자 캐시를 지울 필요는 없습니다.

명령행:

    .\gradlew.bat -g D:/kotlin/ak2\.tools\gradle-home help --no-daemon

Windows Wrapper는 GRADLE_USER_HOME 환경변수가 없을 때 프로젝트의 .tools/gradle-home을 기본값으로 사용합니다. Android Studio는 Wrapper 배치 파일을 실행하지 않으므로 IDE의 Gradle user home도 별도로 설정해야 합니다. 기존 환경변수가 한글 경로를 가리키면 위 명령처럼 -g를 명시하세요.

## AKPDA 이름 변경

프로젝트 이름과 앱 표시 이름은 AKPDA, 패키지는 kr.co.akpda.*, Android applicationId는 kr.co.akpda.android입니다. 환경변수는 AKPDA_MODE, AKPDA_JDBC_URL, AKPDA_DB_USER, AKPDA_DB_PASSWORD, AKPDA_BIND, AKPDA_PORT, AKPDA_WRITE_CONTRACTS, AKPDA_RECYCLE_CANCEL_PROCEDURE를 사용합니다. HTTP 테스트 서버 주소는 AKPDA_API_URL로 지정합니다.

기존 환경변수로 실행 중인 서버는 새 환경변수 이름으로 변경 후 재시작해야 합니다. Android applicationId가 바뀌므로 기존 앱과 별도로 설치되며 설정과 미확정 저장 요청은 자동 이전되지 않습니다. 기존 앱에 미확정 요청이 있으면 기존 서버에서 먼저 확인하세요.

새 DB는 server/sql/001_request_ledger.sql을 사용합니다. 이미 요청 기록 테이블을 만든 DB는 기존 서버를 중지한 상태에서 server/sql/000_rename_legacy_ledger.sql로 기존 데이터를 유지하며 테이블 이름을 변경합니다. 이 스크립트는 자동 실행되지 않습니다. 두 테이블이 모두 존재하면 자동 병합하지 않고 중단합니다. 기존 이름은 이전 DB 식별·마이그레이션 SQL에서만 유지됩니다.

이름 변경 이전의 빌드 결과·APK 해시는 과거 검증 기록이며 현재 소스에서 재생성된 APK의 검증 결과가 아닙니다.

이름 변경 후 Android Studio에서 Sync Project with Gradle Files를 실행하세요. IDE가 열린 상태에서는 workspace.xml에 이전 모듈명이 다시 기록될 수 있으므로 프로젝트를 다시 열고 AKPDA 모듈을 선택하세요. 현재 AKPDA APK 빌드·서명 검증, 테스트 10개, 데모 HTTP 통합 테스트가 통과했습니다.

## 이름 변경 후 unexpected end of stream 오류

/health는 정상인데 POST 요청이 응답 없이 끊기고 서버 로그에 이전 패키지의 LoginRequest 또는 ApiError에 대한 NoClassDefFoundError가 있으면 이름 변경 전 서버가 실행 중인 상태입니다. 기존 서버를 실행한 터미널에서 Ctrl+C로 중지한 뒤 현재 프로젝트에서 .\run-sql.ps1을 실행하세요. SQL 모드를 유지하며 현재 터미널의 AKPDA_* DB 설정을 사용합니다. 이름 변경 전 환경변수가 남아 있으면 DB 접속 설정만 새 이름으로 재사용합니다. 비밀번호는 출력하거나 파일에 저장하지 않습니다. 다른 터미널이면 접속 환경변수를 다시 설정해야 합니다.

    cd D:\kotlin\ak2
    .\run-sql.ps1

실물 휴대폰 USB 테스트 주소는 http://127.0.0.1:8080이며 adb reverse tcp:8080 tcp:8080 연결이 필요합니다. 서버 재시작 시 세션이 초기화되므로 앱에서 다시 로그인하세요. /health는 DB 연결 검사가 아닙니다.

## SQLServerException 상세 진단

서버 시작 메시지는 DB 연결 성공을 뜻하지 않습니다. DB 연결은 요청 시 수행됩니다. SQL 예외가 발생하면 서버 콘솔에 errorCode, SQLState, 상세 메시지와 원인 예외를 출력합니다. DB 비밀번호는 가리고 앱에는 코드와 상태만 전달합니다.

DB 접속 환경변수를 설정한 기존 PowerShell에서 다음 명령으로 연결만 확인할 수 있습니다.

    .\run-sql.ps1 -CheckDb

이 명령은 SELECT 1만 실행하고 종료하며 API 포트를 열거나 업무 프로시저를 실행하지 않습니다. 성공 시 AKPDA DB connection check: OK (SELECT 1)이 표시됩니다. 실패하면 SQL[0]의 errorCode, SQLState, message를 확인하세요. 연결 성공은 업무 프로시저의 존재·실행 권한까지 보장하지 않습니다.

변경된 진단 코드를 적용하려면 기존 서버를 Ctrl+C로 중지하고 .\run-sql.ps1로 재시작한 뒤 앱에서 다시 로그인하세요. 오류를 공유할 때 접속 문자열과 비밀번호는 제외하세요.

## SQL API Java 런타임 분리

신규 사출기 촬영 메뉴 `Injection_1`의 사용법과 DB 적용 절차는 [INJECTION_1.md](INJECTION_1.md)를 참고하세요.

이 PC에서 Java 17.0.14의 --check-db가 실제 DB SELECT 1 성공으로 확인됐습니다. Java 21에서는 TLS ClientHello 이후 연결이 종료됐으며 정확한 보안 정책 차이는 미확정입니다. run-sql.ps1은 Gradle로 :server:installDist를 빌드한 다음 API만 사용자 .jdks/jbr-17.0.14의 java.exe로 실행합니다. Gradle JVM과 API JVM은 별도입니다. :server:run은 이 실행 경로를 사용하지 않습니다.

기존 DB 환경변수가 있는 PowerShell에서 .\run-sql.ps1 -CheckDb 또는 .\run-sql.ps1을 실행하세요. 다른 검증된 런타임은 -ServerJavaHome 또는 AKPDA_SERVER_JAVA_HOME으로 지정합니다. 서버 Java 보안 설정을 완화하지 않았습니다. Java 업데이트 시 연결 검사를 다시 수행하세요.
