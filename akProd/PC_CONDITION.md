# TB_PC_CONDITION · CP 수치 통합 테이블

CP_mono(CP14, 모노)와 CP_Colored(CP3, 컬러)를 `[dbo].[TB_PC_CONDITION]` 하나로 합쳤다.
DB에는 [scripts/pc-condition-schema.sql](scripts/pc-condition-schema.sql)을 1회 적용한다.
이 스크립트는 기존 두 테이블의 자료를 옮긴 뒤 같은 트랜잭션에서 CP_mono와 CP_Colored를 삭제한다.
그래서 적용 후에는 이 테이블을 쓰는 앱 버전만 동작한다.

| 컬럼 | 내용 |
|---|---|
| REQUEST_ID | bigint, 시퀀스 `dbo.SEQ_PC_CONDITION`에서 발급. 기존 UUID는 옮기지 않음 |
| DEVICE_CODE | 사출기 호기 C01~C16 (기존 MACHINE_CODE) |
| ROUND_NO | 차수 1, 2, 3… (호기와 함께 선택, 옮긴 기존 자료는 NULL) |
| USER_ID | 작업자 |
| CAPTURED_AT | 현지 촬영/불러오기 시각 |
| INSERT_AT | 서버 등록 시각 (기존 REGISTERED_AT) |
| REG_DT | 등록일 `YYYYMMDD` (char(8)). INSERT 때 DB가 현재일로 자동 입력, UPDATE 때는 그대로 둠 ([scripts/pc-condition-reg-dt.sql](scripts/pc-condition-reg-dt.sql), AKEP 적용 완료) |
| JSON1 | 사출/보압(mono)(CP_mono 메뉴의 모든 화면), 사출/보압(color) 메뉴의 입력값 JSON |
| JSON2 | 계량/배압(m)(S5~S1·MIRACON-I), 계량/흘림방지(c) 메뉴의 입력값 JSON |
| JSON3 | 추가 JSON 값용 예비 컬럼 |
| PHOTO_COUNT | 사진 수 (현재는 항상 0) |

## 저장 규칙

- 한 행 = 하루 · 호기(DEVICE_CODE) · 차수(ROUND_NO). 저장할 때 메뉴와 관계없이 같은 날(CAPTURED_AT 날짜),
  같은 호기, 같은 차수의 행이 있는지 확인한다. 없을 때만 INSERT하고, 있으면 그 행의 메뉴 컬럼(JSON1 또는 JSON2)만
  UPDATE한다. 작업자·촬영시각·등록시각은 바꾸지 않는다. 컬럼은 화면이 아니라 메뉴로 정한다.
- 같은 날·호기·차수 행 찾기는 호기 기준이다(한 호기는 모노 또는 컬러 화면 중 하나). JSON에는 `"SCREEN"`을 넣지 않는다.
- 저장자료 조회는 JSON의 `"HMI"`로 모노(`"M"`)와 컬러(`"C"`) 메뉴를 나눈다. `"HMI"`가 없는 예전 JSON은 양쪽 조회에 모두 나온다.
- 모든 메뉴에서 `"NOT_DISPLAYED"`를 넣지 않는다. 표시 없음 항목은 FIELDS에 null로만 남는다.
- 컬러 메뉴의 FIELDS에서 사출 1~5단과 계량 1~5단 항목은 짧은 이름으로 저장한다.
  사출: `INJECT_n_PRESSURE`→`IPn`, `_SPEED`→`IVn`, `_TIME`→`ITn`, `_POSITION`→`IFn` (예: `IP5`).
  계량: `METER_n_PRESSURE`→`RPn`, `_SPEED`→`RVn`, `_POSITION`→`Sn`, `_BACK_PRESSURE`→`BPn` (예: `RP1`).
  보압(`HOLD_…`)과 흘림방지(`SUCKBACK_…`) 항목은 그대로이고,
  이전에 저장된 `INJECT_…`·`METER_…` 이름도 조회 화면에서 그대로 읽는다. 컬러 조회 화면의 사출·계량 칸에는
  저장 이름을 값 위에 함께 표시한다 (예: `IP5` / `78`).
  이미 저장된 자료의 이름은 [scripts/pc-condition-rename-fields.sql](scripts/pc-condition-rename-fields.sql)로
  바꿨다 (AKEP 적용 완료, 2026-11-24: REQUEST_ID 22·23). 다시 실행해도 안전하다.
- JSON 맨 앞에 `"HMI"`(컬러 메뉴 `"C"`, 모노 메뉴 `"M"`)를 넣고, 이어서 `"MACHINE"`(호기), `"ROUND"`(차수),
  `"SCREEN_TYPE"`을 저장한다. SCREEN_TYPE은
  사출/보압 `INJECTION_HOLDING`, 계량 `METERING_BACK_PRESSURE`, MIRACON-I `METERING_MIRACON`이다.
- 한 메뉴 안의 여러 화면(CP_mono의 사출/보압·계량 S5~S1, 계량/배압(m)의 S5~S1·MIRACON-I)은 같은 컬럼이므로,
  같은 날·호기·차수에서는 나중에 저장한 화면이 앞의 값을 덮어쓴다.
- 차수가 없는 예전 임시저장 건은 비교할 수 없으므로 항상 새 행으로 들어간다.
- 저장자료 조회의 삭제는 그 메뉴의 JSON만 지운다. 같은 행에 다른 컬럼 값이 남아 있으면 행은 남고,
  없으면 행을 삭제한다.
- PAYLOAD_SHA256과 요청 단위 중복 확인은 없다.
- [scripts/pc-condition-color-merge.sql](scripts/pc-condition-color-merge.sql)(AKEP에 적용 완료)은
  CK_TB_PC_CONDITION_KIND를 "JSON1 또는 JSON2 중 하나 이상"으로 바꾸고,
  같은 날·호기·차수를 찾는 인덱스를 추가한다.

```sql
SELECT REQUEST_ID, DEVICE_CODE, ROUND_NO, USER_ID, CAPTURED_AT,
       JSON_VALUE(JSON1,'$.FIELDS.IP5') AS IP5,      -- 사출/보압
       JSON_VALUE(JSON2,'$.FIELDS.S5') AS S5         -- 계량
FROM [dbo].[TB_PC_CONDITION]
WHERE DEVICE_CODE = 'C05' AND CAPTURED_AT >= '2026-11-23' AND CAPTURED_AT < '2026-11-24'
ORDER BY ROUND_NO;
```
