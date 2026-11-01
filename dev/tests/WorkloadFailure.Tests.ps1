#Requires -Version 7.4
BeforeAll {
   $script:entrypoint = Join-Path $PSScriptRoot '../Invoke-Dev.ps1'
   function docker { throw 'Unmocked Docker call refused.' }
}

Describe 'Early workload client failure' {
   BeforeEach {
      $script:state = Join-Path $TestDrive ([Guid]::NewGuid().ToString('N'))
      @{success = $false; stage = 'native_login_and_channels'; error = 'QqqSessionException'; reason = 'secret-value https://private.invalid/?token=secret'; completed = 0} | ConvertTo-Json | Set-Content (Join-Path $TestDrive 'client-fixture.json')
      Mock docker {
         $global:LASTEXITCODE = 0
         if ($args[0] -eq 'compose')
         {
            if ($args -contains 'ps')
            {
               if ($args[-1] -eq 'test-client') { return ('c' * 64) }
               if ($args[-1] -eq 'test-server') { return ('b' * 64) }
            }
            return
         }
         if ($args[0] -eq 'inspect')
         {
            if ($args[1] -eq ('c' * 64)) { return 'false|17' }
            throw 'SERVER_WAIT_REACHED'
         }
         if ($args[0] -eq 'cp')
         {
            $fixtureRoot = [IO.Directory]::GetParent([IO.Directory]::GetParent([IO.Directory]::GetParent($args[-1]).FullName).FullName).FullName
            Copy-Item -LiteralPath (Join-Path $fixtureRoot 'client-fixture.json') -Destination $args[-1]
            return
         }
         if ($args[0] -in @('ps', 'network', 'volume')) { return }
         throw 'Unexpected fake Docker operation.'
      }
   }
   It 'surfaces the client failure before server waiting and still cleans owned containers' {
      { & $entrypoint -Action Test -StateDirectory $script:state } | Should -Throw '*Workload client failed at native_login_and_channels (QqqSessionException)*'
      Should -Invoke docker -ParameterFilter { $args[0] -eq 'inspect' -and $args[1] -eq ('b' * 64) } -Times 0
      Should -Invoke docker -ParameterFilter { $args[0] -eq 'compose' -and $args -contains 'rm' -and $args -contains '--stop' } -Times 1 -Exactly
      $result = Get-ChildItem -LiteralPath $script:state -Filter result.json -Recurse
      (Get-Content -LiteralPath $result.FullName -Raw | ConvertFrom-Json).reason | Should -Be 'secret-value https://private.invalid/?token=secret'
   }
   It 'does not print unrecognized stage or exception text' {
      @{success = $false; stage = 'secret-stage https://private.invalid/'; error = 'secret-error'} | ConvertTo-Json | Set-Content (Join-Path $TestDrive 'client-fixture.json')
      { & $entrypoint -Action Test -StateDirectory $script:state } | Should -Throw '*Workload client failed at unknown (UnknownFailure)*'
      Should -Invoke docker -ParameterFilter { $args[0] -eq 'inspect' -and $args[1] -eq ('b' * 64) } -Times 0
   }
   It 'fails safely for <Kind> diagnostics and still cleans owned containers' -TestCases @(
      @{Kind = 'malformed'; Content = '{secret-malformed-json'},
      @{Kind = 'null'; Content = 'null'},
      @{Kind = 'oversized'; Content = (@{success = $false; stage = 'input'; error = 'JsonException'; reason = ('s' * 65536)} | ConvertTo-Json -Compress)}
   ) {
      param($Content)
      $Content | Set-Content (Join-Path $TestDrive 'client-fixture.json')
      { & $entrypoint -Action Test -StateDirectory $script:state } | Should -Throw '*Workload client failed at unknown (UnknownFailure)*'
      Should -Invoke docker -ParameterFilter { $args[0] -eq 'inspect' -and $args[1] -eq ('b' * 64) } -Times 0
      Should -Invoke docker -ParameterFilter { $args[0] -eq 'compose' -and $args -contains 'rm' -and $args -contains '--stop' } -Times 1 -Exactly
   }
}
