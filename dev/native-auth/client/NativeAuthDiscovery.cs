/*
 * Copyright 2026 Kingsrook, LLC
 * SPDX-License-Identifier: Apache-2.0
 */

using Qqq.PrintAgent.Core.Authentication;

namespace Printing.NativeAuth;

/// <summary>Requires the pinned experimental native grant before provider authentication.</summary>
internal static class NativeAuthDiscovery
{
   /// <summary>Checks actual production discovery without sending credentials.</summary>
   internal static async Task DiscoverAsync(QqqSessionClient client, CancellationToken token)
   {
      QqqApiDiscovery discovery = await WorkloadDiscovery.DiscoverRealtimeAsync(client, token);
      if(discovery.ApiVersion != "1.9.0" || !discovery.Features.Contains("nativeSessionLogin", StringComparer.Ordinal))
      {
         throw new InvalidOperationException("The actual server did not advertise the pinned native-session API.");
      }
   }
}
