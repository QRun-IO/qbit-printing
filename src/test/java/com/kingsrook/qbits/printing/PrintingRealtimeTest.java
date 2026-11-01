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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncher;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncherConfig;
import io.javalin.Javalin;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real native authentication and WebSocket lifecycle with durable synthetic jobs only. */
public class PrintingRealtimeTest
{
   @TempDir
   Path directory;


   /** An old client cannot ignore media intent, including after replacing a capable connection. */
   @Test
   void mediaCapabilityIsReplacedOnEveryAuthenticatedHello() throws Exception
   {
      String jdbc = "jdbc:h2:file:" + directory.resolve("media-realtime").toString().replace('\\', '/');
      PrintingLocalHost host = new PrintingLocalHost(new PrintingLocalSettings(jdbc, "sa", "", "demo", 0, "synthetic-management-token-long-enough", "operator", "synthetic-realtime-password-1234"));
      AtomicReference<Javalin> app = new AtomicReference<>();
      QApplicationLauncher launcher = QApplicationLauncher.run(host, new QApplicationLauncherConfig().withRegisterShutdownHook(false).withServerCustomizer(server ->
      {
         host.configure(server, false);
         server.withJavalinConfigurationCustomizer(app::set);
      }));
      PrintingService service = launcher.getQInstance().getQBits().values().stream().map(value -> value.getConfig()).filter(PrintingQBitConfig.class::isInstance)
         .map(PrintingQBitConfig.class::cast).findFirst().orElseThrow().getService();
      String base = "http://127.0.0.1:" + app.get().port();
      JSONObject enrolled = service.enrollForUser(service.createEnrollment("default", "Media fixture").put("deviceName", "Media fixture").put("agentVersion", "test"), "operator");
      String device = enrolled.getString("deviceId");
      String proof = enrolled.getString("deviceToken");
      JSONObject media = new PrintingMediaProfile("default", "a".repeat(64), "4 x 6", 101600, 152400, 300, 300).json();
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic queue").put("formats", new JSONArray().put("ZPL"))
         .put("mediaProfiles", new JSONArray().put(media))));
      service.enablePrinter(device, "queue", true);
      String job = service.submit(request(device, "media").put("options", new JSONObject().put("mediaProfile", new JSONObject().put("id", "default").put("revision", "a".repeat(64))))).getString("jobId");
      try(HttpClient http = HttpClient.newHttpClient())
      {
         String cookie = login(http, base);
         try(PrintingSocketFixture old = PrintingSocketFixture.connect(http, base, cookie, device, proof))
         {
            assertEquals(409, assertThrows(PrintingException.class, () -> service.claim(device)).getStatus());
            assertEquals("QUEUED", service.getJob(job).getString("status"));
         }
         try(PrintingSocketFixture capable = PrintingSocketFixture.open(http, base, cookie, device, proof))
         {
            capable.initialize(new JSONArray().put("realtime-v1").put("REFRESH_INVENTORY").put("CANCEL_SPOOL").put("printer-media-v1"));
            assertEquals(job, service.claim(device).getJSONObject("job").getString("jobId"));
         }
         try(PrintingSocketFixture downgraded = PrintingSocketFixture.connect(http, base, cookie, device, proof))
         {
            assertEquals(409, assertThrows(PrintingException.class, () -> service.claim(device)).getStatus());
            assertEquals("CLAIMED", service.getJob(job).getString("status"));
         }
      }
      finally
      {
         launcher.stop();
      }
   }


   /**
    * Closing the routes waits for a channel worker that is already running. A drain that outlived the host could query a stopped file
    * database and recreate it empty, which previously left a test's temporary directory populated after cleanup.
    */
   @Test
   void closeWaitsForChannelWorkersAlreadyRunning() throws Exception
   {
      PrintingService service = new PrintingService(new DriverDataSource("jdbc:h2:mem:routes-close-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""), "close-tenant");
      PrintingRealtimeRoutes routes = new PrintingRealtimeRoutes(service);
      java.lang.reflect.Field field = PrintingRealtimeRoutes.class.getDeclaredField("workers");
      field.setAccessible(true);
      ExecutorService workers = (ExecutorService) field.get(routes);
      CountDownLatch started = new CountDownLatch(1);
      AtomicBoolean finished = new AtomicBoolean();
      workers.execute(() ->
      {
         started.countDown();
         try
         {
            Thread.sleep(400);
            finished.set(true);
         }
         catch(InterruptedException expected)
         {
            Thread.currentThread().interrupt();
         }
      });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      routes.close();
      assertTrue(finished.get(), "close() returned while a channel worker was still running");
      assertTrue(workers.isTerminated());
   }


   /** A transport ping while disconnected proves contact, never state application or normal heartbeat. */
   private static void disconnectedPingUpdatesContactOnly(PrintingService service, String device, PrintingSocketFixture socket) throws Exception
   {
      service.deviceAction(device, action("DISCONNECT"), "operator");
      JSONObject desired = socket.take("DESIRED_STATE").getJSONObject("payload");
      while(!"DISCONNECTED".equals(desired.getString("connectionState")))
      {
         desired = socket.take("DESIRED_STATE").getJSONObject("payload");
      }
      JSONObject before = deviceSnapshot(service, device);
      long applied = before.getJSONObject("control").getLong("lastAppliedRevision");
      assertTrue(applied < desired.getLong("revision"));
      Instant sent = Instant.now();
      socket.send("PING", "disconnected-contact", new JSONObject());
      socket.take("PONG");
      JSONObject after = deviceSnapshot(service, device);
      assertFalse(Instant.parse(after.getJSONObject("control").getString("lastPollAt")).isBefore(sent));
      assertEquals(applied, after.getJSONObject("control").getLong("lastAppliedRevision"));
      assertEquals(before.getString("lastSeenAt"), after.getString("lastSeenAt"));
      assertEquals("DISCONNECTED", after.getJSONObject("control").getJSONObject("desiredState").getString("connectionState"));
   }


   /** Select the exact synthetic device when the shared-bus test has created others. */
   private static JSONObject deviceSnapshot(PrintingService service, String device) throws Exception
   {
      for(Object value : service.devices())
      {
         JSONObject row = (JSONObject) value;
         if(device.equals(row.getString("deviceId")))
         {
            return (row);
         }
      }
      throw new AssertionError("Synthetic device was not found");
   }


   /** Commit wakeups, exact receipts, replacement and revocation all use actual QQQ sessions. */
   @Test
   void authenticatedPushReconcilesDurableWorkWithoutHttpControlPolling() throws Exception
   {
      String jdbc = "jdbc:h2:file:" + directory.resolve("realtime").toString().replace('\\', '/');
      PrintingLocalHost host = new PrintingLocalHost(new PrintingLocalSettings(jdbc, "sa", "", "demo", 0, "synthetic-management-token-long-enough", "operator", "synthetic-realtime-password-1234"));
      AtomicReference<Javalin> app = new AtomicReference<>();
      QApplicationLauncher launcher = QApplicationLauncher.run(host, new QApplicationLauncherConfig().withRegisterShutdownHook(false).withServerCustomizer(server ->
      {
         host.configure(server, false);
         server.withJavalinConfigurationCustomizer(app::set);
      }));
      PrintingService service = launcher.getQInstance().getQBits().values().stream().map(value -> value.getConfig()).filter(PrintingQBitConfig.class::isInstance)
         .map(PrintingQBitConfig.class::cast).findFirst().orElseThrow().getService();
      String base = "http://127.0.0.1:" + app.get().port();
      JSONObject enrolled = service.enrollForUser(service.createEnrollment("default", "Synthetic realtime").put("deviceName", "Synthetic realtime").put("agentVersion", "test"), "operator");
      String device = enrolled.getString("deviceId");
      String proof = enrolled.getString("deviceToken");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic queue").put("status", "READY").put("formats", new JSONArray().put("ZPL"))));
      service.enablePrinter(device, "queue", true);
      try(HttpClient http = HttpClient.newHttpClient())
      {
         String cookie = login(http, base);
         assertThrows(Exception.class, () -> PrintingSocketFixture.connect(http, base, "sessionUUID=invalid", device, proof));
         assertThrows(Exception.class, () -> PrintingSocketFixture.connect(http, base, cookie, device, "wrong-proof"));
         try(PrintingSocketFixture first = PrintingSocketFixture.connect(http, base, cookie, device, proof))
         {
            first.take("JOB_AVAILABLE");
            first.send("INVENTORY", "idle-inventory", new JSONObject().put("printers", new JSONArray().put(new JSONObject().put("printerId", "queue")
               .put("name", "Synthetic queue").put("status", "READY").put("formats", new JSONArray().put("ZPL")))));
            assertEquals("idle-inventory", first.take("ACK").getJSONObject("payload").getString("acknowledgedMessageId"));
            org.junit.jupiter.api.Assertions.assertNull(first.received.poll(300, TimeUnit.MILLISECONDS), "Idle inventory must not create a self-sustaining job wakeup");
            sharedBusRoundTrip(launcher, service, jdbc, http, cookie, device, first);
            first.received.clear();
            String job = service.submit(request(device, "one")).getString("jobId");
            first.take("JOB_AVAILABLE");
            assertEquals(job, service.claim(device).getJSONObject("job").getString("jobId"));
            JSONObject event = new JSONObject().put("eventId", "stable-event").put("status", "RECEIVED");
            first.send("JOB_EVENT", "event-stable-event", new JSONObject().put("jobId", job).put("event", event));
            assertEquals("event-stable-event", first.take("ACK").getJSONObject("payload").getString("acknowledgedMessageId"));
            assertEquals("RECEIVED", service.getJob(job).getString("status"));
            JSONObject policy = service.getRealtimeSettings();
            policy.remove("revision");
            service.saveRealtimeSettings(policy.put("maxCopies", 99));
            assertEquals(1, first.take("SETTINGS").getJSONObject("payload").getLong("revision"));
            first.apply(0, 0);
            assertEquals(409, assertThrows(PrintingException.class, () -> service.requireRealtimeReady(device)).getStatus());
            first.apply(0, 1);
            service.requireRealtimeReady(device);
            JSONObject pause = service.deviceAction(device, action("PAUSE_DISPATCH"), "operator");
            JSONObject desired = first.take("DESIRED_STATE").getJSONObject("payload");
            while(!desired.getBoolean("dispatchPaused"))
            {
               desired = first.take("DESIRED_STATE").getJSONObject("payload");
            }
            first.apply(desired.getLong("revision"), 1);
            assertTrue(service.claim(device).isNull("job"));
            assertFalse(pause.isEmpty());
            JSONObject command = service.deviceAction(device, action("REFRESH_INVENTORY"), "operator");
            JSONObject pushed = first.take("COMMAND").getJSONObject("payload");
            assertEquals(command.getString("commandId"), pushed.getString("commandId"));
            JSONObject receipt = new JSONObject().put("receiptId", "stable-receipt").put("outcome", "INVENTORY_REFRESHED").put("completedAt", Instant.now().toString());
            JSONObject delivery = new JSONObject().put("commandId", pushed.getString("commandId")).put("receipt", receipt);
            first.send("COMMAND_RECEIPT", "receipt-stable-receipt", delivery);
            first.take("ACK");
            first.send("COMMAND_RECEIPT", "receipt-pruned", new JSONObject().put("commandId", UUID.randomUUID().toString()).put("receipt", receipt));
            assertEquals("NOT_FOUND", first.take("ERROR").getJSONObject("payload").getString("code"));
            first.send("PING", "after-pruned-receipt", new JSONObject());
            first.take("PONG");
            try(PrintingSocketFixture second = PrintingSocketFixture.connect(http, base, cookie, device, proof))
            {
               assertEquals(1008, first.closed.get(10, TimeUnit.SECONDS));
               second.send("COMMAND_RECEIPT", "receipt-stable-receipt", delivery);
               assertEquals("receipt-stable-receipt", second.take("ACK").getJSONObject("payload").getString("acknowledgedMessageId"));
               second.send("JOB_EVENT", "event-stable-event", new JSONObject().put("jobId", job).put("event", event));
               second.take("ACK");
               assertEquals("RECEIVED", service.getJob(job).getString("status"));
               disconnectedPingUpdatesContactOnly(service, device, second);
               service.deviceAction(device, action("REVOKE"), "operator");
               assertEquals(1008, second.closed.get(10, TimeUnit.SECONDS));
            }
         }
         authenticationChangesAreImmediate(launcher, service, http, base, cookie);
      }
      finally
      {
         launcher.stop();
      }
   }


   /** Cookie variants, changed provider roles, channel policy and logout are checked after upgrade. */
   private static void authenticationChangesAreImmediate(QApplicationLauncher launcher, PrintingService service, HttpClient http, String base, String cookie) throws Exception
   {
      JSONObject enrolled = service.enrollForUser(service.createEnrollment("default", "Authentication fixture").put("deviceName", "Authentication fixture").put("agentVersion", "test"), "operator");
      String device = enrolled.getString("deviceId");
      String proof = enrolled.getString("deviceToken");
      var table = launcher.getQInstance().getTable(PrintingTables.DEVICE);
      var originalRules = table.getPermissionRules();
      try(PrintingSocketFixture channel = PrintingSocketFixture.connect(http, base, cookie.replace("sessionUUID=", "sessionId="), device, proof))
      {
         table.withPermissionRules(PrintingProcesses.permissionRules().withDeniedClients(java.util.Set.of("api")));
         channel.send("PING", "channel-denied", new JSONObject());
         assertEquals("AUTHORIZATION_REQUIRED", channel.take("ERROR").getJSONObject("payload").getString("code"));
         assertEquals(1008, channel.closed.get(10, TimeUnit.SECONDS));
      }
      finally
      {
         table.withPermissionRules(originalRules);
      }
      var auth = launcher.getQInstance().getAuthentication();
      var originalModule = auth.getModuleCodeReference();
      try(PrintingSocketFixture channel = PrintingSocketFixture.connect(http, base, cookie, device, proof))
      {
         auth.withModuleCodeReference(new com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference(RemovedRoleAuthentication.class));
         service.getRealtimeBus().publish("demo", device);
         assertEquals("AUTHORIZATION_REQUIRED", channel.take("ERROR").getJSONObject("payload").getString("code"));
         assertEquals(1008, channel.closed.get(10, TimeUnit.SECONDS));
      }
      finally
      {
         auth.withModuleCodeReference(originalModule);
      }
      try(PrintingSocketFixture channel = PrintingSocketFixture.connect(http, base, cookie, device, proof))
      {
         HttpResponse<String> logout = http.send(HttpRequest.newBuilder(URI.create(base + "/qqq/v1/logout")).header("Origin", base).header("Cookie", cookie)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
         assertEquals(200, logout.statusCode());
         channel.send("PING", "logged-out", new JSONObject());
         assertEquals("AUTHORIZATION_REQUIRED", channel.take("ERROR").getJSONObject("payload").getString("code"));
         assertEquals(1008, channel.closed.get(10, TimeUnit.SECONDS));
      }
   }


   /** Native authentication still verifies the actual password/session; only current role membership changes. */
   public static class RemovedRoleAuthentication extends PrintingLocalAuthentication
   {
      @Override
      public com.kingsrook.qqq.backend.core.model.session.QSession createSession(com.kingsrook.qqq.backend.core.model.metadata.QInstance instance, java.util.Map<String, String> context)
            throws com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException
      {
         var session = super.createSession(instance, context);
         session.setPermissions(java.util.Set.of());
         return (session);
      }
   }


   /** Two independent route providers receive only their scoped commit wakeups through an explicitly shared bus. */
   private static void sharedBusRoundTrip(QApplicationLauncher launcher, PrintingService service, String jdbc, HttpClient http, String cookie, String firstDevice, PrintingSocketFixture first) throws Exception
   {
      PrintingService sibling = new PrintingService(new DriverDataSource(jdbc, "sa", ""), "demo").withRealtimeBus(service.getRealtimeBus());
      PrintingRealtimeRoutes routes = new PrintingRealtimeRoutes(sibling);
      routes.setQInstance(launcher.getQInstance());
      Javalin other = Javalin.create(config ->
      {
         config.jetty.host = "127.0.0.1";
         routes.acceptJavalinConfig(config);
      }).start(0);
      JSONObject enrolled = service.enrollForUser(service.createEnrollment("default", "Second synthetic device").put("deviceName", "Second synthetic device").put("agentVersion", "test"), "operator");
      String secondDevice = enrolled.getString("deviceId");
      service.inventory(secondDevice, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic queue").put("formats", new JSONArray().put("ZPL"))));
      service.enablePrinter(secondDevice, "queue", true);
      try(PrintingSocketFixture second = PrintingSocketFixture.open(http, "http://127.0.0.1:" + other.port(), cookie, secondDevice, enrolled.getString("deviceToken")))
      {
         String early = service.submit(request(secondDevice, "commit-between-upgrade-and-hello")).getString("jobId");
         second.initialize();
         second.take("JOB_AVAILABLE");
         assertEquals("QUEUED", sibling.getJob(early).getString("status"));
         sibling.cancel(secondDevice, early);
         first.received.clear();
         second.received.clear();
         String firstJob = sibling.submit(request(firstDevice, "other-service-to-first-hub")).getString("jobId");
         first.take("JOB_AVAILABLE");
         String secondJob = service.submit(request(secondDevice, "first-service-to-other-hub")).getString("jobId");
         second.take("JOB_AVAILABLE");
         assertEquals("QUEUED", sibling.getJob(firstJob).getString("status"));
         assertEquals("QUEUED", service.getJob(secondJob).getString("status"));
         service.cancel(firstDevice, firstJob);
         sibling.cancel(secondDevice, secondJob);
      }
      finally
      {
         other.stop();
      }
   }


   /** A real browser login supplies only its own temporary test session. */
   private static String login(HttpClient client, String base) throws Exception
   {
      HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/qqq/v1/manageSession")).header("Origin", base)
         .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("operator:synthetic-realtime-password-1234".getBytes(StandardCharsets.UTF_8)))
         .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      return (response.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("sessionUUID=")).findFirst().orElseThrow().split(";", 2)[0]);
   }


   /** Stable synthetic submission identity; no physical printer exists in this fixture. */
   private static JSONObject request(String device, String key)
   {
      return (new JSONObject().put("deviceId", device).put("printerId", "queue").put("title", "Synthetic realtime")
         .put("format", "ZPL").put("contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(StandardCharsets.US_ASCII))).put("idempotencyKey", key));
   }


   /** Native actions retain their existing temporal replay boundary. */
   private static JSONObject action(String name)
   {
      return (new JSONObject().put("action", name).put("requestId", UUID.randomUUID().toString()).put("issuedAt", Instant.now().toString()));
   }


   /** Policy updates preserve validation, tenant isolation and durable revisions. */
   @Test
   void policyRejectsInconsistentLimitsAndPersistsRevisions() throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:policy-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      PrintingService first = new PrintingService(source, "one");
      first.initialize();
      PrintingService other = new PrintingService(source, "two");
      JSONObject values = first.getRealtimeSettings();
      assertEquals(104857600, values.getInt("maxPdfBytes"));
      values.remove("revision");
      JSONObject invalid = new JSONObject(values.toString()).put("heartbeatTimeoutSeconds", 10);
      assertThrows(PrintingException.class, () -> first.saveRealtimeSettings(invalid));
      assertThrows(PrintingException.class, () -> first.saveRealtimeSettings(new JSONObject(values.toString()).put("maxCopies", 1.5)));
      values.put("maxPdfBytes", 30 * 1024 * 1024);
      assertEquals(1, first.saveRealtimeSettings(values).getLong("revision"));
      assertEquals(1, new PrintingService(source, "one").getRealtimeSettings().getLong("revision"));
      assertNotEquals(first.getRealtimeSettings().getLong("maxPdfBytes"), other.getRealtimeSettings().getLong("maxPdfBytes"));
      assertThrows(PrintingException.class, () -> PrintingRealtimePolicy.defaults().requireClientLimits(new JSONObject().put("maxPdfBytes", 1)));
   }
}
