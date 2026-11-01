// Copyright 2026 Kingsrook, LLC
// SPDX-License-Identifier: Apache-2.0
using System.Net;
using System.Threading.Channels;
namespace Printing.NativeAuth;



/// <summary>Receives the actual HTTPS-proxied callback only inside this test network.</summary>
internal sealed class CallbackReceiver : IDisposable
{
   private readonly HttpListener _listener = new();



   private readonly Channel<Uri> _callbacks = Channel.CreateBounded<Uri>(1);



   private readonly Uri _callback;



   /// <summary>Receive callbacks only inside the owned native-auth container.</summary>
   internal CallbackReceiver(Uri callback)
   {
      _callback = callback;
      _listener.Prefixes.Add("http://+:8088/");
      _listener.Start();
      _ = ReceiveAsync();
   }



   /// <summary>Accept only the configured callback path and publish one bounded result.</summary>
   private async Task ReceiveAsync()
   {
      try
      {
         while(_listener.IsListening)
         {
            HttpListenerContext context = await _listener.GetContextAsync();
            Uri? request = context.Request.Url;
            bool valid = request is not null
               && string.Equals(request.AbsolutePath, _callback.AbsolutePath, StringComparison.Ordinal)
               && string.Equals(context.Request.Headers["Host"], _callback.Authority, StringComparison.Ordinal)
               && string.Equals(context.Request.Headers["X-Forwarded-Proto"], "https", StringComparison.Ordinal)
               && string.Equals(context.Request.HttpMethod, "GET", StringComparison.Ordinal)
               && request.Query.Length is > 0 and < 16384;
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



   /// <summary>Await the actual provider callback under the caller deadline.</summary>
   internal ValueTask<Uri> ReadAsync(CancellationToken token) => _callbacks.Reader.ReadAsync(token);



   /// <summary>Close the owned callback listener.</summary>
   public void Dispose() => _listener.Close();
}
