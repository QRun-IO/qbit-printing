#Requires -Version 7.4
# Copyright 2026 Kingsrook, LLC
# SPDX-License-Identifier: Apache-2.0

# Ownership regressions use mocked Docker metadata; no containers or network calls.
BeforeAll {
   $script:entrypoint = Join-Path $PSScriptRoot '../Invoke-Dev.ps1'
   $tokens = $null
   $errors = $null
   $ast = [Management.Automation.Language.Parser]::ParseFile($entrypoint, [ref]$tokens, [ref]$errors)
   if ($errors.Count)
   {
      throw 'Entrypoint parsing failed.'
   }
   foreach ($name in @('Assert-OwnedResource', 'Get-RecordedAuthentikPort'))
   {
      $guard = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name }, $true)
      . ([scriptblock]::Create($guard.Extent.Text))
   }
   $script:project = 'qbit-printing-dev-0123456789abcdef0123456789abcdef'
   function docker
   {
      throw 'Unmocked Docker call refused.'
   }
}

Describe 'Development lifecycle ownership' {
   BeforeEach {
      $script:wrongResource = ''
      Mock docker {
         $global:LASTEXITCODE = 0
         $command = $args -join ' '
         if ($command.StartsWith('ps -aq', [StringComparison]::Ordinal))
         {
            return 'container-id'
         }
         if ($command.StartsWith('network ls', [StringComparison]::Ordinal))
         {
            return 'network-id'
         }
         if ($command.StartsWith('volume ls', [StringComparison]::Ordinal))
         {
            return 'volume-id'
         }
         if ($script:wrongResource -and $command.Contains($script:wrongResource, [StringComparison]::Ordinal))
         {
            return 'another-project'
         }
         return $script:project
      }
   }
   It 'accepts only consistently labeled containers, networks and volumes' {
      { Assert-OwnedResource } | Should -Not -Throw
      Should -Invoke docker -Times 6 -Exactly
   }
   It 'rejects a foreign container before any cleanup' {
      $script:wrongResource = 'container-id'
      { Assert-OwnedResource } | Should -Throw '*Container ownership mismatch*'
      Should -Invoke docker -ParameterFilter { ($args -join ' ') -match 'down|remove|prune' } -Times 0
   }
   It 'rejects a foreign network before any cleanup' {
      $script:wrongResource = 'network-id'
      { Assert-OwnedResource } | Should -Throw '*Network ownership mismatch*'
      Should -Invoke docker -ParameterFilter { ($args -join ' ') -match 'down|remove|prune' } -Times 0
   }
   It 'rejects a foreign volume before any cleanup' {
      $script:wrongResource = 'volume-id'
      { Assert-OwnedResource } | Should -Throw '*Volume ownership mismatch*'
      Should -Invoke docker -ParameterFilter { ($args -join ' ') -match 'down|remove|prune' } -Times 0
   }
   It 'refuses an existing unowned state directory before contacting Docker' {
      { & $entrypoint -Action Stop -StateDirectory $TestDrive } | Should -Throw '*lacks this tool ownership record*'
      Should -Invoke docker -Times 0
   }
   It 'rejects persistent experimental startup before any state creation' {
      $path = Join-Path $TestDrive 'experimental-start'
      { & $entrypoint -Action Start -ExperimentalNativeAuth -StateDirectory $path } | Should -Throw '*Test-only profile*'
      Test-Path -LiteralPath $path | Should -BeFalse
      Should -Invoke docker -Times 0
   }
   It 'does not repurpose a saved standard environment for experimental authentication' {
      $path = Join-Path $TestDrive 'standard-environment'
      [IO.Directory]::CreateDirectory($path) | Out-Null
      @{sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')); project = $project; authentik = $false } |
         ConvertTo-Json | Set-Content (Join-Path $path 'owner.json')
      { & $entrypoint -Action Test -ExperimentalNativeAuth -StateDirectory $path } | Should -Throw '*cannot be repurposed*'
      Should -Invoke docker -Times 0
   }
   It 'refuses a forged project name before contacting Docker' {
      $path = Join-Path $TestDrive 'forged'
      [IO.Directory]::CreateDirectory($path) | Out-Null
      @{sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')); project = 'unrelated'; authentik = $false } | ConvertTo-Json | Set-Content (Join-Path $path 'owner.json')
      { & $entrypoint -Action Stop -StateDirectory $path } | Should -Throw '*State ownership mismatch*'
      Should -Invoke docker -Times 0
   }
   It 'restores a nondefault Authentik port from saved state' {
      $script:envFile = Join-Path $TestDrive 'ports.env'
      [IO.File]::WriteAllText($envFile, "QQQ_HTTP_PORT=19081`nAUTHENTIK_HTTP_PORT=19011`n")
      Get-RecordedAuthentikPort | Should -Be 19011
   }
   It 'rejects ambiguous saved port configuration' {
      $script:envFile = Join-Path $TestDrive 'ambiguous.env'
      [IO.File]::WriteAllText($envFile, "AUTHENTIK_HTTP_PORT=19010`nAUTHENTIK_HTTP_PORT=19011`n")
      { Get-RecordedAuthentikPort } | Should -Throw '*missing or invalid*'
   }
   It 'rejects a state ancestor junction or symlink before writing or contacting Docker' {
      $target = Join-Path $TestDrive 'target'
      $link = Join-Path $TestDrive 'linked'
      [IO.Directory]::CreateDirectory($target) | Out-Null
      if ($IsWindows)
      {
         New-Item -ItemType Junction -Path $link -Target $target -ErrorAction Stop | Out-Null
      }
      else
      {
         New-Item -ItemType SymbolicLink -Path $link -Target $target -ErrorAction Stop | Out-Null
      }
      try
      {
         { & $entrypoint -Action Start -StateDirectory (Join-Path $link 'new-state') } | Should -Throw '*symbolic link or reparse point*'
         Test-Path -LiteralPath (Join-Path $target 'new-state') | Should -BeFalse
         Should -Invoke docker -Times 0
      }
      finally
      {
         [IO.Directory]::Delete($link)
      }
   }
}
