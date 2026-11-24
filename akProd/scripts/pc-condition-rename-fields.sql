-- Rename the CP3 color FIELDS keys already saved in TB_PC_CONDITION to the short names the app now saves:
--   사출: INJECT_n_PRESSURE→IPn, _SPEED→IVn, _TIME→ITn, _POSITION→IFn
--   계량: METER_n_PRESSURE→RPn, _SPEED→RVn, _POSITION→Sn, _BACK_PRESSURE→BPn      (n = 1..5)
-- Only the key names change; values, other keys and other columns are kept. Runs in one transaction and
-- rolls back unless every changed JSON is valid and keeps the same FIELDS count and values. Safe to run again.
SET XACT_ABORT ON;
BEGIN TRY
    BEGIN TRANSACTION;

    -- OPENJSON returns keys in Latin1_General_BIN2 (exact, case-sensitive); compare in the same collation.
    DECLARE @map TABLE (OldKey nvarchar(40) COLLATE Latin1_General_BIN2 PRIMARY KEY, NewKey nvarchar(10) COLLATE Latin1_General_BIN2 NOT NULL UNIQUE);
    DECLARE @n int = 1;
    WHILE @n <= 5
    BEGIN
        INSERT INTO @map VALUES
            (CONCAT(N'INJECT_',@n,N'_PRESSURE'),      CONCAT(N'IP',@n)),
            (CONCAT(N'INJECT_',@n,N'_SPEED'),         CONCAT(N'IV',@n)),
            (CONCAT(N'INJECT_',@n,N'_TIME'),          CONCAT(N'IT',@n)),
            (CONCAT(N'INJECT_',@n,N'_POSITION'),      CONCAT(N'IF',@n)),
            (CONCAT(N'METER_',@n,N'_PRESSURE'),       CONCAT(N'RP',@n)),
            (CONCAT(N'METER_',@n,N'_SPEED'),          CONCAT(N'RV',@n)),
            (CONCAT(N'METER_',@n,N'_POSITION'),       CONCAT(N'S',@n)),
            (CONCAT(N'METER_',@n,N'_BACK_PRESSURE'),  CONCAT(N'BP',@n));
        SET @n += 1;
    END;

    -- Rows to change and a copy of their FIELDS before the change, for the checks below.
    DECLARE @before TABLE (REQUEST_ID bigint, Col char(5), [Key] nvarchar(4000) COLLATE Latin1_General_BIN2, Value nvarchar(max) COLLATE Latin1_General_BIN2, Type int);
    INSERT INTO @before
    SELECT t.REQUEST_ID, 'JSON1', f.[key], f.value, f.type FROM dbo.TB_PC_CONDITION t CROSS APPLY OPENJSON(t.JSON1,'$.FIELDS') f
    WHERE EXISTS (SELECT 1 FROM OPENJSON(t.JSON1,'$.FIELDS') o JOIN @map m ON m.OldKey=o.[key])
    UNION ALL
    SELECT t.REQUEST_ID, 'JSON2', f.[key], f.value, f.type FROM dbo.TB_PC_CONDITION t CROSS APPLY OPENJSON(t.JSON2,'$.FIELDS') f
    WHERE EXISTS (SELECT 1 FROM OPENJSON(t.JSON2,'$.FIELDS') o JOIN @map m ON m.OldKey=o.[key]);

    -- A saved name must not already be in use in a JSON that still has the old name (that would merge two items).
    IF EXISTS (SELECT 1 FROM @before b JOIN @map m ON m.NewKey=b.[Key]
               WHERE EXISTS (SELECT 1 FROM @before o WHERE o.REQUEST_ID=b.REQUEST_ID AND o.Col=b.Col AND o.[Key]=m.OldKey))
        THROW 50001,N'같은 JSON에 예전 이름과 새 이름이 함께 있습니다. 변경하지 않습니다.',1;

    -- Keys are quoted JSON member names ("INJECT_1_PRESSURE"); values are numbers or null, so they never match.
    DECLARE @old nvarchar(40), @new nvarchar(10);
    DECLARE keys CURSOR LOCAL FAST_FORWARD FOR SELECT OldKey, NewKey FROM @map;
    OPEN keys;
    FETCH NEXT FROM keys INTO @old, @new;
    WHILE @@FETCH_STATUS = 0
    BEGIN
        UPDATE dbo.TB_PC_CONDITION SET JSON1 = REPLACE(JSON1, N'"'+@old+N'"', N'"'+@new+N'"')
        WHERE REQUEST_ID IN (SELECT REQUEST_ID FROM @before WHERE Col='JSON1') AND JSON1 LIKE N'%"'+@old+N'"%';
        UPDATE dbo.TB_PC_CONDITION SET JSON2 = REPLACE(JSON2, N'"'+@old+N'"', N'"'+@new+N'"')
        WHERE REQUEST_ID IN (SELECT REQUEST_ID FROM @before WHERE Col='JSON2') AND JSON2 LIKE N'%"'+@old+N'"%';
        FETCH NEXT FROM keys INTO @old, @new;
    END;
    CLOSE keys; DEALLOCATE keys;

    -- After: every FIELDS item is still there with the same value and type, under its new name.
    DECLARE @after TABLE (REQUEST_ID bigint, Col char(5), [Key] nvarchar(4000) COLLATE Latin1_General_BIN2, Value nvarchar(max) COLLATE Latin1_General_BIN2, Type int);
    INSERT INTO @after
    SELECT t.REQUEST_ID, 'JSON1', f.[key], f.value, f.type FROM dbo.TB_PC_CONDITION t CROSS APPLY OPENJSON(t.JSON1,'$.FIELDS') f
    WHERE t.REQUEST_ID IN (SELECT REQUEST_ID FROM @before WHERE Col='JSON1')
    UNION ALL
    SELECT t.REQUEST_ID, 'JSON2', f.[key], f.value, f.type FROM dbo.TB_PC_CONDITION t CROSS APPLY OPENJSON(t.JSON2,'$.FIELDS') f
    WHERE t.REQUEST_ID IN (SELECT REQUEST_ID FROM @before WHERE Col='JSON2');

    IF EXISTS (SELECT 1 FROM dbo.TB_PC_CONDITION WHERE REQUEST_ID IN (SELECT REQUEST_ID FROM @before)
               AND ((JSON1 IS NOT NULL AND ISJSON(JSON1)=0) OR (JSON2 IS NOT NULL AND ISJSON(JSON2)=0)))
        THROW 50002,N'변경 후 JSON이 올바르지 않습니다. 변경하지 않습니다.',1;
    IF (SELECT COUNT(*) FROM @before) <> (SELECT COUNT(*) FROM @after)
       OR EXISTS (
            SELECT b.REQUEST_ID, b.Col, COALESCE(m.NewKey,b.[Key]), b.Value, b.Type FROM @before b LEFT JOIN @map m ON m.OldKey=b.[Key]
            EXCEPT
            SELECT REQUEST_ID, Col, [Key], Value, Type FROM @after)
        THROW 50003,N'변경 후 FIELDS 항목이나 값이 다릅니다. 변경하지 않습니다.',1;

    COMMIT TRANSACTION;
    SELECT a.REQUEST_ID, a.Col, COUNT(*) AS FIELDS_COUNT, COUNT(m.NewKey) AS RENAMED
    FROM @after a LEFT JOIN @map m ON m.NewKey=a.[Key] GROUP BY a.REQUEST_ID, a.Col ORDER BY a.REQUEST_ID, a.Col;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
