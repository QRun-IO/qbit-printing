#Requires -Version 7.4
<#
.SYNOPSIS
Starts, tests, or removes one owned local Docker printing environment.
.DESCRIPTION
Uses synthetic accounts and documents. Never imports installed device credentials,
contacts printers, changes host trust, or prunes shared Docker resources.
#>
[CmdletBinding()]
param(
   [ValidateSet('Start', 'Test', 'Stop')][string]$Action = 'Test',
   [string]$StateDirectory,
   [switch]$Authentik,
   [switch]$ExperimentalNativeAuth,
   [ValidateSet(40, 10000)][int]$Workload = 40,
   [ValidateRange(1024, 65535)][int]$QqqPort = 19081,
   [ValidateRange(1024, 65535)][int]$MinioPort = 19095,
   [ValidateRange(1024, 65535)][int]$AuthentikPort = 19010
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$ephemeral = $Action -eq 'Test' -and [string]::IsNullOrWhiteSpace($StateDirectory)
if ($ExperimentalNativeAuth)
{
   if ($Action -ne 'Test')
   {
      throw 'Experimental native authentication is a disposable Test-only profile.'
   }
   $Authentik = $true
}

function Assert-StateLocation([string]$Path)
{
   <# .SYNOPSIS Rejects links before any private state is read or written. #>
   $ancestor = [IO.DirectoryInfo]::new($Path)
   while ($null -ne $ancestor)
   {
      if (![string]::IsNullOrEmpty($ancestor.LinkTarget) -or ($ancestor.Exists -and ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0))
      {
         throw 'Development state cannot use a symbolic link or reparse point.'
      }
      $ancestor = $ancestor.Parent
   }
   foreach ($name in @('owner.json', 'compose.env', 'compose.yaml', 'compose.authentik.yaml', 'compose.native-auth.yaml', 'auth-secrets.json', 'qqq-result.json', 'auth-result.json', 'native-input.json', 'native-auth-result.json', 'native-auth-server-errors.json'))
   {
      $file = [IO.FileInfo]::new((Join-Path $Path $name))
      if (![string]::IsNullOrEmpty($file.LinkTarget) -or ($file.Exists -and ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0))
      {
         throw 'Development state files cannot use symbolic links or reparse points.'
      }
   }
}
function Get-RecordedAuthentikPort
{
   <# .SYNOPSIS Restores the existing environment port without reading it from caller defaults. #>
   $lines = @(Get-Content -LiteralPath $script:envFile | Where-Object { $_.StartsWith('AUTHENTIK_HTTP_PORT=', [StringComparison]::Ordinal) })
   if ($lines.Count -ne 1 -or $lines[0] -cnotmatch '^AUTHENTIK_HTTP_PORT=([0-9]{4,5})$')
   {
      throw 'Recorded Authentik port is missing or invalid.'
   }
   $port = [int]$Matches[1]
   if ($port -lt 1024 -or $port -gt 65535)
   {
      throw 'Recorded Authentik port is outside the supported range.'
   }
   return $port
}
function Get-FixtureSecret
{
   <# .SYNOPSIS Generates only disposable local credentials. #>
   return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
}
function Protect-State([string]$Path)
{
   <# .SYNOPSIS Restricts the generated state to its user and local administrators. #>
   if ($IsWindows)
   {
      $acl = [Security.AccessControl.DirectorySecurity]::new()
      $acl.SetAccessRuleProtection($true, $false)
      foreach ($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User,
            [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')))
      {
         $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
      }
      Set-Acl -LiteralPath $Path -AclObject $acl
   }
   else
   {
      [IO.File]::SetUnixFileMode($Path, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
   }
}
function Set-FixtureEnvironment
{
   [CmdletBinding(SupportsShouldProcess)]
   param([string]$Name, [string]$Value)
   <# .SYNOPSIS Updates one safe dotenv field without displaying secret values. #>
   if ($Name -cnotmatch '^[A-Z_]+$' -or $Value -match '[\r\n]')
   {
      throw 'Unsafe generated environment field.'
   }
   if (!$PSCmdlet.ShouldProcess($Name, 'Write protected fixture setting'))
   {
      return
   }
   $lines = @()
   if (Test-Path -LiteralPath $script:envFile)
   {
      $lines = @(Get-Content -LiteralPath $script:envFile | Where-Object { !$_.StartsWith($Name + '=', [StringComparison]::Ordinal) })
   }
   [IO.File]::WriteAllLines($script:envFile, @($lines) + @($Name + '=' + $Value))
}
function Invoke-Compose([string[]]$Arguments)
{
   <# .SYNOPSIS Runs only this recorded Compose project. #>
   & docker compose @script:composeArguments @Arguments
   if ($LASTEXITCODE -ne 0)
   {
      throw 'Owned Docker Compose operation failed; inspect local command output.'
   }
}
function Assert-OwnedResource
{
   <# .SYNOPSIS Refuses cleanup if any project resource lacks the ownership label. #>
   $ids = @(& docker ps -aq --filter "label=com.docker.compose.project=$script:project")
   if ($LASTEXITCODE -ne 0)
   {
      throw 'Cannot inspect owned Docker containers.'
   }
   foreach ($id in $ids)
   {
      $label = & docker inspect $id --format '{{index .Config.Labels "io.qrun.printing.dev"}}'
      if ($LASTEXITCODE -ne 0 -or $label -cne $script:project)
      {
         throw 'Container ownership mismatch; cleanup refused.'
      }
   }
   $networks = @(& docker network ls -q --filter "label=com.docker.compose.project=$script:project")
   if ($LASTEXITCODE -ne 0)
   {
      throw 'Cannot inspect owned Docker networks.'
   }
   foreach ($network in $networks)
   {
      $label = & docker network inspect $network --format '{{index .Labels "io.qrun.printing.dev"}}'
      if ($LASTEXITCODE -ne 0 -or $label -cne $script:project)
      {
         throw 'Network ownership mismatch; cleanup refused.'
      }
   }
   $volumes = @(& docker volume ls -q --filter "label=com.docker.compose.project=$script:project")
   if ($LASTEXITCODE -ne 0)
   {
      throw 'Cannot inspect owned Docker volumes.'
   }
   foreach ($volume in $volumes)
   {
      $label = & docker volume inspect $volume --format '{{index .Labels "io.qrun.printing.dev"}}'
      if ($LASTEXITCODE -ne 0 -or $label -cne $script:project)
      {
         throw 'Volume ownership mismatch; cleanup refused.'
      }
   }
}
function Stop-OwnedEnvironment
{
   [CmdletBinding(SupportsShouldProcess)]
   param()
   <# .SYNOPSIS Removes only this environment's containers, network and synthetic volumes. #>
   if (!$PSCmdlet.ShouldProcess($script:project, 'Remove this owned development environment and its synthetic data'))
   {
      return
   }
   Assert-OwnedResource
   Invoke-Compose @('--profile', 'test', '--profile', 'auth', '--profile', 'native-auth', 'down', '--remove-orphans', '--volumes', '--timeout', '30')
}
function ConvertTo-ProviderErrorSummary([string[]]$Lines)
{
   <# .SYNOPSIS Retains only exception types and bounded source-frame coordinates. #>
   $types = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
   $frames = [Collections.Generic.List[object]]::new()
   $warnings = @{invalidClientIdentifier = $false; nonApplicableAuthenticationFlow = $false; failedToResolveApplication = $false}
   foreach ($line in ($Lines | Select-Object -Last 1000))
   {
      if ($line.Length -gt 65536) { continue }
      $warnings.invalidClientIdentifier = $warnings.invalidClientIdentifier -or $line.Contains('Invalid client identifier', [StringComparison]::Ordinal)
      $warnings.nonApplicableAuthenticationFlow = $warnings.nonApplicableAuthenticationFlow -or $line.Contains('Non-applicable authentication flow', [StringComparison]::Ordinal)
      $warnings.failedToResolveApplication = $warnings.failedToResolveApplication -or $line.Contains('failed to resolve application', [StringComparison]::Ordinal)
      try { $record = ConvertFrom-Json -InputObject $line -AsHashtable -ErrorAction Stop }
      catch { continue }
      if ($record -isnot [Collections.IDictionary]) { continue }
      foreach ($exception in @($record['exception']))
      {
         if ($exception -isnot [Collections.IDictionary]) { continue }
         $type = [string]$exception['exc_type']
         if ($types.Count -lt 16 -and ($type -cmatch '^[A-Za-z_][A-Za-z0-9_.]{0,119}(Error|Exception)$' -or $type -cin @('PermissionDenied', 'NotAuthenticated', 'AuthenticationFailed', 'Throttled'))) { [void]$types.Add($type) }
         foreach ($frame in @($exception['frames']))
         {
            if ($frames.Count -ge 12 -or $frame -isnot [Collections.IDictionary]) { continue }
            $filename = [string]$frame['filename']
            $function = [string]$frame['name']
            $lineNumber = 0
            if ($filename.Length -le 300 -and $filename -cmatch '^/authentik/[A-Za-z0-9_./-]+\.py$' -and
               $function -cmatch '^[A-Za-z0-9_<>.]{1,120}$' -and
               [int]::TryParse([string]$frame['lineno'], [ref]$lineNumber) -and $lineNumber -gt 0)
            {
               $frames.Add(@{filename = $filename; line = $lineNumber; function = $function})
            }
         }
      }
   }
   return @{exceptionTypes = @($types | Sort-Object); frames = @($frames.ToArray()); warnings = $warnings}
}
function Save-ProviderErrorSummary
{
   <# .SYNOPSIS Reads bounded logs only from the exact owned synthetic provider. #>
   $ids = @(& docker ps -aq --filter "label=com.docker.compose.project=$script:project" --filter 'label=com.docker.compose.service=authentik-server')
   if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -cnotmatch '^[a-f0-9]{12,64}$') { return }
   $label = & docker inspect $ids[0] --format '{{index .Config.Labels "io.qrun.printing.dev"}}'
   if ($LASTEXITCODE -ne 0 -or $label -cne $script:project) { throw 'Provider diagnostic ownership mismatch.' }
   $lines = @(& docker logs --tail 1000 $ids[0] 2>&1 | ForEach-Object { $_.ToString() })
   if ($LASTEXITCODE -ne 0) { return }
   $summary = ConvertTo-ProviderErrorSummary $lines
   $lines = $null
   Assert-StateLocation $StateDirectory
   $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $StateDirectory 'native-auth-server-errors.json')
}
function Wait-OwnedContainer([string]$Id, [int]$Seconds)
{
   <# .SYNOPSIS Observes bounded completion without accessing application data. #>
   $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
   do
   {
      $state = & docker inspect $Id --format '{{.State.Running}}|{{.State.ExitCode}}'
      if ($LASTEXITCODE -ne 0)
      {
         throw 'Owned fixture container disappeared.'
      }
      if ($state.StartsWith('false|', [StringComparison]::Ordinal))
      {
         return [int]$state.Split('|')[1]
      }
      Start-Sleep -Seconds 1
   } while ([DateTime]::UtcNow -lt $deadline)
   throw 'Bounded fixture deadline expired; owned resources will be cleaned.'
}

if ([string]::IsNullOrWhiteSpace($StateDirectory))
{
   if ($Action -eq 'Stop')
   {
      throw 'Stop requires the state directory printed by Start.'
   }
   $base = if ($IsWindows)
   {
      Join-Path $env:LOCALAPPDATA 'QQQ/PrintingDevelopment'
   }
   else
   {
      Join-Path $HOME '.local/state/qqq-printing-development'
   }
   $StateDirectory = Join-Path $base ('environment-' + [Guid]::NewGuid().ToString('N'))
}
$StateDirectory = [IO.Path]::GetFullPath($StateDirectory)
if ($StateDirectory.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))
{
   throw 'Keep secret state outside the repository.'
}
Assert-StateLocation $StateDirectory
$ownerFile = Join-Path $StateDirectory 'owner.json'
$script:envFile = Join-Path $StateDirectory 'compose.env'
$createdState = $false
if (Test-Path -LiteralPath $StateDirectory)
{
   if (!(Test-Path -LiteralPath $ownerFile))
   {
      throw 'Existing state directory lacks this tool ownership record.'
   }
   $owner = Get-Content -LiteralPath $ownerFile -Raw | ConvertFrom-Json
   if ($owner.sourceRoot -cne $sourceRoot -or $owner.project -cnotmatch '^qbit-printing-dev-[a-f0-9]{32}$')
   {
      throw 'State ownership mismatch.'
   }
   $script:project = $owner.project
   $Authentik = [bool]$owner.authentik
   $savedNativeProfile = $null -ne $owner.PSObject.Properties['experimentalNativeAuth'] -and [bool]$owner.experimentalNativeAuth
   if ($ExperimentalNativeAuth -and !$savedNativeProfile)
   {
      throw 'An existing standard environment cannot be repurposed for experimental native authentication.'
   }
   $ExperimentalNativeAuth = $savedNativeProfile
   if ($ExperimentalNativeAuth -and $Action -eq 'Start')
   {
      throw 'Experimental native authentication is a disposable Test-only profile.'
   }
}
else
{
   if ($Action -eq 'Stop')
   {
      throw 'Owned state directory does not exist.'
   }
   [IO.Directory]::CreateDirectory($StateDirectory) | Out-Null
   Protect-State $StateDirectory
   $createdState = $true
   $script:project = 'qbit-printing-dev-' + [Guid]::NewGuid().ToString('N')
   $commit = (& git -C $sourceRoot rev-parse HEAD).Trim()
   if ($LASTEXITCODE -ne 0 -or $commit -cnotmatch '^[a-f0-9]{40}$')
   {
      throw 'Cannot record source identity.'
   }
   foreach ($field in @{PRINTING_DEV_PROJECT = $project; DEV_SOURCE_DIR = $sourceRoot.Replace('\', '/'); SOURCE_COMMIT = $commit; QQQ_HTTP_PORT = "$QqqPort"; MINIO_HTTP_PORT = "$MinioPort"; MINIO_ROOT_USER = 'printing-development'; MINIO_ROOT_PASSWORD = (Get-FixtureSecret); QQQ_DATABASE_PASSWORD = (Get-FixtureSecret); QQQ_ADMIN_PASSWORD = (Get-FixtureSecret); QQQ_MANAGEMENT_TOKEN = (Get-FixtureSecret) }.GetEnumerator())
   {
      Set-FixtureEnvironment $field.Key $field.Value
   }
   Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'compose.yaml') -Destination (Join-Path $StateDirectory 'compose.yaml')
   if ($Authentik)
   {
      & (Join-Path $PSScriptRoot 'Invoke-Authentik.ps1') -Action Initialize -StateDirectory $StateDirectory -ProjectName $project -Port $AuthentikPort
      Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'compose.authentik.yaml') -Destination (Join-Path $StateDirectory 'compose.authentik.yaml')
   }
   if ($ExperimentalNativeAuth)
   {
      & (Join-Path $PSScriptRoot 'Invoke-NativeAuth.ps1') -Action Initialize -StateDirectory $StateDirectory -ProjectName $project
      Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'compose.native-auth.yaml') -Destination (Join-Path $StateDirectory 'compose.native-auth.yaml')
   }
   @{project = $project; sourceRoot = $sourceRoot; sourceCommit = $commit; authentik = [bool]$Authentik; experimentalNativeAuth = [bool]$ExperimentalNativeAuth; createdAt = [DateTime]::UtcNow.ToString('O') } | ConvertTo-Json | Set-Content -LiteralPath $ownerFile
}
$script:composeArguments = @('--project-name', $project, '--env-file', $envFile, '-f', (Join-Path $StateDirectory 'compose.yaml'))
if ($Authentik)
{
   $script:composeArguments += @('-f', (Join-Path $StateDirectory 'compose.authentik.yaml'))
}
if ($ExperimentalNativeAuth)
{
   $script:composeArguments += @('-f', (Join-Path $StateDirectory 'compose.native-auth.yaml'))
}
if ($Action -eq 'Stop')
{
   Stop-OwnedEnvironment
   Write-Output "Stopped owned development environment. Reports remain in $StateDirectory"
   return
}

if ($ExperimentalNativeAuth)
{
   $nativePassed = $false
   try
   {
      Invoke-Compose @('--profile', 'auth', '--profile', 'native-auth', 'build', 'native-qqq', 'native-client')
      Invoke-Compose @('--profile', 'auth', '--profile', 'native-auth', 'up', '-d', 'authentik-server', 'authentik-worker', 'native-tls')
      Invoke-Compose @('exec', '-T', 'native-tls', '/bin/sh', '/etc/caddy/copy-root.sh')
      Invoke-Compose @('--profile', 'auth', '--profile', 'native-auth', 'up', '-d', 'native-qqq')
      Invoke-Compose @('--profile', 'auth', '--profile', 'native-auth', 'run', '--rm', '--use-aliases', 'native-client')
      & (Join-Path $PSScriptRoot 'Invoke-NativeAuth.ps1') -Action VerifyResult -StateDirectory $StateDirectory -ProjectName $project
      $nativePassed = $true
   }
   finally
   {
      try
      {
         if (!$nativePassed) { Save-ProviderErrorSummary }
      }
      finally { Stop-OwnedEnvironment }
      Write-Output "Protected experimental native-auth result: $(Join-Path $StateDirectory 'native-auth-result.json')"
   }
   return
}

if ($Action -eq 'Start')
{
   $services = @('minio', 'qqq')
   $profiles = @()
   if ($Authentik)
   {
      $profiles = @('--profile', 'auth'); $services += @('authentik-server', 'authentik-worker')
   }
   try
   {
      Invoke-Compose ($profiles + @('up', '-d', '--build') + $services); & (Join-Path $PSScriptRoot 'Test-DevelopmentHost.ps1') -StateDirectory $StateDirectory
   }
   catch
   {
      if ($createdState)
      {
         Stop-OwnedEnvironment
      }; throw
   }
   Write-Output "Owned environment: $StateDirectory"
   Write-Output 'Read QQQ_ADMIN_PASSWORD in its protected compose.env; username is developer. No password is logged.'
   return
}

$testRun = 'run-' + [Guid]::NewGuid().ToString('N')
Set-FixtureEnvironment 'TEST_RUN' $testRun
Set-FixtureEnvironment 'WORKLOAD_MODE' $(if ($Workload -eq 10000)
   {
      'burst'
   }
   else
   {
      'idle'
   })
$reports = Join-Path $StateDirectory $testRun
[IO.Directory]::CreateDirectory($reports) | Out-Null
$passed = $false
try
{
   if ($Authentik)
   {
      Invoke-Compose @('--profile', 'auth', 'up', '-d', '--build', 'qqq', 'authentik-server', 'authentik-worker')
      & (Join-Path $PSScriptRoot 'Test-DevelopmentHost.ps1') -StateDirectory $StateDirectory
      & (Join-Path $PSScriptRoot 'Invoke-Authentik.ps1') -Action Test -StateDirectory $StateDirectory -ProjectName $project -Port (Get-RecordedAuthentikPort)
   }
   Invoke-Compose @('--profile', 'test', 'up', '-d', '--build', 'test-client')
   $client = (& docker compose @composeArguments ps -a -q test-client).Trim()
   $server = (& docker compose @composeArguments ps -a -q test-server).Trim()
   if ($client -cnotmatch '^[a-f0-9]{64}$' -or $server -cnotmatch '^[a-f0-9]{64}$')
   {
      throw 'Owned fixture identity missing.'
   }
   $clientExit = Wait-OwnedContainer $client 1200
   $serverExit = Wait-OwnedContainer $server 45
   foreach ($file in @('result.json', 'server-result.json', 'stopped.json', 's3/results.json', 's3/completed-checks.json'))
   {
      $destination = Join-Path $reports $file.Replace('/', '-')
      & docker cp "${server}:/work/$testRun/$file" $destination
      if ($LASTEXITCODE -ne 0)
      {
         throw 'Required sanitized fixture result missing.'
      }
   }
   if ($clientExit -ne 0 -or $serverExit -ne 0)
   {
      throw 'Fixture failed; see protected results.'
   }
   $clientResult = Get-Content (Join-Path $reports 'result.json') -Raw | ConvertFrom-Json
   $serverResult = Get-Content (Join-Path $reports 'server-result.json') -Raw | ConvertFrom-Json
   $s3Result = Get-Content (Join-Path $reports 's3-results.json') -Raw | ConvertFrom-Json
   if (!$clientResult.success -or !$serverResult.success -or !$s3Result.passed -or $clientResult.jobs -ne $Workload -or @($s3Result.checks).Count -ne 65)
   {
      throw 'Result invariants failed.'
   }
   $passed = $true
}
finally
{
   & docker compose @composeArguments logs --no-color --no-log-prefix test-server test-client 2>&1 | Set-Content -LiteralPath (Join-Path $reports 'fixture.log')
   if ($ephemeral)
   {
      Stop-OwnedEnvironment
   }
   else
   {
      Assert-OwnedResource
      Invoke-Compose @('--profile', 'test', 'rm', '--stop', '--force', 'test-client', 'test-server', 'minio-test')
   }
   @{passed = $passed; workload = $Workload; physicalPrinting = $false; tlsQualified = $false; finishedAt = [DateTime]::UtcNow.ToString('O') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reports 'execution.json')
   Write-Output "Protected developer-test results: $reports"
}
if (!$passed)
{
   throw 'Development qualification did not pass.'
}
