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


import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import io.javalin.Javalin;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Exercise the public HTTP boundary with shared, language-neutral fixtures.
 *******************************************************************************/
class PrintingContractTest
{
   private static final String ADMIN = "contract-test-administrator-token";
   private static final Path FIXTURES = Path.of(System.getProperty("printing.protocol.fixtures", "../qqq-print-protocol/fixtures"));



   /***************************************************************************
    ** Enrollment through spool acknowledgment uses exactly the connector wire DTOs.
    ***************************************************************************/
   @Test
   void sharedFixturesCompletePrintingRoundTrip() throws Exception
   {
      PrintingService service = new PrintingService(new DriverDataSource("jdbc:h2:mem:contract-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""), "contract-tenant");
      service.initialize();
      PrintingRoutes routes = new PrintingRoutes(service, context -> ADMIN.equals(PrintingRoutes.bearer(context)));
      Javalin app = Javalin.create(routes::acceptJavalinConfig).start("127.0.0.1", 0);
      try(HttpClient client = HttpClient.newHttpClient())
      {
         String base = "http://127.0.0.1:" + app.port() + PrintingRoutes.BASE;
         JSONObject code = json(send(client, base, "POST", "/admin/enrollments", ADMIN, fixture("create-enrollment.request.json")), 201);
         JSONObject enrollment = fixture("enroll.request.json").put("enrollmentCode", code.getString("enrollmentCode"));
         JSONObject registered = json(send(client, base, "POST", "/enroll", null, enrollment), 200);
         assertEquals(1, registered.getInt("protocolVersion"));
         String token = registered.getString("deviceToken");
         String device = registered.getString("deviceId");
         assertFalse(token.isBlank());

         assertTrue(json(send(client, base, "PUT", "/device/printers", token, fixture("printers.request.json")), 200).getBoolean("accepted"));
         JSONObject heartbeat = json(send(client, base, "POST", "/device/heartbeat", token, fixture("heartbeat.request.json")), 200);
         assertTrue(heartbeat.has("serverTime"));
         JSONObject devices = json(send(client, base, "GET", "/admin/devices", ADMIN, null), 200);
         assertEquals(device, devices.getJSONArray("devices").getJSONObject(0).getString("deviceId"));
         JSONObject printers = json(send(client, base, "GET", "/admin/devices/" + device + "/printers", ADMIN, null), 200);
         assertEquals(2, printers.getJSONArray("printers").length());
         assertFalse(printers.getJSONArray("printers").getJSONObject(0).getBoolean("enabled"));

         JSONObject submission = fixture("submit.request.json").put("deviceId", device);
         String printer = submission.getString("printerId");
         String approvalPath = "/admin/devices/" + device + "/printers/" + printer;
         json(send(client, base, "PUT", approvalPath, ADMIN, fixture("approval.request.json")), 200);
         JSONObject queued = json(send(client, base, "POST", "/admin/jobs", ADMIN, submission), 200);
         String jobId = queued.getString("jobId");
         assertEquals("QUEUED", queued.getString("status"));
         assertEquals(jobId, json(send(client, base, "POST", "/admin/jobs", ADMIN, submission), 200).getString("jobId"));
         JSONObject claimed = json(send(client, base, "POST", "/device/jobs/claim", token, new JSONObject()), 200).getJSONObject("job");
         assertEquals(jobId, claimed.getString("jobId"));
         assertEquals("CLAIMED", claimed.getString("status"));

         String jobPath = "/device/jobs/" + jobId;
         JSONObject received = fixture("received.event.json");
         json(send(client, base, "POST", jobPath + "/events", token, received), 200);
         json(send(client, base, "POST", jobPath + "/events", token, received), 200);
         HttpResponse<byte[]> content = send(client, base, "GET", jobPath + "/content", token, null);
         assertEquals(200, content.statusCode());
         assertArrayEquals(Files.readAllBytes(FIXTURES.resolve("test-label.zpl")), content.body());
         assertEquals(claimed.getLong("contentLength"), content.body().length);
         assertEquals(claimed.getString("contentSha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.body())));
         json(send(client, base, "POST", jobPath + "/events", token, fixture("submitting.event.json")), 200);
         json(send(client, base, "POST", jobPath + "/events", token, fixture("spooled.event.json")), 200);
         JSONObject completed = json(send(client, base, "GET", "/admin/jobs/" + jobId, ADMIN, null), 200);
         assertEquals("SPOOLED", completed.getString("status"));
         assertEquals("42", completed.getString("spoolerJobId"));
         assertTrue(json(send(client, base, "POST", "/device/jobs/claim", token, new JSONObject()), 200).isNull("job"));
         verifyOperatorRoutes(client, base, token, printer, jobPath);

         HttpResponse<byte[]> denied = send(client, base, "GET", "/admin/jobs", token, null);
         JSONObject error = json(denied, 403);
         assertTrue(error.has("code"));
         assertTrue(error.has("message"));
         assertFalse(error.has("error"));
      }
      finally
      {
         app.stop();
      }
   }



   /** Legacy compatibility transport preserves the same shared control contract and authorization boundary. */
   @Test
   void sharedControlFixturesRequireScopedAuthorizationAndPreserveReplay() throws Exception
   {
      PrintingService service = new PrintingService(new DriverDataSource("jdbc:h2:mem:control-contract-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""), "contract-tenant");
      service.initialize();
      PrintingRoutes routes = new PrintingRoutes(service, context -> ADMIN.equals(PrintingRoutes.bearer(context)));
      Javalin app = Javalin.create(routes::acceptJavalinConfig).start("127.0.0.1", 0);
      try(HttpClient client = HttpClient.newHttpClient())
      {
         String base = "http://127.0.0.1:" + app.port() + PrintingRoutes.BASE;
         JSONObject enrolled = service.enroll(fixture("enroll.request.json").put("enrollmentCode", service.createEnrollment("facility", "device").getString("enrollmentCode")));
         String token = enrolled.getString("deviceToken");
         String device = enrolled.getString("deviceId");
         String actions = "/admin/devices/" + device + "/actions";
         String history = "/admin/devices/" + device + "/commands";
         assertEquals("device-control-v1", json(send(client, base, "GET", "/version", null, null), 200).getJSONArray("capabilities").getString(0));
         JSONObject poll = fixture("control-poll.request.json").put("lastAppliedRevision", 0);
         json(send(client, base, "POST", "/device/control/poll", null, poll), 401);
         json(send(client, base, "POST", "/device/control/poll", token, poll), 200);
         JSONObject refresh = fixture("action-refresh-inventory.request.json").put("issuedAt", java.time.Instant.now().toString());
         json(send(client, base, "POST", actions, token, refresh), 403);
         JSONObject command = json(send(client, base, "POST", actions, ADMIN, refresh), 200);
         assertEquals(command.toString(), json(send(client, base, "POST", actions, ADMIN, refresh), 200).toString());
         String commandId = command.getString("commandId");
         assertEquals(commandId, json(send(client, base, "POST", "/device/control/poll", token, poll), 200).getJSONArray("commands").getJSONObject(0).getString("commandId"));
         JSONObject receipt = fixture("control-inventory-refreshed.receipt.json");
         String receiptPath = "/device/control/commands/" + commandId + "/receipt";
         json(send(client, base, "POST", receiptPath, token, receipt), 200);
         json(send(client, base, "POST", receiptPath, token, receipt), 200);
         json(send(client, base, "POST", receiptPath, token, new JSONObject(receipt.toString()).put("message", "changed")), 409);
         json(send(client, base, "POST", receiptPath, token, new JSONObject(receipt.toString()).put("message", "x".repeat(9000))), 413);
         json(send(client, base, "GET", history, token, null), 403);
         assertEquals("SUCCEEDED", json(send(client, base, "GET", history + "/" + commandId, ADMIN, null), 200).getString("status"));
         json(send(client, base, "GET", history + "?limit=201", ADMIN, null), 400);
         JSONObject revoke = fixture("action-revoke.request.json").put("issuedAt", java.time.Instant.now().toString());
         json(send(client, base, "POST", actions, ADMIN, revoke), 200);
         json(send(client, base, "POST", "/device/control/poll", token, poll), 401);
         json(send(client, base, "POST", "/device/jobs/claim", token, new JSONObject()), 401);
         assertEquals(2, json(send(client, base, "GET", history, ADMIN, null), 200).getJSONArray("commands").length());
      }
      finally
      {
         app.stop();
      }
   }


   /***************************************************************************
    ** Claim waits for its bounded JSON body before committing the response.
    ***************************************************************************/
   @Test
   void claimConsumesSplitRequestBodyBeforeResponding() throws Exception
   {
      PrintingService service = new PrintingService(new DriverDataSource("jdbc:h2:mem:split-body-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""), "contract-tenant");
      service.initialize();
      String code = service.createEnrollment("default", "Synthetic device").getString("enrollmentCode");
      String token = service.enroll(fixture("enroll.request.json").put("enrollmentCode", code)).getString("deviceToken");
      PrintingRoutes routes = new PrintingRoutes(service, context -> false);
      Javalin app = Javalin.create(routes::acceptJavalinConfig).start("127.0.0.1", 0);
      try(Socket socket = new Socket("127.0.0.1", app.port()))
      {
         socket.setSoTimeout(250);
         String headers = "POST " + PrintingRoutes.BASE + "/device/jobs/claim HTTP/1.1\r\nHost: 127.0.0.1:" + app.port()
            + "\r\nAuthorization: Bearer " + token + "\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{";
         socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
         socket.getOutputStream().flush();
         assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
         socket.setSoTimeout(5000);
         socket.getOutputStream().write('}');
         socket.getOutputStream().flush();
         String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
         assertTrue(response.startsWith("HTTP/1.1 200"));
         assertTrue(response.contains("\"job\":null"));
      }
      finally
      {
         app.stop();
      }
   }



   /***************************************************************************
    ** Load the same fixture consumed by the Windows protocol tests.
    ***************************************************************************/
   private static JSONObject fixture(String name) throws Exception
   {
      return (new JSONObject(Files.readString(FIXTURES.resolve(name))));
   }



   /***************************************************************************
    ** Device actions use authenticated routes and preserve operator intent.
    ***************************************************************************/
   private static void verifyOperatorRoutes(HttpClient client, String base, String token, String printer, String jobPath) throws Exception
   {
      JSONObject request = fixture("device-retry.request.json");
      json(send(client, base, "POST", jobPath + "/retry", null, request), 401);
      json(send(client, base, "POST", jobPath + "/retry", token, new JSONObject().put("requestId", "no-confirm")), 409);
      JSONObject retry = json(send(client, base, "POST", jobPath + "/retry", token, request), 200);
      assertEquals("QUEUED", retry.getString("status"));
      assertEquals(retry.getString("jobId"), json(send(client, base, "GET", "/device/jobs/queued", token, null), 200).getJSONArray("jobs").getJSONObject(0).getString("jobId"));
      assertTrue(json(send(client, base, "POST", "/device/jobs/" + retry.getString("jobId") + "/cancel", token, new JSONObject()), 200).getBoolean("accepted"));
      assertEquals(0, json(send(client, base, "GET", "/device/jobs/queued", token, null), 200).getJSONArray("jobs").length());
      JSONObject test = fixture("device-test.request.json").put("printerId", printer);
      JSONObject queued = json(send(client, base, "POST", "/device/test-print", token, test), 200);
      assertEquals("QUEUED", queued.getString("status"));
      assertEquals(queued.getString("jobId"), json(send(client, base, "POST", "/device/test-print", token, test), 200).getString("jobId"));
   }



   /***************************************************************************
    ** Keep binary content intact while constructing authenticated HTTP messages.
    ***************************************************************************/
   private static HttpResponse<byte[]> send(HttpClient client, String base, String method, String path, String token, JSONObject body) throws Exception
   {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
      if(token != null)
      {
         request.header("Authorization", "Bearer " + token);
      }
      if(body != null)
      {
         request.header("Content-Type", "application/json");
      }
      request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
      return (client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()));
   }



   /***************************************************************************
    ** Assert status without leaking enrollment or credential bodies on failure.
    ***************************************************************************/
   private static JSONObject json(HttpResponse<byte[]> response, int expected)
   {
      assertEquals(expected, response.statusCode());
      return (new JSONObject(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)));
   }
}


