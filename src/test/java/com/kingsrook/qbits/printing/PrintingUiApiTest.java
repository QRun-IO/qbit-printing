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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncher;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncherConfig;
import io.javalin.Javalin;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Native session, proof, principal, tenant and child-record boundaries over real QQQ/H2. */
class PrintingUiApiTest
{
   @TempDir
   Path directory;


   /** Each test owns its native context. */
   @AfterEach
   void clearContext()
   {
      QContext.clear();
   }


   /** Device proof cannot authorize native routes without a current authenticated QQQ user. */
   @Test
   void actualTableAuthHttpRequiresExplicitBindingAndPreservesBinaryContent() throws Exception
   {
      String jdbc = "jdbc:h2:file:" + directory.resolve("native-http").toString().replace('\\', '/');
      DriverDataSource source = new DriverDataSource(jdbc, "sa", "");
      String password = "synthetic-native-api-password-1234";
      PrintingLocalHost host = new PrintingLocalHost(new PrintingLocalSettings(jdbc, "sa", "", "demo", 0, "synthetic-management-token-long-enough", "operator", password));
      PrintingService service = new PrintingService(source, "demo");
      JSONObject enrolled = service.enroll(service.createEnrollment("default", "Synthetic device").put("deviceName", "Synthetic device").put("agentVersion", "test"));
      String device = enrolled.getString("deviceId");
      String proof = enrolled.getString("deviceToken");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic queue").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "queue", true);
      byte[] content = "^XA^FO10,10^FDsynthetic^FS^XZ".getBytes(StandardCharsets.UTF_8);
      String job = service.submit(new JSONObject().put("deviceId", device).put("printerId", "queue").put("title", "Synthetic")
         .put("format", "ZPL").put("contentBase64", Base64.getEncoder().encodeToString(content)).put("idempotencyKey", "http")).getString("jobId");
      AtomicReference<Javalin> javalin = new AtomicReference<>();
      QApplicationLauncher launcher = QApplicationLauncher.run(host, new QApplicationLauncherConfig().withRegisterShutdownHook(false).withServerCustomizer(server ->
      {
         host.configure(server, false);
         server.withJavalinConfigurationCustomizer(javalin::set);
      }));
      String base = "http://127.0.0.1:" + javalin.get().port();
      String prefix = "/qqq/v1" + PrintingUiApi.PREFIX;
      try(HttpClient client = HttpClient.newHttpClient())
      {
         assertEquals(401, send(client, base, prefix + "/device/jobs/claim", "POST", new JSONObject(), null, device, proof).statusCode());
         HttpRequest bearerOnly = HttpRequest.newBuilder(URI.create(base + prefix + "/device/jobs/claim")).header("Authorization", "Bearer " + proof)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
         assertEquals(401, client.send(bearerOnly, HttpResponse.BodyHandlers.discarding()).statusCode());
         assertEquals("QUEUED", service.getJob(job).getString("status"));
         HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/qqq/v1/manageSession")).header("Origin", base)
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("operator:" + password).getBytes(StandardCharsets.UTF_8)))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
         HttpResponse<String> authenticated = client.send(login, HttpResponse.BodyHandlers.ofString());
         assertEquals(200, authenticated.statusCode());
         String cookie = authenticated.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("sessionUUID=")).findFirst().orElseThrow().split(";", 2)[0];
         JSONObject session = json(send(client, base, prefix + "/session", "GET", null, cookie, null, null), 200);
         assertEquals("operator", session.getString("principalId"));
         assertEquals("demo", session.getString("tenantId"));
         assertFalse(session.has("deviceId"));
         assertEquals(403, send(client, base, prefix + "/device/jobs/claim", "POST", new JSONObject(), cookie, device, proof).statusCode());
         assertEquals(403, send(client, base, prefix + "/device/bind", "POST", new JSONObject(), cookie, device, "wrong").statusCode());
         json(send(client, base, prefix + "/device/bind", "POST", new JSONObject(), cookie, device, proof), 200);
         assertEquals(403, send(client, base, prefix + "/device/jobs/claim", "POST", new JSONObject(), cookie, null, null).statusCode());
         JSONObject others = service.enrollForUser(service.createEnrollment("default", "Other owner").put("deviceName", "Other owner").put("agentVersion", "test"), "another-principal");
         assertEquals(403, send(client, base, prefix + "/device/heartbeat", "POST", new JSONObject().put("agentVersion", "test"), cookie, others.getString("deviceId"), others.getString("deviceToken")).statusCode());
         assertEquals(device, json(send(client, base, prefix + "/session", "GET", null, cookie, device, proof), 200).getString("deviceId"));
         assertEquals(job, json(send(client, base, prefix + "/device/jobs/claim", "POST", new JSONObject(), cookie, device, proof), 200).getJSONObject("job").getString("jobId"));
         HttpResponse<byte[]> downloaded = send(client, base, prefix + "/device/jobs/" + job + "/content", "GET", null, cookie, device, proof);
         assertEquals(200, downloaded.statusCode());
         assertArrayEquals(content, downloaded.body());
         JSONObject openApi = json(send(client, base, "/qqq/v1/openapi.json", "GET", null, null, null, null), 200);
         assertTrue(openApi.getJSONObject("paths").has(prefix + "/devices/enroll"));
         assertTrue(openApi.toString().contains("application/octet-stream"));
         JSONObject action = action("REVOKE");
         json(send(client, base, prefix + "/admin/devices/" + device + "/actions", "POST", action, cookie, null, null), 200);
         assertEquals(403, send(client, base, prefix + "/device/heartbeat", "POST", new JSONObject().put("agentVersion", "test"), cookie, device, proof).statusCode());
         JSONObject nativeEnrollment = service.createEnrollment("default", "Native enrollment").put("deviceName", "Native enrollment").put("agentVersion", "test");
         JSONObject fresh = json(send(client, base, prefix + "/devices/enroll", "POST", nativeEnrollment, cookie, null, null), 200);
         service.authorizeAgent(fresh.getString("deviceId"), fresh.getString("deviceToken"), "operator", false);
         JSONObject unavailable = service.createEnrollment("inaccessible-facility", "Denied enrollment").put("deviceName", "Denied enrollment").put("agentVersion", "test");
         assertEquals(403, send(client, base, prefix + "/devices/enroll", "POST", unavailable, cookie, null, null).statusCode());
         assertEquals("inaccessible-facility", service.enrollmentFacility(unavailable.getString("enrollmentCode")));
      }
      finally
      {
         launcher.stop();
      }
   }


   /** Native context permissions and principal ownership are re-evaluated on each adapter invocation. */
   @Test
   void currentPrincipalTenantPermissionAndRevocationAreAllRequired() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      JSONObject enrolled = f.service().enroll(f.service().createEnrollment("default", "Bound").put("deviceName", "Bound").put("agentVersion", "test"));
      String device = enrolled.getString("deviceId");
      String proof = enrolled.getString("deviceToken");
      assertThrows(PrintingException.class, () -> PrintingAgentAuthorization.device(device, proof, false));
      PrintingAgentAuthorization.device(device, proof, true);
      QContext.getQSession().setUser(new QUser().withIdReference("different-user"));
      assertThrows(PrintingException.class, () -> PrintingAgentAuthorization.device(device, proof, false));
      assertThrows(PrintingException.class, () -> PrintingAgentAuthorization.device(device, proof, true));
      QContext.getQSession().setUser(new QUser().withIdReference("operator"));
      QContext.getQSession().setPermissions(Set.of());
      assertThrows(QException.class, () -> PrintingAgentAuthorization.device(device, proof, false));
      QContext.setQSession(new QSession().withUser(new QUser().withIdReference("operator")).withPermissions("printing_admin").withSecurityKeyValue("printing_tenant", "tenant-b"));
      assertThrows(QException.class, () -> PrintingAgentAuthorization.device(device, proof, false));
      QContext.setQSession(new QSession().withUser(new QUser().withIdReference("operator")).withPermissions("printing_admin").withSecurityKeyValue("printing_tenant", "tenant-a"));
      assertThrows(QException.class, () -> PrintingControlAuthorization.record(PrintingTables.DEVICE, f.foreignDevice()));
      f.service().deviceAction(device, action("REVOKE"), "administrator");
      assertThrows(PrintingException.class, () -> PrintingAgentAuthorization.device(device, proof, false));
   }


   /** An accessible device never grants hidden child jobs or lets claim choose a different job. */
   @Test
   void claimAndClearHonorJobRowsAndQueueSnapshotRace() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      String hidden = submit(f.service(), f.device(), "hidden");
      String visible = submit(f.service(), f.device(), "visible");
      f.instance().addSecurityKeyType(new QSecurityKeyType().withName("job_scope"));
      f.instance().getTable(PrintingTables.JOB).withRecordSecurityLock(new RecordSecurityLock().withFieldName("title").withSecurityKeyType("job_scope"));
      QContext.getQSession().withSecurityKeyValue("job_scope", "visible");
      assertThrows(QException.class, () -> PrintingControlAuthorization.deviceAction(f.device(), action("CLEAR_QUEUE")));
      assertEquals("QUEUED", f.service().getJob(hidden).getString("status"));
      assertEquals(visible, PrintingAgentAuthorization.claim(f.device()).getJSONObject("job").getString("jobId"));
      assertEquals("QUEUED", f.service().getJob(hidden).getString("status"));
      Set<String> expected = f.service().queuedJobIdsForControl(f.device());
      String newcomer = submit(f.service(), f.device(), "newcomer");
      assertEquals(409, assertThrows(PrintingException.class, () -> f.service().deviceAction(f.device(), action("CLEAR_QUEUE"), "operator", expected)).getStatus());
      assertEquals("QUEUED", f.service().getJob(newcomer).getString("status"));
   }


   /** Shared-printer queue reviews retain Get-only policy with one printer lookup per request. */
   @Test
   void clearDeduplicatesPrinterReviewWithoutSkippingGetPolicy() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      String first = submit(f.service(), f.device(), "first");
      String second = submit(f.service(), f.device(), "second");
      f.instance().getTable(PrintingTables.PRINTER).getPermissionRules().withCustomPermissionChecker(new QCodeReference(PrinterGetPolicy.class));
      PrinterGetPolicy.calls.set(0);
      PrinterGetPolicy.denied = true;
      assertThrows(QException.class, () -> PrintingControlAuthorization.deviceAction(f.device(), action("CLEAR_QUEUE")));
      assertEquals("QUEUED", f.service().getJob(first).getString("status"));
      assertEquals("QUEUED", f.service().getJob(second).getString("status"));
      PrinterGetPolicy.calls.set(0);
      PrinterGetPolicy.denied = false;
      assertEquals(2, PrintingControlAuthorization.deviceAction(f.device(), action("CLEAR_QUEUE")).getInt("cancelledJobCount"));
      assertEquals(1, PrinterGetPolicy.calls.get());
   }


   /** A host can distinguish Get authority from query visibility; this must remain effective. */
   public static class PrinterGetPolicy extends PrintingReadPermission
   {
      static final AtomicInteger calls = new AtomicInteger();
      static boolean denied;

      /** Apply the deliberately Get-only test policy. */
      @Override
      public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException
      {
         super.checkPermissionsThrowing(input, metadata);
         if(input instanceof GetInput)
         {
            calls.incrementAndGet();
            if(denied)
            {
               throw new com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException("Synthetic Get-only policy");
            }
         }
      }
   }


   /** Native selection preserves the oldest eligible job, including stable ID ordering on ties. */
   @Test
   void nativeClaimUsesDurableCreationOrder() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      String newer = submit(f.service(), f.device(), "newer");
      String older = submit(f.service(), f.device(), "older");
      var backend = (com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData) f.instance().getBackend(f.instance().getTable(PrintingTables.JOB).getBackendName());
      try(var connection = new DriverDataSource(backend.getJdbcUrl(), "sa", "").getConnection())
      {
         PrintingService.update(connection, "UPDATE printing_job SET created_at=? WHERE id=?", "2026-01-02T00:00:00Z", newer);
         PrintingService.update(connection, "UPDATE printing_job SET created_at=? WHERE id=?", "2026-01-01T00:00:00Z", older);
      }
      assertEquals(older, PrintingAgentAuthorization.claim(f.device()).getJSONObject("job").getString("jobId"));
      f.service().event(f.device(), older, new JSONObject().put("eventId", "received-old").put("status", "RECEIVED"));
      assertEquals(newer, PrintingAgentAuthorization.claim(f.device()).getJSONObject("job").getString("jobId"));
   }


   /** Printer eligibility is applied before the bounded candidate limit, not after it. */
   @Test
   void olderDisabledPrinterJobsCannotStarveEligibleClaim() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      for(int index = 0; index < 20; index++)
      {
         submit(f.service(), f.device(), "disabled-" + index);
      }
      f.service().inventory(f.device(), new JSONArray()
         .put(new JSONObject().put("printerId", "laser").put("name", "Disabled").put("formats", new JSONArray(List.of("PDF"))))
         .put(new JSONObject().put("printerId", "ready").put("name", "Ready").put("formats", new JSONArray(List.of("PDF")))));
      f.service().enablePrinter(f.device(), "laser", false);
      f.service().enablePrinter(f.device(), "ready", true);
      String ready = f.service().submit(new JSONObject().put("deviceId", f.device()).put("printerId", "ready").put("title", "eligible")
         .put("format", "PDF").put("contentBase64", Base64.getEncoder().encodeToString("%PDF-synthetic".getBytes(StandardCharsets.UTF_8)))
         .put("idempotencyKey", "eligible")).getString("jobId");
      assertEquals(ready, PrintingAgentAuthorization.claim(f.device()).getJSONObject("job").getString("jobId"));
      assertEquals(20, f.service().queuedJobs(f.device()).length());
   }


   /** Inventory omission cannot update hidden rows or a concurrent unreviewed row. */
   @Test
   void inventoryRequiresAllExistingRowsAndExactLockedSnapshot() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      Set<String> before = PrintingAgentAuthorization.inventory(f.device());
      JSONArray snapshot = new JSONArray().put(new JSONObject().put("printerId", "laser").put("name", "Packing laser").put("formats", new JSONArray(List.of("PDF"))))
         .put(new JSONObject().put("printerId", "hidden").put("name", "Hidden printer").put("formats", new JSONArray(List.of("PDF"))));
      f.service().inventory(f.device(), snapshot);
      assertEquals(409, assertThrows(PrintingException.class, () -> f.service().inventory(f.device(), new JSONArray(), before)).getStatus());
      assertTrue(f.service().printers(f.device()).getJSONObject(0).getBoolean("available"));
      f.instance().addSecurityKeyType(new QSecurityKeyType().withName("printer_scope"));
      f.instance().getTable(PrintingTables.PRINTER).withRecordSecurityLock(new RecordSecurityLock().withFieldName("name").withSecurityKeyType("printer_scope"));
      QContext.getQSession().withSecurityKeyValue("printer_scope", "Packing laser");
      assertThrows(QException.class, () -> PrintingAgentAuthorization.inventory(f.device()));
      assertEquals(2, f.service().printers(f.device()).length());
   }


   /** Synthetic PDF bytes are queued only; no renderer or printer is invoked. */
   private static String submit(PrintingService service, String device, String title) throws Exception
   {
      return (service.submit(new JSONObject().put("deviceId", device).put("printerId", "laser").put("title", title).put("format", "PDF")
         .put("contentBase64", Base64.getEncoder().encodeToString("%PDF-synthetic".getBytes(StandardCharsets.UTF_8))).put("idempotencyKey", title)).getString("jobId"));
   }


   /** Preserve the same request across retries. */
   private static JSONObject action(String action)
   {
      return (new JSONObject().put("requestId", UUID.randomUUID().toString()).put("issuedAt", Instant.now().toString()).put("action", action));
   }


   /** Real HTTP uses native session cookies plus separate device proof headers. */
   private static HttpResponse<byte[]> send(HttpClient client, String base, String path, String method, JSONObject body, String cookie, String device, String proof) throws Exception
   {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).header("Origin", base).header("X-QQQ-Client", "api/qqq-print-agent");
      if(cookie != null)
      {
         request.header("Cookie", cookie);
      }
      if(device != null)
      {
         request.header(PrintingAgentAuthorization.DEVICE_HEADER, device);
      }
      if(proof != null)
      {
         request.header(PrintingAgentAuthorization.TOKEN_HEADER, proof);
      }
      request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
      if(body != null)
      {
         request.header("Content-Type", "application/json");
      }
      return (client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()));
   }


   /** Check status before interpreting success; no secret-bearing body is echoed on failure. */
   private static JSONObject json(HttpResponse<byte[]> response, int status)
   {
      assertEquals(status, response.statusCode());
      return (new JSONObject(new String(response.body(), StandardCharsets.UTF_8)));
   }
}
