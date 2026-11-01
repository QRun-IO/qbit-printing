// Copyright 2026 Kingsrook, LLC
// SPDX-License-Identifier: Apache-2.0
using Qqq.PrintAgent.Core.Authentication;
namespace Printing.Development;



/// <summary>Applies production Core discovery rules before any fixture credentials are sent.</summary>
internal static class WorkloadDiscovery
{
   /// <summary>Require a compatible realtime extension after strict production discovery validation.</summary>
   internal static async Task<QqqApiDiscovery> DiscoverRealtimeAsync(QqqSessionClient client, CancellationToken cancellationToken)
   {
      QqqApiDiscovery discovery = await client.DiscoverAsync(cancellationToken);
      // Core discovery validates strict stable SemVer; realtime uses the same major gate as ConnectPrintingAsync.
      QqqApiDiscovery.Extension extension = discovery.Extensions.Single(item => string.Equals(item.Name, "printing", StringComparison.Ordinal));
      if(!Version.TryParse(extension.Version, out Version? version) || version.Major != 2)
      {
         throw new InvalidDataException("Native realtime printing API required.");
      }
      return discovery;
   }
}
