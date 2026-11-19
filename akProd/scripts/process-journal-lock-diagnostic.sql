-- Read-only. Run in the same DB_NAME if the first small INSERT still waits.
-- DMV details require VIEW SERVER STATE (or appropriate server administrator access).
SELECT DB_NAME() AS DATABASE_NAME, name, is_disabled
FROM sys.triggers WHERE parent_id=OBJECT_ID(N'dbo.AKPROD_PROCESS_JOURNAL');

SELECT r.session_id,r.status,r.command,r.wait_type,r.wait_time,
       r.blocking_session_id,r.total_elapsed_time,s.host_name,s.program_name,
       DB_NAME(r.database_id) AS database_name,t.text AS request_text
FROM sys.dm_exec_requests r
JOIN sys.dm_exec_sessions s ON s.session_id=r.session_id
OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t
WHERE r.database_id=DB_ID() AND r.session_id<>@@SPID
ORDER BY r.total_elapsed_time DESC;

SELECT l.request_session_id,l.resource_type,l.request_mode,l.request_status,
       s.host_name,s.program_name,s.open_transaction_count
FROM sys.dm_tran_locks l
LEFT JOIN sys.dm_exec_sessions s ON s.session_id=l.request_session_id
WHERE l.resource_database_id=DB_ID()
  AND (l.resource_associated_entity_id=OBJECT_ID(N'dbo.AKPROD_PROCESS_JOURNAL')
       OR l.resource_associated_entity_id IN
           (SELECT hobt_id FROM sys.partitions WHERE object_id=OBJECT_ID(N'dbo.AKPROD_PROCESS_JOURNAL')))
ORDER BY l.request_session_id;
