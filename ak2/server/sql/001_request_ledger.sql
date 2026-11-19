-- Existing databases must migrate the request history before creating this table.
IF OBJECT_ID(N'dbo.UNIPDA_API_REQUEST', N'U') IS NOT NULL
    THROW 50002, 'Run 000_rename_legacy_ledger.sql before creating the AKPDA request ledger.', 1;

-- 검증용 DB에서 먼저 적용하세요. 프로시저의 BEGIN/COMMIT/ROLLBACK 규칙도 확인해야 합니다.
IF OBJECT_ID(N'dbo.AKPDA_API_REQUEST', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.AKPDA_API_REQUEST (
        OWNER_KEY varchar(120) NOT NULL,
        REQUEST_ID varchar(36) NOT NULL,
        PAYLOAD_HASH char(64) NOT NULL,
        RESPONSE_JSON nvarchar(max) NOT NULL,
        CREATED_AT datetime2 NOT NULL CONSTRAINT DF_AKPDA_API_REQUEST_CREATED DEFAULT SYSUTCDATETIME(),
        CONSTRAINT PK_AKPDA_API_REQUEST PRIMARY KEY (OWNER_KEY, REQUEST_ID)
    );
END;
-- 이 테이블은 요청 재처리 방지 기록입니다. 자동 삭제하지 마세요.
