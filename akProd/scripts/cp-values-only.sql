-- Run once in akProd's configured DB_NAME before installing the values-only APK.
-- Keeps all existing parent rows and photographs. Allows PHOTO_COUNT=0 for new records.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    IF OBJECT_ID(N'dbo.CP_mono',N'U') IS NULL OR OBJECT_ID(N'dbo.CP_Colored',N'U') IS NULL
        THROW 50001,N'CP_mono와 CP_Colored 테이블을 먼저 설치하세요.',1;
    DECLARE @table sysname, @constraint sysname, @sql nvarchar(max);
    DECLARE photo_checks CURSOR LOCAL FAST_FORWARD FOR
        SELECT OBJECT_NAME(parent_object_id),name FROM sys.check_constraints
        WHERE parent_object_id IN (OBJECT_ID(N'dbo.CP_mono'),OBJECT_ID(N'dbo.CP_Colored'))
          AND (parent_column_id=COLUMNPROPERTY(parent_object_id,N'PHOTO_COUNT','ColumnId')
               OR definition LIKE N'%PHOTO_COUNT%');
    OPEN photo_checks;
    FETCH NEXT FROM photo_checks INTO @table,@constraint;
    WHILE @@FETCH_STATUS=0
    BEGIN
        SET @sql=N'ALTER TABLE [dbo].'+QUOTENAME(@table)+N' DROP CONSTRAINT '+QUOTENAME(@constraint);
        EXEC sys.sp_executesql @sql;
        FETCH NEXT FROM photo_checks INTO @table,@constraint;
    END;
    CLOSE photo_checks;
    DEALLOCATE photo_checks;
    ALTER TABLE dbo.CP_mono WITH CHECK ADD CONSTRAINT CK_CP_mono_PHOTO_COUNT CHECK(PHOTO_COUNT BETWEEN 0 AND 5);
    ALTER TABLE dbo.CP_Colored WITH CHECK ADD CONSTRAINT CK_CP_Colored_PHOTO_COUNT CHECK(PHOTO_COUNT BETWEEN 0 AND 5);
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
