#Requires -Version 7.4
BeforeAll {
   $path = Join-Path $PSScriptRoot '../Invoke-Dev.ps1'
   $tokens = $null
   $errors = $null
   $ast = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
   if ($errors.Count) { throw 'Entrypoint parsing failed.' }
   $function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'ConvertTo-ProviderErrorSummary' }, $true)
   . ([scriptblock]::Create($function.Extent.Text))
}

Describe 'Private provider diagnostic redaction' {
   It 'retains source coordinates but excludes exception values, locals and request data' {
      $record = @{event = 'secret-request'; exception = @(@{exc_type = 'KeyError'; exc_value = 'secret-value'; frames = @(
                  @{filename = '/authentik/flows/views/executor.py'; lineno = 42; name = 'post'; locals = @{password = 'secret-password'}; source = 'secret-source'},
                  @{filename = '/outside/secret-path.py'; lineno = 3; name = 'unsafe'}
               )
            })
      } | ConvertTo-Json -Depth 8 -Compress
      $result = ConvertTo-ProviderErrorSummary @($record, 'secret-unstructured-line')
      $result.exceptionTypes | Should -Be @('KeyError')
      $result.frames.Count | Should -Be 1
      $result.frames[0].function | Should -Be 'post'
      $result | ConvertTo-Json -Depth 6 | Should -Not -Match 'secret|password|outside|locals|source'
   }
   It 'bounds frames and does not retain malformed or query-bearing coordinates' {
      $frames = @(1..20 | ForEach-Object { @{filename = '/authentik/test.py'; lineno = $_; name = 'get'} })
      $record = @{exception = @(@{exc_type = 'ValueError'; frames = $frames})} | ConvertTo-Json -Depth 8 -Compress
      (ConvertTo-ProviderErrorSummary @($record)).frames.Count | Should -Be 12
      $bad = @{exception = @(@{exc_type = 'secret?Error'; frames = @(@{filename = '/authentik/test.py?secret'; lineno = 1; name = 'get'})})} | ConvertTo-Json -Depth 8 -Compress
      $result = ConvertTo-ProviderErrorSummary @($bad, '{broken', 'null')
      $result.exceptionTypes.Count | Should -Be 0
      $result.frames.Count | Should -Be 0
   }
   It 'records only fixed known warning flags from unstructured output' {
      $result = ConvertTo-ProviderErrorSummary @('Invalid client identifier secret-query', 'Non-applicable authentication flow secret', 'failed to resolve application secret')
      $result.warnings.invalidClientIdentifier | Should -BeTrue
      $result.warnings.nonApplicableAuthenticationFlow | Should -BeTrue
      $result.warnings.failedToResolveApplication | Should -BeTrue
      $result | ConvertTo-Json -Depth 6 | Should -Not -Match 'secret'
   }
   It 'retains fixed DRF exception names without their messages' {
      $record = @{exception = @(@{exc_type = 'PermissionDenied'; exc_value = 'secret-csrf-value'; frames = @()})} | ConvertTo-Json -Depth 5 -Compress
      $result = ConvertTo-ProviderErrorSummary @($record)
      $result.exceptionTypes | Should -Be @('PermissionDenied')
      $result | ConvertTo-Json -Depth 6 | Should -Not -Match 'secret'
   }
}
