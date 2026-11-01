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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qqq.backend.core.actions.profile.UserProfiles;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.ClientChannelsMetaData;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleDispatcher;
import com.kingsrook.qqq.middleware.javalin.QJavalinRouteProviderInterface;
import com.kingsrook.qqq.middleware.javalin.executors.ExecutorSessionUtils;
import com.uber.nullaway.annotations.MonotonicNonNull;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.websocket.WsContext;
import org.eclipse.jetty.websocket.api.Callback;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;


/** Current-user authenticated outbound channels. SQL is authoritative; notifications are only wakeups. */
public final class PrintingRealtimeRoutes implements QJavalinRouteProviderInterface, AutoCloseable
{
   public static final String PATH = "/qqq/v1/qbit/printing/device/realtime";
   public static final String SUBPROTOCOL = "qqq-printing.v1";
   private final PrintingService service;
   private final Map<String, Channel> channels = new ConcurrentHashMap<>();
   private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
   private final AutoCloseable subscription;
   private @MonotonicNonNull QInstance instance;


   /** Register this provider alongside the host's ordinary native QQQ endpoints. */
   public PrintingRealtimeRoutes(PrintingService service)
   {
      this.service = java.util.Objects.requireNonNull(service);
      service.withRealtimeClaimGuard(this::requireReady);
      subscription = service.getRealtimeBus().subscribe((tenant, device) ->
      {
         if(service.getTenantId().equals(tenant))
         {
            channels.values().stream().filter(channel -> device == null || device.equals(channel.device)).forEach(Channel::changed);
         }
      });
   }


   @Override
   public void setQInstance(QInstance value)
   {
      instance = value;
   }


   @Override
   public void acceptJavalinConfig(JavalinConfig config)
   {
      config.routes.wsBeforeUpgrade(PATH, context ->
      {
         try
         {
            validateUpgrade(context);
            authorize(context);
            String cookieName = context.cookie("sessionId") != null && !context.cookie("sessionId").isBlank() ? "sessionId" : "sessionUUID";
            context.attribute("printingRealtimeCredential", new Credential(cookieName, context.cookie(cookieName), context.header(PrintingAgentAuthorization.TOKEN_HEADER), context.header("X-QQQ-Client")));
         }
         catch(Exception exception)
         {
            throw new HttpResponseException(exception instanceof QAuthenticationException ? 401 : 403, "Realtime authentication or origin denied", Map.of());
         }
         finally
         {
            QContext.clear();
         }
      });
      config.routes.ws(PATH, socket ->
      {
         socket.onConnect(context ->
         {
            String device = context.header(PrintingAgentAuthorization.DEVICE_HEADER);
            Channel next = new Channel(context, device, context.attribute("printingRealtimeCredential"));
            next.policy = service.realtimePolicy();
            Channel previous = channels.put(device, next);
            if(previous != null)
            {
               previous.stop("Connection replaced");
            }
            service.getRealtimeBus().acquire(service.getTenantId(), device, next.generation, () -> next.stop("Connection replaced"));
            context.session.setMaxTextMessageSize(next.policy.get("maxFrameBytes"));
            context.session.setMaxBinaryMessageSize(0);
            context.session.setIdleTimeout(Duration.ofMillis(next.policy.get("webSocketHandshakeTimeoutMillis")));
         });
         socket.onMessage(context ->
         {
            Channel channel = current(context);
            if(channel != null)
            {
               channel.accept(context.message());
            }
         });
         socket.onBinaryMessage(context -> context.closeSession(1003, "Only JSON text is supported"));
         socket.onClose(context -> remove(context));
         socket.onError(context -> remove(context));
      });
      config.events.serverStopping(this::close);
   }


   /** Resolve only the current socket generation. */


   // Identity is the socket-generation fence; value equality must never admit a replaced socket.
   @SuppressWarnings("ReferenceEquality")
   private @Nullable Channel current(WsContext context)
   {
      Channel channel = channels.get(context.header(PrintingAgentAuthorization.DEVICE_HEADER));
      return channel != null && channel.socket.session == context.session ? channel : null;
   }


   /** Remove only the closing socket generation. */


   private void remove(WsContext context)
   {
      Channel channel = current(context);
      if(channel != null)
      {
         channel.stop("Connection closed");
      }
   }


   /** Gate native claims on acknowledged current settings. */


   private void requireReady(String device) throws java.sql.SQLException
   {
      Channel channel = channels.get(device);
      if(channel == null || !channel.ready(service.getRealtimeSettings().getLong("revision")))
      {
         throw new PrintingException(409, "An authenticated realtime connection must apply current state and settings before claiming work");
      }
   }


   /** Reject query credentials, non-session authentication and foreign origins. */


   private static void validateUpgrade(Context context)
   {
      if(context.queryString() != null || !SUBPROTOCOL.equals(context.header("Sec-WebSocket-Protocol"))
         || ((context.cookie("sessionUUID") == null || context.cookie("sessionUUID").isBlank()) && (context.cookie("sessionId") == null || context.cookie("sessionId").isBlank()))
         || context.header("Authorization") != null || context.header("x-api-key") != null || context.header("X-QQQ-In-Process-Session") != null)
      {
         throw new PrintingException(403, "A standard QQQ session and the printing subprotocol are required");
      }
      URI request = URI.create(context.url());
      URI origin = URI.create(java.util.Objects.toString(context.header("Origin"), ""));
      boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(request.getHost());
      if(!("https".equals(request.getScheme()) || (loopback && "http".equals(request.getScheme())))
         || !java.util.Objects.equals(request.getScheme(), origin.getScheme())
         || !java.util.Objects.equals(request.getRawAuthority(), origin.getRawAuthority())
         || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
         || !(origin.getPath().isEmpty() || "/".equals(origin.getPath())))
      {
         throw new PrintingException(403, "Realtime origin must match the authenticated server origin");
      }
   }


   /** Revalidate the standard current QQQ session and secondary device ownership. */


   private void authorize(Context context) throws Exception
   {
      ExecutorSessionUtils.setupSession(context, instance);
      PrintingAgentAuthorization.device(context.header(PrintingAgentAuthorization.DEVICE_HEADER), context.header(PrintingAgentAuthorization.TOKEN_HEADER), false);
   }


   /***************************************************************************
    ** Revalidation requires the host instance already used during upgrade.
    ** Check at use, so route registration ordering remains unchanged.
    ***************************************************************************/
   private QInstance configuredInstance()
   {
      QInstance configured = instance;
      if(configured == null)
      {
         throw new IllegalStateException("The realtime route provider has no configured QQQ instance");
      }
      return configured;
   }



   /** Only transport credentials survive upgrade; user roles and session authority are never cached. */
   private static final class Credential
   {
      private final String cookie;
      private final String cookieName;
      private final String proof;
      private final String clientChannel;


      /** Capture immutable authenticated transport inputs before Jetty releases the HTTP request. */
      private Credential(String cookieName, String cookie, String proof, String clientChannel)
      {
         this.cookieName = cookieName;
         this.cookie = cookie;
         this.proof = proof;
         this.clientChannel = clientChannel;
      }


      @Override
      public String toString()
      {
         return "Printing realtime credential [redacted]";
      }
   }


   @Override
   public void close()
   {
      channels.values().forEach(channel -> channel.stop("Server stopping"));
      try
      {
         subscription.close();
      }
      catch(Exception exception)
      {
         throw new IllegalStateException("Unable to remove realtime subscription", exception);
      }
      finally
      {
         stopWorkers();
      }
   }


   /**
    * Stop accepting channel work and wait, bounded, for drains already running. Every channel was fenced and its session closed first, so
    * an in-flight drain fails quickly; without this wait it could query durable state after the host has stopped (and, for a file database,
    * recreate it empty), which is what left a test's temporary directory populated after cleanup.
    */
   private void stopWorkers()
   {
      workers.shutdown();
      try
      {
         if(!workers.awaitTermination(5, TimeUnit.SECONDS))
         {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
         }
      }
      catch(InterruptedException exception)
      {
         workers.shutdownNow();
         Thread.currentThread().interrupt();
      }
   }


   /** One serial bounded mailbox per socket; network waits never run on the committing caller. */
   private final class Channel
   {
      private final WsContext socket;
      private final String device;
      private final String generation = UUID.randomUUID().toString();
      private final Credential credential;
      private final ArrayDeque<String> mailbox = new ArrayDeque<>();
      private PrintingRealtimePolicy policy = PrintingRealtimePolicy.defaults();
      private @MonotonicNonNull JSONObject hello;
      private boolean running;
      private boolean dirty;
      private boolean stopped;
      private long queuedBytes;
      private long appliedState = -1;
      private long appliedSettings = -1;
      private long sentState = -1;
      private long sentSettings = -1;


      /** Bind one mailbox to its authenticated socket. */


      private Channel(WsContext socket, String device, Credential credential)
      {
         this.socket = socket;
         this.device = device;
         this.credential = credential;
      }


      /** Re-enter the configured native authentication module for every operation after HTTP upgrade. */
      private void authorizeCurrent() throws Exception
      {
         QInstance instance = configuredInstance();
         QContext.init(instance, null);
         var module = new QAuthenticationModuleDispatcher().getQModule(instance.getAuthentication());
         var session = module.createSession(instance, Map.of(credential.cookieName, credential.cookie));
         UserProfiles.applyIfInstalled(instance, session);
         QContext.init(instance, session, null, null);
         if(session.getCredentialScope() != null)
         {
            throw new QAuthenticationException("Realtime printing requires a current user session");
         }
         String channel = credential.clientChannel == null || credential.clientChannel.isBlank() ? null : credential.clientChannel.strip();
         if(channel != null && (!ClientChannelsMetaData.of(instance).isAllowedChannel(ClientChannelsMetaData.channelName(channel)) || channel.length() > 200))
         {
            throw new QPermissionDeniedException("The current client channel is not allowed");
         }
         session.setClientChannel(channel == null ? null : channel.strip());
         PrintingAgentAuthorization.device(device, credential.proof, false);
      }


      /** Read synchronized connection application state. */


      private synchronized boolean ready(long revision)
      {
         return !stopped && socket.session.isOpen() && service.getRealtimeBus().owns(service.getTenantId(), device, generation)
            && hello != null && appliedState == sentState && appliedSettings == sentSettings && appliedSettings == revision;
      }


      /** Bound inbound work before scheduling any database operation. */


      private synchronized void accept(String frame)
      {
         int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
         if(bytes > policy.get("maxFrameBytes") || mailbox.size() >= policy.get("maxQueuedFrames") || queuedBytes + bytes > policy.get("maxQueuedFrameBytes"))
         {
            stop("Realtime mailbox limit exceeded");
            return;
         }
         mailbox.add(frame);
         queuedBytes += bytes;
         schedule();
      }


      /** Coalesce commit wakeups without blocking the committing caller. */


      private synchronized void changed()
      {
         dirty = true;
         schedule();
      }


      /** Start at most one serial worker for this connection. */


      private void schedule()
      {
         if(!running && !stopped)
         {
            running = true;
            workers.execute(this::drain);
         }
      }


      /** Process bounded inbound work and coalesced durable-state wakeups. */


      private void drain()
      {
         while(true)
         {
            String frame;
            synchronized(this)
            {
               frame = mailbox.poll();
               if(frame != null)
               {
                  queuedBytes -= frame.getBytes(StandardCharsets.UTF_8).length;
               }
               else if(!dirty || hello == null || stopped)
               {
                  running = false;
                  return;
               }
               else
               {
                  dirty = false;
               }
            }
            try
            {
               if(channels.get(device) != this || stopped || !service.getRealtimeBus().owns(service.getTenantId(), device, generation))
               {
                  return;
               }
               authorizeCurrent();
               if(frame == null)
               {
                  snapshot(false, helloPayload());
               }
               else
               {
                  receive(new JSONObject(frame));
               }
            }
            catch(Exception exception)
            {
               if(reject(frame, exception))
               {
                  return;
               }
            }
            finally
            {
               QContext.clear();
            }
         }
      }


      /** Apply one authenticated protocol operation. */


      private void receive(JSONObject frame) throws Exception
      {
         if(!frame.keySet().equals(Set.of("type", "messageId", "payload")) || !(frame.opt("messageId") instanceof String messageId)
            || !messageId.matches("[A-Za-z0-9._-]{1,200}") || !(frame.opt("payload") instanceof JSONObject payload))
         {
            throw new PrintingException(400, "Invalid realtime envelope");
         }
         String type = frame.getString("type");
         if(hello == null && !"HELLO".equals(type))
         {
            throw new PrintingException(409, "HELLO is required first");
         }
         switch(type)
         {
            case "HELLO" ->
            {
               if(hello != null || !payload.keySet().equals(Set.of("agentVersion", "capabilities", "lastAppliedRevision", "lastSettingsRevision", "limits"))
                  || !payload.getJSONArray("capabilities").toList().contains("realtime-v1")
                  || payload.getJSONArray("capabilities").length() > 4
                  || !Set.of("realtime-v1", "REFRESH_INVENTORY", "CANCEL_SPOOL", "printer-media-v1").containsAll(payload.getJSONArray("capabilities").toList()))
               {
                  throw new PrintingException(409, "Realtime capability is required");
               }
               hello = payload;
               snapshot(true, payload);
               return;
            }
            case "STATE_APPLIED" ->
            {
               long state = integer(payload, "lastAppliedRevision");
               long settings = integer(payload, "lastSettingsRevision");
               if(state > sentState || settings > sentSettings || state < appliedState || settings < appliedSettings)
               {
                  throw new PrintingException(409, "Only the current delivered state and settings may be acknowledged");
               }
               service.applyRealtimeState(device, controlRequest(helloPayload(), state), settings);
               synchronized(this)
               {
                  appliedState = state;
                  appliedSettings = settings;
               }
               changed();
            }
            case "JOB_EVENT" ->
            {
               String job = payload.getString("jobId");
               PrintingControlAuthorization.job(device, job);
               service.event(device, job, payload.getJSONObject("event"));
            }
            case "COMMAND_RECEIPT" ->
            {
               String command = payload.getString("commandId");
               PrintingControlAuthorization.table(PrintingTables.COMMAND);
               service.getDeviceCommand(device, command);
               PrintingUiApiSpec.command(device, command);
               service.deviceControlReceipt(device, command, payload.getJSONObject("receipt"));
               changed();
            }
            case "HEARTBEAT" -> service.heartbeat(device, payload);
            case "INVENTORY" -> service.inventory(device, payload.getJSONArray("printers"), PrintingAgentAuthorization.inventory(device));
            case "PING" ->
            {
               service.realtimeContact(device);
               send("PONG", new JSONObject().put("serverTime", Instant.now().toString()));
               return;
            }
            default -> throw new PrintingException(400, "Unsupported realtime message");
         }
         send("ACK", new JSONObject().put("acknowledgedMessageId", messageId));
      }


      /** Domain errors retain their identity and never masquerade as a durable acknowledgment. */
      private boolean reject(String request, Exception exception)
      {
         System.getLogger(PrintingRealtimeRoutes.class.getName()).log(System.Logger.Level.WARNING,
            "Realtime operation rejected: " + exception.getClass().getSimpleName() + " at " + (exception.getStackTrace().length == 0 ? "unknown" : exception.getStackTrace()[0]));
         boolean authorization = exception instanceof QAuthenticationException || exception instanceof QPermissionDeniedException;
         int status = exception instanceof PrintingException printing ? printing.getStatus() : authorization ? 403 : exception instanceof org.json.JSONException ? 400 : 500;
         boolean connectionError = request == null || hello == null || sentSettings < 0 || status == 401 || status == 403 || status >= 500;
         String code = switch(status)
         {
            case 401, 403 -> "AUTHORIZATION_REQUIRED";
            case 404 -> "NOT_FOUND";
            case 410 -> "EXPIRED";
            case 409 -> connectionError ? "CONFIGURATION_REQUIRED" : "CONFLICT";
            case 400, 413 -> "INVALID_MESSAGE";
            default -> "OPERATION_FAILED";
         };
         String id = UUID.randomUUID().toString();
         try
         {
            if(request != null)
            {
               String requested = new JSONObject(request).optString("messageId");
               if(requested.matches("[A-Za-z0-9._-]{1,200}"))
               {
                  id = requested;
               }
            }
            String error = new JSONObject().put("type", "ERROR").put("messageId", id).put("payload", new JSONObject().put("code", code)
               .put("message", "The realtime operation was rejected; inspect the current state before retrying").put("retryable", status >= 500)).toString();
            CompletableFuture<Void> sent = new CompletableFuture<>();
            socket.session.sendText(error, Callback.from(() -> sent.complete(null), sent::completeExceptionally));
            sent.get(policy.get("ackTimeoutMillis"), TimeUnit.MILLISECONDS);
         }
         catch(Exception failure)
         {
            connectionError = true;
         }
         if(connectionError)
         {
            stop("Realtime operation rejected");
         }
         return connectionError;
      }


      /***************************************************************************
       ** Pass a validated immutable HELLO reference into subsequent operations.
       ** Pre-HELLO operations keep the protocol's existing rejection contract.
       ***************************************************************************/
      private JSONObject helloPayload()
      {
         JSONObject payload = hello;
         if(payload == null)
         {
            throw new PrintingException(409, "HELLO is required first");
         }
         return payload;
      }



      /** Reuse the durable control protocol with advertised supported operations. */


      private JSONObject controlRequest(JSONObject hello, long state)
      {
         JSONArray capabilities = new JSONArray();
         for(Object value : hello.getJSONArray("capabilities"))
         {
            if(Set.of("REFRESH_INVENTORY", "CANCEL_SPOOL", "printer-media-v1").contains(value))
            {
               capabilities.put(value);
            }
         }
         return new JSONObject().put("controlVersion", 1).put("agentVersion", hello.getString("agentVersion"))
            .put("capabilities", capabilities).put("lastAppliedRevision", state);
      }


      /** Reconstruct current state and pending commands after registration or commit. */


      private void snapshot(boolean initial, JSONObject hello) throws Exception
      {
         JSONObject settings = service.getRealtimeSettings();
         if(initial && integer(hello, "lastSettingsRevision") > settings.getLong("revision"))
         {
            throw new PrintingException(409, "Unknown realtime settings revision");
         }
         JSONObject values = new JSONObject(settings.toString());
         values.remove("revision");
         policy = PrintingRealtimePolicy.fromJson(values);
         policy.requireClientLimits(hello.getJSONObject("limits"));
         socket.session.setMaxTextMessageSize(policy.get("maxFrameBytes"));
         socket.session.setIdleTimeout(Duration.ofSeconds(policy.get("heartbeatTimeoutSeconds")));
         JSONObject state = service.deviceControlPoll(device, controlRequest(hello, initial ? integer(hello, "lastAppliedRevision") : Math.max(0, appliedState)));
         PrintingControlAuthorization.table(PrintingTables.COMMAND);
         JSONObject desired = state.getJSONObject("desiredState");
         long previousState;
         long previousSettings;
         synchronized(this)
         {
            previousState = sentState;
            previousSettings = sentSettings;
            sentState = desired.getLong("revision");
            sentSettings = settings.getLong("revision");
         }
         if(initial)
         {
            send("WELCOME", new JSONObject().put("connectionId", UUID.randomUUID().toString()).put("serverTime", Instant.now().toString())
               .put("desiredState", desired).put("settings", settings));
         }
         else
         {
            if(previousSettings != sentSettings)
            {
               send("SETTINGS", settings);
            }
            if(previousState != sentState)
            {
               send("DESIRED_STATE", desired);
            }
         }
         if(!ready(sentSettings))
         {
            return;
         }
         for(Object item : state.getJSONArray("commands"))
         {
            JSONObject command = (JSONObject) item;
            PrintingUiApiSpec.command(device, command.getString("commandId"));
            send("COMMAND", command);
         }
         send("JOB_AVAILABLE", new JSONObject());
      }


      /** Revalidate authority and await one bounded asynchronous socket write. */


      private void send(String type, JSONObject payload) throws Exception
      {
         authorizeCurrent();
         synchronized(this)
         {
            if(stopped || channels.get(device) != this || !service.getRealtimeBus().owns(service.getTenantId(), device, generation))
            {
               throw new PrintingException(409, "Connection was replaced");
            }
         }
         String frame = new JSONObject().put("type", type).put("messageId", UUID.randomUUID().toString()).put("payload", payload).toString();
         if(frame.getBytes(StandardCharsets.UTF_8).length > policy.get("maxFrameBytes"))
         {
            throw new PrintingException(413, "Realtime frame limit exceeded");
         }
         CompletableFuture<Void> sent = new CompletableFuture<>();
         socket.session.sendText(frame, Callback.from(() -> sent.complete(null), sent::completeExceptionally));
         sent.get(policy.get("ackTimeoutMillis"), TimeUnit.MILLISECONDS);
      }


      /** Fence and remove this connection without affecting a replacement. */


      private synchronized void stop(String reason)
      {
         if(!stopped)
         {
            stopped = true;
            mailbox.clear();
            queuedBytes = 0;
            channels.remove(device, this);
            service.getRealtimeBus().release(service.getTenantId(), device, generation);
            socket.closeSession(1008, reason);
         }
      }
   }


   /** Reject absent, fractional and negative revision values. */


   private static long integer(JSONObject value, String name)
   {
      Object raw = value.get(name);
      if(!(raw instanceof Number number) || !number.toString().matches("[0-9]+"))
      {
         throw new PrintingException(400, "Expected nonnegative integer " + name);
      }
      return number.longValue();
   }
}
