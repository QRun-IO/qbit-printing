using System.Net;
using System.Threading.Channels;

namespace Printing.NativeAuth;

/// <summary>Receives the actual HTTPS-proxied callback only inside this test network.</summary>
internal sealed class CallbackReceiver : IDisposable
{
   private readonly HttpListener _listener = new();
   private readonly Channel<Uri> _callbacks = Channel.CreateBounded<Uri>(1);
   private readonly Uri _callback;

   internal CallbackReceiver(Uri callback)
   {
      _callback = callback;
      _listener.Prefixes.Add("http://+:8088/");
      _listener.Start();
      _ = ReceiveAsync();
   }

   private async Task ReceiveAsync()
   {
      try
      {
         while(_listener.IsListening)
         {
            HttpListenerContext context = await _listener.GetContextAsync();
            Uri? request = context.Request.Url;
            bool valid = request is not null && request.AbsolutePath == _callback.AbsolutePath
               && context.Request.Headers["Host"] == _callback.Authority
               && context.Request.Headers["X-Forwarded-Proto"] == "https"
               && context.Request.HttpMethod == "GET" && request.Query.Length is > 0 and < 16384;
            if(valid)
            {
               var actual = new UriBuilder(_callback) { Query = request!.Query.TrimStart('?') };
               valid = _callbacks.Writer.TryWrite(actual.Uri);
            }
            context.Response.StatusCode = valid ? 200 : 400;
            context.Response.ContentLength64 = 0;
            context.Response.Close();
         }
      }
      catch(Exception exception) when(exception is HttpListenerException or ObjectDisposedException)
      {
         _callbacks.Writer.TryComplete();
      }
   }

   internal ValueTask<Uri> ReadAsync(CancellationToken token) => _callbacks.Reader.ReadAsync(token);
   public void Dispose() => _listener.Close();
}
