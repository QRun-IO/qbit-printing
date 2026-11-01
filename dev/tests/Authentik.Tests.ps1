#Requires -Version 7.4
# No Docker or HTTP calls: these regressions exercise private bootstrap state.
BeforeAll {
   $script:authScript = Join-Path $PSScriptRoot '../Invoke-Authentik.ps1'
   $script:project = 'qbit-printing-dev-0123456789abcdef0123456789abcdef'
}

Describe 'Authentik development bootstrap' {
   BeforeEach {
      $state = Join-Path $TestDrive ([Guid]::NewGuid().ToString('N'))
      New-Item -ItemType Directory -Path $state | Out-Null
      $envFile = Join-Path $state 'compose.env'
      $prefix = "PRINTING_DEV_PROJECT=$project`nMINIO_ROOT_USER=synthetic`nQQQ_HTTP_PORT=19081`n"
      [IO.File]::WriteAllText($envFile, $prefix)
   }

   It 'preserves common environment values and creates a matching hashed admin password without console output' {
      $output = @(& $authScript -Action Initialize -StateDirectory $state -ProjectName $project)
      $output.Count | Should -Be 0
      $environment = Get-Content -LiteralPath $envFile -Raw
      $environment.StartsWith($prefix, [StringComparison]::Ordinal) | Should -BeTrue
      [regex]::Matches($environment, '(?m)^AUTHENTIK_[A-Z_]+=').Count | Should -Be 5
      $private = Get-Content -LiteralPath (Join-Path $state 'auth-secrets.json') -Raw | ConvertFrom-Json
      $match = [regex]::Match($environment, '(?m)^AUTHENTIK_BOOTSTRAP_PASSWORD_HASH=''pbkdf2_sha256\$1000000\$([^$]+)\$([^'']+)''')
      $match.Success | Should -BeTrue
      $actual = [Convert]::ToBase64String([Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($private.adminPassword, [Text.Encoding]::UTF8.GetBytes($match.Groups[1].Value), 1000000, [Security.Cryptography.HashAlgorithmName]::SHA256, 32))
      [string]::Equals($actual, $match.Groups[2].Value, [StringComparison]::Ordinal) | Should -BeTrue
   }

   It 'does not rotate credentials or append duplicate keys on repeated initialization' {
      & $authScript -Action Initialize -StateDirectory $state -ProjectName $project
      $beforeEnvironment = (Get-FileHash -LiteralPath $envFile).Hash
      $beforeSecrets = (Get-FileHash -LiteralPath (Join-Path $state 'auth-secrets.json')).Hash
      & $authScript -Action Initialize -StateDirectory $state -ProjectName $project
      (Get-FileHash -LiteralPath $envFile).Hash | Should -Be $beforeEnvironment
      (Get-FileHash -LiteralPath (Join-Path $state 'auth-secrets.json')).Hash | Should -Be $beforeSecrets
   }

   It 'rejects a changed project or port without overwriting private state' {
      & $authScript -Action Initialize -StateDirectory $state -ProjectName $project
      $before = (Get-FileHash -LiteralPath $envFile).Hash
      { & $authScript -Action Initialize -StateDirectory $state -ProjectName $project -Port 19011 } | Should -Throw '*different project or port*'
      { & $authScript -Action Initialize -StateDirectory $state -ProjectName 'qbit-printing-dev-aaaaaaaa' } | Should -Throw '*different project or port*'
      (Get-FileHash -LiteralPath $envFile).Hash | Should -Be $before
   }

   It 'rejects partial initialization rather than assigning different secrets to an existing database' {
      Add-Content -LiteralPath $envFile -Value 'AUTHENTIK_SECRET_KEY=synthetic'
      { & $authScript -Action Initialize -StateDirectory $state -ProjectName $project } | Should -Throw '*Partial Authentik initialization*'
      Test-Path -LiteralPath (Join-Path $state 'auth-secrets.json') | Should -BeFalse
   }

   It 'rejects missing environment keys after initialization' {
      & $authScript -Action Initialize -StateDirectory $state -ProjectName $project
      [IO.File]::WriteAllText($envFile, $prefix)
      { & $authScript -Action Initialize -StateDirectory $state -ProjectName $project } | Should -Throw '*incomplete or ambiguous*'
   }

   It 'withholds malformed private configuration contents' {
      [IO.File]::WriteAllText((Join-Path $state 'auth-secrets.json'), '{"private":"synthetic-value-to-withhold"')
      { & $authScript -Action Initialize -StateDirectory $state -ProjectName $project } | Should -Throw '*contents are withheld*'
   }

   It 'refuses a state directory inside the source checkout before writing' {
      $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
      { & $authScript -Action Initialize -StateDirectory $repository -ProjectName $project } | Should -Throw '*outside the source checkout*'
   }
}
