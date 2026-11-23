-- Add REG_DT (registration date, 'YYYYMMDD') to TB_PC_CONDITION.
-- New rows get the server's current date automatically (DEFAULT CONVERT(char(8),GETDATE(),112));
-- the app does not send it, and an update of an existing row keeps it. Existing rows take the date of INSERT_AT.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    IF OBJECT_ID(N'dbo.TB_PC_CONDITION',N'U') IS NULL
        THROW 50001,N'TB_PC_CONDITION 테이블이 없습니다. pc-condition-schema.sql을 먼저 실행하세요.',1;

    IF COL_LENGTH(N'dbo.TB_PC_CONDITION',N'REG_DT') IS NULL
    BEGIN
        ALTER TABLE dbo.TB_PC_CONDITION ADD REG_DT char(8) NULL
            CONSTRAINT DF_TB_PC_CONDITION_REG_DT DEFAULT (CONVERT(char(8),GETDATE(),112));
        -- REG_DT did not exist when this batch was compiled, so the statements that use it run as their own batches.
        EXEC(N'UPDATE dbo.TB_PC_CONDITION SET REG_DT=CONVERT(char(8),INSERT_AT,112) WHERE REG_DT IS NULL;');
        EXEC(N'ALTER TABLE dbo.TB_PC_CONDITION ALTER COLUMN REG_DT char(8) NOT NULL;');
        EXEC(N'ALTER TABLE dbo.TB_PC_CONDITION WITH CHECK ADD CONSTRAINT CK_TB_PC_CONDITION_REG_DT
                  CHECK (REG_DT LIKE ''[12][0-9][0-9][0-9][01][0-9][0-3][0-9]'' AND ISDATE(REG_DT)=1);');
    END;

    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
