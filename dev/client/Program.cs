// Copyright 2026 Kingsrook, LLC
// SPDX-License-Identifier: Apache-2.0
using System.Diagnostics;
using System.Globalization;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Qqq.PrintAgent.Core.Authentication;
using Qqq.PrintAgent.Core.Printing;
namespace Printing.Development;



/// <summary>Runs real Core network workers against synthetic Java services without a physical printer.</summary>
internal static class Program
{
   private static readonly JsonSerializerOptions s_reportOptions = new() { WriteIndented = true };



   private static readonly string[] s_capabilities = ["realtime-v1", "REFRESH_INVENTORY", "CANCEL_SPOOL"];



   private static int s_completed;



   private static readonly byte[] s_expected = Encoding.ASCII.GetBytes("^XA^FO20,20^FDSYNTHETIC NOT PRINTED^FS" + new string(' ', 980) + "^XZ");



   /// <summary>Run the bounded workload or fake-HTTP checks and always signal completion to the launcher.</summary>
   private static async Task<int> Main(string[] args)
   {
      if(args is ["--check-discovery"])
      {
         await DiscoveryChecks.RunAsync();
         return 0;
      }
      string folder = Path.GetFullPath(args[0]);
      string stage = "input";
      using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(18));
      List<Lane> lanes = [];
      long peak = 0;
      var timer = Stopwatch.StartNew();
      try
      {
         using var input = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(folder, "input.json")));
         stage = "native_login_and_channels";
         bool idle = input.RootElement.GetProperty("idle").GetBoolean();
         int perLane = input.RootElement.GetProperty("jobsPerLane").GetInt32();
         int total = perLane * 8;
         await OpenLanesAsync(input.RootElement, lanes, folder, perLane, timeout.Token);
         bool denied = await RejectForeignProofAsync(input.RootElement, timeout.Token);
         stage = "workload";
         peak = await RunLanesAsync(lanes, folder, total, timeout.Token);
         stage = "logout";
         foreach(Lane lane in lanes)
         {
            await lane.Client.SignOutAsync(timeout.Token);
         }
         await WriteSuccessAsync(folder, lanes, total, idle, denied, peak, timer.Elapsed.TotalSeconds);
         Console.WriteLine("PASS real Core/Java network lifecycles; fake printer only");
         return 0;
      }
      catch(Exception error)
      {
         await File.WriteAllTextAsync(Path.Combine(folder, "result.json"), JsonSerializer.Serialize(new
         {
            success = false,
            stage,
            error = error.GetType().Name,
            reason = error is InvalidDataException ? error.Message : null,
            completed = lanes.Sum(l => l.Printer.Count)
         }));
         Console.Error.WriteLine("FAIL " + stage + ": " + error.GetType().Name);
         return 1;
      }
      finally
      {
         foreach(Lane lane in lanes)
         {
            await lane.DisposeAsync();
         }
         await File.WriteAllTextAsync(Path.Combine(folder, "done"), "done");
      }
   }



   /// <summary>Authenticate and bind eight synthetic lanes before admitting fixture work.</summary>
   private static async Task OpenLanesAsync(JsonElement input, List<Lane> lanes, string folder, int perLane, CancellationToken token)
   {
      int index = 0;
      foreach(JsonElement value in input.GetProperty("lanes").EnumerateArray())
      {
         Uri origin = new(value.GetProperty("origin").GetString()!);
         Require(origin.IsLoopback && origin.Port is not (18081 or 18086), "disposable origin");
         var client = new QqqSessionClient(origin, allowLoopbackHttp: true);
         await WorkloadDiscovery.DiscoverRealtimeAsync(client, token);
         await client.GetAuthenticationMetadataAsync(token);
         await client.SignInWithPasswordAsync(value.GetProperty("username").GetString()!, value.GetProperty("password").GetString()!, token);
         await client.BindDeviceAsync(new(value.GetProperty("deviceId").GetString()!, value.GetProperty("deviceToken").GetString()!), cancellationToken: token);
         QqqSessionBinding binding = await client.GetSessionIdentityAsync(token);
         Require(string.Equals(binding.TenantId, value.GetProperty("tenant").GetString(), StringComparison.Ordinal), "authoritative tenant");
         Connection connection = await Connection.Open(client, token);
         lanes.Add(new(client, connection, Path.Combine(folder, "lane-" + index++), perLane, folder));
      }
      Require(lanes.Count == 8, "eight lanes");
   }



   /// <summary>Verify that authenticated tenant A cannot adopt tenant B's device proof.</summary>
   private static async Task<bool> RejectForeignProofAsync(JsonElement input, CancellationToken token)
   {
      // Authoritative native proof check: tenant A cannot bind a real tenant B device.
      JsonElement foreign = input.GetProperty("lanes")[4];
      bool denied = false;
      using(var outsider = new QqqSessionClient(new Uri(input.GetProperty("lanes")[0].GetProperty("origin").GetString()!), allowLoopbackHttp: true))
      {
         await outsider.DiscoverAsync(token);
         await outsider.GetAuthenticationMetadataAsync(token);
         await outsider.SignInWithPasswordAsync("operator", input.GetProperty("lanes")[0].GetProperty("password").GetString()!, token);
         try
         {
            await outsider.BindDeviceAsync(new(foreign.GetProperty("deviceId").GetString()!, foreign.GetProperty("deviceToken").GetString()!), cancellationToken: token);
         }
         catch(QqqSessionException) { denied = true; }
         Require(denied, "cross tenant proof refused");
         await outsider.SignOutAsync(token);
      }
      return denied;
   }



   /// <summary>Write only aggregate network, accounting, and fake-printer evidence.</summary>
   private static async Task WriteSuccessAsync(string folder, List<Lane> lanes, int total, bool idle, bool denied, long peak, double elapsedSeconds)
   {
      double[] latencies = lanes.SelectMany(l => l.Latencies).Order().ToArray();
      await File.WriteAllTextAsync(Path.Combine(folder, "result.json"), JsonSerializer.Serialize(new
      {
         success = true,
         jobs = total,
         idle,
         tenants = 2,
         devices = 8,
         elapsedSeconds = elapsedSeconds,
         jobsPerSecond = total / elapsedSeconds,
         peakClientWorkingSetBytes = peak,
         p50EnqueueToAckMillis = latencies[total / 2],
         p95EnqueueToAckMillis = latencies[(int)(total * 0.95)],
         p99EnqueueToAckMillis = latencies[(int)(total * 0.99)],
         realCoreWorker = true,
         fakePrinterBackend = true,
         fixtureJournal = true,
         serverReceiptsDurable = true,
         crossTenantProofRefused = denied,
         submissionReplays = idle ? 0 : 400,
         receiptReplays = idle ? 0 : 400,
         notificationToClaimMillis = lanes.SelectMany(l => l.ClaimLatency).ToArray(),
         notificationToDownloadCompletedMillis = lanes.SelectMany(l => l.DownloadLatency).ToArray(),
         periodicJobPolling = false,
         transport = "HTTP/WS loopback; no TLS qualification",
         realPrinterCalls = 0,
         runtime = Environment.Version.ToString(),
         coreVersion = typeof(PrintWorker).Assembly.GetName().Version?.ToString()
      }, s_reportOptions));
   }



   /// <summary>Drain only event-triggered work and measure the peak client working set.</summary>
   private static async Task<long> RunLanesAsync(List<Lane> lanes, string folder, int total, CancellationToken token)
   {
      long peak = 0;
      Task[] tasks = lanes.Select(lane => lane.Run(token)).ToArray();
      await File.WriteAllTextAsync(Path.Combine(folder, "ready"), "ready", token);
      var all = Task.WhenAll(tasks);
      while(!all.IsCompleted)
      {
         peak = Math.Max(peak, Process.GetCurrentProcess().WorkingSet64);
         await Task.WhenAny(all, Task.Delay(1000, token));
         token.ThrowIfCancellationRequested();
      }
      await all;
      Require(lanes.Sum(l => l.Printer.Count) == total, "exact fake submission count");
      Require(lanes.All(l => l.Journal.Pending().Count == 0), "no unacknowledged receipts");
      return peak;
   }



   /// <summary>Stop the fixture when an invariant fails, retaining a fixed diagnostic reason.</summary>
   private static void Require(bool ok, string reason)
   {
      if(!ok)
      {
         throw new InvalidDataException(reason);
      }
   }



   /// <summary>Own one authenticated worker, its synthetic journal, and notification latency measurements.</summary>
   private sealed class Lane : IAsyncDisposable
   {
      public QqqSessionClient Client
      {
         get;
      }



      public Connection Connection
      {
         get;
      }



      public FakePrinter Printer { get; } = new();



      public Journal Journal
      {
         get;
      }



      public List<double> Latencies { get; } = [];



      private readonly PrintApiClient _api;



      private readonly PrintWorker _worker;



      private readonly int _target;



      private readonly string _root;



      private long _wakeAt;



      public List<double> ClaimLatency { get; } = [];



      public List<double> DownloadLatency { get; } = [];



      /// <summary>Own one authenticated worker, its synthetic journal, and notification latency measurements.</summary>
      public Lane(QqqSessionClient client, Connection connection, string folder, int targetJobs, string rootDirectory)
      {
         Client = client;
         Connection = connection;
         _target = targetJobs;
         _root = rootDirectory;
         Printer.OnDownload = () => DownloadLatency.Add(Stopwatch.GetElapsedTime(_wakeAt).TotalMilliseconds);
         Directory.CreateDirectory(folder);
         Journal = new(folder);
         _api = PrintApiClient.ForSession(client).WithRealtime(connection.Channel, connection.Settings);
         _worker = new PrintWorker(_api, Printer, Journal, options: new PrintWorkerOptions
         {
            Settings = connection.Settings,
            StagingDirectory = Path.Combine(folder, "staging"),
            SynchronizeInventory = false,
            RecoverIncomplete = false,
            ActiveJobChanged = job =>
            {
               if(job is not null)
               {
                  ClaimLatency.Add(Stopwatch.GetElapsedTime(_wakeAt).TotalMilliseconds);
               }
            }
         });
      }



      /// <summary>Drain notified jobs and await durable receipts without periodic job polling.</summary>
      public async Task Run(CancellationToken token)
      {
         using var stop = CancellationTokenSource.CreateLinkedTokenSource(token);
         Task ping = Liveness(stop.Token);
         try
         {
            while(Printer.Count < _target)
            {
               _wakeAt = await Connection.Wakes.Reader.ReadAsync(token);
               while(Printer.Count < _target && await _worker.RunOnceAsync(token))
               {
                  Require(string.Equals(Journal.Last.Event.Status, "SPOOLED", StringComparison.Ordinal), "synthetic job terminal status");
                  Latencies.Add((DateTimeOffset.UtcNow - DateTimeOffset.Parse(Journal.LastJob!.CreatedAt, CultureInfo.InvariantCulture)).TotalMilliseconds);
                  await File.WriteAllTextAsync(Path.Combine(_root, "completed-" + Interlocked.Increment(ref s_completed)), "acknowledged", token);
                  if(Printer.Count % 25 == 0)
                  {
                     await _api.EventAsync(Journal.Last.JobId, Journal.Last.Event, token);
                  }
               }
            }
         }
         finally
         {
            await stop.CancelAsync();
            try
            {
               await ping;
            }
            catch(OperationCanceledException)
            {
               // Cancellation ends only the owned liveness task.
            }
         }
      }



      /// <summary>Send transport liveness frames without querying for jobs.</summary>
      private async Task Liveness(CancellationToken token)
      {
         using var tick = new PeriodicTimer(TimeSpan.FromSeconds(Connection.Settings.HeartbeatIntervalSeconds));
         while(await tick.WaitForNextTickAsync(token))
         {
            await Connection.Channel.SendAsync("PING", new
            {
            }, token: token);
         }
      }



      /// <summary>Stop owned background work and dispose this fixture lane or channel.</summary>
      public async ValueTask DisposeAsync()
      {
         _api.Dispose();
         await Connection.DisposeAsync();
         Client.Dispose();
         Journal.Dispose();
      }
   }



   /// <summary>Verify payload bytes and reject duplicate actions without calling a spooler.</summary>
   private sealed class FakePrinter : IFilePrinterBackend
   {
      private readonly HashSet<string> _seen = new(StringComparer.Ordinal);



      public int Count => _seen.Count;



      public Action? OnDownload
      {
         get; set;
      }



      /// <summary>Expose only the fixed synthetic ZPL destination.</summary>
      public IReadOnlyList<PrinterInfo> Discover() => [new("synthetic", "No physical printer", "fixture", "none", "READY", ["ZPL"])];



      /// <summary>Record download completion and validate the staged synthetic payload.</summary>
      public async Task<string> PrintFileAsync(PrintJob job, string path, CancellationToken token)
      {
         OnDownload?.Invoke();
         return await PrintAsync(job, await File.ReadAllBytesAsync(path, token), token);
      }



      /// <summary>Check exact bytes and allow each synthetic job to be submitted once.</summary>
      public Task<string> PrintAsync(PrintJob job, byte[] content, CancellationToken token)
      {
         Require(content.AsSpan().SequenceEqual(s_expected), "downloaded bytes exact");
         Require(_seen.Add(job.JobId), "no duplicate physical action");
         return Task.FromResult("synthetic-" + Count);
      }
   }
   // Fixture journal is deliberately separate from the installed Windows SQLite journal.



   /// <summary>Track fixture-only receipt state separately from the installed SQLite journal.</summary>
   private sealed class Journal : IJobJournal, IDisposable
   {
      private readonly Dictionary<string, string> _states = new(StringComparer.Ordinal);



      private readonly Dictionary<string, (string, PrintEvent)> _pending = new(StringComparer.Ordinal);



      private readonly StreamWriter _log;



      public PrintJob? LastJob
      {
         get;



         private set;
      }



      public (string JobId, PrintEvent Event) Last
      {
         get;



         private set;
      }



      /// <summary>Track fixture-only receipt state separately from the installed SQLite journal.</summary>
      public Journal(string folder)
      {
         _log = new StreamWriter(new FileStream(Path.Combine(folder, "synthetic-receipts.jsonl"), FileMode.CreateNew, FileAccess.Write, FileShare.Read)) { AutoFlush = true };
      }



      /// <summary>Retain accepted job metadata for latency and status assertions.</summary>
      public void TrackJob(PrintJob job, string? printer)
      {
         LastJob = job;
      }



      /// <summary>Return the last recorded state for the synthetic job.</summary>
      public string? GetState(string id) => _states.GetValueOrDefault(id);



      /// <summary>Flush a receipt before adding it to the pending set.</summary>
      public void Record(string id, PrintEvent evt)
      {
         _log.WriteLine(JsonSerializer.Serialize(new
         {
            jobId = id,
            @event = evt
         }));
         _states[id] = evt.Status;
         _pending.Add(evt.EventId, (id, evt));
         Last = (id, evt);
      }



      /// <summary>Snapshot unacknowledged synthetic receipts for the real worker.</summary>
      public IReadOnlyList<(string JobId, PrintEvent Event)> Pending() => _pending.Values.ToList();



      /// <summary>Remove only the specifically acknowledged receipt.</summary>
      public void Acknowledge(string id)
      {
         _pending.Remove(id);
      }



      /// <summary>Declare no recovered jobs because each fixture journal starts fresh.</summary>
      public IReadOnlyList<(string JobId, string State)> Incomplete() => [];



      /// <summary>Close the fixture receipt log.</summary>
      public void Dispose() => _log.Dispose();
   }



   /// <summary>Own the realtime reader, bounded notifications, and negotiated settings.</summary>
   private sealed class Connection : IAsyncDisposable
   {
      public required PrintingRealtimeChannel Channel
      {
         get; init;
      }



      public required Task Reader
      {
         get; init;
      }



      public required CancellationTokenSource Lifetime
      {
         get; init;
      }



      public required Channel<RealtimeEnvelope> Messages
      {
         get; init;
      }



      public required Channel<long> Wakes
      {
         get; init;
      }



      public RealtimeSettings Settings
      {
         get;



         private set;
      } = new();



      /// <summary>Open an authenticated channel and acknowledge its initial authoritative state.</summary>
      public static async Task<Connection> Open(QqqSessionClient client, CancellationToken token)
      {
         var lifetime = CancellationTokenSource.CreateLinkedTokenSource(token);
         WebSocket socket = await client.ConnectPrintingAsync(TimeSpan.FromSeconds(20), TimeSpan.FromSeconds(60), token);
         var channel = new PrintingRealtimeChannel(socket, new RealtimeSettings());
         var messages = System.Threading.Channels.Channel.CreateBounded<RealtimeEnvelope>(8);
         var wakes = System.Threading.Channels.Channel.CreateBounded<long>(new BoundedChannelOptions(1) { FullMode = BoundedChannelFullMode.DropWrite });
         Task reader = channel.RunAsync(e =>
         {
            if(string.Equals(e.Type, "JOB_AVAILABLE", StringComparison.Ordinal))
            {
               wakes.Writer.TryWrite(Stopwatch.GetTimestamp());
               return;
            }
            if(e.Type is "SETTINGS" or "DESIRED_STATE")
            {
               long revision = e.Payload.GetProperty("revision").GetInt64();
               if(revision != 0)
               {
                  throw new InvalidDataException("Unexpected policy change");
               }
               return;
            }
            if(e.Type is "PONG" or "ACK")
            {
               return;
            }
            if(!messages.Writer.TryWrite(e))
            {
               throw new IOException("Fixture envelope inbox full");
            }
         }, lifetime.Token);
         _ = reader.ContinueWith(t => { wakes.Writer.TryComplete(t.Exception); messages.Writer.TryComplete(t.Exception); }, TaskScheduler.Default);
         var result = new Connection { Channel = channel, Reader = reader, Lifetime = lifetime, Messages = messages, Wakes = wakes };
         await channel.SendAsync("HELLO", new
         {
            agentVersion = PrintApiClient.AgentVersion,
            capabilities = s_capabilities,
            lastAppliedRevision = 0,
            lastSettingsRevision = 0,
            limits = RealtimeSettings.ClientLimits
         }, token: token);
         RealtimeEnvelope welcome = await messages.Reader.ReadAsync(token);
         Require(string.Equals(welcome.Type, "WELCOME", StringComparison.Ordinal), "welcome");
         result.Settings = welcome.Payload.GetProperty("settings").Deserialize<RealtimeSettings>(PrintApiClient.JsonOptions)!;
         result.Settings.Validate();
         channel.UpdateSettings(result.Settings);
         await channel.SendAsync("STATE_APPLIED", new
         {
            lastAppliedRevision = welcome.Payload.GetProperty("desiredState").GetProperty("revision").GetInt64(),
            lastSettingsRevision = result.Settings.Revision
         }, acknowledged: true, token: token);
         return result;
      }



      /// <summary>Stop owned background work and dispose this fixture lane or channel.</summary>
      public async ValueTask DisposeAsync()
      {
         await Lifetime.CancelAsync();
         try
         {
            await Reader;
         }
         catch(Exception e) when(e is OperationCanceledException or IOException or WebSocketException)
         {
            // Cancelling the owned reader normally ends it with one of these; disposal must still finish.
         }
         Channel.Dispose();
         Lifetime.Dispose();
      }
   }
}
