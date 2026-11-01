/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2026.  Kingsrook, LLC
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.session.QCredentialScope;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Real native row security, operator isolation and immutable queue media facts.
 *******************************************************************************/
class PrintingOperatorTest
{
   @TempDir
   Path directory;

   /***************************************************************************
    ** Each fixture owns its native context.
    ***************************************************************************/
   @AfterEach
   void clear()
   {
      QContext.clear();
   }


   /***************************************************************************
    ** Operators can submit and reconcile their own request, never manage devices.
    ***************************************************************************/
   @Test
   void operatorSubmitsWithoutAdminAndCannotEnumerateJobsOrOtherActors() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      assertEquals(2, PrintingOperatorAuthorization.catalog(50, null).getJSONArray("printers").length());
      assertThrows(QException.class, () -> PrintingControlAuthorization.principal());
      QueryInput denied = new QueryInput().withTableName(PrintingTables.JOB);
      denied.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      assertThrows(QException.class, () -> new QueryAction().execute(denied));
      JSONObject request = request(f.printerId(), "echo:scan-1");
      JSONObject accepted = PrintingOperatorAuthorization.submit(request);
      assertEquals(accepted.getString("jobId"), PrintingOperatorAuthorization.submit(request).getString("jobId"));
      assertEquals(accepted.getString("jobId"), PrintingOperatorAuthorization.observe("echo:scan-1").getString("jobId"));
      assertTrue(PrintingOperatorAuthorization.submit(request(f.printerId(), "x".repeat(128))).has("jobId"));
      assertEquals(400, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.submit(request(f.printerId(), "x".repeat(129)))).getStatus());
      assertEquals(409, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.submit(new JSONObject(request.toString()).put("title", "Changed"))).getStatus());
      QContext.getQSession().setUser(new QUser().withIdReference("another-operator"));
      assertEquals(404, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.observe("echo:scan-1")).getStatus());
      assertFalse(accepted.getString("jobId").equals(PrintingOperatorAuthorization.submit(request).getString("jobId")));
      QContext.getQSession().setPermissions(Set.of());
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.submit(request));
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.observe("echo:scan-1"));
   }


   /***************************************************************************
    ** Tenant and parent device locks apply to catalog, submit and replay reads.
    ***************************************************************************/
   @Test
   void nativeLocksAndExposureFilterCatalogAndPreventForgedDestinations() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.submit(request(f.foreignPrinterId(), "foreign")));
      f.service().enablePrinter(f.otherDevice(), "laser", false);
      assertEquals(1, PrintingOperatorAuthorization.catalog(50, null).getJSONArray("printers").length());
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.submit(request(f.otherPrinterId(), "disabled")));
      f.instance().addSecurityKeyType(new QSecurityKeyType().withName("operator_device"));
      f.instance().getTable(PrintingTables.DEVICE).withRecordSecurityLock(new RecordSecurityLock().withFieldName("id").withSecurityKeyType("operator_device"));
      QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("tenant-a"), "operator_device", List.of(f.otherDevice())));
      assertEquals(0, PrintingOperatorAuthorization.catalog(50, null).getJSONArray("printers").length());
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.submit(request(f.printerId(), "locked")));
      QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("tenant-b")));
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.catalog(50, null));
   }


   /***************************************************************************
    ** Changed default media refuses new work while exact accepted replay survives.
    ***************************************************************************/
   @Test
   void mediaFactsRoundTripAndRevisionChecksPrecedeAdmission() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      JSONObject media = media();
      f.service().inventory(f.device(), new JSONArray().put(printer(media)));
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      JSONObject item = PrintingOperatorAuthorization.catalog(50, null).getJSONArray("printers").toList().stream()
         .map(value -> new JSONObject((Map<?, ?>) value)).filter(value -> value.getString("printerRowId").equals(f.printerId())).findFirst().orElseThrow();
      assertTrue(media.similar(item.getJSONArray("mediaProfiles").getJSONObject(0)));
      JSONObject request = request(f.printerId(), "media").put("options", new JSONObject().put("mediaProfile", new JSONObject().put("id", "default").put("revision", "a".repeat(64))));
      JSONObject accepted = PrintingOperatorAuthorization.submit(request);
      f.service().inventory(f.device(), new JSONArray().put(printer(media.put("revision", "b".repeat(64)))));
      assertEquals(accepted.getString("jobId"), PrintingOperatorAuthorization.submit(request).getString("jobId"));
      assertEquals(409, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.submit(new JSONObject(request.toString()).put("requestId", "stale"))).getStatus());
      assertEquals(1, f.service().queuedJobs(f.device()).length());
      assertEquals("a".repeat(64), f.service().getJob(accepted.getString("jobId")).getJSONObject("options").getJSONObject("mediaProfile").getString("revision"));
      f.service().inventory(f.device(), new JSONArray().put(printer(null)));
      assertEquals(0, f.service().printers(f.device()).getJSONObject(0).getJSONArray("mediaProfiles").length());
   }


   /***************************************************************************
    ** A malformed inventory rolls back rather than retaining invented stock data.
    ***************************************************************************/
   @Test
   void invalidMediaAndAmbiguousDimensionsAreRejected() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      for(JSONObject bad : List.of(media().put("widthMicrons", 0), media().put("dpiX", 9601), media().put("revision", "A".repeat(64)), media().put("applyMode", "CUSTOM"), media().put("heightMicrons", 152400.5)))
      {
         assertThrows(PrintingException.class, () -> f.service().inventory(f.device(), new JSONArray().put(printer(bad))));
      }
      assertThrows(PrintingException.class, () -> f.service().inventory(f.device(), new JSONArray().put(printer(media()).put("mediaProfiles", new JSONArray().put(media()).put(media())))));
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      JSONObject request = request(f.printerId(), "bad").put("options", new JSONObject().put("paperWidthMm", 101.6).put("mediaProfile", new JSONObject().put("id", "default").put("revision", "a".repeat(64))));
      assertEquals(400, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.submit(request)).getStatus());
      assertEquals(0, f.service().queuedJobs(f.device()).length());
   }


   /***************************************************************************
    ** Additive facts remain compatible, but cannot broaden the stored selection.
    ***************************************************************************/
   @Test
   void futureReportedMetadataIsIgnoredButSelectionRemainsExact() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      f.service().inventory(f.device(), new JSONArray().put(printer(media().put("futureFact", new JSONObject().put("label", "future")))));
      JSONObject stored = f.service().printers(f.device()).getJSONObject(0).getJSONArray("mediaProfiles").getJSONObject(0);
      assertTrue(media().similar(stored));
      assertFalse(stored.has("futureFact"));
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      JSONObject selected = new JSONObject().put("id", "default").put("revision", "a".repeat(64)).put("futureFact", true);
      assertEquals(400, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.submit(request(f.printerId(), "future")
         .put("options", new JSONObject().put("mediaProfile", selected)))).getStatus());
      assertEquals(0, f.service().queuedJobs(f.device()).length());
   }


   /** Both inventory and immutable selections use the shared 128-character ID bound. */
   @Test
   void mediaIdentityBoundsMatchTheSharedWireContract() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      String maximum = "m".repeat(128);
      f.service().inventory(f.device(), new JSONArray().put(printer(media().put("id", maximum))));
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      JSONObject options = new JSONObject().put("mediaProfile", new JSONObject().put("id", maximum).put("revision", "a".repeat(64)));
      assertTrue(PrintingOperatorAuthorization.submit(request(f.printerId(), "maximum-media").put("options", options)).has("jobId"));
      assertEquals(400, assertThrows(PrintingException.class, () -> f.service().inventory(f.device(),
         new JSONArray().put(printer(media().put("id", maximum + "m"))))).getStatus());
      options.getJSONObject("mediaProfile").put("id", maximum + "m");
      assertEquals(400, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.submit(request(f.printerId(), "oversized-media")
         .put("options", options))).getStatus());
      assertEquals(1, f.service().queuedJobs(f.device()).length());
   }


   /***************************************************************************
    ** The native backend process uses the same caller and stable request boundary.
    ***************************************************************************/
   @Test
   void processUsesOperatorPermissionAndReplaysSameRequest() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      RunBackendStepInput input = new RunBackendStepInput().withProcessName(PrintingOperatorProcesses.SUBMIT);
      JSONObject request = request(f.printerId(), "process");
      request.keySet().forEach(key -> input.addValue(key, request.getString(key)));
      RunBackendStepOutput first = new RunBackendStepOutput();
      new PrintingOperatorProcesses().run(input, first);
      new PrintingOperatorProcesses().run(input, new RunBackendStepOutput());
      assertEquals(1, f.service().queuedJobs(f.device()).length());
      assertTrue(first.getValueString("job").contains("jobId"));
   }


   /***************************************************************************
    ** A read-only credential cannot bypass the domain write ceiling via processes.
    ***************************************************************************/
   @Test
   void scopeAndChannelCeilingsRemainEffective() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      QContext.getQSession().setCredentialScope(new QCredentialScope().withReadOnly(true).withProcesses(Set.of(PrintingOperatorProcesses.SUBMIT)));
      assertEquals(2, PrintingOperatorAuthorization.catalog(50, null).getJSONArray("printers").length());
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.submit(request(f.printerId(), "scope")));
      QContext.getQSession().setCredentialScope(null);
      QContext.getQSession().setClientChannel("api/test");
      f.instance().getTable(PrintingTables.JOB).getPermissionRules().withDeniedClients(Set.of("api"));
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.submit(request(f.printerId(), "channel")));
      assertEquals(0, f.service().queuedJobs(f.device()).length());
   }


   /***************************************************************************
    ** Bounded pages retain cursor progress and old agents report no invented stock.
    ***************************************************************************/
   @Test
   void catalogPagesAndLegacyProfilesRemainHonest() throws Exception
   {
      var f = PrintingProcessesTest.fixture();
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      JSONObject first = PrintingOperatorAuthorization.catalog(1, null);
      JSONObject last = PrintingOperatorAuthorization.catalog(1, first.getString("nextCursor"));
      assertEquals(1, first.getJSONArray("printers").length());
      assertEquals(1, last.getJSONArray("printers").length());
      assertTrue(last.isNull("nextCursor"));
      JSONObject row = first.getJSONArray("printers").getJSONObject(0);
      assertEquals(0, row.getJSONArray("mediaProfiles").length());
      assertFalse(row.getString("name").isBlank());
      assertFalse(row.getString("deviceName").isBlank());
      assertFalse(row.getString("printerRowId").equals(last.getJSONArray("printers").getJSONObject(0).getString("printerRowId")));
      assertEquals(400, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.catalog(201, null)).getStatus());
   }


   /***************************************************************************
    ** Real native HTTP authenticates a non-admin and keeps mutation Origin checks.
    ***************************************************************************/
   @Test
   void nativeHttpOperatorBoundaryUsesStandardSessionAndReadOnlyLookup() throws Exception
   {
      String jdbc = "jdbc:h2:file:" + directory.resolve("operator-http").toString().replace('\\', '/');
      String password = "synthetic-operator-password-1234";
      PrintingLocalHost host = new PrintingLocalHost(new PrintingLocalSettings(jdbc, "sa", "", "demo", 0, "synthetic-management-token-long-enough", "operator", password))
      {
         @Override
         public QInstance defineQInstance() throws QException
         {
            QInstance instance = super.defineQInstance();
            instance.getAuthentication().withModuleCodeReference(new QCodeReference(OperatorAuthentication.class));
            return instance;
         }
      };
      PrintingService service = new PrintingService(new DriverDataSource(jdbc, "sa", ""), "demo");
      String device = service.enroll(service.createEnrollment("default", "Synthetic").put("deviceName", "Synthetic").put("agentVersion", "test")).getString("deviceId");
      service.inventory(device, new JSONArray().put(printer(media())));
      service.enablePrinter(device, "laser", true);
      AtomicReference<Javalin> server = new AtomicReference<>();
      QApplicationLauncher launcher = QApplicationLauncher.run(host, new QApplicationLauncherConfig().withRegisterShutdownHook(false).withServerCustomizer(value ->
      {
         host.configure(value, false);
         value.withJavalinConfigurationCustomizer(server::set);
      }));
      String base = "http://127.0.0.1:" + server.get().port();
      String prefix = "/qqq/v1/qbit/printing";
      try(HttpClient client = HttpClient.newHttpClient())
      {
         assertEquals(401, http(client, base, prefix + "/operator/printers", null, null, null).statusCode());
         HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/qqq/v1/manageSession")).header("Origin", base)
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("operator:" + password).getBytes(StandardCharsets.UTF_8)))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
         HttpResponse<String> signedIn = client.send(login, HttpResponse.BodyHandlers.ofString());
         assertEquals(200, signedIn.statusCode());
         String cookie = signedIn.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("sessionUUID=")).findFirst().orElseThrow().split(";", 2)[0];
         HttpResponse<String> catalog = http(client, base, prefix + "/operator/printers", cookie, null, null);
         assertEquals(200, catalog.statusCode());
         String printer = new JSONObject(catalog.body()).getJSONArray("printers").getJSONObject(0).getString("printerRowId");
         assertEquals(404, http(client, base, prefix + "/operator/requests/echo:missing", cookie, null, null).statusCode());
         assertEquals(0, service.queuedJobs(device).length());
         JSONObject request = request(printer, "echo:http");
         assertEquals(403, http(client, base, prefix + "/operator/jobs", cookie, request, null).statusCode());
         assertEquals(403, http(client, base, prefix + "/operator/jobs", cookie, request, "https://foreign.invalid").statusCode());
         HttpResponse<String> submitted = http(client, base, prefix + "/operator/jobs", cookie, request, base);
         assertEquals(200, submitted.statusCode(), submitted::body);
         assertEquals(200, http(client, base, prefix + "/operator/requests/echo:http", cookie, null, null).statusCode());
         assertEquals(403, http(client, base, prefix + "/session", cookie, null, null).statusCode());
         assertEquals(1, service.queuedJobs(device).length());
      }
      finally
      {
         launcher.stop();
      }
   }


   /***************************************************************************
    ** Test-only role mapping follows the real TABLE authentication first.
    ***************************************************************************/
   public static class OperatorAuthentication extends PrintingLocalAuthentication
   {
      @Override
      public QSession createSession(QInstance instance, Map<String, String> context) throws QAuthenticationException
      {
         QSession session = super.createSession(instance, context);
         session.setPermissions(Set.of("printing_operator"));
         return session;
      }
   }


   /***************************************************************************
    ** Fixed test-only same-origin HTTP; no device proof or real network target.
    ***************************************************************************/
   private static HttpResponse<String> http(HttpClient client, String base, String path, String cookie, JSONObject body, String origin) throws Exception
   {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(10));
      if(cookie != null)
      {
         request.header("Cookie", cookie);
      }
      if(origin != null)
      {
         request.header("Origin", origin);
      }
      if(body != null)
      {
         request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
      }
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
   }


   /***************************************************************************
    ** Synthetic PDF bytes are never sent to a printer in this suite.
    ***************************************************************************/
   private static JSONObject request(String printer, String id)
   {
      return new JSONObject().put("printerRowId", printer).put("requestId", id).put("title", "Synthetic")
         .put("format", "PDF").put("contentBase64", Base64.getEncoder().encodeToString("%PDF-synthetic".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
   }


   /***************************************************************************
    ** Test facts represent one real-format queue default, not a stock mutation.
    ***************************************************************************/
   private static JSONObject media()
   {
      return new JSONObject().put("id", "default").put("revision", "a".repeat(64)).put("name", "4 x 6")
         .put("widthMicrons", 101600).put("heightMicrons", 152400).put("dpiX", 300).put("dpiY", 300)
         .put("source", "WINDOWS_QUEUE_DEFAULT").put("applyMode", "DRIVER_DEFAULT");
   }


   /***************************************************************************
    ** Existing queue identity is preserved through inventory refreshes.
    ***************************************************************************/
   private static JSONObject printer(JSONObject media)
   {
      JSONObject printer = new JSONObject().put("printerId", "laser").put("name", "Synthetic").put("formats", new JSONArray(List.of("PDF")));
      if(media != null)
      {
         printer.put("mediaProfiles", new JSONArray().put(media));
      }
      return printer;
   }
}
