# Loads .env from the project root and starts the bot locally for manual testing.
# Not used in Docker/production - there env vars are passed by docker-compose instead.

# Java 21+ required by the project; adjust this path if your default JAVA_HOME differs.
$env:JAVA_HOME = 'C:\Users\emanuele.patruno\.jdks\openjdk-25'

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root ".env"

if (-not (Test-Path $envFile)) {
    Write-Error "File .env non trovato in $envFile - copialo dal template nel README prima di continuare."
    exit 1
}

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1].Trim(), $Matches[2].Trim())
    }
}

Set-Location $root
mvn spring-boot:run
