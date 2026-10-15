param(
    [ValidateSet('PrepareSigning', 'SaveToken', 'Build', 'Publish', 'Status')]
    [string]$Action = 'Build',
    [ValidateSet('Core', 'GameLib')]
    [string]$Target = 'Core',
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$configDir = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle\rwml-central'
$keyHome = Join-Path $configDir 'gnupg'
$passwordFile = Join-Path $configDir 'gpg-password.xml'
$tokenFile = Join-Path $configDir 'portal-token.xml'
$identityFile = Join-Path $configDir 'signing.json'
$versionLine = Get-Content -LiteralPath (Join-Path $repo 'gradle.properties') |
    Where-Object { $_ -match '^version\s*=' } | Select-Object -First 1
if (!$versionLine) { throw 'The project version is missing from gradle.properties.' }
$releaseVersion = ($versionLine -split '=', 2)[1].Trim()
if ($releaseVersion -notmatch '^\d+\.\d+\.\d+$') { throw "Expected a release version, got $releaseVersion" }
$deploymentFile = Join-Path $repo "build\central-deployment-rwml-core-$releaseVersion.json"
$coordinates = "io.github.rwpp-team:rwml-core:$releaseVersion"
$bundle = Join-Path $repo "build\rwml-$releaseVersion-central-bundle.zip"
$bundleTask = 'bundleForCentral'
if ($Target -eq 'GameLib') {
    $deploymentFile = Join-Path $repo 'build\game-lib-central-deployment.json'
    $coordinates = 'io.github.rwpp-team:game-lib:1.15'
    $bundle = Join-Path $repo 'build\game-lib-1.15-central-bundle.zip'
    $bundleTask = 'bundleGameLibForCentral'
}

function Invoke-Gpg([string[]]$GpgArgs, [string]$InputText) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:gpg
    $start.Arguments = ($GpgArgs | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }) -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        $process.Start() | Out-Null
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($PSBoundParameters.ContainsKey('InputText')) {
            # Write LF explicitly: PowerShell's CRLF pipe changes a GPG passphrase on MSYS.
            $process.StandardInput.Write($InputText + "`n")
        }
        $process.StandardInput.Close()
        $process.WaitForExit()
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "GPG failed ($($process.ExitCode)): $output" }
        return $output.Split([char]"`n", [StringSplitOptions]::RemoveEmptyEntries)
    } finally { $process.Dispose() }
}

function Gpg-Home {
    if ($script:gpg -match '[\\/]usr[\\/]bin[\\/]gpg\.exe$') {
        return '/' + $keyHome.Substring(0, 1).ToLowerInvariant() + $keyHome.Substring(2).Replace('\', '/')
    }
    return $keyHome
}

function Unprotect([Security.SecureString]$Secret) {
    return [Net.NetworkCredential]::new('', $Secret).Password
}

if ($Action -eq 'SaveToken') {
    New-Item -ItemType Directory -Path $configDir -Force | Out-Null
    $username = Read-Host 'Central User Token username (not your login name)'
    $password = Read-Host 'Central User Token password' -AsSecureString
    if ([string]::IsNullOrWhiteSpace($username) -or $password.Length -eq 0) { throw 'Token values cannot be empty.' }
    [Management.Automation.PSCredential]::new($username, $password) | Export-Clixml -LiteralPath $tokenFile
    Write-Host 'Token saved outside the repository, encrypted for this Windows user.'
    return
}

if ($Action -eq 'PrepareSigning') {
    $gpgCommand = Get-Command gpg.exe -ErrorAction SilentlyContinue
    $script:gpg = if ($gpgCommand) { $gpgCommand.Source } else { 'C:\Program Files\Git\usr\bin\gpg.exe' }
    if (!(Test-Path -LiteralPath $script:gpg)) { throw 'GPG is required.' }
    $gpgHome = Gpg-Home
    New-Item -ItemType Directory -Path $keyHome -Force | Out-Null
    if (!(Test-Path -LiteralPath $passwordFile)) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force |
            Export-Clixml -LiteralPath $passwordFile
    }
    $passphrase = Unprotect (Import-Clixml -LiteralPath $passwordFile)
    if (Test-Path -LiteralPath $identityFile) {
        $identity = Get-Content -LiteralPath $identityFile -Raw | ConvertFrom-Json
        $fingerprint = $identity.fingerprint
    } else {
        $listing = Invoke-Gpg -GpgArgs @('--homedir', $gpgHome, '--batch', '--with-colons', '--list-secret-keys')
        $fingerprints = @($listing | Where-Object { "$_" -like 'fpr:*' })
        if ($fingerprints.Count -eq 0) {
            Invoke-Gpg -GpgArgs @('--homedir', $gpgHome, '--batch', '--pinentry-mode', 'loopback',
                '--passphrase-fd', '0', '--quick-generate-key', 'RWPP Team <1225327866@qq.com>', 'rsa4096', 'sign', '2y') -InputText $passphrase | Out-Null
            $listing = Invoke-Gpg -GpgArgs @('--homedir', $gpgHome, '--batch', '--with-colons', '--list-secret-keys')
            $fingerprints = @($listing | Where-Object { "$_" -like 'fpr:*' })
        }
        if ($fingerprints.Count -ne 1) { throw 'Expected one dedicated signing key.' }
        $fingerprint = ("$($fingerprints[0])" -split ':')[9]
        @{ fingerprint = $fingerprint; executable = $script:gpg } | ConvertTo-Json |
            Set-Content -LiteralPath $identityFile -Encoding UTF8
    }
    Invoke-Gpg -GpgArgs @('--homedir', $gpgHome, '--batch', '--yes', '--armor', '--output',
        (Join-Path $configDir 'public-key.asc'), '--export', $fingerprint) | Out-Null
    Invoke-Gpg -GpgArgs @('--homedir', $gpgHome, '--batch', '--yes', '--pinentry-mode', 'loopback',
        '--passphrase-fd', '0', '--armor', '--output', (Join-Path $configDir 'private-key-backup.asc'),
        '--export-secret-keys', $fingerprint) -InputText $passphrase | Out-Null
    $passphrase = $null
    Write-Host "Signing key: $fingerprint"
    Write-Host "Protected key and password stored in $configDir"
    Invoke-Gpg -GpgArgs @('--homedir', $gpgHome, '--batch', '--keyserver',
        'hkps://keyserver.ubuntu.com', '--send-keys', $fingerprint) | Out-Null
    Write-Host 'Public key submitted to keyserver.ubuntu.com.'
    return
}

if ($Action -eq 'Build' -or ($Action -eq 'Publish' -and !(Test-Path -LiteralPath $deploymentFile))) {
    if (!(Test-Path -LiteralPath $identityFile)) { throw 'Run -Action PrepareSigning first.' }
    $identity = Get-Content -LiteralPath $identityFile -Raw | ConvertFrom-Json
    $script:gpg = $identity.executable
    $gpgHome = Gpg-Home
    $properties = @{
        GNUPGHOME = $gpgHome
        GPG_PRIVATE_KEY = ''
        'ORG_GRADLE_PROJECT_signing.gnupg.executable' = $script:gpg
        'ORG_GRADLE_PROJECT_signing.gnupg.keyName' = $identity.fingerprint
        'ORG_GRADLE_PROJECT_signing.gnupg.passphrase' = (Unprotect (Import-Clixml -LiteralPath $passwordFile))
    }
    $previous = @{}
    foreach ($key in $properties.Keys) {
        $variable = $key
        $previous[$variable] = [Environment]::GetEnvironmentVariable($variable, 'Process')
        [Environment]::SetEnvironmentVariable($variable, $properties[$key], 'Process')
    }
    Push-Location $repo
    try {
        $gradleArgs = @($bundleTask, '--console=plain', '--no-daemon')
        if ($Offline) { $gradleArgs += '--offline' }
        & (Join-Path $repo 'gradlew.bat') @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Central bundle build failed.' }
    } finally {
        Pop-Location
        foreach ($variable in $previous.Keys) { [Environment]::SetEnvironmentVariable($variable, $previous[$variable], 'Process') }
        $properties.Clear()
    }
    # Verify the exact ZIP payload, not files left over in the staging repository.
    & python (Join-Path $PSScriptRoot 'verify-central-bundle.py') $bundle --gpg $script:gpg --homedir $gpgHome --coordinates $coordinates
    if ($LASTEXITCODE -ne 0) { throw 'Central bundle validation failed.' }
    if ($Action -eq 'Build') { return }
}

if (!(Test-Path -LiteralPath $tokenFile)) { throw 'Run -Action SaveToken first.' }
$credential = Import-Clixml -LiteralPath $tokenFile
# Pass credentials only through stdin; Python uses the TLS path verified on this machine.
$inputJson = @{
    username = $credential.UserName
    password = $credential.GetNetworkCredential().Password
} | ConvertTo-Json -Compress
try {
    $inputJson | & python (Join-Path $PSScriptRoot 'central-portal.py') $Action $bundle $deploymentFile $coordinates
    if ($LASTEXITCODE -ne 0) { throw 'Maven Central publishing failed; see the response above.' }
} finally { $credential = $null; $inputJson = $null }
