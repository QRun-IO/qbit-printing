#Requires -Version 7.4
<#
.SYNOPSIS
Prepares or checks the optional, owned Authentik development fixture.
.DESCRIPTION
Initialize only writes private state. Test configures a fresh synthetic provider
and checks its real HTTP discovery and rejection boundary; it never automates a
user login or changes QQQ authentication. The common development entrypoint owns
all Docker lifecycle operations. Provider response bodies and secrets are never
printed, including on errors.
#>
[CmdletBinding()]
param(
   [Parameter(Mandatory)][ValidateSet('Initialize', 'Test')][string] $Action,
   [Parameter(Mandatory)][string] $StateDirectory,
   [Parameter(Mandatory)][ValidatePattern('^qbit-printing-dev-[a-z0-9-]{8,48}$')][string] $ProjectName,
   [ValidateRange(1024, 65535)][int] $Port = 19010,
   [ValidateRange(10, 300)][int] $ReadyTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

<# .SYNOPSIS Generates a private random value without placing it on a command line. #>
function Get-FixtureSecret
{
   [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

<# .SYNOPSIS Limits the helper to an existing state directory outside the checkout. #>
function Get-FixtureState
{
   param([string] $Directory)
   $state = [IO.Path]::GetFullPath($Directory)
   $repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
   $comparison = if($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
   if($state.Equals($repository, $comparison) -or $state.StartsWith($repository + [IO.Path]::DirectorySeparatorChar, $comparison))
   {
      throw 'Development secrets must be outside the source checkout.'
   }
   if(-not (Test-Path -LiteralPath $state -PathType Container) -or -not (Test-Path -LiteralPath (Join-Path $state 'compose.env') -PathType Leaf))
   {
      throw 'The common development entrypoint must create protected state and compose.env first.'
   }
   $ancestor = [IO.DirectoryInfo]::new($state)
   while($null -ne $ancestor)
   {
      if(($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
      {
         throw 'Development state cannot use a symbolic link or reparse point.'
      }
      $ancestor = $ancestor.Parent
   }
   return $state
}

<# .SYNOPSIS Reads private configuration without echoing malformed JSON or credential values. #>
function Get-FixtureConfiguration
{
   param([string] $Path)
   try
   {
      $value = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable
      foreach($field in @('project', 'port', 'adminPassword', 'bootstrapToken', 'clientId', 'clientSecret', 'username', 'userPassword'))
      {
         if(-not $value.ContainsKey($field) -or $null -eq $value[$field]) { throw 'Missing field.' }
      }
      return $value
   }
   catch { throw 'The private Authentik configuration is unreadable or incomplete; its contents are withheld.' }
}

<# .SYNOPSIS Performs bounded same-origin HTTP without redirects or response-body diagnostics. #>
function Invoke-FixtureHttp
{
   param(
      [Parameter(Mandatory)][Net.Http.HttpClient] $Client,
      [Parameter(Mandatory)][string] $Path,
      [ValidateSet('GET', 'POST')][string] $Method = 'GET',
      [object] $Body,
      [string] $Token,
      [int[]] $ExpectedStatus = @(200)
   )
   if(-not $Path.StartsWith('/', [StringComparison]::Ordinal) -or $Path.StartsWith('//', [StringComparison]::Ordinal))
   {
      throw 'Only an absolute path on the fixture origin is permitted.'
   }
   $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Path)
   $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(15))
   $response = $null
   $status = 0
   try
   {
      if($Token)
      {
         $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
      }
      if($null -ne $Body)
      {
         $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 12 -Compress), [Text.Encoding]::UTF8, 'application/json')
      }
      $response = $Client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $timeout.Token).GetAwaiter().GetResult()
      $status = [int]$response.StatusCode
      if($status -notin $ExpectedStatus)
      {
         throw "Fixture HTTP status $status was not expected. Response content is withheld."
      }
      $stream = $response.Content.ReadAsStreamAsync($timeout.Token).GetAwaiter().GetResult()
      $bytes = [IO.MemoryStream]::new()
      try
      {
         $buffer = [byte[]]::new(8192)
         while(($read = $stream.ReadAsync($buffer, 0, $buffer.Length, $timeout.Token).GetAwaiter().GetResult()) -gt 0)
         {
            if($bytes.Length + $read -gt 524288)
            {
               throw 'Fixture response exceeded the 512 KiB limit.'
            }
            $bytes.Write($buffer, 0, $read)
         }
         $text = [Text.Encoding]::UTF8.GetString($bytes.ToArray())
         $data = if($text.Length -gt 0 -and $response.Content.Headers.ContentType.MediaType -eq 'application/json')
         {
            ConvertFrom-Json -InputObject $text -AsHashtable
         }
         else { $null }
         return @{ Status = $status; Data = $data; Location = $response.Headers.Location }
      }
      finally
      {
         $bytes.Dispose()
         $stream.Dispose()
      }
   }
   catch
   {
      # The failing API may contain passwords/tokens in its error response.
      $operation = [uri]::new($Client.BaseAddress, $Path).AbsolutePath
      throw "Fixture $Method $operation failed (HTTP $status; 0 means no response). Response content and authentication values are withheld."
   }
   finally
   {
      if($null -ne $response) { $response.Dispose() }
      $request.Dispose()
      $timeout.Dispose()
   }
}

<# .SYNOPSIS Requires one exact default object rather than choosing an arbitrary provider resource. #>
function Get-FixtureObject
{
   param([Net.Http.HttpClient] $Client, [string] $Path, [string] $Token)
   $page = (Invoke-FixtureHttp -Client $Client -Path $Path -Token $Token).Data
   if($null -eq $page -or $page.results.Count -ne 1)
   {
      throw 'A required Authentik default object is missing or ambiguous.'
   }
   return $page.results[0]
}

$state = Get-FixtureState -Directory $StateDirectory
$envPath = Join-Path $state 'compose.env'
$secretPath = Join-Path $state 'auth-secrets.json'
foreach($path in @($envPath, $secretPath))
{
   if((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
   {
      throw 'Development configuration cannot use a symbolic link or reparse point.'
   }
}
if($Action -eq 'Initialize')
{
   if(Test-Path -LiteralPath $secretPath)
   {
      $existing = Get-FixtureConfiguration -Path $secretPath
      if($existing.project -ne $ProjectName -or $existing.port -ne $Port)
      {
         throw 'Existing Authentik state belongs to a different project or port.'
      }
      $existingEnvironment = Get-Content -LiteralPath $envPath -Raw
      foreach($key in @('AUTHENTIK_HTTP_PORT', 'AUTHENTIK_POSTGRES_PASSWORD', 'AUTHENTIK_SECRET_KEY', 'AUTHENTIK_BOOTSTRAP_PASSWORD_HASH', 'AUTHENTIK_BOOTSTRAP_TOKEN'))
      {
         if([regex]::Matches($existingEnvironment, '(?m)^' + $key + '=').Count -ne 1)
         {
            throw 'The private Authentik environment is incomplete or ambiguous; use a fresh owned state directory.'
         }
      }
      return
   }
   $contents = Get-Content -LiteralPath $envPath -Raw
   if($contents -match '(?m)^AUTHENTIK_')
   {
      throw 'Partial Authentik initialization exists. Use a fresh owned state directory.'
   }
   $adminPassword = Get-FixtureSecret
   $salt = Get-FixtureSecret
   $passwordHash = [Convert]::ToBase64String([Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($adminPassword, [Text.Encoding]::UTF8.GetBytes($salt), 1000000, [Security.Cryptography.HashAlgorithmName]::SHA256, 32))
   $secrets = [ordered]@{
      project = $ProjectName
      port = $Port
      adminPassword = $adminPassword
      bootstrapToken = Get-FixtureSecret
      clientId = $ProjectName
      clientSecret = Get-FixtureSecret
      username = 'printing-developer'
      userPassword = Get-FixtureSecret
   }
   $secrets | ConvertTo-Json | Set-Content -LiteralPath $secretPath -Encoding utf8
   $values = [ordered]@{
      AUTHENTIK_HTTP_PORT = $Port
      AUTHENTIK_POSTGRES_PASSWORD = Get-FixtureSecret
      AUTHENTIK_SECRET_KEY = Get-FixtureSecret
      AUTHENTIK_BOOTSTRAP_PASSWORD_HASH = 'pbkdf2_sha256$1000000$' + $salt + '$' + $passwordHash
      AUTHENTIK_BOOTSTRAP_TOKEN = $secrets.bootstrapToken
   }
   # Single quotes preserve the dollar signs in Django's password hash.
   $lines = @('') + @($values.GetEnumerator() | ForEach-Object { $_.Key + "='" + $_.Value + "'" })
   Add-Content -LiteralPath $envPath -Value $lines -Encoding utf8
   if(-not $IsWindows)
   {
      [IO.File]::SetUnixFileMode($secretPath, [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite)
   }
   return
}

$secrets = Get-FixtureConfiguration -Path $secretPath
if($secrets.project -cne $ProjectName -or $secrets.port -ne $Port -or $secrets.clientId -cne $ProjectName -or $secrets.username -cne 'printing-developer')
{
   throw 'Authentik state does not match the owned development project.'
}
$handler = [Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseProxy = $false
$handler.UseCookies = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.BaseAddress = [uri]"http://127.0.0.1:$Port"
$client.Timeout = [Threading.Timeout]::InfiniteTimeSpan
try
{
   $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
   $ready = $false
   do
   {
      try
      {
         $authorization = Get-FixtureObject -Client $client -Path '/api/v3/flows/instances/?slug=default-provider-authorization-implicit-consent' -Token $secrets.bootstrapToken
         $invalidation = Get-FixtureObject -Client $client -Path '/api/v3/flows/instances/?slug=default-provider-invalidation-flow' -Token $secrets.bootstrapToken
         $ready = $true
      }
      catch
      {
         if([DateTimeOffset]::UtcNow -ge $deadline) { throw 'Authentik did not become ready before the bounded startup deadline.' }
         Start-Sleep -Seconds 1
      }
   } while(-not $ready)

   $mappingIds = @(foreach($scope in @('openid', 'profile', 'email', 'offline_access'))
   {
      (Get-FixtureObject -Client $client -Path "/api/v3/propertymappings/provider/scope/?scope_name=$scope" -Token $secrets.bootstrapToken).pk
   })
   $providerPage = (Invoke-FixtureHttp -Client $client -Path ('/api/v3/providers/oauth2/?client_id=' + $secrets.clientId) -Token $secrets.bootstrapToken).Data
   $callback = "http://127.0.0.1:$Port/reference-callback/"
   if($providerPage.results.Count -eq 0)
   {
      $provider = (Invoke-FixtureHttp -Client $client -Method POST -Path '/api/v3/providers/oauth2/' -Token $secrets.bootstrapToken -ExpectedStatus @(201) -Body @{
         name = $ProjectName
         authorization_flow = $authorization.pk
         invalidation_flow = $invalidation.pk
         client_type = 'confidential'
         grant_types = @('authorization_code', 'refresh_token')
         client_id = $secrets.clientId
         client_secret = $secrets.clientSecret
         access_code_validity = 'minutes=1'
         access_token_validity = 'minutes=2'
         refresh_token_validity = 'minutes=10'
         redirect_uris = @(@{ matching_mode = 'strict'; url = $callback })
         property_mappings = $mappingIds
         issuer_mode = 'per_provider'
      }).Data
   }
   elseif($providerPage.results.Count -eq 1)
   {
      $provider = $providerPage.results[0]
      if($provider.name -ne $ProjectName) { throw 'The fixture provider identity changed.' }
   }
   else { throw 'The fixture provider identity is ambiguous.' }
   if($provider.redirect_uris.Count -ne 1 -or $provider.redirect_uris[0].matching_mode -cne 'strict' -or
      $provider.redirect_uris[0].url -cne $callback -or $provider.client_type -cne 'confidential' -or
      $provider.access_token_validity -cne 'minutes=2' -or $provider.refresh_token_validity -cne 'minutes=10' -or
      (@($provider.grant_types | Sort-Object) -join ',') -cne 'authorization_code,refresh_token' -or
      @($mappingIds | Where-Object { $_ -notin $provider.property_mappings }).Count -ne 0)
   {
      throw 'The existing provider configuration differs from the bounded reference settings.'
   }

   $applicationPage = (Invoke-FixtureHttp -Client $client -Path ('/api/v3/core/applications/?slug=' + $ProjectName) -Token $secrets.bootstrapToken).Data
   if($applicationPage.results.Count -eq 0)
   {
      $null = Invoke-FixtureHttp -Client $client -Method POST -Path '/api/v3/core/applications/' -Token $secrets.bootstrapToken -ExpectedStatus @(201) -Body @{ name = $ProjectName; slug = $ProjectName; provider = $provider.pk; policy_engine_mode = 'all' }
   }
   elseif($applicationPage.results.Count -ne 1 -or $applicationPage.results[0].provider -ne $provider.pk)
   {
      throw 'The fixture application does not match its provider.'
   }
   $userPage = (Invoke-FixtureHttp -Client $client -Path ('/api/v3/core/users/?username=' + $secrets.username) -Token $secrets.bootstrapToken).Data
   if($userPage.results.Count -eq 0)
   {
      $user = (Invoke-FixtureHttp -Client $client -Method POST -Path '/api/v3/core/users/' -Token $secrets.bootstrapToken -ExpectedStatus @(201) -Body @{ username = $secrets.username; name = 'Printing development user'; email = 'printing-developer@example.invalid'; is_active = $true; type = 'internal' }).Data
   }
   elseif($userPage.results.Count -eq 1)
   {
      $user = $userPage.results[0]
      if($user.name -cne 'Printing development user') { throw 'The synthetic user identity changed.' }
   }
   else { throw 'The synthetic user identity is ambiguous.' }
   # Reusing the same generated value also recovers an interrupted first setup.
   $null = Invoke-FixtureHttp -Client $client -Method POST -Path ("/api/v3/core/users/$($user.pk)/set_password/") -Token $secrets.bootstrapToken -ExpectedStatus @(204) -Body @{ password = $secrets.userPassword }

   $issuer = "http://127.0.0.1:$Port/application/o/$ProjectName/"
   $discovery = (Invoke-FixtureHttp -Client $client -Path "/application/o/$ProjectName/.well-known/openid-configuration").Data
   if($discovery.issuer -cne $issuer -or 'S256' -notin $discovery.code_challenge_methods_supported)
   {
      throw 'The real provider discovery does not match the fixture issuer and S256 capability.'
   }
   foreach($endpoint in @('authorization_endpoint', 'token_endpoint', 'userinfo_endpoint', 'jwks_uri'))
   {
      $uri = [uri]$discovery[$endpoint]
      if($uri.GetLeftPart([UriPartial]::Authority) -cne "http://127.0.0.1:$Port")
      {
         throw 'Provider discovery returned an endpoint outside the fixture origin.'
      }
   }
   $verifier = Get-FixtureSecret
   $challenge = [Convert]::ToBase64String([Security.Cryptography.SHA256]::HashData([Text.Encoding]::ASCII.GetBytes($verifier))).TrimEnd('=').Replace('+', '-').Replace('/', '_')
   $stateValue = Get-FixtureSecret
   $authorize = "/application/o/authorize/?client_id=$($secrets.clientId)&response_type=code&scope=openid%20profile%20email%20offline_access&state=$stateValue&code_challenge=$challenge&code_challenge_method=S256&prompt=login&redirect_uri="
   $accepted = Invoke-FixtureHttp -Client $client -Path ($authorize + [uri]::EscapeDataString($callback)) -ExpectedStatus @(302, 303)
   if($null -eq $accepted.Location)
   {
      throw 'The registered redirect did not continue to the real provider login flow.'
   }
   $login = [uri]::new($client.BaseAddress, $accepted.Location)
   if($login.GetLeftPart([UriPartial]::Authority) -cne "http://127.0.0.1:$Port" -or -not $login.AbsolutePath.StartsWith('/if/flow/', [StringComparison]::Ordinal))
   {
      throw 'The registered redirect did not continue to the same-origin provider login flow.'
   }
   # Change only the callback: a generic malformed authorization request is not proof.
   $invalidRedirect = [uri]::EscapeDataString("http://127.0.0.1:$Port/not-registered/")
   $null = Invoke-FixtureHttp -Client $client -Path ($authorize + $invalidRedirect) -ExpectedStatus @(400)
   $qqqPortMatch = [regex]::Match((Get-Content -LiteralPath $envPath -Raw), '(?m)^QQQ_HTTP_PORT=[''"]?(\d+)[''"]?\s*$')
   if(-not $qqqPortMatch.Success) { throw 'The common environment has no QQQ HTTP port.' }
   $qqqHandler = [Net.Http.HttpClientHandler]::new()
   $qqqHandler.AllowAutoRedirect = $false
   $qqqHandler.UseProxy = $false
   $qqqClient = [Net.Http.HttpClient]::new($qqqHandler)
   try
   {
      $qqqClient.BaseAddress = [uri]("http://127.0.0.1:" + $qqqPortMatch.Groups[1].Value)
      $qqqClient.Timeout = [Threading.Timeout]::InfiniteTimeSpan
      $qqqClient.DefaultRequestHeaders.Add('X-QQQ-Client', 'api/printing-dev-reference')
      $version = (Invoke-FixtureHttp -Client $qqqClient -Path '/qqq/v1/version').Data
   }
   finally { $qqqClient.Dispose() }
   if($version.apiVersion -ne '1.8.0' -or 'nativeSessionLogin' -in $version.features)
   {
      throw 'The core capability baseline changed; review the Authentik qualification scope before proceeding.'
   }
   [ordered]@{
      status = 'PASS'
      observedUtc = [DateTimeOffset]::UtcNow.ToString('O')
      project = $ProjectName
      providerOrigin = "http://127.0.0.1:$Port"
      providerVersion = '2026.8.5'
      discovery = 'PASS'
      registeredRedirect = 'LOGIN_REQUIRED'
      unregisteredRedirect = 'REJECTED'
      qqqApiVersion = $version.apiVersion
      nativeSessionLogin = 'UNAVAILABLE_EXPECTED'
      browserLogin = 'NOT_RUN_OPERATOR_REQUIRED'
      renewal = 'NOT_RUN'
      windowsCallback = 'NOT_RUN_REQUIRES_QUALIFIED_CORE_AND_HTTPS'
   } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $state 'auth-result.json') -Encoding utf8
   Write-Output 'Authentik reference discovery and redirect rejection passed; QQQ native browser login remains unavailable on RC.20.'
}
catch
{
   @{ status = 'FAILED'; observedUtc = [DateTimeOffset]::UtcNow.ToString('O'); project = $ProjectName; message = 'Reference checks failed; no browser or native-login success is claimed.' } |
      ConvertTo-Json | Set-Content -LiteralPath (Join-Path $state 'auth-result.json') -Encoding utf8
   throw
}
finally
{
   $client.Dispose()
}
