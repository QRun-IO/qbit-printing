#Requires -Version 7.4
# No Docker, TLS, provider or QQQ calls; only private input and result validation.
BeforeAll {
   $script:nativeScript = Join-Path $PSScriptRoot '../Invoke-NativeAuth.ps1'
   $script:project = 'qbit-printing-dev-0123456789abcdef0123456789abcdef'
   $script:requiredChecks = @('untrusted-tls-refused', 'typed-native-grant', 'real-provider-login', 'wrong-origin-cookie-denied', 'permission-denied-user', 'logout-replay-denied', 'server-refresh-after-expiry', 'provider-revocation-denied')
}

Describe 'Experimental native provider qualification state' {
   BeforeEach {
      $state = Join-Path $TestDrive ([Guid]::NewGuid().ToString('N'))
      New-Item -ItemType Directory -Path $state | Out-Null
      $envFile = Join-Path $state 'compose.env'
      $prefix = "PRINTING_DEV_PROJECT=$project`nMINIO_ROOT_USER=synthetic`n"
      [IO.File]::WriteAllText($envFile, $prefix)
      @{ project = $project; clientId = $project; bootstrapToken = 'synthetic-admin'; clientSecret = 'synthetic-client'; userPassword = 'synthetic-user' } |
         ConvertTo-Json | Set-Content -LiteralPath (Join-Path $state 'auth-secrets.json')
      $script:report = @{
         status = 'PASS'; scope = 'DEVELOPMENT_ONLY_UNRELEASED'; project = $project
         coreSource = 'ecf0f550a0357cec6387c2b54efca128a405a629'
         clientSource = '346fc285198908c47a2ee9db7b9adb53d5e88c3c'; checks = $requiredChecks
      }
      $script:resultFile = Join-Path $state 'native-auth-result.json'
   }

   It 'preserves common state and creates only the exact owned destinations without console secrets' {
      $output = @(& $nativeScript -Action Initialize -StateDirectory $state -ProjectName $project)
      $output.Count | Should -Be 0
      (Get-Content -LiteralPath $envFile -Raw).StartsWith($prefix, [StringComparison]::Ordinal) | Should -BeTrue
      $inputData = Get-Content -LiteralPath (Join-Path $state 'native-input.json') -Raw | ConvertFrom-Json
      $inputData.qqqOrigin | Should -Be 'https://qqq.printing.internal'
      $inputData.callback | Should -Be 'https://callback.printing.internal/oauth/callback'
      $inputData.project | Should -Be $project
      (Get-Content -LiteralPath $script:resultFile -Raw | ConvertFrom-Json).status | Should -Be 'NOT_RUN'
      $before = (Get-FileHash -LiteralPath $envFile).Hash
      { & $nativeScript -Action Initialize -StateDirectory $state -ProjectName $project } | Should -Throw '*fresh owned environment*'
      (Get-FileHash -LiteralPath $envFile).Hash | Should -Be $before
   }

   It 'refuses credentials owned by another project before writing native input' {
      { & $nativeScript -Action Initialize -StateDirectory $state -ProjectName 'qbit-printing-dev-aaaaaaaa' } | Should -Throw '*does not match*'
      Test-Path -LiteralPath (Join-Path $state 'native-input.json') | Should -BeFalse
   }

   It 'accepts the exact source and complete bounded report' {
      $script:report | ConvertTo-Json | Set-Content -LiteralPath $script:resultFile
      & $nativeScript -Action VerifyResult -StateDirectory $state -ProjectName $project | Should -Match 'candidate is unpublished'
   }

   It 'rejects a passing report from a different source' {
      $script:report.coreSource = '0000000000000000000000000000000000000000'
      $script:report | ConvertTo-Json | Set-Content -LiteralPath $script:resultFile
      { & $nativeScript -Action VerifyResult -StateDirectory $state -ProjectName $project } | Should -Throw '*exact source*'
   }

   It 'rejects missing renewal evidence instead of treating successful login as complete' {
      $script:report.checks = @($requiredChecks | Where-Object { $_ -ne 'server-refresh-after-expiry' })
      $script:report | ConvertTo-Json | Set-Content -LiteralPath $script:resultFile
      { & $nativeScript -Action VerifyResult -StateDirectory $state -ProjectName $project } | Should -Throw '*required checks*'
   }

   It 'rejects malformed or oversized reports without echoing their contents' {
      [IO.File]::WriteAllText($script:resultFile, '{"private":"do-not-echo"')
      { & $nativeScript -Action VerifyResult -StateDirectory $state -ProjectName $project } | Should -Throw '*contents are withheld*'
      [IO.File]::WriteAllText($script:resultFile, ('x' * 65537))
      { & $nativeScript -Action VerifyResult -StateDirectory $state -ProjectName $project } | Should -Throw '*bounded size*'
   }
}
