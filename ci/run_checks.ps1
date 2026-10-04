#!/usr/bin/env pwsh
# Runs every check for the dashboard work: the Java unit tests and the browser
# side suites, plus the static contract checks.
#
# Exists because a one-off edit to a function another suite slices broke that
# suite while the touched suite still passed. Run all of them, always.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

$env:JAVA_HOME = 'D:\jdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = 'D:\gradle-home'

$failed = @()

Write-Host '== java ==' -ForegroundColor Cyan
$out = & .\gradlew.bat :v26_2:test --console=plain --no-daemon 2>&1
if ($LASTEXITCODE -ne 0) {
    $failed += 'gradle test'
    $out | Select-String -Pattern 'FAILED|error:' | Select-Object -First 10 | ForEach-Object { "  $($_.Line)" }
} else {
    $files = Get-ChildItem 'versions\26.2\build\test-results\test\*.xml' -ErrorAction SilentlyContinue
    $t = 0; $f = 0; $e = 0
    foreach ($x in $files) {
        [xml]$d = Get-Content $x.FullName
        $t += [int]$d.testsuite.tests; $f += [int]$d.testsuite.failures; $e += [int]$d.testsuite.errors
    }
    Write-Host "  $t tests, $f failures, $e errors"
    if ($f -gt 0 -or $e -gt 0) { $failed += 'java assertions' }
}

Write-Host '== javascript ==' -ForegroundColor Cyan
node --check src\main\resources\web\app.js
if ($LASTEXITCODE -ne 0) { $failed += 'app.js syntax' } else { Write-Host '  app.js parses' }

foreach ($suite in @('test_market', 'test_stepper', 'test_currency', 'test_orders')) {
    $res = & node "ci\$suite.js" 2>&1
    if ($LASTEXITCODE -ne 0) {
        $failed += $suite
        Write-Host "  $suite FAILED" -ForegroundColor Red
        $res | Select-String -Pattern 'FAIL' | Select-Object -First 6 | ForEach-Object { "    $($_.Line)" }
    } else {
        Write-Host "  $suite ok"
    }
}

Write-Host '== static contracts ==' -ForegroundColor Cyan
foreach ($check in @('check_web_ui.py', 'check_css_classes.py', 'check_issue_fixes.py')) {
    $res = & python "ci\$check" 2>&1
    if ($LASTEXITCODE -ne 0) {
        $failed += $check
        Write-Host "  $check FAILED" -ForegroundColor Red
        $res | Select-Object -Last 8 | ForEach-Object { "    $_" }
    } else {
        Write-Host "  $check ok"
    }
}

if ($failed.Count -gt 0) {
    Write-Host ''
    Write-Host "FAILED: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host 'all checks passed' -ForegroundColor Green
