-- Apply server/sql/001_request_ledger.sql first (follow legacy migration instructions there).
IF OBJECT_ID(N'dbo.AKPDA_INJECTION_CAPTURE', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.AKPDA_INJECTION_CAPTURE (
        CAPTURE_ID bigint IDENTITY(1,1) NOT NULL PRIMARY KEY,
        REQUEST_ID varchar(36) NOT NULL,
        PLANT_CD varchar(30) NOT NULL,
        MACHINE_CODE nvarchar(80) NOT NULL,
        MODEL_NAME nvarchar(80) NOT NULL,
        CAPTURED_AT datetime2(3) NOT NULL, -- UTC; phone capture/import time
        USER_ID varchar(80) NOT NULL,
        VALUES_JSON nvarchar(max) NOT NULL,
        PHOTO_JPEG varbinary(max) NOT NULL, -- app-normalized JPEG, not original camera bytes
        REGISTERED_AT datetime2(3) NOT NULL DEFAULT SYSUTCDATETIME(),
        CONSTRAINT CK_AKPDA_INJECTION_JSON CHECK(ISJSON(VALUES_JSON)=1),
        CONSTRAINT UQ_AKPDA_INJECTION_REQUEST UNIQUE(PLANT_CD,USER_ID,REQUEST_ID)
    );
    CREATE INDEX IX_AKPDA_INJECTION_MACHINE ON dbo.AKPDA_INJECTION_CAPTURE(PLANT_CD,MACHINE_CODE,CAPTURED_AT);
END;

