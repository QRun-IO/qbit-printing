#Requires -Version 7.4
<# .SYNOPSIS Checks the owned local QQQ development host without enrolling or printing. #>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$StateDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$state = [IO.Path]::GetFullPath($StateDirectory)
$owner = Get-Content -LiteralPath (Join-Path $state 'owner.json') -Raw | ConvertFrom-Json
if ($owner.project -cnotmatch '^qbit-printing-dev-[a-f0-9]{32}$')
{
   throw 'Expected an owned development environment.'
}
$values = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $state 'compose.env'))
{
   if ($line -match '^([A-Z_]+)=(.*)$')
   {
      $values[$Matches[1]] = $Matches[2]
   }
}
$port = [int]$values.QQQ_HTTP_PORT
if ($port -lt 1024 -or $port -gt 65535 -or $port -in @(18081, 18086))
{
   throw 'Expected a separate development port.'
}
$origin = "http://127.0.0.1:$port"
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$handler.CookieContainer = [Net.CookieContainer]::new()
$client = [Net.Http.HttpClient]::new($handler)
$client.BaseAddress = [Uri]$origin
$client.Timeout = [TimeSpan]::FromSeconds(15)
$client.MaxResponseContentBufferSize = 1048576
$client.DefaultRequestHeaders.Add('X-QQQ-Client', 'api/printing-development-check')
$client.DefaultRequestHeaders.Add('Origin', $origin)
$loggedIn = $false

function Invoke-DevelopmentRequest([string]$Path, [string]$Method = 'GET', [string]$Authorization)
{
   <# .SYNOPSIS Sends one bounded native request, without returning credentials. #>
   $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Path)
   if ($Method -eq 'POST')
   {
      $request.Content = [Net.Http.StringContent]::new('{}', [Text.Encoding]::UTF8, 'application/json')
   }
   if ($Authorization)
   {
      $request.Headers.TryAddWithoutValidation('Authorization', $Authorization) | Out-Null
   }
   $response = $null
   try
   {
      $response = $client.SendAsync($request).GetAwaiter().GetResult()
      if (!$response.IsSuccessStatusCode)
      {
         throw 'Owned native endpoint returned an unsuccessful status.'
      }
      return $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
   }
   finally
   {
      if ($null -ne $response)
      {
         $response.Dispose()
      }; $request.Dispose()
   }
}
try
{
   $deadline = [DateTime]::UtcNow.AddSeconds(90)
   do
   {
      try
      {
         $version = (Invoke-DevelopmentRequest '/qqq/v1/version') | ConvertFrom-Json; break
      }
      catch [Net.Http.HttpRequestException]
      {
         if ([DateTime]::UtcNow -ge $deadline)
         {
            throw
         }; Start-Sleep -Seconds 1
      }
   } while ($true)
   if (@($version.extensions | Where-Object { $_.name -ceq 'printing' -and $_.version -ceq '2.0.0' }).Count -ne 1)
   {
      throw 'Expected current printing extension.'
   }
   $metadata = (Invoke-DevelopmentRequest '/qqq/v1/metaData/authentication') | ConvertFrom-Json
   if ($metadata.type -cne 'TABLE_BASED')
   {
      throw 'Development host must retain standard TABLE authentication.'
   }
   $basic = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('developer:' + $values.QQQ_ADMIN_PASSWORD))
   try
   {
      $null = Invoke-DevelopmentRequest -Path '/qqq/v1/manageSession' -Method POST -Authorization $basic
   }
   finally
   {
      $basic = $null; $values = $null
   }
   $cookies = $handler.CookieContainer.GetCookies([Uri]$origin)
   $cookie = $cookies['sessionId']; if ($null -eq $cookie)
   {
      $cookie = $cookies['sessionUUID']
   }
   $loggedIn = $null -ne $cookie
   if (!$loggedIn -or !$cookie.HttpOnly)
   {
      throw 'Native HttpOnly session was not established.'
   }
   $identity = (Invoke-DevelopmentRequest '/qqq/v1/qbit/printing/session') | ConvertFrom-Json
   if ($identity.principalId -cne 'developer' -or $identity.tenantId -cne 'development')
   {
      throw 'Native development identity mismatch.'
   }
   $html = Invoke-DevelopmentRequest '/'
   if (!$html.Contains('<html', [StringComparison]::OrdinalIgnoreCase))
   {
      throw 'Native frontend asset missing.'
   }
   $null = Invoke-DevelopmentRequest -Path '/qqq/v1/logout' -Method POST
   $loggedIn = $false
   @{passed = $true; nativeTableLogin = $true; principalAndTenantVerified = $true; frontendHtml = $true; logout = $true; workSubmitted = $false; observedUtc = [DateTime]::UtcNow.ToString('O') } |
      ConvertTo-Json | Set-Content -LiteralPath (Join-Path $state 'qqq-result.json')
   Write-Output 'Owned QQQ native login, identity, frontend asset and logout passed; no work submitted.'
}
finally
{
   if ($loggedIn)
   {
      $null = Invoke-DevelopmentRequest -Path '/qqq/v1/logout' -Method POST
   }
   $client.Dispose()
}
