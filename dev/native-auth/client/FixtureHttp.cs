// Copyright 2026 Kingsrook, LLC
// SPDX-License-Identifier: Apache-2.0
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
namespace Printing.NativeAuth;



/// <summary>Small bounded fixture transport; no redirects, proxy or response-body diagnostics.</summary>
internal sealed class FixtureHttp : IDisposable
{
   /// <summary>Carry only the parsed bounded response and optional redirect location.</summary>
   internal sealed record Reply(HttpStatusCode Status, JsonObject? Json, Uri? Location);



   /// <summary>Record fixed classifications without credentials, URLs, or response bodies.</summary>
   internal sealed record TraceEntry(string Kind, int Status, bool HasQuery, string? LocationKind, bool LocationHasQuery);



   internal static List<TraceEntry> Trace { get; } = [];



   internal static string? LastRequestKind
   {
      get;



      private set;
   }



   internal static int? LastStatus
   {
      get;



      private set;
   }



   private readonly HttpClient _client;



   private readonly CookieContainer _cookies = new();



   private readonly Uri _origin;



   private readonly string? _adminToken;



   /// <summary>Configure bounded same-origin HTTP with redirects and proxies disabled.</summary>
   internal FixtureHttp(Uri origin, bool cookies = false, string? adminToken = null)
   {
      _origin = origin;
      _adminToken = adminToken;
      _client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false, UseCookies = cookies, CookieContainer = _cookies })
      {
         Timeout = TimeSpan.FromSeconds(15),
         MaxResponseContentBufferSize = 524288,
      };
   }



   /// <summary>Send one origin-confined request and retain bounded sanitized diagnostics.</summary>
   internal async Task<Reply> SendAsync(string method, Uri uri, JsonObject? body, CancellationToken cancellationToken)
   {
      if(!string.Equals(uri.Scheme, "https", StringComparison.Ordinal) || !string.Equals(uri.GetLeftPart(UriPartial.Authority), _origin.GetLeftPart(UriPartial.Authority), StringComparison.Ordinal))
      {
         throw new InvalidOperationException("A fixture request left its configured HTTPS origin.");
      }
      using var request = new HttpRequestMessage(new HttpMethod(method), uri);
      // Fixed classifications only: never retain a query, callback, identifier or body.
      LastRequestKind = Classify(uri, method);
      LastStatus = null;
      if(_adminToken is not null)
      {
         if(!uri.AbsolutePath.StartsWith("/api/v3/", StringComparison.Ordinal))
         {
            throw new InvalidOperationException("Administrative credential scope refused.");
         }
         request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _adminToken);
      }
      if(body is not null)
      {
         request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
         request.Headers.Add("Origin", _origin.GetLeftPart(UriPartial.Authority));
         request.Headers.Referrer = _origin;
         Cookie? csrf = _cookies.GetCookies(_origin).Cast<Cookie>().SingleOrDefault(cookie => string.Equals(cookie.Name, "authentik_csrf", StringComparison.Ordinal));
         csrf ??= _cookies.GetCookies(_origin).Cast<Cookie>().SingleOrDefault(cookie => string.Equals(cookie.Name, "csrftoken", StringComparison.Ordinal));
         if(csrf is not null)
         {
            request.Headers.Add("X-authentik-CSRF", csrf.Value);
         }
      }
      using HttpResponseMessage response = await _client.SendAsync(request, cancellationToken);
      LastStatus = (int)response.StatusCode;
      Uri? location = response.Headers.Location is null ? null : new Uri(_origin, response.Headers.Location);
      if(Trace.Count == 32)
      {
         Trace.RemoveAt(0);
      }
      Trace.Add(new TraceEntry(LastRequestKind, (int)response.StatusCode, uri.Query.Length != 0,
         location is null ? null : Classify(location, "GET"), location?.Query.Length > 0));
      string content = await response.Content.ReadAsStringAsync(cancellationToken);
      JsonObject? json = string.Equals(response.Content.Headers.ContentType?.MediaType, "application/json", StringComparison.Ordinal) && content.Length != 0
         ? JsonNode.Parse(content)?.AsObject() : null;
      return new Reply(response.StatusCode, json, response.Headers.Location);
   }



   /// <summary>Send one origin-confined request and retain bounded sanitized diagnostics.</summary>
   internal Task<Reply> SendAsync(string method, string path, JsonObject? body, CancellationToken cancellationToken)
      => SendAsync(method, new Uri(_origin, path), body, cancellationToken);



   /// <summary>Map an endpoint to a fixed label without exposing its path or query.</summary>
   private static string Classify(Uri uri, string method) => string.Equals(uri.Host, "callback.printing.internal", StringComparison.Ordinal) ? "callback"
      : uri.AbsolutePath.StartsWith("/application/o/authorize", StringComparison.Ordinal) ? "provider-authorization"
      : uri.AbsolutePath.StartsWith("/if/flow/", StringComparison.Ordinal) ? "provider-flow-page"
      : uri.AbsolutePath.StartsWith("/api/v3/flows/executor/", StringComparison.Ordinal) ? "provider-flow-executor-" + method
      : "fixture-api";



   /// <summary>Require the expected status and a parsed JSON object.</summary>
   internal static JsonObject RequireJson(Reply reply, HttpStatusCode expected)
   {
      if(reply.Status != expected || reply.Json is null)
      {
         throw new InvalidOperationException("Unexpected fixture API status or document shape.");
      }
      return reply.Json;
   }



   /// <summary>Dispose the owned HTTP client and handler.</summary>
   public void Dispose() => _client.Dispose();
}
