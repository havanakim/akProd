-- Let one TB_PC_CONDITION row hold both screens of a 호기: 사출/보압 in JSON1 and 계량 in JSON2
-- for the same day, 호기 (DEVICE_CODE) and 차수 (ROUND_NO). Run once before installing the app version
-- that updates the existing row instead of inserting a new one. Existing rows are not changed.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    IF OBJECT_ID(N'dbo.TB_PC_CONDITION',N'U') IS NULL
        THROW 50001,N'TB_PC_CONDITION 테이블이 없습니다. pc-condition-schema.sql을 먼저 실행하세요.',1;

    -- Was: exactly one of JSON1/JSON2. Now: at least one of them.
    IF OBJECT_ID(N'dbo.CK_TB_PC_CONDITION_KIND',N'C') IS NOT NULL
        ALTER TABLE dbo.TB_PC_CONDITION DROP CONSTRAINT CK_TB_PC_CONDITION_KIND;
    ALTER TABLE dbo.TB_PC_CONDITION WITH CHECK
        ADD CONSTRAINT CK_TB_PC_CONDITION_KIND CHECK (JSON1 IS NOT NULL OR JSON2 IS NOT NULL);

    -- The save looks up the same day/호기/차수 row with UPDLOCK,HOLDLOCK; this index keeps that lock narrow.
    IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.TB_PC_CONDITION') AND name=N'IX_TB_PC_CONDITION_DEVICE_ROUND_DATE')
        CREATE INDEX IX_TB_PC_CONDITION_DEVICE_ROUND_DATE ON dbo.TB_PC_CONDITION(DEVICE_CODE,ROUND_NO,CAPTURED_AT);

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
