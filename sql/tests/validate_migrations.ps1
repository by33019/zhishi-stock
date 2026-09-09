$ErrorActionPreference = 'Stop'

$sqlRoot = Split-Path -Parent $PSScriptRoot
$migrationRoot = Join-Path $sqlRoot 'flyway'
$checkRoot = Join-Path $sqlRoot 'checks'

$expectedFiles = @(
    'flyway/V1__baseline_existing_schema.sql',
    'flyway/V2__harden_legacy_schema.sql',
    'flyway/V3__create_market_domain.sql',
    'flyway/V4__create_news_domain.sql',
    'flyway/V5__create_watchlist_domain.sql',
    'flyway/V6__create_ai_domain.sql',
    'flyway/V7__create_job_and_outbox_domain.sql',
    'checks/preflight_existing_schema.sql',
    'checks/post_migration_validation.sql',
    'README.md'
)

$missingFiles = @(
    $expectedFiles | Where-Object {
        -not (Test-Path -LiteralPath (Join-Path $sqlRoot $_) -PathType Leaf)
    }
)

if ($missingFiles.Count -gt 0) {
    throw "缺少数据库交付文件: $($missingFiles -join ', ')"
}

$migrationFiles = @(
    Get-ChildItem -LiteralPath $migrationRoot -Filter 'V*.sql' -File |
        Sort-Object Name
)

$expectedMigrationNames = @(
    'V1__baseline_existing_schema.sql',
    'V2__harden_legacy_schema.sql',
    'V3__create_market_domain.sql',
    'V4__create_news_domain.sql',
    'V5__create_watchlist_domain.sql',
    'V6__create_ai_domain.sql',
    'V7__create_job_and_outbox_domain.sql'
)

if (($migrationFiles.Name -join ',') -ne ($expectedMigrationNames -join ',')) {
    throw "Flyway 文件集合或顺序不正确: $($migrationFiles.Name -join ', ')"
}

$allMigrationSql = ($migrationFiles | ForEach-Object {
    Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
}) -join "`n"

$baselineSql = Get-Content -LiteralPath (Join-Path $migrationRoot $expectedMigrationNames[0]) -Raw -Encoding UTF8
$legacySql = Get-Content -LiteralPath (Join-Path $migrationRoot $expectedMigrationNames[1]) -Raw -Encoding UTF8
$readme = Get-Content -LiteralPath (Join-Path $sqlRoot 'README.md') -Raw -Encoding UTF8
$legacyDumpSql = Get-Content -LiteralPath (Join-Path $sqlRoot 'stock_db.sql') -Raw -Encoding UTF8

$legacyTables = @(
    'stock_block_rt_info', 'stock_business', 'stock_market_index_info',
    'stock_outer_market_index_info', 'stock_rt_info', 'sys_log',
    'sys_permission', 'sys_role', 'sys_role_permission', 'sys_user',
    'sys_user_role'
)

$newTables = @(
    'external_provider', 'data_sync_checkpoint', 'stock_exchange',
    'stock_security', 'stock_security_status_history', 'stock_sector',
    'stock_security_sector', 'stock_trade_calendar', 'stock_limit_rule',
    'stock_minute_bar', 'stock_kline_day', 'news_source', 'stock_news',
    'stock_news_relation', 'user_watchlist_group', 'user_watchlist_item',
    'ai_session', 'ai_task', 'ai_task_target', 'ai_context_snapshot',
    'ai_message', 'ai_report', 'ai_evidence', 'ai_feedback', 'ai_usage',
    'job_execution_summary', 'data_quality_issue', 'event_outbox'
)

foreach ($table in $legacyTables) {
    if ($baselineSql -notmatch "(?i)CREATE\s+TABLE\s+``$([regex]::Escape($table))``") {
        throw "V1 缺少现有表: $table"
    }
}

function Get-CreateTableColumns([string]$sql, [string]$table) {
    $pattern = '(?is)CREATE\s+TABLE\s+`' + [regex]::Escape($table) + '`\s*\((.*?)\)\s*ENGINE\s*='
    $tableMatch = [regex]::Match($sql, $pattern)
    if (-not $tableMatch.Success) {
        throw "无法提取建表结构: $table"
    }

    return @(
        [regex]::Matches($tableMatch.Groups[1].Value, '(?m)^\s*`([^`]+)`\s+') |
            ForEach-Object { $_.Groups[1].Value }
    )
}

foreach ($table in $legacyTables) {
    $sourceColumns = @(Get-CreateTableColumns $legacyDumpSql $table)
    $baselineColumns = @(Get-CreateTableColumns $baselineSql $table)
    if (($sourceColumns -join ',') -ne ($baselineColumns -join ',')) {
        throw "V1 与原始 SQL 字段集合不一致: $table；原始=$($sourceColumns -join ',')；基线=$($baselineColumns -join ',')"
    }
}

foreach ($table in $newTables) {
    if ($allMigrationSql -notmatch "(?i)CREATE\s+TABLE\s+``$([regex]::Escape($table))``") {
        throw "迁移缺少新增表: $table"
    }
}

if ($baselineSql -match '(?im)^\s*(INSERT|REPLACE|UPDATE|DELETE)\s+') {
    throw 'V1 基线不得包含业务数据写入语句'
}

$forbiddenPatterns = [ordered]@{
    '数据库外键' = '(?i)FOREIGN\s+KEY|\bREFERENCES\s+`'
    '数据库创建' = '(?im)^\s*CREATE\s+DATABASE\b'
    '数据库切换' = '(?im)^\s*USE\s+'
    '数据库自增主键' = '(?i)AUTO_INCREMENT'
    'MySQL ENUM' = '(?i)\bENUM\s*\('
    '明文密钥字段' = '(?i)(api_key|secret_key|access_key)\s+varchar'
}

foreach ($entry in $forbiddenPatterns.GetEnumerator()) {
    if ($allMigrationSql -match $entry.Value) {
        throw "迁移中发现禁止项: $($entry.Key)"
    }
}

$newDomainSql = ($migrationFiles | Where-Object { $_.Name -match '^V[3-7]__' } | ForEach-Object {
    Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
}) -join "`n"

$aiMigrationSql = Get-Content -LiteralPath (Join-Path $migrationRoot 'V6__create_ai_domain.sql') -Raw -Encoding UTF8
$aiReportMatch = [regex]::Match($aiMigrationSql, '(?is)CREATE\s+TABLE\s+`ai_report`\s*\((.*?)\)\s*ENGINE\s*=')
if (-not $aiReportMatch.Success) {
    throw '无法提取 ai_report 建表结构'
}
if ($aiReportMatch.Groups[1].Value -match "'REJECTED'") {
    throw 'ai_report 不得保存校验拒绝结果，校验失败应只记录在 ai_task'
}

$newTableContract = [ordered]@{
    'InnoDB' = '(?i)ENGINE\s*=\s*InnoDB'
    'utf8mb4' = '(?i)DEFAULT\s+CHARACTER\s+SET\s*=\s*utf8mb4'
    'DYNAMIC 行格式' = '(?i)ROW_FORMAT\s*=\s*DYNAMIC'
}

foreach ($entry in $newTableContract.GetEnumerator()) {
    $count = ([regex]::Matches($newDomainSql, $entry.Value)).Count
    if ($count -ne $newTables.Count) {
        throw "新增表未全部满足 $($entry.Key)，期望 $($newTables.Count)，实际 $count"
    }
}

foreach ($checkFile in @('preflight_existing_schema.sql', 'post_migration_validation.sql')) {
    $checkSql = Get-Content -LiteralPath (Join-Path $checkRoot $checkFile) -Raw -Encoding UTF8
    $checkSqlWithoutComments = [regex]::Replace($checkSql, '(?m)^\s*--.*$', '')
    if ($checkSqlWithoutComments -match '(?im)^\s*(INSERT|REPLACE|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE)\b') {
        throw "检查脚本必须只读: $checkFile"
    }
}

$requiredLegacyFragments = @(
    'MODIFY COLUMN `block_label` varchar(20)',
    'CHANGE COLUMN `user_id` `legacy_user_ref`',
    'ADD COLUMN `user_id` bigint',
    'ADD COLUMN `active_email`',
    'ADD COLUMN `active_name`',
    'ADD COLUMN `active_code`',
    'ADD COLUMN `active_perms`',
    'UNIQUE INDEX `uk_sys_user_email`',
    'UNIQUE INDEX `uk_sys_user_role`',
    'UNIQUE INDEX `uk_sys_role_permission`'
)

foreach ($fragment in $requiredLegacyFragments) {
    if (-not $legacySql.Contains($fragment)) {
        throw "V2 缺少兼容修复: $fragment"
    }
}

$dynamicRowFormatCount = ([regex]::Matches($legacySql, '(?i)ROW_FORMAT\s*=\s*DYNAMIC')).Count
if ($dynamicRowFormatCount -ne $legacyTables.Count) {
    throw "V2 必须将全部遗留表升级为 DYNAMIC 行格式，期望 $($legacyTables.Count)，实际 $dynamicRowFormatCount"
}

$requiredReadmeFragments = @(
    'baselineOnMigrate', 'baselineVersion', 'Asia/Shanghai',
    'stock_db.sql', 'preflight_existing_schema.sql',
    'post_migration_validation.sql'
)

foreach ($fragment in $requiredReadmeFragments) {
    if (-not $readme.Contains($fragment)) {
        throw "README 缺少使用说明: $fragment"
    }
}

$createTableCount = ([regex]::Matches($allMigrationSql, '(?i)CREATE\s+TABLE\s+`')).Count
$expectedTableCount = $legacyTables.Count + $newTables.Count
if ($createTableCount -ne $expectedTableCount) {
    throw "建表数量不正确，期望 $expectedTableCount，实际 $createTableCount"
}

Write-Output "迁移文件: $($migrationFiles.Count)/$($expectedMigrationNames.Count)"
Write-Output "基线表: $($legacyTables.Count)/$($legacyTables.Count)"
Write-Output "新增表: $($newTables.Count)/$($newTables.Count)"
Write-Output "总表数: $createTableCount"
Write-Output 'STATIC MIGRATION VALIDATION PASSED'
