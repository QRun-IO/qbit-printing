// Copyright 2026 Kingsrook, LLC
// SPDX-License-Identifier: Apache-2.0

using System.Net;
using System.Text.Json.Nodes;

namespace Printing.NativeAuth;

/// <summary>Uses Authentik's real synthetic-user login stages; never fabricates a session or code.</summary>
internal static class ProviderFlow
{
   internal static string? LastComponent { get; private set; }
   internal static async Task AuthenticateAsync(Uri authorization, Uri origin, Uri callback, string username, string password, CancellationToken token)
   {
      using var browser = new FixtureHttp(origin, cookies: true);
      using var callbackHttp = new FixtureHttp(new Uri(callback.GetLeftPart(UriPartial.Authority)));
      Uri next = authorization;
      for(int hop = 0; hop < 24; hop++)
      {
         if(next.GetLeftPart(UriPartial.Authority) == callback.GetLeftPart(UriPartial.Authority))
         {
            if(next.AbsolutePath != callback.AbsolutePath) { throw new InvalidOperationException("Unexpected callback path."); }
            FixtureHttp.Reply result = await callbackHttp.SendAsync("GET", next, null, token);
            if(result.Status != HttpStatusCode.OK) { throw new InvalidOperationException("The HTTPS callback receiver refused the provider redirect."); }
            return;
         }
         if(next.GetLeftPart(UriPartial.Authority) != origin.GetLeftPart(UriPartial.Authority))
         { throw new InvalidOperationException("A provider redirect left the fixture."); }
         FixtureHttp.Reply page = await browser.SendAsync("GET", next, null, token);
         if(page.Status is HttpStatusCode.Redirect or HttpStatusCode.SeeOther or HttpStatusCode.TemporaryRedirect)
         {
            next = new Uri(next, page.Location ?? throw new InvalidOperationException("Missing provider redirect."));
            continue;
         }
         if(page.Status != HttpStatusCode.OK || !next.AbsolutePath.StartsWith("/if/flow/", StringComparison.Ordinal))
         { throw new InvalidOperationException("Unexpected provider login navigation."); }
         string slug = next.AbsolutePath["/if/flow/".Length..].Trim('/');
         if(slug.Length == 0 || slug.Any(character => !char.IsAsciiLetterOrDigit(character) && character != '-'))
         { throw new InvalidOperationException("Unexpected provider flow identifier."); }
         var executor = new Uri(origin, "/api/v3/flows/executor/" + slug + "/?query=" + Uri.EscapeDataString(next.Query.TrimStart('?')));
         JsonObject challenge = await ChallengeAsync(browser, executor, await browser.SendAsync("GET", executor, null, token), token);
         bool redirected = false;
         for(int stage = 0; stage < 10; stage++)
         {
            string component = ProviderSetup.Text(challenge, "component");
            LastComponent = component switch
            {
               "ak-stage-identification" => "identification",
               "ak-stage-password" => "password",
               "ak-stage-consent" => "consent",
               "xak-flow-redirect" => "redirect",
               "ak-stage-access-denied" => "access-denied",
               "ak-stage-flow-error" => "flow-error",
               _ => "unsupported",
            };
            if(component == "xak-flow-redirect")
            {
               next = new Uri(origin, ProviderSetup.Text(challenge, "to"));
               redirected = true;
               break;
            }
            JsonObject response = component switch
            {
               "ak-stage-identification" => new JsonObject { ["component"] = component, ["uid_field"] = username },
               "ak-stage-password" => new JsonObject { ["component"] = component, ["password"] = password },
               "ak-stage-consent" => new JsonObject { ["component"] = component, ["consent"] = true },
               _ => throw new InvalidOperationException("The provider requested an unsupported test login stage."),
            };
            challenge = await ChallengeAsync(browser, executor, await browser.SendAsync("POST", executor, response, token), token);
            if(challenge["response_errors"] is JsonObject errors && errors.Count != 0)
            { throw new InvalidOperationException("The real provider rejected the synthetic login response."); }
         }
         if(!redirected) { throw new InvalidOperationException("Provider stage bound exceeded."); }
      }
      throw new InvalidOperationException("Provider redirect bound exceeded.");
   }

   private static async Task<JsonObject> ChallengeAsync(FixtureHttp browser, Uri executor, FixtureHttp.Reply response, CancellationToken token)
   {
      // Authentik uses POST/redirect/GET when a real stage accepts its answer.
      // Follow only that same HTTPS executor; other navigation uses typed flow redirects.
      for(int redirect = 0; redirect < 8; redirect++)
      {
         if(response.Status == HttpStatusCode.OK) { return FixtureHttp.RequireJson(response, HttpStatusCode.OK); }
         if(response.Status is not (HttpStatusCode.Redirect or HttpStatusCode.SeeOther) || response.Location is null)
         { throw new InvalidOperationException("Unexpected provider stage response."); }
         var location = new Uri(executor, response.Location);
         if(location.GetLeftPart(UriPartial.Authority) != executor.GetLeftPart(UriPartial.Authority)
            || location.AbsolutePath != executor.AbsolutePath)
         { throw new InvalidOperationException("A stage redirect left its exact HTTPS executor."); }
         response = await browser.SendAsync("GET", location, null, token);
      }
      throw new InvalidOperationException("Provider stage redirect bound exceeded.");
   }
}
