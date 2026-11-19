-- Apply once to the DB_NAME configured for akSmart. Does not change existing PDA tables.
SET XACT_ABORT ON;
BEGIN TRANSACTION;
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
IF OBJECT_ID(N'[dbo].[CP_mono_PHOTO]',N'U') IS NULL
BEGIN
    CREATE TABLE [dbo].[CP_mono_PHOTO] (
        REQUEST_ID varchar(36) NOT NULL,
        PHOTO_NO int NOT NULL,
        LOADED_AT datetime2(3) NOT NULL,
        PHOTO_JPEG varbinary(max) NOT NULL,
        OCR_TEXT nvarchar(max) NOT NULL,
        CONSTRAINT PK_AKSMART_CP14_CAPTURE_PHOTO PRIMARY KEY (REQUEST_ID,PHOTO_NO),
        CONSTRAINT FK_AKSMART_CP14_CAPTURE_PHOTO FOREIGN KEY (REQUEST_ID)
            REFERENCES [dbo].[CP_mono](REQUEST_ID)
    );
END;
COMMIT TRANSACTION;



