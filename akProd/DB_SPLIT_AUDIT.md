# akPDA → akProd DB 저장 점검 (2026-11-17)

## 실제 단말 저장 성공 (v1.1.2)

- 단말의 JDBC 응답 대기 중 서버 세션은 sleeping / 열린 트랜잭션 1개였고, 입력 버퍼에는 INSERT 직전 SELECT만 남아 있었다.
- CP 연결을 prepareSQL=2(sp_executesql), packetSize=512로 변경했다. GETDATE()로 서버 로컬 등록시각을 명시한다.
- 같은 실패 요청 85adf3ce-683c-4928-9051-98cf3e2dca24를 복원한 뒤 실제 SM-A245N에서 INSERT 1행 / 19ms, COMMIT 완료를 확인했다.
- 앱 저장 상세 화면 진입 및 서버의 커밋된 행 1건 확인. REGISTERED_AT=2026-11-17 10:28:21.387, PHOTO_COUNT=0, JSON null 항목 2개.
- 사용자가 조회한 실제 DB 행도 일치했다. 저장 성공 로그: exports/akprod-insert-v1.1.2.log.
- 빌드 및 JUnit 26개 통과. CP_mono는 같은 연결·저장 경로를 사용하지만 이번 단말 실저장 확인은 CP_Colored에 대해 수행했다.
- 전송 설정 변경의 효과는 확인했으나 네트워크 장비나 MTU 등 하위 원인까지 확정한 것은 아니다.

## 단말 설치본 확인

연결된 SM-A245N의 설치 APK를 읽어 확인한 결과, CaptureSaveRepository 공통 저장 코드 및
REGISTERED_AT 로컬 시간 INSERT 수정이 포함되지 않은 이전 빌드였다.
설치본은 versionCode=1 / versionName=1.0, 마지막 설치시각 2026-11-17 07:59:49로 표시됐다.
업데이트 식별을 위해 수정본은 versionCode=2 / versionName=1.1.0으로 변경하고 CP 화면에 버전을 표시한다.
이는 단말에서 같은 시간 초과가 반복되는 상황을 설명하는 추가 확인 사항이며,
새 버전의 단말 COMMIT 성공은 별도로 검증해야 한다.

v1.1.0 APK 빌드 및 JUnit 26개 통과 후 연결 단말에 `adb install -r` 업데이트 설치 성공.
설치된 versionCode=2 / versionName=1.1.0을 확인했으며 cp3_pending.xml·cp14_pending.xml 등
기존 보관 설정 파일이 유지되어 있다. 사용자 요청의 실제 재확인·COMMIT은 아직 미검증이다.

후속 변경: 사용자 요청에 따라 현재 등록 쿼리는 SYSDATETIME()을 사용해 DB 서버 로컬 시간을 저장한다.
아래 SYSUTCDATETIME() 언급은 최초 오류 수정 당시의 시험 기록이다.

## 확인된 실패와 수정

현재 local.properties 대상 DB(JSAK_ERP_TEST)에 jTDS 1.3.1로 직접 접속해 앱과 같은 등록 SQL을 시험했다.
기존 CP_Colored INSERT는 SQL 515 / SQLState 23000으로 실패했다.
오류는 NOT NULL 열 REGISTERED_AT에 NULL을 넣을 수 없다는 내용이었다.
원본 및 분리된 쿼리는 이 열을 생략하고 DB의 DEFAULT SYSUTCDATETIME()에 의존한다.
현재 DB에서는 해당 기본값이 공급되지 않아 저장에 실패한다.

공통 저장 쿼리에 REGISTERED_AT와 SYSUTCDATETIME()을 명시했다.
수정 SQL로 CP_Colored·CP_mono 각각 1행 INSERT가 약 12ms에 성공했다.
수정 APK의 assembleDebug·testDebugUnitTest 성공, JUnit 26개 통과.
각 시험 직후 롤백하고 해당 요청 ID의 잔존 행이 0건임을 확인했다.
기존 자료·DB 스키마·원본 akPDA 코드는 수정하지 않았다.

## 분리 누락 점검

| 항목 | 결과 |
|---|---|
| local.properties DB_IP·PORT·NAME·USER·PASSWORD·CLASSES | 원본 akPDA와 akProd 값 일치(비밀값은 보고서 제외) |
| JDBC 드라이버 | 양쪽 jTDS 1.3.1 |
| 인터넷 권한 | 양쪽 Manifest에 존재 |
| CP 저장 진입점 | Activity → Repository → DBHelper 연결 유지 |
| 로그인 사용자 전달 | empNo·empName을 등록·조회 화면에 전달 |
| CP 전용 저장 프로시저 | 원본에도 없음. CP Repository가 직접 SELECT·INSERT·COMMIT 실행 |
| 원본 DBHelper에서 제외된 물류 기능 | CP 저장 경로는 해당 함수를 호출하지 않음 |
| 실DB 두 테이블 | CP_Colored·CP_mono 존재, SELECT·INSERT 권한 정상 |
| 사진 수 제약 | 두 테이블 모두 0~5 허용 적용 확인 |
| 두 테이블 트리거 | 없음 |
| 점검 시 실행 중 DB 요청 | 다른 실행 요청 없음(촬영 시점의 잠금 여부를 소급 확정할 수는 없음) |

따라서 현재 확인된 문제는 저장 프로그램 파일·프로시저 누락이 아니라,
쿼리가 가정한 DB 등록시각 기본값과 실제 테이블 상태의 불일치다.
서버 기본값에 의존하지 않도록 앱에서 명시하여 해결한다.

## 오류 표시 개선과 한계

SQL 실패 후 rollback/close가 기다리는 동안에도 실제 오류 코드·메시지를 진행 상태에 표시한다.
이전 화면의 'INSERT 단계에서 35초 대기'만으로는 실제 SQL 오류와 정리 대기를 구분하기 어려웠다.
이번 SQL 515 재현은 확정된 실패 원인이지만, 과거 모든 시간 초과의 단일 원인임을 단정하지 않는다.

실DB 시험은 데스크톱 JVM에서 실제 테이블에 INSERT 후 롤백한 결과다.
Android 실기기의 최종 COMMIT·앱 조회 확인은 추가로 필요하다.
새 APK 설치 후 기존 미확인 요청을 동일 ID로 재확인하고 저장자료 조회에서 결과를 확인한다.
