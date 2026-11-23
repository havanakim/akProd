-- Merge CP_mono (CP14, mono) and CP_Colored (CP3, color) into dbo.TB_PC_CONDITION.
-- Run once in akProd's configured DB_NAME, then install the app version that writes TB_PC_CONDITION.
-- Older app versions stop working once this runs, because CP_mono/CP_Colored are dropped.
--
--   REQUEST_ID    bigint from dbo.SEQ_PC_CONDITION (was a client UUID; no duplicate-request check)
--   JSON1         CP14 mono values  (was CP_mono.VALUES_JSON)
--   JSON2         CP3 color values  (was CP_Colored.VALUES_JSON)
--   JSON3         reserved for additional JSON values
--   DEVICE_CODE   사출기 호기 C01~C16 (was MACHINE_CODE)
--   ROUND_NO      차수 1,2,3… chosen with the 호기 (NULL for rows moved from the old tables)
--   INSERT_AT     server time of the insert (was REGISTERED_AT)
--   REG_DT        registration date YYYYMMDD, filled by the DB on insert (see also pc-condition-reg-dt.sql)
--   PAYLOAD_SHA256 is not carried over.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;

    IF OBJECT_ID(N'dbo.TB_PC_CONDITION',N'U') IS NOT NULL
        THROW 50001,N'TB_PC_CONDITION 테이블이 이미 있습니다. 다시 실행하지 않습니다.',1;

    IF OBJECT_ID(N'dbo.SEQ_PC_CONDITION',N'SO') IS NULL
        EXEC(N'CREATE SEQUENCE dbo.SEQ_PC_CONDITION AS bigint START WITH 1 INCREMENT BY 1 NO CACHE;');

    CREATE TABLE dbo.TB_PC_CONDITION (
        REQUEST_ID bigint NOT NULL
            CONSTRAINT DF_TB_PC_CONDITION_REQUEST_ID DEFAULT (NEXT VALUE FOR dbo.SEQ_PC_CONDITION)
            CONSTRAINT PK_TB_PC_CONDITION PRIMARY KEY,
        DEVICE_CODE nvarchar(80) NOT NULL,
        ROUND_NO int NULL CONSTRAINT CK_TB_PC_CONDITION_ROUND_NO CHECK (ROUND_NO IS NULL OR ROUND_NO>=1),
        USER_ID nvarchar(100) NOT NULL,
        CAPTURED_AT datetime2(3) NOT NULL,
        INSERT_AT datetime2(3) NOT NULL CONSTRAINT DF_TB_PC_CONDITION_INSERT_AT DEFAULT SYSDATETIME(),
        REG_DT char(8) NOT NULL CONSTRAINT DF_TB_PC_CONDITION_REG_DT DEFAULT (CONVERT(char(8),GETDATE(),112))
            CONSTRAINT CK_TB_PC_CONDITION_REG_DT CHECK (REG_DT LIKE '[12][0-9][0-9][0-9][01][0-9][0-3][0-9]' AND ISDATE(REG_DT)=1),
        JSON1 nvarchar(max) NULL CONSTRAINT CK_TB_PC_CONDITION_JSON1 CHECK (JSON1 IS NULL OR ISJSON(JSON1)=1),
        JSON2 nvarchar(max) NULL CONSTRAINT CK_TB_PC_CONDITION_JSON2 CHECK (JSON2 IS NULL OR ISJSON(JSON2)=1),
        JSON3 nvarchar(max) NULL CONSTRAINT CK_TB_PC_CONDITION_JSON3 CHECK (JSON3 IS NULL OR ISJSON(JSON3)=1),
        PHOTO_COUNT int NOT NULL CONSTRAINT DF_TB_PC_CONDITION_PHOTO_COUNT DEFAULT 0
            CONSTRAINT CK_TB_PC_CONDITION_PHOTO_COUNT CHECK (PHOTO_COUNT BETWEEN 0 AND 5),
        -- At least one JSON. A row may hold both screens of a 호기 (사출/보압 JSON1 + 계량 JSON2); see pc-condition-color-merge.sql.
        CONSTRAINT CK_TB_PC_CONDITION_KIND CHECK (JSON1 IS NOT NULL OR JSON2 IS NOT NULL)
    );
    CREATE INDEX IX_TB_PC_CONDITION_USER_DATE ON dbo.TB_PC_CONDITION(USER_ID,CAPTURED_AT DESC);
    CREATE INDEX IX_TB_PC_CONDITION_DEVICE_ROUND_DATE ON dbo.TB_PC_CONDITION(DEVICE_CODE,ROUND_NO,CAPTURED_AT);

    -- Move existing rows in capture order so new REQUEST_IDs follow time. Old UUIDs are not kept.
    DECLARE @mono int=0,@colored int=0,@moved int;
    IF OBJECT_ID(N'dbo.CP_mono',N'U') IS NOT NULL OR OBJECT_ID(N'dbo.CP_Colored',N'U') IS NOT NULL
    BEGIN
        IF OBJECT_ID(N'dbo.CP_mono',N'U') IS NULL OR OBJECT_ID(N'dbo.CP_Colored',N'U') IS NULL
            THROW 50002,N'CP_mono와 CP_Colored 중 하나만 있습니다. 확인 후 실행하세요.',1;
        EXEC sys.sp_executesql N'
            SELECT @m=COUNT(*) FROM dbo.CP_mono;
            SELECT @c=COUNT(*) FROM dbo.CP_Colored;
            INSERT INTO dbo.TB_PC_CONDITION (DEVICE_CODE,USER_ID,CAPTURED_AT,INSERT_AT,REG_DT,JSON1,JSON2,PHOTO_COUNT)
            SELECT MACHINE_CODE AS DEVICE_CODE,USER_ID,CAPTURED_AT,REGISTERED_AT AS INSERT_AT,CONVERT(char(8),REGISTERED_AT,112),JSON1,JSON2,PHOTO_COUNT FROM (
                SELECT MACHINE_CODE,USER_ID,CAPTURED_AT,REGISTERED_AT,VALUES_JSON AS JSON1,CAST(NULL AS nvarchar(max)) AS JSON2,PHOTO_COUNT,REQUEST_ID FROM dbo.CP_mono
                UNION ALL
                SELECT MACHINE_CODE,USER_ID,CAPTURED_AT,REGISTERED_AT,NULL,VALUES_JSON,PHOTO_COUNT,REQUEST_ID FROM dbo.CP_Colored
            ) AS old
            ORDER BY CAPTURED_AT,REQUEST_ID;
            SET @n=@@ROWCOUNT;',
            N'@m int OUTPUT,@c int OUTPUT,@n int OUTPUT',@m=@mono OUTPUT,@c=@colored OUTPUT,@n=@moved OUTPUT;
        IF @moved<>@mono+@colored
            THROW 50003,N'옮긴 건수가 기존 건수와 다릅니다. 변경 없이 취소합니다.',1;
        DROP TABLE dbo.CP_mono;
        DROP TABLE dbo.CP_Colored;
    END;

    COMMIT TRANSACTION;
    SELECT @mono AS MOVED_MONO,@colored AS MOVED_COLORED;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT>0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
