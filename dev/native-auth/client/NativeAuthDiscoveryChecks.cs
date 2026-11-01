/*
 * Copyright 2026 Kingsrook, LLC
 * SPDX-License-Identifier: Apache-2.0
 */

using System.Net;
using System.Text.Json;
using Qqq.PrintAgent.Core.Authentication;

namespace Printing.NativeAuth;

/// <summary>Exercises the real native probe gate against bounded, credential-free HTTP responses.</summary>
internal static class NativeAuthDiscoveryChecks
{
   /// <summary>Accepts compatible printing releases while preserving the exact upstream grant requirement.</summary>
   internal static async Task RunAsync()
   {
      (string PrintingVersion, string ApiVersion, bool NativeGrant, bool Accepted)[] cases =
      [
         ("2.0.0", "1.9.0", true, true),
         ("2.1.0", "1.9.0", true, true),
         ("2.9.17", "1.9.0", true, true),
         ("1.9.0", "1.9.0", true, false),
         ("3.0.0", "1.9.0", true, false),
         ("2.1", "1.9.0", true, false),
         ("2.01.0", "1.9.0", true, false),
         ("2.1.0-rc.1", "1.9.0", true, false),
         ("not-a-version", "1.9.0", true, false),
         ("2.1.0", "1.8.0", true, false),
         ("2.1.0", "1.10.0", true, false),
         ("2.1.0", "1.9.0", false, false),
      ];
      foreach((string printingVersion, string apiVersion, bool nativeGrant, bool expected) in cases)
      {
         using var handler = new DiscoveryHandler(printingVersion, apiVersion, nativeGrant);
         using var client = new QqqSessionClient(new Uri("https://printing.example.invalid"), handler);
         bool accepted = false;
         try
         {
            await NativeAuthDiscovery.DiscoverAsync(client, CancellationToken.None);
            accepted = true;
         }
         catch(QqqSessionException error) when(error.Failure == QqqSessionException.FailureKind.ConfigurationRequired)
         {
         }
         catch(InvalidDataException)
         {
         }
         catch(InvalidOperationException)
         {
         }
         if(accepted != expected || handler.Requests != 1)
         {
            throw new InvalidDataException($"Native discovery compatibility case {printingVersion}/{apiVersion}/grant={nativeGrant} failed.");
         }
      }
      Console.WriteLine($"Native discovery compatibility checks passed: {cases.Length}.");
   }



   /// <summary>Supplies discovery only and fails if the probe attempts authenticated traffic.</summary>
   private sealed class DiscoveryHandler(string printingVersion, string apiVersion, bool nativeGrant) : HttpMessageHandler
   {
      /// <summary>Ensures rejected metadata still traversed the public HTTP discovery boundary.</summary>
      internal int Requests { get; private set; }



      /// <summary>Refuses any credential or request outside the public discovery boundary.</summary>
      protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
      {
         if(request.Method != HttpMethod.Get || request.RequestUri?.AbsoluteUri != "https://printing.example.invalid/qqq/v1/version"
            || request.Headers.Contains("Cookie") || request.Headers.Contains("Authorization")
            || request.Headers.Contains("X-QQQ-Device-Id") || request.Headers.Contains("X-QQQ-Device-Token"))
         {
            throw new InvalidOperationException("Native discovery must precede authentication and device proof.");
         }
         Requests++;
         string[] features = nativeGrant
            ? ["version", "metaData", "processes", "processCancel", "extensions", "widgets", "nativeSessionLogin"]
            : ["version", "metaData", "processes", "processCancel", "extensions", "widgets"];
         string json = JsonSerializer.Serialize(new
         {
            apiVersion,
            features,
            clientChannels = new[] { "api" },
            extensions = new[] { new { name = "printing", version = printingVersion, pathPrefix = "/qbit/printing" } }
         });
         return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json) });
      }
   }
}
