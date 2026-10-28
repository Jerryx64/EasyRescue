param([switch]$Offline, [switch]$Debug)
$ErrorActionPreference = 'Stop'
$rescuePrior = @{}
foreach ($rescueKey in @('TEMP','TMP','GRADLE_USER_HOME','GRADLE_RO_DEP_CACHE')) {
    $rescuePrior[$rescueKey] = [Environment]::GetEnvironmentVariable($rescueKey,'Process')
}
Push-Location $PSScriptRoot
try {
    $rescueTmp = Join-Path $PSScriptRoot '.local\tmp'
    New-Item -ItemType Directory -Force $rescueTmp | Out-Null
    $env:TEMP = $rescueTmp
    $env:TMP = $rescueTmp
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.local\gradle-home'
    $rescueCache = 'E:\Codex风控封存_20261013\03_项目_Phone\.gradle-home\caches'
    if (Test-Path -LiteralPath $rescueCache) { $env:GRADLE_RO_DEP_CACHE = $rescueCache }
    $rescueInit = @"
allprojects {
    tasks.withType(Test).configureEach {
        doFirst {
            classpath = files(classpath.files.collect { entry ->
                if (entry.isFile() && entry.path.any { ch -> ((int) ch) > 127 }) {
                    def local = new File(temporaryDir, 'ascii-runtime/' + entry.name)
                    local.parentFile.mkdirs()
                    java.nio.file.Files.copy(entry.toPath(), local.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    return local
                }
                return entry
            })
        }
    }
}
"@
    $rescueInitFile = Join-Path $rescueTmp 'test-classpath.gradle'
    [IO.File]::WriteAllText($rescueInitFile,$rescueInit,[Text.UTF8Encoding]::new($false))
    $rescueArguments = @('--no-daemon','--console=plain','-I',$rescueInitFile)
    if ($Offline) { $rescueArguments += '--offline' }
    # Run the bundled offline query/type regressions; upstream's other suites fetch remote snapshots.
    $rescueVariant = if ($Debug) { 'GkdDebug' } else { 'GkdRelease' }
    $rescueArguments += @(
        ":app:assemble${rescueVariant}"
        ':selector:jvmTest'
        '--tests'
        'li.songe.selector.ElderAdSnapshotTest'
        '--tests'
        'li.songe.selector.TypeUnitTest'
        ':app:testGkdDebugUnitTest'
        ":app:lint${rescueVariant}"
    )
    & .\gradlew.bat @rescueArguments
    if ($LASTEXITCODE -ne 0) { throw "Build failed (exit $LASTEXITCODE)." }
} finally {
    foreach ($rescueKey in $rescuePrior.Keys) {
        [Environment]::SetEnvironmentVariable($rescueKey,$rescuePrior[$rescueKey],'Process')
    }
    Pop-Location
}
