-- Existing installations only. Run against the intended MES database before starting AKPDA.
-- Keep request history so previously committed requests cannot be executed twice.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    IF OBJECT_ID(N'dbo.UNIPDA_API_REQUEST', N'U') IS NOT NULL
    BEGIN
        IF OBJECT_ID(N'dbo.AKPDA_API_REQUEST', N'U') IS NOT NULL
            THROW 50001, 'Both legacy and AKPDA request tables exist. Review their data before migrating.', 1;
        EXEC sys.sp_rename @objname=N'dbo.UNIPDA_API_REQUEST', @newname=N'AKPDA_API_REQUEST', @objtype=N'OBJECT';
        IF OBJECT_ID(N'dbo.PK_UNIPDA_API_REQUEST', N'PK') IS NOT NULL
            EXEC sys.sp_rename @objname=N'dbo.PK_UNIPDA_API_REQUEST', @newname=N'PK_AKPDA_API_REQUEST', @objtype=N'OBJECT';
        IF OBJECT_ID(N'dbo.DF_UNIPDA_API_REQUEST_CREATED', N'D') IS NOT NULL
            EXEC sys.sp_rename @objname=N'dbo.DF_UNIPDA_API_REQUEST_CREATED', @newname=N'DF_AKPDA_API_REQUEST_CREATED', @objtype=N'OBJECT';
    END;
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
