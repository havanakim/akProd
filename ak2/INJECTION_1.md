# Injection_1

AK PDA 메인 메뉴에 추가한 사출기 사진 등록 화면입니다. 기존 로그인 성공 사용자에게 이 신규 메뉴를 제공합니다. 기존 6개 업무 권한은 변경하지 않습니다. 메뉴를 역할별로 제한하려면 WorkService.login의 신규 메뉴 부여를 DB 권한 조회로 변경해야 합니다.

## 사용

1. 로그인 후 Injection_1을 선택합니다.
2. 사진 촬영 또는 사진 선택으로 판넬 사진을 읽습니다. 가능하면 화면이 정면으로 크게 보이도록 촬영합니다.
3. 설비번호와 모델/금형명 표기를 입력합니다.
4. 배정할 항목을 선택하고 OCR 숫자 후보를 누르거나 항목에 직접 입력합니다. 소수점은 반드시 사진과 비교합니다.
5. 사진과 값을 확인했다는 체크 후 서버 등록을 누릅니다.

생산량 좌우, RPM, 5개 시간 항목의 좌우, 8개 온도 구역의 PV/SV를 제공합니다. 값이 없는 항목은 빈칸 또는 `---`로 둡니다. 숫자 하나 이상이 필요하며, `0`도 유효합니다. 좌우 값의 설정/실측 의미와 단위는 아직 장비 문서로 확인되지 않아 단정하지 않습니다. Robot은 ON/OFF를 수동 입력합니다.

이 버전은 숫자 OCR 후보를 작업자가 항목에 배정합니다. 화면 전체의 항목 자동 매핑, 원근 보정 및 PLC 직접 통신은 구현하지 않았습니다. 사진 한 장당 한 건을 저장하며 새 사진을 읽으면 기존 측정값을 비웁니다. 여러 부분을 따로 찍으면 사진마다 개별 등록합니다. OCR은 기기에서 실행됩니다. 통신이 끊긴 저장은 기존 '동일 저장 요청 재확인' 버튼으로 재시도합니다.

## 서버 적용

현재 설정된 DB에서 기존 요청 원장 `server/sql/001_request_ledger.sql` 적용 여부를 먼저 확인한 뒤 `scripts/injection-schema.sql`을 실행합니다. 기존 원장 이름 마이그레이션 안내도 준수합니다. 이 작업에서 DB SQL은 자동 실행하지 않습니다.

서버를 현재 소스로 다시 빌드·재시작하고 앱에서 다시 로그인해야 메뉴가 나타납니다. `POST /api/work`의 screen=Injection_1, operation=save를 사용하며 공장·등록자는 로그인 세션에서 가져옵니다. 이 신규 저장 경로에는 별도 프로시저/쓰기 계약 설정이 필요하지 않습니다. DB 계정에는 신규 테이블 INSERT와 기존 요청 원장의 SELECT/INSERT 권한이 필요합니다.

저장 테이블은 `dbo.AKPDA_INJECTION_CAPTURE`이며 설비, 사용자, UTC 촬영/불러오기 시각, 서버 등록시각, 항목 JSON과 앱에서 방향/용량을 보정한 JPEG를 함께 보관합니다. 카메라 원본 바이트를 그대로 보관하는 방식은 아닙니다. 사진 선택의 시각은 파일 EXIF 시간이 아닌 앱에서 불러온 시각입니다. 원장과 사진 등록은 같은 트랜잭션에서 실행해 동일 요청의 중복 등록을 막습니다.

예시 조회:

```sql
SELECT CAPTURE_ID, MACHINE_CODE, CAPTURED_AT, USER_ID,
       JSON_VALUE(VALUES_JSON,'$.fields.COUNT_RIGHT') AS COUNT_RIGHT,
       JSON_VALUE(VALUES_JSON,'$.fields.RPM') AS RPM
FROM dbo.AKPDA_INJECTION_CAPTURE
ORDER BY CAPTURE_ID DESC;
```

OCR 구현 참고: https://developers.google.com/ml-kit/vision/text-recognition/v2/android

## 이번 변경 검증

2026-11-13: 캐시된 Kotlin 2.1.21 컴파일러로 shared/server의 현재 소스와 테스트 소스를 컴파일하고 JUnit에서 18개 테스트를 실행해 전부 통과했습니다. 신규 사출 등록의 권한, 입력 오류, 확인 체크, 요청 재시도 및 내용 충돌을 포함합니다. Android Manifest와 FileProvider 경로 XML 파싱도 통과했습니다.

전체 Gradle 빌드는 실행 환경의 데몬 연결 오류와 디렉터리 쓰기 가능 여부 판정 오류로 완료되지 않았습니다. Android APK 생성·lint·실기기 촬영/OCR 정확도와 실제 SQL Server 테이블 저장은 검증되지 않았습니다. 새 ML Kit 및 EXIF 의존성 다운로드를 포함한 Android Studio Gradle Sync와 APK 빌드가 필요합니다. 테스트 결과는 서버/공통 코드에 한정됩니다.
