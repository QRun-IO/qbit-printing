#Requires -Version 7.4
# Copyright 2026 Kingsrook, LLC
# SPDX-License-Identifier: Apache-2.0

<#
.SYNOPSIS
Prepares or verifies the opt-in, unpublished native-session Docker qualification.
.DESCRIPTION
Docker lifecycle belongs to Invoke-Dev.ps1. This helper creates only protected
synthetic input and reads a bounded sanitized report. It never installs trust,
uses host Maven caches, starts a browser, or operates the installed connector.
#>
[CmdletBinding()]
param(
   [Parameter(Mandatory)][ValidateSet('Initialize', 'VerifyResult')][string] $Action,
   [Parameter(Mandatory)][string] $StateDirectory,
   [Parameter(Mandatory)][ValidatePattern('^qbit-printing-dev-[a-z0-9-]{8,48}$')][string] $ProjectName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$coreSource = 'ecf0f550a0357cec6387c2b54efca128a405a629'
$clientSource = '346fc285198908c47a2ee9db7b9adb53d5e88c3c'
$state = [IO.Path]::GetFullPath($StateDirectory)
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$comparison = if($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
if($state.Equals($repository, $comparison) -or $state.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, $comparison))
{
   throw 'Experimental credentials must be outside the checkout.'
}
$envFile = Join-Path $state 'compose.env'
$inputFile = Join-Path $state 'native-input.json'
$resultFile = Join-Path $state 'native-auth-result.json'
if(-not (Test-Path -LiteralPath $envFile -PathType Leaf))
{
   throw 'The common entrypoint must initialize protected owned state first.'
}

if($Action -eq 'VerifyResult')
{
   if(-not (Test-Path -LiteralPath $resultFile -PathType Leaf) -or (Get-Item -LiteralPath $resultFile).Length -gt 65536)
   {
      throw 'The experimental result is missing or exceeds its bounded size.'
   }
   try { $report = Get-Content -LiteralPath $resultFile -Raw | ConvertFrom-Json -AsHashtable }
   catch { throw 'The experimental result is malformed; its contents are withheld.' }
   $required = @('untrusted-tls-refused', 'typed-native-grant', 'real-provider-login', 'wrong-origin-cookie-denied', 'permission-denied-user', 'logout-replay-denied', 'server-refresh-after-expiry', 'provider-revocation-denied')
   if($report.status -cne 'PASS' -or $report.scope -cne 'DEVELOPMENT_ONLY_UNRELEASED' -or
      $report.coreSource -cne $coreSource -or $report.clientSource -cne $clientSource -or
      $report.project -cne $ProjectName -or @($required | Where-Object { $_ -notin $report.checks }).Count -ne 0)
   {
      throw 'The experimental native-session qualification did not pass its exact source and required checks.'
   }
   Write-Output 'Experimental native-session provider checks passed; the candidate is unpublished and no installed/browser-UI acceptance is claimed.'
   return
}

if((Test-Path -LiteralPath $inputFile) -or (Get-Content -LiteralPath $envFile -Raw) -match '(?m)^NATIVE_')
{
   throw 'Experimental native tests require a fresh owned environment; partial state is not reused.'
}
try
{
   $auth = Get-Content -LiteralPath (Join-Path $state 'auth-secrets.json') -Raw | ConvertFrom-Json -AsHashtable
   if($auth.project -cne $ProjectName -or $auth.clientId -cne $ProjectName) { throw 'Wrong owner.' }
}
catch { throw 'Private Authentik bootstrap state is absent or does not match this project.' }
$random = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$deniedPassword = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$issuer = "https://auth.printing.internal/application/o/$ProjectName/"
$inputData = [ordered]@{
   project = $ProjectName
   coreSource = $coreSource
   clientSource = $clientSource
   providerOrigin = 'https://auth.printing.internal'
   qqqOrigin = 'https://qqq.printing.internal'
   issuer = $issuer
   callback = 'https://callback.printing.internal/oauth/callback'
   adminToken = $auth.bootstrapToken
   clientId = $auth.clientId
   clientSecret = $auth.clientSecret
   authorizedUsername = 'printing-authorized'
   authorizedPassword = $auth.userPassword
   deniedUsername = 'printing-denied'
   deniedPassword = $deniedPassword
}
$inputData | ConvertTo-Json | Set-Content -LiteralPath $inputFile -Encoding utf8
@{ status = 'NOT_RUN'; scope = 'DEVELOPMENT_ONLY_UNRELEASED'; project = $ProjectName } |
   ConvertTo-Json | Set-Content -LiteralPath $resultFile -Encoding utf8
$values = [ordered]@{
   NATIVE_QQQ_DATABASE_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
   NATIVE_QQQ_REFRESH_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
   NATIVE_AUTH_ISSUER = $issuer
   NATIVE_AUTH_CLIENT_ID = $auth.clientId
   NATIVE_AUTH_CLIENT_SECRET = $auth.clientSecret
   NATIVE_AUTH_INPUT_FILE = $inputFile.Replace('\', '/')
   NATIVE_AUTH_RESULT_FILE = $resultFile.Replace('\', '/')
}
foreach($entry in $values.GetEnumerator())
{
   if($entry.Value -match '[\r\n'']') { throw 'Unsupported experimental environment value.' }
}
Add-Content -LiteralPath $envFile -Value @($values.GetEnumerator() | ForEach-Object { $_.Key + "='" + $_.Value + "'" }) -Encoding utf8
if(-not $IsWindows)
{
   foreach($file in @($inputFile, $resultFile))
   {
      [IO.File]::SetUnixFileMode($file, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)
   }
}
