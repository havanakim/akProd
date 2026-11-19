/*
  1. Run this script in SSMS first.
  2. Immediately press the journal Save/Retry button on the handset.
  3. Wait 45 seconds, then return both result tables.
  Reads server state and writes only a session-local temporary table.
  Requires VIEW SERVER STATE. Does not cancel sessions or modify application data.
*/
USE JSAK_ERP_TEST;
SET NOCOUNT ON;
SELECT SUSER_SNAME() AS LOGIN_NAME,
       IS_SRVROLEMEMBER(N'sysadmin') AS IS_SYSADMIN,
       HAS_PERMS_BY_NAME(NULL,NULL,N'VIEW SERVER STATE') AS CAN_VIEW_SERVER_STATE;
IF ISNULL(IS_SRVROLEMEMBER(N'sysadmin'),0)<>1
   AND ISNULL(HAS_PERMS_BY_NAME(NULL,NULL,N'VIEW SERVER STATE'),0)<>1
BEGIN
    RAISERROR(N'다른 세션을 조회할 권한이 없습니다. VIEW SERVER STATE 권한이 있는 관리자 연결에서 실행하세요.',16,1);
    RETURN;
END;

SELECT @@SERVERNAME AS SERVER_NAME, @@SPID AS MONITOR_SESSION_ID,
       SYSDATETIME() AS TRACE_START;

IF OBJECT_ID('tempdb..#AKPROD_SAVE_TRACE') IS NOT NULL
    DROP TABLE #AKPROD_SAVE_TRACE;

CREATE TABLE #AKPROD_SAVE_TRACE (
    sampled_at datetime2(3), session_id smallint,
    program_name nvarchar(128), client_interface_name nvarchar(128),
    client_ip varchar(48), session_status nvarchar(30), command nvarchar(32),
    wait_type nvarchar(60), blocking_session_id smallint,
    elapsed_ms int, open_transaction_count int,
    last_request_start_time datetime, last_request_end_time datetime,
    sql_prefix nvarchar(300)
);

DECLARE @finish datetime2 = DATEADD(second,45,SYSDATETIME());
WHILE SYSDATETIME()<@finish
BEGIN
    INSERT #AKPROD_SAVE_TRACE
    SELECT SYSDATETIME(),s.session_id,s.program_name,s.client_interface_name,
           c.client_net_address,s.status,r.command,r.wait_type,
           r.blocking_session_id,r.total_elapsed_time,s.open_transaction_count,
           s.last_request_start_time,s.last_request_end_time,
           LEFT(t.text,300)
    FROM sys.dm_exec_sessions s
    LEFT JOIN sys.dm_exec_requests r ON r.session_id=s.session_id
    LEFT JOIN sys.dm_exec_connections c ON c.session_id=s.session_id
    OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t
    WHERE s.is_user_process=1 AND s.session_id<>@@SPID
;
    WAITFOR DELAY '00:00:01';
END;

SELECT session_id,program_name,client_interface_name,client_ip,
       MIN(sampled_at) AS first_seen,MAX(sampled_at) AS last_seen,
       MAX(open_transaction_count) AS max_open_transactions,COUNT(*) AS samples
FROM #AKPROD_SAVE_TRACE
GROUP BY session_id,program_name,client_interface_name,client_ip
ORDER BY first_seen DESC,session_id;

SELECT TOP (300) * FROM #AKPROD_SAVE_TRACE
WHERE command IS NOT NULL OR wait_type IS NOT NULL OR open_transaction_count>0
ORDER BY sampled_at DESC,session_id;
IF NOT EXISTS(SELECT 1 FROM #AKPROD_SAVE_TRACE)
    SELECT N'45초 동안 다른 사용자 세션이 관찰되지 않았습니다. 추적 권한과 실행 시점을 확인하세요.' AS TRACE_RESULT;
