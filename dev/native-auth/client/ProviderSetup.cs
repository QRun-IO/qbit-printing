using System.Net;
using System.Text.Json.Nodes;

namespace Printing.NativeAuth;

/// <summary>Configures only the fresh project's synthetic provider and subjects.</summary>
internal sealed class ProviderSetup : IDisposable
{
   internal static string? LastLookupKind { get; private set; }
   internal static int? LastLookupCount { get; private set; }
   internal static Dictionary<string, int> ReadinessCounts { get; } = new(StringComparer.Ordinal);
   private readonly JsonObject _input;
   private readonly FixtureHttp _admin;
   private string _provider = "";
   private string _authorizedUser = "";

   internal ProviderSetup(JsonObject input)
   {
      _input = input;
      _admin = new FixtureHttp(new Uri(Text(input, "providerOrigin")), adminToken: Text(input, "adminToken"));
   }

   internal static string Text(JsonObject document, string key) => document[key]?.ToString() ?? throw new InvalidOperationException("Required fixture field is absent.");

   private async Task<JsonObject> ExactAsync(string path, CancellationToken token)
   {
      JsonObject page = FixtureHttp.RequireJson(await _admin.SendAsync("GET", path, null, token), HttpStatusCode.OK);
      JsonArray records = page["results"]!.AsArray();
      LastLookupKind = LookupKind(path);
      LastLookupCount = records.Count;
      if(records.Count != 1) { throw new InvalidOperationException("Required provider object is absent or ambiguous."); }
      return records[0]!.AsObject();
   }

   private async Task<JsonObject> CreateAsync(string path, JsonObject body, CancellationToken token)
      => FixtureHttp.RequireJson(await _admin.SendAsync("POST", path, body, token), HttpStatusCode.Created);

   internal async Task WaitReadyAsync(CancellationToken token)
   {
      string[] paths = ["/api/v3/flows/instances/?slug=default-authentication-flow",
         "/api/v3/flows/instances/?slug=default-provider-authorization-implicit-consent",
         "/api/v3/flows/instances/?slug=default-provider-invalidation-flow",
         "/api/v3/propertymappings/provider/scope/?scope_name=openid",
         "/api/v3/propertymappings/provider/scope/?scope_name=profile",
         "/api/v3/propertymappings/provider/scope/?scope_name=email",
         "/api/v3/propertymappings/provider/scope/?scope_name=offline_access"];
      for(int attempt = 0; attempt < 60; attempt++)
      {
         bool ready = true;
         try
         {
            foreach(string path in paths)
            {
               FixtureHttp.Reply reply = await _admin.SendAsync("GET", path, null, token);
               JsonArray? records = reply.Status == HttpStatusCode.OK ? reply.Json?["results"] as JsonArray : null;
               string kind = LookupKind(path);
               ReadinessCounts[kind] = records?.Count ?? -1;
               if(records?.Count > 1) { throw new InvalidOperationException("An owned provider bootstrap dependency is ambiguous."); }
               bool present = records is { Count: 1 };
               if(kind == "authentication-flow")
               { present &= records is { Count: 1 } && records[0]?["stages"] is JsonArray { Count: >= 4 }; }
               ready &= present;
            }
            if(ready) { return; }
         }
         catch(HttpRequestException) { }
         await Task.Delay(TimeSpan.FromSeconds(2), token);
      }
      throw new InvalidOperationException("The owned provider bootstrap did not become ready.");
   }

   private static string LookupKind(string path) => path switch
   {
      "/api/v3/flows/instances/?slug=default-authentication-flow" => "authentication-flow",
      "/api/v3/flows/instances/?slug=default-provider-authorization-implicit-consent" => "authorization-flow",
      "/api/v3/flows/instances/?slug=default-provider-invalidation-flow" => "invalidation-flow",
      "/api/v3/propertymappings/provider/scope/?scope_name=openid" => "scope-openid",
      "/api/v3/propertymappings/provider/scope/?scope_name=profile" => "scope-profile",
      "/api/v3/propertymappings/provider/scope/?scope_name=email" => "scope-email",
      "/api/v3/propertymappings/provider/scope/?scope_name=offline_access" => "scope-offline-access",
      _ => "other",
   };

   internal async Task ConfigureAsync(CancellationToken token)
   {
      JsonObject authentication = await ExactAsync("/api/v3/flows/instances/?slug=default-authentication-flow", token);
      JsonObject authorization = await ExactAsync("/api/v3/flows/instances/?slug=default-provider-authorization-implicit-consent", token);
      JsonObject invalidation = await ExactAsync("/api/v3/flows/instances/?slug=default-provider-invalidation-flow", token);
      var mappings = new JsonArray();
      foreach(string scope in new[] { "openid", "profile", "email", "offline_access" })
      {
         mappings.Add((await ExactAsync("/api/v3/propertymappings/provider/scope/?scope_name=" + scope, token))["pk"]!.DeepClone());
      }
      JsonObject groupsMapping = await CreateAsync("/api/v3/propertymappings/provider/scope/", new JsonObject
      {
         ["name"] = Text(_input, "project") + "-groups",
         ["scope_name"] = "profile",
         ["expression"] = "return {\"groups\": [group.name for group in user.ak_groups.all()]}",
      }, token);
      mappings.Add(groupsMapping["pk"]!.DeepClone());
      JsonObject group = await CreateAsync("/api/v3/core/groups/", new JsonObject { ["name"] = "printing_admin", ["is_superuser"] = false }, token);
      JsonObject user = await CreateUserAsync("authorizedUsername", "authorizedPassword", new JsonArray(group["pk"]!.DeepClone()), token);
      _authorizedUser = Text(user, "pk");
      await CreateUserAsync("deniedUsername", "deniedPassword", new JsonArray(), token);
      JsonObject provider = await CreateAsync("/api/v3/providers/oauth2/", new JsonObject
      {
         ["name"] = Text(_input, "project"),
         ["authentication_flow"] = authentication["pk"]!.DeepClone(),
         ["authorization_flow"] = authorization["pk"]!.DeepClone(),
         ["invalidation_flow"] = invalidation["pk"]!.DeepClone(),
         ["client_type"] = "confidential",
         ["grant_types"] = new JsonArray("authorization_code", "refresh_token"),
         ["client_id"] = Text(_input, "clientId"),
         ["client_secret"] = Text(_input, "clientSecret"),
         ["access_code_validity"] = "minutes=1",
         ["access_token_validity"] = "seconds=20",
         ["refresh_token_validity"] = "minutes=5",
         ["refresh_token_threshold"] = "minutes=4",
         ["redirect_uris"] = new JsonArray(new JsonObject { ["matching_mode"] = "strict", ["url"] = Text(_input, "callback") }),
         ["property_mappings"] = mappings,
         ["issuer_mode"] = "per_provider",
         ["include_claims_in_id_token"] = true,
      }, token);
      _provider = Text(provider, "pk");
      if(Text(provider, "client_id") != Text(_input, "clientId")
         || Text(provider, "authentication_flow") != Text(authentication, "pk"))
      { throw new InvalidOperationException("The real provider did not retain the configured client identifier."); }
      JsonObject application = await CreateAsync("/api/v3/core/applications/", new JsonObject
      {
         ["name"] = Text(_input, "project"), ["slug"] = Text(_input, "project"),
         ["provider"] = provider["pk"]!.DeepClone(), ["policy_engine_mode"] = "all",
      }, token);
      if(Text(application, "provider") != _provider)
      { throw new InvalidOperationException("The real provider application binding differs from the configured provider."); }
   }

   private async Task<JsonObject> CreateUserAsync(string usernameKey, string passwordKey, JsonArray groups, CancellationToken token)
   {
      JsonObject user = await CreateAsync("/api/v3/core/users/", new JsonObject
      {
         ["username"] = Text(_input, usernameKey), ["name"] = "Synthetic native qualification subject",
         ["email"] = Text(_input, usernameKey) + "@example.invalid", ["is_active"] = true,
         ["type"] = "internal", ["groups"] = groups,
      }, token);
      FixtureHttp.Reply response = await _admin.SendAsync("POST", "/api/v3/core/users/" + Text(user, "pk") + "/set_password/",
         new JsonObject { ["password"] = Text(_input, passwordKey) }, token);
      if(response.Status != HttpStatusCode.NoContent) { throw new InvalidOperationException("Synthetic account password configuration failed."); }
      return user;
   }

   private async Task<JsonArray> GrantsAsync(string kind, CancellationToken token)
   {
      JsonObject page = FixtureHttp.RequireJson(await _admin.SendAsync("GET", "/api/v3/oauth2/" + kind + "/?provider=" + _provider + "&user=" + _authorizedUser, null, token), HttpStatusCode.OK);
      JsonArray records = page["results"]!.AsArray();
      if(records.Count is 0 or > 20) { throw new InvalidOperationException("The synthetic provider grant set is missing or unexpectedly large."); }
      foreach(JsonNode? node in records)
      {
         JsonObject record = node!.AsObject();
         string provider = record["provider"] is JsonObject nested ? Text(nested, "pk") : Text(record, "provider");
         if(provider != _provider || Text(record["user"]!.AsObject(), "pk") != _authorizedUser)
         { throw new InvalidOperationException("The provider grant escaped the synthetic subject scope."); }
      }
      return records;
   }

   internal async Task<HashSet<string>> AccessGrantIdsAsync(CancellationToken token)
      => (await GrantsAsync("access_tokens", token)).Select(node => Text(node!.AsObject(), "pk")).ToHashSet(StringComparer.Ordinal);

   internal async Task RevokeRefreshGrantsAsync(CancellationToken token)
   {
      foreach(JsonNode? node in await GrantsAsync("refresh_tokens", token))
      {
         string id = Text(node!.AsObject(), "pk");
         if(!Guid.TryParse(id, out _) && !long.TryParse(id, out _)) { throw new InvalidOperationException("Unexpected provider grant identifier."); }
         FixtureHttp.Reply response = await _admin.SendAsync("DELETE", "/api/v3/oauth2/refresh_tokens/" + id + "/", null, token);
         if(response.Status != HttpStatusCode.NoContent) { throw new InvalidOperationException("Synthetic provider revocation failed."); }
      }
   }

   public void Dispose() => _admin.Dispose();
}
