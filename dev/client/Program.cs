// SPDX-License-Identifier: Apache-2.0
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Qqq.PrintAgent.Core.Authentication;
using Qqq.PrintAgent.Core.Printing;
internal static class Program {
 static int Completed; static readonly byte[] Expected=Encoding.ASCII.GetBytes("^XA^FO20,20^FDSYNTHETIC NOT PRINTED^FS"+new string(' ',980)+"^XZ");
 static async Task<int> Main(string[] args) {
  string folder=Path.GetFullPath(args[0]); string stage="input";
  using var timeout=new CancellationTokenSource(TimeSpan.FromMinutes(18));
  List<Lane> lanes=[]; long peak=0; var timer=Stopwatch.StartNew();
  try {
   using JsonDocument input=JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(folder,"input.json")));
   stage="native_login_and_channels";
   bool idle=input.RootElement.GetProperty("idle").GetBoolean(); int perLane=input.RootElement.GetProperty("jobsPerLane").GetInt32(); int total=perLane*8; int index=0;
   foreach(var value in input.RootElement.GetProperty("lanes").EnumerateArray()) {
    Uri origin=new(value.GetProperty("origin").GetString()!);
    Require(origin.IsLoopback && origin.Port is not (18081 or 18086),"disposable origin");
    var client=new QqqSessionClient(origin,allowLoopbackHttp:true);
    var discovery=await client.DiscoverAsync(timeout.Token);
    Require(discovery.Extensions.Any(e=>e.Name=="printing"&&e.Version=="2.0.0"),"published native API");
    await client.GetAuthenticationMetadataAsync(timeout.Token);
    await client.SignInWithPasswordAsync(value.GetProperty("username").GetString()!,value.GetProperty("password").GetString()!,timeout.Token);
    await client.BindDeviceAsync(new(value.GetProperty("deviceId").GetString()!,value.GetProperty("deviceToken").GetString()!),cancellationToken:timeout.Token);
    var binding=await client.GetSessionIdentityAsync(timeout.Token);
    Require(binding.TenantId==value.GetProperty("tenant").GetString(),"authoritative tenant");
    var connection=await Connection.Open(client,timeout.Token);
    lanes.Add(new(client,connection,Path.Combine(folder,"lane-"+index++),perLane,folder));
   }
   Require(lanes.Count==8,"eight lanes");
   // Authoritative native proof check: tenant A cannot bind a real tenant B device.
   var foreign=input.RootElement.GetProperty("lanes")[4]; bool denied=false;
   using(var outsider=new QqqSessionClient(new Uri(input.RootElement.GetProperty("lanes")[0].GetProperty("origin").GetString()!),allowLoopbackHttp:true)) {
    await outsider.DiscoverAsync(timeout.Token);await outsider.GetAuthenticationMetadataAsync(timeout.Token);
    await outsider.SignInWithPasswordAsync("operator",input.RootElement.GetProperty("lanes")[0].GetProperty("password").GetString()!,timeout.Token);
    try {await outsider.BindDeviceAsync(new(foreign.GetProperty("deviceId").GetString()!,foreign.GetProperty("deviceToken").GetString()!),cancellationToken:timeout.Token);}catch(QqqSessionException){denied=true;}
    Require(denied,"cross tenant proof refused");await outsider.SignOutAsync(timeout.Token);
   }
   stage="workload";
   var tasks=lanes.Select(lane=>lane.Run(timeout.Token)).ToArray();
   await File.WriteAllTextAsync(Path.Combine(folder,"ready"),"ready",timeout.Token);
   Task all=Task.WhenAll(tasks);
   while(!all.IsCompleted) {
    peak=Math.Max(peak,Process.GetCurrentProcess().WorkingSet64);
    await Task.WhenAny(all,Task.Delay(1000,timeout.Token));timeout.Token.ThrowIfCancellationRequested();
   }
   await all;
   Require(lanes.Sum(l=>l.Printer.Count)==total,"exact fake submission count");
   Require(lanes.All(l=>l.Journal.Pending().Count==0),"no unacknowledged receipts");
   stage="logout";foreach(var lane in lanes)await lane.Client.SignOutAsync(timeout.Token);
   double[] latencies=lanes.SelectMany(l=>l.Latencies).Order().ToArray();
   await File.WriteAllTextAsync(Path.Combine(folder,"result.json"),JsonSerializer.Serialize(new {
    success=true,jobs=total,idle,tenants=2,devices=8,elapsedSeconds=timer.Elapsed.TotalSeconds,jobsPerSecond=total/timer.Elapsed.TotalSeconds,
    peakClientWorkingSetBytes=peak,p50EnqueueToAckMillis=latencies[total/2],p95EnqueueToAckMillis=latencies[(int)(total*0.95)],p99EnqueueToAckMillis=latencies[(int)(total*0.99)],
    realCoreWorker=true,fakePrinterBackend=true,fixtureJournal=true,serverReceiptsDurable=true,crossTenantProofRefused=denied,
    submissionReplays=idle?0:400,receiptReplays=idle?0:400,notificationToClaimMillis=lanes.SelectMany(l=>l.ClaimLatency).ToArray(),notificationToDownloadCompletedMillis=lanes.SelectMany(l=>l.DownloadLatency).ToArray(),periodicJobPolling=false,transport="HTTP/WS loopback; no TLS qualification",realPrinterCalls=0,
    runtime=Environment.Version.ToString(),coreVersion=typeof(PrintWorker).Assembly.GetName().Version?.ToString()
   },new JsonSerializerOptions{WriteIndented=true}));
   Console.WriteLine("PASS real Core/Java network lifecycles; fake printer only");return 0;
  } catch(Exception error) {
   await File.WriteAllTextAsync(Path.Combine(folder,"result.json"),JsonSerializer.Serialize(new{success=false,stage,error=error.GetType().Name,reason=error is InvalidDataException?error.Message:null,completed=lanes.Sum(l=>l.Printer.Count)}));
   Console.Error.WriteLine("FAIL "+stage+": "+error.GetType().Name);return 1;
  } finally {
   foreach(var lane in lanes)await lane.DisposeAsync();
   await File.WriteAllTextAsync(Path.Combine(folder,"done"),"done");
  }
 }
 static void Require(bool ok,string reason){if(!ok)throw new InvalidDataException(reason);}
 sealed class Lane: IAsyncDisposable {
  public QqqSessionClient Client{get;} public Connection Connection{get;}
  public FakePrinter Printer{get;}=new(); public Journal Journal{get;} public List<double> Latencies{get;}=[];
  readonly PrintApiClient api;readonly PrintWorker worker; readonly int target; readonly string root; long wakeAt; public List<double> ClaimLatency{get;}=[]; public List<double> DownloadLatency{get;}=[];
  public Lane(QqqSessionClient client,Connection connection,string folder,int target,string root) {
   Client=client;Connection=connection;this.target=target;this.root=root;Printer.OnDownload=()=>DownloadLatency.Add(Stopwatch.GetElapsedTime(wakeAt).TotalMilliseconds);Directory.CreateDirectory(folder);Journal=new(folder);
   api=PrintApiClient.ForSession(client).WithRealtime(connection.Channel,connection.Settings);
   worker=new PrintWorker(api,Printer,Journal,options:new PrintWorkerOptions{Settings=connection.Settings,StagingDirectory=Path.Combine(folder,"staging"),SynchronizeInventory=false,RecoverIncomplete=false,ActiveJobChanged=job=>{if(job is not null)ClaimLatency.Add(Stopwatch.GetElapsedTime(wakeAt).TotalMilliseconds);}});
  }
  public async Task Run(CancellationToken token) {
   using var stop=CancellationTokenSource.CreateLinkedTokenSource(token);
   Task ping=Liveness(stop.Token);
   try {
    while(Printer.Count<target) {
     wakeAt=await Connection.Wakes.Reader.ReadAsync(token);
     while(Printer.Count<target && await worker.RunOnceAsync(token)) {
      Require(Journal.Last.Event.Status=="SPOOLED","synthetic job terminal status");
      Latencies.Add((DateTimeOffset.UtcNow-DateTimeOffset.Parse(Journal.LastJob!.CreatedAt,CultureInfo.InvariantCulture)).TotalMilliseconds);
      await File.WriteAllTextAsync(Path.Combine(root,"completed-"+Interlocked.Increment(ref Completed)),"acknowledged",token);
      if(Printer.Count%25==0)await api.EventAsync(Journal.Last.JobId,Journal.Last.Event,token);
     }
    }
   } finally {await stop.CancelAsync();try{await ping;}catch(OperationCanceledException){}}
  }
  async Task Liveness(CancellationToken token) {
   using var tick=new PeriodicTimer(TimeSpan.FromSeconds(Connection.Settings.HeartbeatIntervalSeconds));
   while(await tick.WaitForNextTickAsync(token))await Connection.Channel.SendAsync("PING",new{},token:token);
  }
  public async ValueTask DisposeAsync(){api.Dispose();await Connection.DisposeAsync();Client.Dispose();Journal.Dispose();}
 }
 sealed class FakePrinter:IFilePrinterBackend {
  readonly HashSet<string> seen=new(StringComparer.Ordinal);public int Count=>seen.Count; public Action? OnDownload{get;set;}
  public IReadOnlyList<PrinterInfo> Discover()=>[new("synthetic","No physical printer","fixture","none","READY",["ZPL"])];
  public async Task<string> PrintFileAsync(PrintJob job,string path,CancellationToken token) {OnDownload?.Invoke();return await PrintAsync(job,await File.ReadAllBytesAsync(path,token),token);}
  public Task<string> PrintAsync(PrintJob job,byte[] content,CancellationToken token) {
   Require(content.AsSpan().SequenceEqual(Expected),"downloaded bytes exact");Require(seen.Add(job.JobId),"no duplicate physical action");return Task.FromResult("synthetic-"+Count);
  }
 }
 // Fixture journal is deliberately separate from the installed Windows SQLite journal.
 sealed class Journal: IJobJournal,IDisposable {
  readonly Dictionary<string,string> states=new(StringComparer.Ordinal);readonly Dictionary<string,(string,PrintEvent)> pending=new(StringComparer.Ordinal);readonly StreamWriter log;
  public PrintJob? LastJob{get;private set;}public (string JobId,PrintEvent Event) Last{get;private set;}
  public Journal(string folder){log=new StreamWriter(new FileStream(Path.Combine(folder,"synthetic-receipts.jsonl"),FileMode.CreateNew,FileAccess.Write,FileShare.Read)){AutoFlush=true};}
  public void TrackJob(PrintJob job,string? printer){LastJob=job;}
  public string? GetState(string id)=>states.GetValueOrDefault(id);
  public void Record(string id,PrintEvent evt){log.WriteLine(JsonSerializer.Serialize(new{jobId=id,@event=evt}));states[id]=evt.Status;pending.Add(evt.EventId,(id,evt));Last=(id,evt);}
  public IReadOnlyList<(string JobId,PrintEvent Event)> Pending()=>pending.Values.ToList();
  public void Acknowledge(string id){pending.Remove(id);}
  public IReadOnlyList<(string JobId,string State)> Incomplete()=>[];
  public void Dispose()=>log.Dispose();
 }
 sealed class Connection:IAsyncDisposable {
  public required PrintingRealtimeChannel Channel{get;init;}public required Task Reader{get;init;}public required CancellationTokenSource Lifetime{get;init;}
  public required Channel<RealtimeEnvelope> Messages{get;init;}public required Channel<long> Wakes{get;init;}public RealtimeSettings Settings{get;private set;}=new();
  public static async Task<Connection> Open(QqqSessionClient client,CancellationToken token) {
   var lifetime=CancellationTokenSource.CreateLinkedTokenSource(token);WebSocket socket=await client.ConnectPrintingAsync(TimeSpan.FromSeconds(20),TimeSpan.FromSeconds(60),token);
   var channel=new PrintingRealtimeChannel(socket,new RealtimeSettings());var messages=System.Threading.Channels.Channel.CreateBounded<RealtimeEnvelope>(8);
   var wakes=System.Threading.Channels.Channel.CreateBounded<long>(new BoundedChannelOptions(1){FullMode=BoundedChannelFullMode.DropWrite});
   Task reader=channel.RunAsync(e=>{
    if(e.Type=="JOB_AVAILABLE"){wakes.Writer.TryWrite(Stopwatch.GetTimestamp());return;}
    if(e.Type is "SETTINGS" or "DESIRED_STATE") {long revision=e.Payload.GetProperty("revision").GetInt64();if(revision!=0)throw new InvalidDataException("Unexpected policy change");return;}
    if(e.Type is "PONG" or "ACK")return;
    if(!messages.Writer.TryWrite(e))throw new IOException("Fixture envelope inbox full");
   },lifetime.Token);
   _=reader.ContinueWith(t=>{wakes.Writer.TryComplete(t.Exception);messages.Writer.TryComplete(t.Exception);},TaskScheduler.Default);
   var result=new Connection{Channel=channel,Reader=reader,Lifetime=lifetime,Messages=messages,Wakes=wakes};
   await channel.SendAsync("HELLO",new{agentVersion=PrintApiClient.AgentVersion,capabilities=new[]{"realtime-v1","REFRESH_INVENTORY","CANCEL_SPOOL"},lastAppliedRevision=0,lastSettingsRevision=0,limits=RealtimeSettings.ClientLimits},token:token);
   RealtimeEnvelope welcome=await messages.Reader.ReadAsync(token);Require(welcome.Type=="WELCOME","welcome");
   result.Settings=welcome.Payload.GetProperty("settings").Deserialize<RealtimeSettings>(PrintApiClient.JsonOptions)!;result.Settings.Validate();channel.UpdateSettings(result.Settings);
   await channel.SendAsync("STATE_APPLIED",new{lastAppliedRevision=welcome.Payload.GetProperty("desiredState").GetProperty("revision").GetInt64(),lastSettingsRevision=result.Settings.Revision},acknowledged:true,token:token);return result;
  }
  public async ValueTask DisposeAsync(){await Lifetime.CancelAsync();try{await Reader;}catch(Exception e)when(e is OperationCanceledException or IOException or WebSocketException){}Channel.Dispose();Lifetime.Dispose();}
 }
}
