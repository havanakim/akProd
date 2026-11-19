-- Existing CP_mono installations: allow one screen photograph per record.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    IF OBJECT_ID(N'dbo.CP_mono',N'U') IS NULL
        THROW 50001,N'CP_mono 테이블을 먼저 생성하세요.',1;
    DECLARE @constraint sysname, @sql nvarchar(max);
    DECLARE constraints_to_update CURSOR LOCAL FAST_FORWARD FOR
        SELECT name FROM sys.check_constraints
        WHERE parent_object_id=OBJECT_ID(N'dbo.CP_mono')
          AND (parent_column_id=COLUMNPROPERTY(OBJECT_ID(N'dbo.CP_mono'),N'PHOTO_COUNT','ColumnId')
               OR definition LIKE N'%PHOTO_COUNT%');
    OPEN constraints_to_update;
    FETCH NEXT FROM constraints_to_update INTO @constraint;
    WHILE @@FETCH_STATUS=0
    BEGIN
        SET @sql=N'ALTER TABLE [dbo].[CP_mono] DROP CONSTRAINT '+QUOTENAME(@constraint);
        EXEC sys.sp_executesql @sql;
        FETCH NEXT FROM constraints_to_update INTO @constraint;
    END;
    CLOSE constraints_to_update;
    DEALLOCATE constraints_to_update;
    ALTER TABLE [dbo].[CP_mono] WITH CHECK ADD CONSTRAINT CK_CP_mono_PHOTO_COUNT
        CHECK (PHOTO_COUNT BETWEEN 0 AND 5);
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
