/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.printing;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.assertNotNull;


/** Real native WebSocket client shared by the HTTP and realtime integration fixtures. */
final class PrintingSocketFixture implements WebSocket.Listener, AutoCloseable
{
   final BlockingQueue<JSONObject> received = new LinkedBlockingQueue<>();
   final CompletableFuture<Integer> closed = new CompletableFuture<>();
   private final StringBuilder text = new StringBuilder();
   private @Nullable WebSocket socket;


   /** Authenticate with the normal session cookie and secondary proof headers. */
   static PrintingSocketFixture connect(HttpClient client, String base, String cookie, String device, String proof) throws Exception
   {
      PrintingSocketFixture fixture = open(client, base, cookie, device, proof);
      fixture.initialize();
      return fixture;
   }


   /** Separate upgrade from HELLO so a commit in that interval can be tested deterministically. */
   static PrintingSocketFixture open(HttpClient client, String base, String cookie, String device, String proof) throws Exception
   {
      PrintingSocketFixture fixture = new PrintingSocketFixture();
      fixture.socket = client.newWebSocketBuilder().subprotocols(PrintingRealtimeRoutes.SUBPROTOCOL).header("Origin", base)
         .header("X-QQQ-Client", "api/qqq-print-agent")
         .header("Cookie", cookie).header(PrintingAgentAuthorization.DEVICE_HEADER, device).header(PrintingAgentAuthorization.TOKEN_HEADER, proof)
         .buildAsync(URI.create(base.replace("http:", "ws:") + PrintingRealtimeRoutes.PATH), fixture).get(10, TimeUnit.SECONDS);
      return fixture;
   }


   /** Reconcile after the connection's subscription is already registered. */
   void initialize() throws Exception
   {
      initialize(new JSONArray().put("realtime-v1").put("REFRESH_INVENTORY").put("CANCEL_SPOOL"));
   }


   /** Explicit capabilities permit downgrade/reconnect qualification without changing old clients. */
   void initialize(JSONArray capabilities) throws Exception
   {
      send("HELLO", "hello", new JSONObject().put("agentVersion", "synthetic-test").put("capabilities", capabilities)
         .put("lastAppliedRevision", 0).put("lastSettingsRevision", 0).put("limits", PrintingRealtimePolicy.defaults().toJson(0)));
      JSONObject welcome = take("WELCOME").getJSONObject("payload");
      apply(welcome.getJSONObject("desiredState").getLong("revision"), welcome.getJSONObject("settings").getLong("revision"));
   }


   /** Observe application ACK before allowing a dependent HTTP claim. */
   void apply(long state, long settings) throws Exception
   {
      send("STATE_APPLIED", "applied", new JSONObject().put("lastAppliedRevision", state).put("lastSettingsRevision", settings));
      take("ACK");
   }


   /** Send one explicit protocol operation, never a timed query. */
   void send(String type, String id, JSONObject payload)
   {
      java.util.Objects.requireNonNull(socket, "Test socket was not opened").sendText(new JSONObject().put("type", type).put("messageId", id).put("payload", payload).toString(), true).join();
   }


   /** Bounded waiting diagnoses absent pushes without introducing a polling loop. */
   JSONObject take(String type) throws Exception
   {
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while(System.nanoTime() < end)
      {
         JSONObject next = received.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
         assertNotNull(next, "Expected realtime frame " + type);
         if("ERROR".equals(next.getString("type")) && !"ERROR".equals(type))
         {
            throw new AssertionError("Realtime rejected synthetic test message: " + next.getJSONObject("payload"));
         }
         if(type.equals(next.getString("type")))
         {
            return next;
         }
      }
      throw new AssertionError("No frame " + type);
   }


   @Override
   public void onOpen(WebSocket webSocket)
   {
      webSocket.request(1);
   }


   @Override
   public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
   {
      text.append(data);
      if(last)
      {
         received.add(new JSONObject(text.toString()));
         text.setLength(0);
      }
      webSocket.request(1);
      return null;
   }


   @Override
   public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
   {
      closed.complete(statusCode);
      return null;
   }


   @Override
   public void onError(WebSocket webSocket, Throwable error)
   {
      closed.completeExceptionally(error);
   }


   @Override
   public void close()
   {
      if(socket != null)
      {
         socket.abort();
      }
   }
}
