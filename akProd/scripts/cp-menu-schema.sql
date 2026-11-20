-- Canonical menu/table names: CP_Colored, CP_mono (values only; the former *_PHOTO tables were dropped). Existing records and object permissions are preserved.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;
    DECLARE @Renames TABLE (Id int IDENTITY(1,1),OldName nvarchar(261),NewName sysname);
    INSERT INTO @Renames (OldName,NewName) VALUES
        (N'dbo.AKSMART_CP3_CAPTURE',N'CP_Colored'),
        (N'[dbo].[CP-Colored]',N'CP_Colored'),
        (N'dbo.AKSMART_CP14_CAPTURE',N'CP_mono'),
        (N'[dbo].[CP-mono]',N'CP_mono');

    -- Reject collisions before renaming anything; never merge or delete existing data.
    IF EXISTS (
        SELECT NewName FROM @Renames GROUP BY NewName
        HAVING SUM(CASE WHEN OBJECT_ID(OldName,N'U') IS NOT NULL THEN 1 ELSE 0 END)
             + MAX(CASE WHEN OBJECT_ID(N'[dbo].'+QUOTENAME(NewName),N'U') IS NOT NULL THEN 1 ELSE 0 END)>1
    )
        THROW 50001,N'같은 메뉴의 기존 테이블과 새 테이블이 함께 있습니다. 자동 병합하지 않습니다.',1;

    DECLARE @Id int=1,@OldName nvarchar(261),@NewName sysname;
    WHILE @Id<=(SELECT COUNT(*) FROM @Renames)
    BEGIN
        SELECT @OldName=OldName,@NewName=NewName FROM @Renames WHERE Id=@Id;
        IF OBJECT_ID(@OldName,N'U') IS NOT NULL
            EXEC sys.sp_rename @OldName,@NewName,N'OBJECT';
        SET @Id+=1;
    END;
-- Apply once to the DB_NAME configured for akSmart. Does not change existing PDA tables.


IF OBJECT_ID(N'dbo.AKSMART_CP3_CAPTURE',N'U') IS NOT NULL OR OBJECT_ID(N'[dbo].[CP-Colored]',N'U') IS NOT NULL THROW 50001,N'기존 테이블이 있습니다. cp-menu-schema.sql을 먼저 실행하세요.',1;

IF OBJECT_ID(N'[dbo].[CP_Colored]',N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[CP_Colored] (
        REQUEST_ID varchar(36) NOT NULL PRIMARY KEY,
        MACHINE_CODE nvarchar(80) NOT NULL,
        USER_ID nvarchar(100) NOT NULL,
        CAPTURED_AT datetime2(3) NOT NULL,
        REGISTERED_AT datetime2(3) NOT NULL DEFAULT SYSDATETIME(),
        VALUES_JSON nvarchar(max) NOT NULL CHECK (ISJSON(VALUES_JSON)=1),
        PAYLOAD_SHA256 varchar(64) NOT NULL,
        PHOTO_COUNT int NOT NULL CHECK (PHOTO_COUNT BETWEEN 0 AND 5)
    );
END;




-- Apply once to the DB_NAME configured for akSmart. Does not change existing PDA tables.


IF OBJECT_ID(N'dbo.AKSMART_CP14_CAPTURE',N'U') IS NOT NULL OR OBJECT_ID(N'[dbo].[CP-mono]',N'U') IS NOT NULL THROW 50001,N'기존 테이블이 있습니다. cp-menu-schema.sql을 먼저 실행하세요.',1;

IF OBJECT_ID(N'[dbo].[CP_mono]',N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[CP_mono] (
        REQUEST_ID varchar(36) NOT NULL PRIMARY KEY,
        MACHINE_CODE nvarchar(80) NOT NULL,
        USER_ID nvarchar(100) NOT NULL,
        CAPTURED_AT datetime2(3) NOT NULL,
        REGISTERED_AT datetime2(3) NOT NULL DEFAULT SYSDATETIME(),
        VALUES_JSON nvarchar(max) NOT NULL CHECK (ISJSON(VALUES_JSON)=1),
        PAYLOAD_SHA256 varchar(64) NOT NULL,
        PHOTO_COUNT int NOT NULL CHECK (PHOTO_COUNT BETWEEN 0 AND 5)
    );
    CREATE INDEX IX_AKSMART_CP14_CAPTURE_USER_DATE ON [dbo].[CP_mono](USER_ID,CAPTURED_AT DESC);
END;





    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
