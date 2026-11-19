# AKPDA 이름 변경 검증 (2026-11-11)

- 프로젝트·앱 이름: AKPDA. Android 패키지: kr.co.akpda.android.
- 공통 테스트 3개 + 서버 테스트 7개: 실패 0, 오류 0.
- 데모 HTTP 통합 테스트: 통과. AKPDA_MODE / AKPDA_PORT / AKPDA_BIND 설정으로 실행했습니다.
- :server:installDist / :app:assembleDebug: BUILD SUCCESSFUL. 현재 Wrapper Gradle 9.9.0 사용.
- APK 서명 검증: 통과. 앱 표시 이름·패키지·실행 Activity 확인.
- APK 6개 DEX 파일에 이전 명칭이 남아 있지 않음을 확인했습니다.
- APK 크기: 9085935 bytes. SHA-256: 34ae248927b8c94d6a8520c744324b4838de9e269d0778cd811ab258d53a5d6d
- 기존 PdaViewModel의 세미콜론/접근자 줄바꿈 오류와 Windows Wrapper의 빈 classpath 실행 오류도 보완했습니다.
- SQL 이름 변경 스크립트는 준비만 했으며 실제 DB에는 실행하지 않았습니다.
- 이전 DB 식별용 SQL에는 이전 테이블·제약조건 이름이 의도적으로 남아 있습니다.

---

## 이전 검증 기록

> 이 문서의 기존 빌드·테스트·APK 해시는 이름 변경 이전 버전의 기록입니다. 현재 AKPDA 버전의 검증 결과는 별도 확인해야 합니다.

# 검증 결과

검증일: 2026-11-10 (한국 시간)

- 공통 검증 JUnit: 3개, 실패 0, 오류 0.
- 서버 업무 JUnit: 7개, 실패 0, 오류 0.
- 실제 데모 HTTP API 통합 테스트: 통과. 인증, 6개 업무, 소수, 요청 중복 방지, 잘못된 수량, 미확인 취소 차단, 로그아웃을 확인했습니다.
- Android 최종 빌드: :app:assembleDebug / :app:lintDebug 모두 BUILD SUCCESSFUL. 스캔 입력 포커스 보완 포함.
- Android Lint: 0 errors, 9 warnings. 새 의존성 버전 안내 7건, 백업 규칙 안내 1건, 아이콘 미지정 1건입니다.
- APK 서명 검증: 통과(APK Signature Scheme v2).
- 이전 APK의 버전·지원 OS: 1.0.0 / Android 8.0(API 26) 이상 / target SDK 35. 현재 패키지: kr.co.akpda.android.
- APK 크기: 9127162 bytes.
- APK SHA-256: 3029d5cd1c0ef37ea0c7294cff05eed325a07b965218b07e7ec1773827bad86f
- 서버 배포본: server/build/install/server.
- 실제 SQL Server, 물리 PDA, 프린터: 미검증.

Windows 한글 경로로 인한 Gradle 테스트 실행기와 Android JDK 이미지 생성 오류를 피하기 위해 이 PC에서는 영문 Gradle/SDK 경로를 사용했습니다.

실행 방법과 DB 연결 전 확인 사항은 README.md를 참고하세요.

## 2026-11-11 Gradle 데몬 시작 오류 확인

- JDK 18.0.2.1에서 동일 SHA-256의 Gradle Agent JAR을 한글 사용자 경로로 로드하면 ClassNotFoundException이 발생하고 영문 경로에서는 정상 로드됨을 재현했습니다.
- Windows Wrapper는 GRADLE_USER_HOME이 없으면 프로젝트 .tools/gradle-home을 사용하도록 보완했습니다.
- gradlew.bat -g D:/kotlin/ak2/.tools/gradle-home help --no-daemon 실행: BUILD SUCCESSFUL.
- Android Studio에서는 Gradle user home을 같은 경로로 별도 지정해야 합니다.

## AGP 새 DSL 전환 검증 (2026-11-11)

- targetSdk=35가 명시되어 있으므로 기본 targetSdk 호환성 옵션을 제거했습니다.
- android.newDsl=false를 제거해 기본 새 ApplicationExtension DSL을 사용합니다. Android 앱은 내장 Kotlin과 compilerOptions 설정을 사용합니다.
- 불필요한 uniquePackageNames, dependency.useConstraints, r8.strictFullModeForKeepRules 호환성 옵션도 제거했습니다.
- :app:assembleDebug :shared:test :server:test --warning-mode all: BUILD SUCCESSFUL. 테스트 10개 통과.
- 요청된 옵션 경고와 BaseAppModuleExtension DSL 경고는 재발하지 않았습니다.
- 남은 Project 객체 의존성 표기 경고는 AGP VariantDependenciesBuilder 내부 호출, Configuration.setVisible 경고는 AGP 및 Kotlin 플러그인 내부 호출로 추적되었습니다. 현재 프로젝트가 Gradle 10/11과 완전 호환된다는 의미는 아닙니다. 해당 플러그인 내부 API 경고는 플러그인 업데이트 대상입니다.
- 상세 확인 로그: .tools/agp-new-dsl-check.log, .tools/gradle-api-warning-trace.log.

## SQL 오류 진단 보완 (2026-11-11)

- :server:test :server:installDist: BUILD SUCCESSFUL. 서버 테스트 12개 통과(진단 테스트 5개 포함).
- run-sql.ps1 PowerShell 문법 검사 통과. -CheckDb는 SELECT 1만 실행하고 종료합니다.
- 격리된 테스트 설정에서 로컬 미사용 포트 연결 실패를 재현: --check-db 종료 코드 1, errorCode/SQLState 출력, 테스트 비밀번호 미출력 확인.
- 같은 설정의 별도 HTTP 서버에서 로그인 요청 시 HTTP 500 JSON 및 SQL 코드/상태 전달, 서버 상세 로그 출력 확인. 테스트 서버는 종료했습니다.
- 실제 SQL Server 접속 및 업무 프로시저는 미검증입니다. 실제 오류 원인은 새 서버의 상세 로그로 확인해야 합니다.
