# MySQL 5.7 — Migrate to utf8mb4 to fix getNString() SQLException

## Problem

MySQL Connector/J 8 throws the following error when `ResultSet.getNString()` is called
on a column whose charset is not UTF-8 (e.g. `latin1`):

```
SQLException: Can not call getNString() when field's charset isn't UTF-8
```

This affects Keycloak 18+ and any application using Hibernate 5.6+ where columns are
mapped as `NVARCHAR`.

---

## Prerequisites — verify InnoDB settings

Before migrating, confirm large prefix support is enabled:

```sql
SHOW VARIABLES LIKE 'innodb_large_prefix';
SHOW VARIABLES LIKE 'innodb_file_format';
```

Both must show `ON` / `Barracuda`. If not, add the following to `my.cnf` and restart MySQL:

```ini
[mysqld]
innodb_large_prefix  = ON
innodb_file_format   = Barracuda
innodb_file_per_table = ON
```

> **Why this matters:** `utf8mb4` uses up to 4 bytes per character.
> A `VARCHAR(255)` index requires 1020 bytes, exceeding MySQL 5.7's default 767-byte key
> limit. With `innodb_large_prefix=ON` and `ROW_FORMAT=DYNAMIC` the limit rises to
> 3072 bytes, so `VARCHAR(255) utf8mb4` fits safely.

---

## Migration — stored procedure

The procedure converts the database default charset and every table in one pass,
printing progress as it goes.

```sql
DROP PROCEDURE IF EXISTS convert_to_utf8mb4;

DELIMITER $$

CREATE PROCEDURE convert_to_utf8mb4()
BEGIN
    DECLARE done      INT DEFAULT 0;
    DECLARE tbl_name  VARCHAR(255);

    DECLARE cur CURSOR FOR
        SELECT TABLE_NAME
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_TYPE = 'BASE TABLE';

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    -- Set database default first
    SET @alter_db = CONCAT(
        'ALTER DATABASE `', DATABASE(), '` ',
        'CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'
    );
    PREPARE stmt FROM @alter_db;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;

    OPEN cur;
    loop_tables: LOOP
        FETCH cur INTO tbl_name;
        IF done THEN
            LEAVE loop_tables;
        END IF;

        SET @sql = CONCAT(
            'ALTER TABLE `', tbl_name, '` ',
            'ROW_FORMAT=DYNAMIC, ',
            'CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'
        );

        SELECT CONCAT('Converting: ', tbl_name) AS status;
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;

    END LOOP;
    CLOSE cur;

    SELECT 'All tables converted.' AS status;
END$$

DELIMITER ;

-- Run against your target database
USE testdb;
CALL convert_to_utf8mb4();

-- Clean up
DROP PROCEDURE IF EXISTS convert_to_utf8mb4;
```

---

## Alternative — preview statements before executing

If you prefer to review every `ALTER TABLE` before running it:

```sql
SELECT CONCAT(
    'ALTER TABLE `', TABLE_NAME, '` ',
    'ROW_FORMAT=DYNAMIC, ',
    'CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
) AS sql_statement
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'testdb'
  AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;
```

Copy the output, review it, then execute.

---

## Verify after migration

```sql
-- Check database charset
SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'testdb';

-- Check all tables
SELECT TABLE_NAME, TABLE_COLLATION, ROW_FORMAT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'testdb'
  AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;

-- Check individual columns
SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'testdb'
  AND CHARACTER_SET_NAME IS NOT NULL
ORDER BY TABLE_NAME, COLUMN_NAME;
```

All entries should show `utf8mb4` / `utf8mb4_unicode_ci` and `ROW_FORMAT = Dynamic`.

---

## JDBC URL — temporary workaround (can be removed after migration)

```
jdbc:mysql://host:3306/testdb?characterEncoding=UTF-8&characterSetResults=UTF-8
```

Once the database is fully migrated to `utf8mb4`, these parameters are no longer needed.
