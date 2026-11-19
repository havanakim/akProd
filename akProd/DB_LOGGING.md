# INSERT 로그 확인

v1.1.1 debug APK는 Logcat `akProdDB` 태그로 두 메뉴의 저장 단계를 기록한다.
Android Studio Logcat에서 `tag:akProdDB`로 검색하거나 USB 연결 후 다음을 실행한다.

```powershell
adb logcat -v time -s akProdDB
```

이미 발생한 로그를 파일로 받으려면:

```powershell
adb logcat -d -v time -s akProdDB > akprod-insert.log
```

이 프로젝트에서는 `scripts/collect-cp-db-log.ps1`을 실행하면 adb 위치를 찾아 exports 폴더에 로그 파일을 만든다.

저장 버튼을 누르면 요청 ID·테이블·DB 세션·SQL 템플릿·바인딩 개수·JSON 전송 길이·실행 결과·COMMIT 결과가 표시된다.
비밀번호, DB 접속 문자열, 사번과 입력 수치 본문은 기록하지 않는다. SQL의 물음표 값은 로그에서 생략한다.
실패 시 SQLState와 오류 코드를 남기며 release 빌드에서는 이 진단 로그를 출력하지 않는다.

v1.1.0 단말 진단에서는 executeUpdate가 서버 응답을 기다리지만 서버 세션은 sleeping이고
입력 버퍼의 마지막 명령이 INSERT 직전 SELECT인 상태를 확인했다.
v1.1.1은 CP 저장 연결의 prepareSQL=1을 비교했으나 단말 INSERT 시간 초과가 재현되었다.
드라이버 상수 확인 후 v1.1.2는 prepareSQL=2(sp_executesql RPC)와 packetSize=512를 사용한다.
공정관리일지 연결의 prepareSQL=0은 유지한다. 등록시각은 사용자가 수정한 GETDATE()로 서버 로컬 시간을 사용한다.
