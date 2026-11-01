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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.modules.authentication.implementations.TableBasedAuthenticationModule;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncher;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncherConfig;
import io.javalin.Javalin;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Isolated durable H2 and ephemeral HTTP prove native auth and shared records.
 *******************************************************************************/
class PrintingLocalHostTest
{
   private static final String TOKEN = "synthetic-admin-token-for-isolated-tests";
   private static final String PASSWORD = "synthetic-login-password-12345";

   @TempDir
   Path directory;



   /***************************************************************************
    ** Real native table auth, origin protection, scheduler and transport coexist.
    ***************************************************************************/
   @Test
   void nativeHostPreservesPairedDataAndRequiresVerifiedSameOriginAdministration() throws Exception
   {
      exerciseHost("legacy", "");
   }



   /***************************************************************************
    ** Explicit case-preserving H2 hosts retain their existing physical names too.
    ***************************************************************************/
   @Test
   void nativeHostSupportsCasePreservingDatabase() throws Exception
   {
      exerciseHost("case-preserving", ";DATABASE_TO_UPPER=FALSE");
   }



   /***************************************************************************
    ** The same acceptance checks apply to both supported H2 naming conventions.
    ***************************************************************************/
   private void exerciseHost(String file, String options) throws Exception
   {
      String jdbc = "jdbc:h2:file:" + directory.resolve(file).toString().replace('\\', '/') + options;
      DriverDataSource source = new DriverDataSource(jdbc, "sa", "");
      PrintingService paired = new PrintingService(source, "demo");
      paired.initialize();
      JSONObject enrolled = paired.enroll(paired.createEnrollment("legacy-facility", "Synthetic station").put("deviceName", "Synthetic station").put("agentVersion", "test"));
      String device = enrolled.getString("deviceId");
      paired.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic laser").put("formats", new JSONArray(List.of("PDF")))));
      PrintingLocalHost host = new PrintingLocalHost(settings(jdbc, PASSWORD));
      AtomicReference<Javalin> javalin = new AtomicReference<>();
      QApplicationLauncher launcher = QApplicationLauncher.run(host, new QApplicationLauncherConfig().withRegisterShutdownHook(false)
         .withServerCustomizer(server ->
         {
            host.configure(server, false);
            server.withJavalinConfigurationCustomizer(javalin::set);
         }));
      String base = "http://127.0.0.1:" + java.util.Objects.requireNonNull(javalin.get(), "Test HTTP server was not started").port();
      try(HttpClient client = HttpClient.newHttpClient())
      {
         assertTrue(launcher.getStartedServiceNames().contains(QApplicationLauncher.SCHEDULE_MANAGER_SERVICE_NAME));
         assertEquals("0 */5 * * * ?", launcher.getQInstance().getProcess(PrintingRetentionProcesses.SCHEDULED).getSchedule().getCronExpression());
         assertRejectedHost(java.util.Objects.requireNonNull(javalin.get(), "Test HTTP server was not started").port());
         assertEquals(401, send(client, base, "/qqq/v1/table/printing_device/count", null, null, null).statusCode());
         assertEquals(401, send(client, base, "/qqq/v1/manageSession", basic("operator", "wrong"), null, null).statusCode());
         HttpResponse<String> login = send(client, base, "/qqq/v1/manageSession", basic("operator", PASSWORD), null, base);
         assertEquals(200, login.statusCode(), login.body());
         String cookie = login.headers().allValues("set-cookie").stream().filter(value -> value.startsWith("sessionUUID=")).findFirst().orElseThrow().split(";", 2)[0];
         String sessionId = cookie.substring("sessionUUID=".length());
         assertTrue(login.headers().allValues("set-cookie").stream().anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains("httponly")));
         assertFalse(login.body().contains("passwordHash"));
         HttpResponse<String> count = send(client, base, "/qqq/v1/table/printing_device/count", null, cookie, base);
         assertEquals(200, count.statusCode(), count.body());
         assertEquals(1, new JSONObject(count.body()).getInt("count"));
         assertEquals(403, send(client, base, "/qqq/v1/table/printing_device/count", null, cookie, "https://unrelated.invalid").statusCode());
         assertEquals(403, send(client, base, "/qqq/v1/table/printing_device/count", null, cookie, null).statusCode());
         assertEquals(403, send(client, base, "/qqq/v1/manageSession", basic("operator", PASSWORD), null, "null").statusCode());
         assertEquals(403, send(client, base, "/qqq/v1/table/printing_admin_user/count", null, cookie, base).statusCode());
         HttpResponse<String> transport = client.send(HttpRequest.newBuilder(URI.create(base + "/printing/v1/admin/devices")).header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
         assertEquals(200, transport.statusCode());
         assertEquals(device, new JSONObject(transport.body()).getJSONArray("devices").getJSONObject(0).getString("deviceId"));
         QContext.init(launcher.getQInstance(), null);
         var session = new PrintingLocalAuthentication().createSession(launcher.getQInstance(), Map.of("sessionId", sessionId));
         assertTrue(session.hasPermission("printing_admin"));
         QContext.init(launcher.getQInstance(), session);
         assertEquals(device, new QueryAction().execute(new QueryInput().withTableName(PrintingTables.DEVICE)).getRecords().getFirst().getValueString("id"));
         assertEquals("legacy-facility", new QueryAction().execute(new QueryInput().withTableName(PrintingTables.DEVICE)).getRecords().getFirst().getValueString("facility_id"));
         QContext.clear();
         assertEquals(200, send(client, base, "/qqq/v1/logout", null, cookie, base).statusCode());
         assertEquals(401, send(client, base, "/qqq/v1/table/printing_device/count", null, cookie, base).statusCode());
         var heartbeat = HttpRequest.newBuilder(URI.create(base + "/printing/v1/device/heartbeat"))
            .header("Authorization", "Bearer " + enrolled.getString("deviceToken")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("agentVersion", "test").toString())).build();
         assertEquals(200, client.send(heartbeat, HttpResponse.BodyHandlers.discarding()).statusCode());
         assertEquals(device, paired.authenticate(enrolled.getString("deviceToken")));
         assertEquals(0, paired.jobs().length());
         verifyUnprivilegedAccountRejected(source, launcher);
      }
      finally
      {
         QContext.clear();
         launcher.stop();
      }
      String originalHash = passwordHash(source);
      new PrintingLocalHost(settings(jdbc, "a-different-password-must-never-reset"));
      assertEquals(originalHash, passwordHash(source));
      assertThrows(IllegalArgumentException.class, () -> new PrintingLocalHost(new PrintingLocalSettings(jdbc, "sa", "", "demo", 0, TOKEN, "different-user", PASSWORD)));
   }



   /***************************************************************************
    ** A hostile DNS name cannot turn a loopback listener into a trusted origin.
    ***************************************************************************/
   private static void assertRejectedHost(int port) throws Exception
   {
      try(var socket = new java.net.Socket(java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port))
      {
         socket.setSoTimeout(5000);
         socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: unrelated.invalid\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
         var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
         assertTrue(reader.readLine().contains("403"));
      }
   }



   /***************************************************************************
    ** A valid password alone never grants printing permissions to another user.
    ***************************************************************************/
   private static void verifyUnprivilegedAccountRejected(DriverDataSource source, QApplicationLauncher launcher) throws Exception
   {
      try(var connection = source.getConnection(); var insert = connection.prepareStatement("INSERT INTO printing_admin_user(username,passwordHash,fullName) VALUES (?,?,?)"))
      {
         insert.setString(1, "viewer");
         insert.setString(2, TableBasedAuthenticationModule.PasswordHasher.createHashedPassword(PASSWORD));
         insert.setString(3, "Synthetic viewer");
         insert.executeUpdate();
      }
      QContext.init(launcher.getQInstance(), null);
      var verified = new TableBasedAuthenticationModule().createSession(launcher.getQInstance(), Map.of(TableBasedAuthenticationModule.BASIC_AUTH_KEY, basic("viewer", PASSWORD).substring(6)));
      assertEquals("viewer", verified.getUser().getIdReference());
      assertThrows(QAuthenticationException.class, () -> new PrintingLocalAuthentication().createSession(launcher.getQInstance(), Map.of("sessionId", verified.getUuid())));
      QContext.clear();
   }



   /***************************************************************************
    ** Missing bootstrap credentials fail closed; no environment secret is echoed.
    ***************************************************************************/
   @Test
   void explicitConfigurationAndBootstrapAreRequired() throws Exception
   {
      assertThrows(IllegalArgumentException.class, () -> PrintingLocalSettings.fromEnvironment(Map.of()));
      assertThrows(IllegalArgumentException.class, () -> PrintingLocalSettings.fromEnvironment(Map.of("QQQ_PRINT_JDBC_URL", "jdbc:h2:mem:wrong")));
      String jdbc = "jdbc:h2:file:" + directory.resolve("empty").toString().replace('\\', '/');
      assertThrows(IllegalArgumentException.class, () -> new PrintingLocalHost(settings(jdbc, null)));
      assertFalse(settings(jdbc, PASSWORD).toString().contains(PASSWORD));
      assertFalse(settings(jdbc, PASSWORD).toString().contains(TOKEN));
   }



   /***************************************************************************
    ** One synthetic account and ephemeral port, never physical printer access.
    ***************************************************************************/
   private static PrintingLocalSettings settings(String jdbc, @Nullable String password)
   {
      return new PrintingLocalSettings(jdbc, "sa", "", "demo", 0, TOKEN, "operator", password);
   }



   /***************************************************************************
    ** Read only the synthetic hash to prove startup never changes it.
    ***************************************************************************/
   private static String passwordHash(DriverDataSource source) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT passwordHash FROM printing_admin_user WHERE username='operator'"))
      {
         rows.next();
         return rows.getString(1);
      }
   }



   /***************************************************************************
    ** Test credentials stay in request headers and never command-line arguments.
    ***************************************************************************/
   private static String basic(String user, String password)
   {
      return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
   }



   /***************************************************************************
    ** Native routes use JSON POST and explicit cookies/origin in these fixtures.
    ***************************************************************************/
   private static HttpResponse<String> send(HttpClient client, String base, String path, @Nullable String authorization, @Nullable String cookie, @Nullable String origin) throws Exception
   {
      var request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
      if(authorization != null)
      {
         request.header("Authorization", authorization);
      }
      if(cookie != null)
      {
         request.header("Cookie", cookie);
      }
      if(origin != null)
      {
         request.header("Origin", origin);
      }
      return client.send(request.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
   }
}
