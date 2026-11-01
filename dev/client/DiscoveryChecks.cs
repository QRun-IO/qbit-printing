// Copyright 2026 Kingsrook, LLC
// SPDX-License-Identifier: Apache-2.0
using System.Net;
using System.Text.Json;
using Qqq.PrintAgent.Core.Authentication;
namespace Printing.Development;



/// <summary>Exercises fixture compatibility decisions using credential-free fake HTTP responses.</summary>
internal static class DiscoveryChecks
{
   private static readonly string[] s_features = ["version", "metaData", "processes", "processCancel", "extensions", "widgets"];



   private static readonly string[] s_channels = ["api"];



   /// <summary>Verify supported stable versions and reject malformed or incompatible versions.</summary>
   internal static async Task RunAsync()
   {
      (string Version, bool Accepted)[] cases =
      [
         ("2.0.0", true), ("2.1.0", true), ("2.9.17", true),
         ("1.9.0", false), ("3.0.0", false), ("2.1", false),
         ("2.01.0", false), ("2.1.0-rc.1", false), ("not-a-version", false)
      ];
      foreach((string version, bool expected) in cases)
      {
         using var handler = new DiscoveryHandler(version);
         using var client = new QqqSessionClient(new Uri("https://printing.example.invalid"), handler);
         bool accepted = false;
         try
         {
            await WorkloadDiscovery.DiscoverRealtimeAsync(client, CancellationToken.None);
            accepted = true;
         }
         catch(QqqSessionException error) when(error.Failure == QqqSessionException.FailureKind.ConfigurationRequired)
         {
            // Expected for incompatible versions: the case stays rejected and the comparison below judges it.
         }
         catch(InvalidDataException)
         {
            // Expected for malformed versions: the case stays rejected and the comparison below judges it.
         }
         if(accepted != expected || handler.Requests != 1)
         {
            throw new InvalidDataException($"Discovery compatibility case {version} failed.");
         }
      }
      Console.WriteLine($"Discovery compatibility checks passed: {cases.Length}.");
   }



   /// <summary>Supply one version response and reject any premature credential transmission.</summary>
   private sealed class DiscoveryHandler(string version) : HttpMessageHandler
   {
      internal int Requests
      {
         get;



         private set;
      }



      /// <summary>Return only the synthetic discovery response and count the request.</summary>
      protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
      {
         if(request.Method != HttpMethod.Get
            || !string.Equals(request.RequestUri?.AbsolutePath, "/qqq/v1/version", StringComparison.Ordinal)
            || request.Headers.Contains("Cookie")
            || request.Headers.Contains("Authorization"))
         {
            throw new InvalidOperationException("Discovery must precede authentication.");
         }
         Requests++;
         string json = JsonSerializer.Serialize(new
         {
            apiVersion = "1.8.0",
            features = s_features,
            clientChannels = s_channels,
            extensions = new[] { new { name = "printing", version, pathPrefix = "/qbit/printing" } }
         });
         return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json) });
      }
   }
}
