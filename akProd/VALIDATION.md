# 검증 결과 (2026-11-16)

## v1.1.2 단말·실DB 저장 성공 (2026-11-17)

- CP 저장 연결: prepareSQL=2 / packetSize=512. INSERT·COMMIT 진단 로그 추가.
- 실제 SM-A245N에서 CP_Colored 미확인 요청 재시도 → INSERT 19ms / COMMIT 성공 / 저장 상세 조회 성공.
- 서버에서 동일 요청 커밋 1건, 사진 수 0, 실제 JSON null 2항목 및 숫자 0 보존 확인. 사용자 조회 행도 일치.
- REGISTERED_AT은 GETDATE()로 서버 로컬 시간 저장. 빌드·JUnit 26개 통과.
- 로그: exports/akprod-insert-v1.1.2.log. 로그 수집 방법: DB_LOGGING.md.

## akPDA 분리 후 저장 실패 실DB 점검 (2026-11-17)

- 원본과 DB 연결 설정·jTDS 버전 일치, CP 저장 프로그램·프로시저 누락 미발견.
- 실제 CP_Colored INSERT에서 REGISTERED_AT NULL 오류(SQL 515)를 재현.
- 저장 SQL에 REGISTERED_AT=SYSUTCDATETIME()을 명시한 후 CP_Colored·CP_mono INSERT 각각 성공(약 12ms).
- 두 시험 모두 롤백 후 잔존 행 0건 확인. 기존 자료와 스키마는 변경하지 않음.
- Android 실기기 COMMIT·조회는 미검증. 상세: DB_SPLIT_AUDIT.md.

## 두 메뉴 0·빈 항목 처리 (2026-11-17)

- 000·000.0을 유효한 숫자 0으로 검증하고 OCR 표의 해당 항목에 매핑한다.
- CP_mono·CP_Colored의 빈 항목 필수 입력 제한을 제거하고 FIELDS의 JSON null로 직렬화한다.
- 0과 null을 구분하며 잘못된 숫자·다른 화면 키는 계속 차단한다. 조회의 null은 —로 표시한다.
- 직접 입력한 숫자의 자릿수는 유지한다. 사진 저장 제거 동작은 유지한다.
- APK 빌드 및 JUnit 26개 통과(기존 21개 + 0·빈 항목 검증 5개). 실제 단말 OCR·서버 저장은 미검증.

## CP 두 메뉴 사진 저장 제거 (2026-11-17)

- CP_mono·CP_Colored는 신규 자료의 수치만 저장하고 PHOTO_COUNT=0으로 등록한다.
- 사진은 OCR에만 사용하며 서버 전송·사진 테이블 쓰기를 제거했다. 사진 없이 직접 입력 등록도 가능하다.
- 기존 자료·사진 조회 유지, 동일 요청 재확인 호환, 진행 단계·DB 세션·35초 UI 대기 처리 추가.
- APK 빌드 및 JUnit 21개 통과. 실제 서버 저장과 전환 SQL 실행은 미검증.
- 배포 전에 대상 DB에 scripts/cp-values-only.sql 적용 필요. 상세: CP_VALUES_ONLY.md.

## LCD OpenCV 추가 검증 (2026-11-17)

- CP14·CP3 전체 사진 자동 인식 및 선택 영역 재인식에 ROI·CLAHE·이진화·방향별 closing 추가.
- 원본 픽셀 기반 0/8 형태 검증과 후보 충돌 시 자동 입력 보류 추가.
- `assembleDebug`, `testDebugUnitTest` 성공(Gradle 9.4.1, OpenCV 4.13.0).
- JUnit 총 16개 통과: 기존 OCR 회귀검증 9개, 신규 세그먼트 검증 7개.
- APK 생성 완료: `app/build/outputs/apk/debug/app-debug.apk`.
- 실기기 네이티브 로딩·실제 사진 OCR 정확도·처리 시간은 미검증. 상세 적용 범위와 확인 항목은 `LCD_OCR.md` 참조.

아래는 이전 작업 당시의 검증 기록이다.

- CP_mono 화면별 검증: 7개 통과 (사출 20항목 / 계량 15항목, 누락/혼합 차단, 과거 합본 호환)
- CP_mono 표 매핑: 20개 통과
- CP_Colored 표 매핑: 20개 통과
- 모니터 영역 검출: 4개 통과
- 사선 0 보정: 11개 합성 이미지 검증 통과
- 학습 글꼴 0 보정: 10개 합성 이미지 검증 통과
- 총 72개 검증 통과. 분리 후 com.bsd.akProd 소스를 Kotlin JVM 컴파일러로 직접 컴파일하여 실행.
- Activity 6개 / 운영 Kotlin 소스 16개. 물류·재고·PB5 소스 및 구 패키지 참조 없음.
- 원본 akPDA 파일과 DB 스키마/데이터는 변경하지 않음.

전체 Gradle APK 빌드와 Android JUnit 실행은 이 환경의 Gradle 데몬 연결 실패로 완료하지 못함.
기본 Gradle 사용자 폴더는 쓰기 제한이 있으며, 쓰기 가능한 Gradle 홈과 설치된 Gradle을 사용해도
데몬 통신이 실패함. Wrapper의 별도 다운로드 시도도 네트워크 DNS 제한으로 실패함.
Android Studio에서 Gradle Sync 후 assembleDebug / testDebugUnitTest를 실행해야 함.
실제 사진 OCR 정확도, 단말 로그인, DB 등록/조회는 미검증.

## 공정관리일지 추가 (2026-11-16)

- JournalForm: 208항목, 1·2·3근별 분리, 고정 좌표 숫자 후보 매핑.
- JournalFormCheck: 22개 검증 통과. 키/항목 수/좌표 범위/빈칸과 0/숫자 모호성/날짜/IF1·IF0 자동입력 제외 검증.
- 신규 등록·조회 Activity와 저장 Repository는 설치된 Android SDK 및 캐시 의존성으로 Kotlin 직접 컴파일 검증.
- 메뉴 XML 및 Manifest XML 파싱, 신규 버튼 ID/Activity 연결 확인.
- Gradle 전체 빌드는 데몬 없이 실행하는 단계까지 진행했으나, 제한 환경에서 프로젝트 폴더를 쓰기 가능한 디렉터리로 판정하지 않아 구성 단계에서 실패함.
- 이번 변경의 APK는 생성하지 않음. 기존 app/build APK가 있다면 신규 기능 검증본이 아님.
- 실제 단말 UI/손글씨 사진 OCR 정확도/SQL Server 테이블 생성/저장·조회 연동은 미검증.
- 사용 전 scripts/process-journal-schema.sql을 설정된 DB_NAME에 1회 적용 필요.
## 공정관리일지 저장 대기 및 입력 UX 수정

- 메인 스레드의 SharedPreferences 동기 디스크 쓰기를 제거하고, DB 요청 직전 필수 보관만 IO 스레드에서 수행.
- JournalSaveWorker로 실제 JDBC 작업과 화면 대기 분리. UI 35초 응답 제한 및 동일 요청 실행 중/완료 시 재실행 방지.
- 일지 테이블 존재 검사, DB 잠금/명령 제한시간, 저장 단계 표시 추가.
- 입력 오류를 항목별로 집계하고 해당 구역으로 이동. 사진 확대/구역 탐색/자동 입력 표시/조회 접기 추가.
- JournalFormCheck 28개, JournalSaveWorkerCheck 9개 통과.
- 관련 Android 소스 직접 컴파일 통과. 전체 APK 빌드 및 실기기/실DB 저장 성공은 미검증.

## 반복 시간 초과 진단 보완

- 저장 작업에서 Exception 이외의 Android 클래스/API 오류가 발생해도 Future를 예외 완료 처리.
- 클래스 오류를 메인 스레드 Error로 다시 던지지 않고 저장 오류로 표시.
- 시간 초과 메시지에 마지막 작업 단계를 포함.
- 단말에서 실행하는 TCP/JDBC/테이블/SELECT·INSERT 권한 읽기 전용 진단 추가.
- 개발 실행 환경의 TCP 진단은 Permission denied: getsockopt로 차단됨. 이 결과로 DB 서버 장애나 권한 문제를 판정할 수 없음.
- 작업자 Thread의 클래스 오류 전파 3개 회귀검증을 추가(저장 작업 검증 총 12개).

## INSERT 단계 사진 전송 변경

- setBytes 전체 사진 파라미터 대신 64KB 단위 16진수 리터럴/varbinary(max) .WRITE 사용.
- 한 트랜잭션으로 삽입·조각 추가·사진 바이트 길이 검증·커밋 수행.
- 새 사진 1.5MB 제한. 기존 보관 사진/요청 해시는 변경하지 않음.
- 사진 조각 변환 및 원본 바이트 재구성: 4바이트, 64KB, 64KB+1, 1.5MB, 8MB 검증.
- SQL Server 실제 저장/성능 및 잠금 해소 여부는 미검증.

## Read timed out 대응: 명령 준비 경로 우회

- 로컬 jTDS 1.3.1 소스에서 SQL Server 기본 prepareSQL=3(sp_prepare/sp_execute), prepareSQL=0(서버 준비 사용 안 함) 확인.
- 일지 저장 전용 연결에 prepareSQL=0, 식별용 appName/progName, tcpNoDelay=true 적용.
- 실패 시 단계·SPID·SQL 상태 표시. 읽기 전용 점검에 저장 세션 wait_type/차단 세션/활성 트리거 포함.
- 관련 Android 소스 직접 컴파일 검증. 실제 SQL Server 실행 경로/저장 성공 여부 미검증.

## 시간 초과 후 재시도 구조 보완

- 시간 초과 이전에는 실행 중 요청 공유, 시간 초과 뒤에는 같은 요청 ID로 새 작업을 허용.
- JDBC 작업 최대 2개 제한, 무한 대기 큐 제거. 작업이 모두 응답하지 않으면 앱 재시작 안내.
- 완료된 새 작업을 오래된 작업이 캐시에서 덮어쓰지 않는 회귀검증 포함.
- 서버 추적은 VIEW SERVER STATE 권한 검사 후 진행하며 프로그램 이름 필터 제거.
- 이 변경이 실제 DB 저장 시간 초과의 원인을 해소했는지는 아직 확인되지 않음.

## CP_Colored / CP_mono 이전 요청으로 입력 잠김 수정

- 새 Activity 진입에서 이전 미확인 draft를 강제로 복원하던 흐름 제거.
- 사용자별/프로그램별 미확인 요청 보관함에 원래 UUID, 입력, 사진 경로, OCR, CP_mono 화면 종류를 그대로 보관.
- 미확인 상태에서도 보관 후 새 등록 시작 가능. 보관함 선택 시 명시적으로만 이전 요청 복원.
- DB 저장 성공 시 해당 사용자/요청 ID의 보관 자료도 정리. 새 등록은 새 UUID 사용.
- 사진 파일 및 기존 DB 저장자료는 삭제하지 않음.
- 두 프로그램의 진입/보관/새 등록 동작 연결 정적 검사 수행. 실제 단말 조작은 미검증.

## 공정관리일지 현재 항목 삭제 후 새 등록

- 미확인 요청이 있다는 이유로 새 등록을 차단하던 조건 제거.
- 확인 후 사용자 초안 제거, 새 UUID 발급, 사진/입력/카메라 URI/구역/오류/진행 표시 초기화.
- DB 저장자료 삭제 쿼리나 진행 중 서버 요청의 취소 명령은 실행하지 않음.
