using System.Net;
using System.Security.Authentication;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Qqq.PrintAgent.Core.Authentication;

namespace Printing.NativeAuth;

internal static class Program
{
   private const string CoreSource = "ecf0f550a0357cec6387c2b54efca128a405a629";
   private const string ClientSource = "346fc285198908c47a2ee9db7b9adb53d5e88c3c";
   private const string InputPath = "/work/input.json";
   private const string ResultPath = "/work/result.json";
   private const string TlsMarker = "/tmp/printing-native-untrusted-tls-passed";
   private static readonly List<string> Checks = [];
   private static string _stage = "input";
   private static bool? _authorizationRequestMatches;

   public static async Task<int> Main(string[] arguments)
   {
      if(arguments is ["--check-discovery"])
      {
         await NativeAuthDiscoveryChecks.RunAsync();
         return 0;
      }
      JsonObject? input = null;
      try
      {
         if(arguments.Length != 1 || arguments[0] is not ("assert-untrusted" or "test"))
         { throw new InvalidOperationException("An explicit fixture action is required."); }
         if(new FileInfo(InputPath).Length is <= 0 or > 65536)
         { throw new InvalidOperationException("Fixture input size is invalid."); }
         input = JsonNode.Parse(await File.ReadAllTextAsync(InputPath))!.AsObject();
         ValidateInput(input);
         using var deadline = new CancellationTokenSource(TimeSpan.FromMinutes(5));
         if(arguments[0] == "assert-untrusted")
         {
            _stage = "untrusted-tls";
            await AssertUntrustedAsync(new Uri(ProviderSetup.Text(input, "providerOrigin")), deadline.Token);
            await File.WriteAllTextAsync(TlsMarker, "passed", deadline.Token);
            return 0;
         }
         if(!File.Exists(TlsMarker)) { throw new InvalidOperationException("The ordinary TLS negative control did not run."); }
         Checks.Add("untrusted-tls-refused");
         await QualifyAsync(input, deadline.Token);
         await WriteResultAsync(input, "PASS", null, null);
         Console.WriteLine("Experimental native Authentik qualification passed; development-only unpublished core.");
         return 0;
      }
      catch(Exception exception)
      {
         // Exception messages, response bodies, callback URLs and credentials are deliberately withheld.
         await WriteResultAsync(input, "FAILED", exception.GetType().Name,
            exception is QqqSessionException sessionException ? sessionException.Failure.ToString() : null);
         Console.Error.WriteLine("Experimental native Authentik qualification failed at " + _stage + " (" + exception.GetType().Name + ").");
         return 1;
      }
   }

   private static void ValidateInput(JsonObject input)
   {
      if(!Regex.IsMatch(ProviderSetup.Text(input, "project"), "^qbit-printing-dev-[a-f0-9]{32}$", RegexOptions.CultureInvariant)
         || ProviderSetup.Text(input, "coreSource") != CoreSource || ProviderSetup.Text(input, "clientSource") != ClientSource
         || ProviderSetup.Text(input, "providerOrigin") != "https://auth.printing.internal"
         || ProviderSetup.Text(input, "qqqOrigin") != "https://qqq.printing.internal"
         || ProviderSetup.Text(input, "callback") != "https://callback.printing.internal/oauth/callback"
         || ProviderSetup.Text(input, "issuer") != "https://auth.printing.internal/application/o/" + ProviderSetup.Text(input, "project") + "/"
         || ProviderSetup.Text(input, "clientId") != ProviderSetup.Text(input, "project"))
      { throw new InvalidOperationException("Fixture identity or destination differs from the pinned local contract."); }
   }

   private static QqqSessionClient Session(Uri origin) => new(origin,
      new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false, UseProxy = false });

   private static async Task AssertUntrustedAsync(Uri origin, CancellationToken token)
   {
      using var transport = new FixtureHttp(origin);
      try { await transport.SendAsync("GET", "/-/health/live/", null, token); }
      catch(HttpRequestException exception)
      {
         for(Exception? cause = exception; cause is not null; cause = cause.InnerException)
         {
            if(cause is AuthenticationException) { return; }
         }
         throw new InvalidOperationException("The negative TLS control failed for a reason other than certificate authentication.");
      }
      throw new InvalidOperationException("The test unexpectedly trusted its private CA before scoped installation.");
   }

   private static async Task QualifyAsync(JsonObject input, CancellationToken token)
   {
      var origin = new Uri(ProviderSetup.Text(input, "qqqOrigin"));
      var providerOrigin = new Uri(ProviderSetup.Text(input, "providerOrigin"));
      var callback = new Uri(ProviderSetup.Text(input, "callback"));
      _stage = "readiness";
      await WaitForQqqAsync(origin, token);
      using var provider = new ProviderSetup(input);
      _stage = "provider-readiness";
      await provider.WaitReadyAsync(token);
      _stage = "provider-configuration";
      await provider.ConfigureAsync(token);
      using var receiver = new CallbackReceiver(callback);
      using var first = Session(origin);
      _stage = "typed-native-discovery";
      await NativeAuthDiscovery.DiscoverAsync(first, token);
      QqqAuthenticationMetadata metadata = await first.GetAuthenticationMetadataAsync(token);
      if(metadata.Values?.ClientId != ProviderSetup.Text(input, "clientId"))
      { throw new InvalidOperationException("Native metadata did not retain the configured client identifier."); }
      Checks.Add("typed-native-grant");

      _stage = "real-provider-login";
      await LoginAsync(first, receiver, input, providerOrigin, callback, authorized: true, token);
      _stage = "authorized-native-identity";
      QqqSessionBinding identity = await first.GetSessionIdentityAsync(token);
      if(string.IsNullOrWhiteSpace(identity.PrincipalId) || identity.TenantId != "printing-native-dev" || identity.DeviceId is not null)
      { throw new InvalidOperationException("The native subject did not receive the intended server-derived identity."); }
      Checks.Add("real-provider-login");
      _stage = "wrong-origin-cookie";
      await AssertWrongOriginAsync(origin, first.ExportCredential(), token);
      await first.GetSessionIdentityAsync(token);
      Checks.Add("wrong-origin-cookie-denied");

      _stage = "permission-denied-user";
      using(var denied = Session(origin))
      {
         await LoginAsync(denied, receiver, input, providerOrigin, callback, authorized: false, token);
         await ExpectFailureAsync(() => denied.GetSessionIdentityAsync(token), QqqSessionException.FailureKind.PermissionDenied);
         await denied.SignOutAsync(token);
      }
      Checks.Add("permission-denied-user");

      _stage = "logout-replay";
      QqqSessionCredential retired = first.ExportCredential();
      await first.SignOutAsync(token);
      using(var replay = Session(origin))
      { await ExpectFailureAsync(() => replay.RestoreSessionAsync(retired, token), QqqSessionException.FailureKind.AuthenticationRequired); }
      Checks.Add("logout-replay-denied");

      _stage = "fresh-login-for-renewal";
      using var renewable = Session(origin);
      await LoginAsync(renewable, receiver, input, providerOrigin, callback, authorized: true, token);
      QqqSessionBinding before = await renewable.GetSessionIdentityAsync(token);
      HashSet<string> previousGrants = await provider.AccessGrantIdsAsync(token);
      _stage = "server-refresh-after-expiry";
      await Task.Delay(TimeSpan.FromSeconds(22), token);
      await renewable.ResumeSessionAsync(token);
      QqqSessionBinding after = await renewable.GetSessionIdentityAsync(token);
      HashSet<string> refreshedGrants = await provider.AccessGrantIdsAsync(token);
      if(before != after || !refreshedGrants.Except(previousGrants, StringComparer.Ordinal).Any())
      { throw new InvalidOperationException("Renewal did not preserve the identity and obtain a new actual provider grant."); }
      Checks.Add("server-refresh-after-expiry");

      _stage = "provider-revocation-after-expiry";
      await provider.RevokeRefreshGrantsAsync(token);
      await Task.Delay(TimeSpan.FromSeconds(22), token);
      await ExpectFailureAsync(() => renewable.ResumeSessionAsync(token), QqqSessionException.FailureKind.AuthenticationRequired);
      Checks.Add("provider-revocation-denied");
      _stage = "complete";
   }

   private static async Task LoginAsync(QqqSessionClient client, CallbackReceiver receiver, JsonObject input,
      Uri providerOrigin, Uri callback, bool authorized, CancellationToken token)
   {
      string role = authorized ? "authorized" : "denied";
      _stage = role + "-begin-pkce";
      QqqBrowserLogin attempt = await client.BeginBrowserLoginAsync(callback, token);
      _stage = role + "-generated-authorization-request";
      Dictionary<string, string> parameters = attempt.AuthorizationUri.Query.TrimStart('?').Split('&')
         .Select(entry => entry.Split('=', 2)).ToDictionary(pair => Uri.UnescapeDataString(pair[0]), pair => Uri.UnescapeDataString(pair[1]), StringComparer.Ordinal);
      _authorizationRequestMatches = attempt.AuthorizationUri.AbsolutePath == "/application/o/authorize/"
         && parameters.GetValueOrDefault("client_id") == ProviderSetup.Text(input, "clientId")
         && parameters.GetValueOrDefault("redirect_uri") == callback.AbsoluteUri
         && parameters.GetValueOrDefault("response_type") == "code"
         && parameters.GetValueOrDefault("code_challenge_method") == "S256"
         && parameters.GetValueOrDefault("state")?.Length == 43;
      if(_authorizationRequestMatches != true)
      { throw new InvalidOperationException("The generated authorization request differs from the exact provider registration."); }
      _stage = role + "-provider-flow";
      await ProviderFlow.AuthenticateAsync(attempt.AuthorizationUri, providerOrigin, callback,
         ProviderSetup.Text(input, role + "Username"), ProviderSetup.Text(input, role + "Password"), token);
      _stage = role + "-callback";
      Uri actualCallback = await receiver.ReadAsync(token);
      _stage = role + "-exchange";
      await client.CompleteBrowserLoginAsync(attempt, actualCallback, token);
   }

   private static async Task ExpectFailureAsync(Func<Task> operation, QqqSessionException.FailureKind expected)
   {
      try { await operation(); }
      catch(QqqSessionException exception) when(exception.Failure == expected) { return; }
      throw new InvalidOperationException("The expected native authentication or permission denial did not occur.");
   }

   private static async Task AssertWrongOriginAsync(Uri origin, QqqSessionCredential credential, CancellationToken token)
   {
      using var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false, UseProxy = false })
      { Timeout = TimeSpan.FromSeconds(15), MaxResponseContentBufferSize = 65536 };
      using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(origin, "/qqq/v1/qbit/printing/session"));
      request.Headers.Add("X-QQQ-Client", QqqSessionClient.ClientChannel);
      request.Headers.Add("Cookie", "sessionUUID=" + credential.SessionToken);
      request.Headers.Add("Origin", "https://wrong-origin.invalid");
      using HttpResponseMessage response = await http.SendAsync(request, token);
      if(response.StatusCode != HttpStatusCode.Forbidden)
      { throw new InvalidOperationException("The fixture did not reject a wrong-Origin cookie request."); }
   }

   private static async Task WaitForQqqAsync(Uri origin, CancellationToken token)
   {
      using var http = new FixtureHttp(origin);
      for(int attempt = 0; attempt < 45; attempt++)
      {
         try
         {
            FixtureHttp.Reply response = await http.SendAsync("GET", "/qqq/v1/version", null, token);
            if(response.Status == HttpStatusCode.OK && response.Json is not null) { return; }
         }
         catch(HttpRequestException) { }
         await Task.Delay(TimeSpan.FromSeconds(2), token);
      }
      throw new InvalidOperationException("The owned native host did not become ready.");
   }

   private static Task WriteResultAsync(JsonObject? input, string status, string? failureType, string? sessionFailure)
      => File.WriteAllTextAsync(ResultPath, JsonSerializer.Serialize(new
      {
         status, scope = "DEVELOPMENT_ONLY_UNRELEASED", coreSource = CoreSource, clientSource = ClientSource,
         project = input?["project"]?.ToString(), observedUtc = DateTimeOffset.UtcNow, stage = _stage, failureType,
         sessionFailure, lastRequestKind = FixtureHttp.LastRequestKind, lastHttpStatus = FixtureHttp.LastStatus,
         lastProviderComponent = ProviderFlow.LastComponent,
         authorizationRequestMatches = _authorizationRequestMatches,
         requestClassifications = FixtureHttp.Trace,
         providerLookupKind = ProviderSetup.LastLookupKind, providerLookupCount = ProviderSetup.LastLookupCount,
         providerReadinessCounts = ProviderSetup.ReadinessCounts,
         checks = Checks, installedUiAcceptance = false, providerRefreshTokensInClient = false,
      }, new JsonSerializerOptions { WriteIndented = true }));
}
