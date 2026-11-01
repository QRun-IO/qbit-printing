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


import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Client-owned profiles retain identity while bounded history scales with rows.
 *******************************************************************************/
class PrintingProfilesTest
{
   /***************************************************************************
    ** Long labels round trip and older clients cannot erase synchronized text.
    ***************************************************************************/
   @Test
   void profilesPreserveIdentityApprovalAndFallback() throws Exception
   {
      DriverDataSource source = source();
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      String computer = "station-" + "c".repeat(900);
      JSONObject enrollment = service.enroll(service.createEnrollment("warehouse", computer).put("deviceName", computer).put("agentVersion", "test"));
      String device = enrollment.getString("deviceId");
      String queue = "queue-" + "q".repeat(1000);
      JSONObject printer = printer(queue).put("displayName", "p".repeat(512)).put("description", "d".repeat(4096)).put("location", "l".repeat(512));
      service.inventory(device, new JSONArray().put(printer));
      service.enablePrinter(device, "queue-id", true);
      JSONObject profile = new JSONObject().put("name", "n".repeat(512)).put("description", "d".repeat(4096)).put("location", "l".repeat(512));
      service.heartbeat(device, new JSONObject().put("agentVersion", "test").put("profile", profile));
      PrintingService restarted = new PrintingService(source, "tenant-a");
      restarted.initialize();
      assertEquals(device, restarted.authenticate(enrollment.getString("deviceToken")));
      assertEquals(profile.getString("name"), restarted.devices().getJSONObject(0).getString("deviceName"));
      assertEquals(computer, restarted.devices().getJSONObject(0).getString("computerName"));
      assertTrue(profile.similar(restarted.devices().getJSONObject(0).getJSONObject("profile")));
      restarted.heartbeat(device, new JSONObject().put("agentVersion", "older"));
      restarted.inventory(device, new JSONArray().put(printer(queue)));
      JSONObject observed = restarted.printers(device).getJSONObject(0);
      assertEquals(queue, observed.getString("name"));
      assertEquals("p".repeat(512), observed.getString("displayName"));
      assertTrue(observed.getBoolean("enabled"));
      assertEquals(4096, observed.getString("description").length());
      restarted.heartbeat(device, new JSONObject().put("agentVersion", "test").put("profile", new JSONObject().put("name", "").put("description", "").put("location", JSONObject.NULL)));
      restarted.inventory(device, new JSONArray().put(printer(queue).put("displayName", "").put("description", "").put("location", "")));
      assertEquals(computer, restarted.devices().getJSONObject(0).getString("deviceName"));
      assertEquals("l".repeat(512), restarted.devices().getJSONObject(0).getJSONObject("profile").getString("location"));
      assertEquals("", restarted.printers(device).getJSONObject(0).getString("description"));
      try(Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT name FROM printing_printer WHERE device_id=?"))
      {
         statement.setString(1, device);
         try(var rows = statement.executeQuery())
         {
            rows.next();
            assertEquals(queue, rows.getString(1));
         }
      }
   }



   /***************************************************************************
    ** Invalid profiles fail atomically without revoking approval or exposure.
    ***************************************************************************/
   @Test
   void rejectsOversizedTextAndCrossTenantWrites() throws Exception
   {
      DriverDataSource source = source();
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      String device = enroll(service);
      service.inventory(device, new JSONArray().put(printer("queue")));
      service.enablePrinter(device, "queue-id", true);
      for(JSONObject invalid : new JSONObject[] {new JSONObject().put("name", "n".repeat(513)), new JSONObject().put("description", "d".repeat(4097)), new JSONObject().put("location", "l".repeat(513))})
      {
         assertEquals(400, assertThrows(PrintingException.class, () -> service.heartbeat(device, new JSONObject().put("agentVersion", "test").put("profile", invalid))).getStatus());
      }
      assertThrows(PrintingException.class, () -> service.inventory(device, new JSONArray().put(printer("q".repeat(1025)))));
      assertThrows(PrintingException.class, () -> service.inventory(device, new JSONArray().put(printer("queue").put("displayName", "n".repeat(513)))));
      JSONObject observed = service.printers(device).getJSONObject(0);
      assertTrue(observed.getBoolean("enabled"));
      assertTrue(observed.getBoolean("available"));
      assertEquals("queue", observed.getString("name"));
      assertEquals(1024, service.submit(request(device).put("title", "t".repeat(1024))).getString("title").length());
      assertThrows(PrintingException.class, () -> service.submit(request(device).put("title", "t".repeat(1025))));
      PrintingService other = new PrintingService(source, "tenant-b");
      assertEquals(404, assertThrows(PrintingException.class, () -> other.heartbeat(device, new JSONObject().put("agentVersion", "test").put("profile", new JSONObject().put("name", "foreign")))).getStatus());
      assertEquals(404, assertThrows(PrintingException.class, () -> other.inventory(device, new JSONArray().put(printer("foreign")))).getStatus());
   }



   /***************************************************************************
    ** Ten thousand historical rows paginate once each and dispatch stays bounded.
    ***************************************************************************/
   @Test
   void paginatesTenThousandJobsWithoutDocumentsOrSkippedRows() throws Exception
   {
      DriverDataSource source = source();
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      String device = enroll(service);
      service.inventory(device, new JSONArray().put(printer("queue")));
      service.enablePrinter(device, "queue-id", true);
      String seed = service.submit(request(device)).getString("jobId");
      try(Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO printing_job (id, tenant_id, device_id, printer_id, title, format, content_hash, content_base64, content_length, options_json, idempotency_key, created_at, status, printer_row_id) SELECT ?, tenant_id, device_id, printer_id, ?, format, content_hash, content_base64, content_length, options_json, ?, ?, ?, printer_row_id FROM printing_job WHERE id=?"))
      {
         connection.setAutoCommit(false);
         for(int index = 0; index < 10000; index++)
         {
            statement.setString(1, UUID.nameUUIDFromBytes(("job-" + index).getBytes(StandardCharsets.UTF_8)).toString());
            statement.setString(2, "Synthetic job " + index);
            statement.setString(3, "volume-" + index);
            statement.setString(4, java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index).toString());
            statement.setString(5, index < 9900 ? "SPOOLER_COMPLETED" : "QUEUED");
            statement.setString(6, seed);
            statement.addBatch();
         }
         statement.executeBatch();
         connection.commit();
      }
      Set<String> ids = new HashSet<>();
      String cursor = null;
      do
      {
         JSONObject page = service.jobsPage(137, cursor);
         JSONArray jobs = page.getJSONArray("jobs");
         assertTrue(jobs.length() <= 137);
         for(int index = 0; index < jobs.length(); index++)
         {
            JSONObject job = jobs.getJSONObject(index);
            assertTrue(ids.add(job.getString("jobId")));
            assertFalse(job.has("contentBase64"));
         }
         cursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
      }
      while(cursor != null);
      assertEquals(10001, ids.size());
      assertEquals(200, service.jobs().length());
      assertEquals(50, service.queuedJobs(device).length());
      JSONObject claimed = service.claim(device).getJSONObject("job");
      assertEquals("Synthetic job 9900", claimed.getString("title"));
      assertEquals(claimed.getString("jobId"), service.claim(device).getJSONObject("job").getString("jobId"));
      assertThrows(PrintingException.class, () -> service.jobsPage(201, null));
      assertEquals(0, new PrintingService(source, "tenant-b").jobsPage(200, null).getJSONArray("jobs").length());
   }



   /***************************************************************************
    ** Isolated synthetic database.
    ***************************************************************************/
   private static DriverDataSource source()
   {
      return new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
   }



   /***************************************************************************
    ** A single stable printer identifier independent of labels.
    ***************************************************************************/
   private static JSONObject printer(String queue)
   {
      return new JSONObject().put("printerId", "queue-id").put("name", queue).put("status", "READY").put("formats", new JSONArray().put("ZPL"));
   }



   /***************************************************************************
    ** Enroll a synthetic computer.
    ***************************************************************************/
   private static String enroll(PrintingService service) throws Exception
   {
      return service.enroll(service.createEnrollment("warehouse", "computer").put("deviceName", "computer").put("agentVersion", "test")).getString("deviceId");
   }



   /***************************************************************************
    ** Small synthetic document; these tests never call a Windows printer.
    ***************************************************************************/
   private static JSONObject request(String device)
   {
      return new JSONObject().put("deviceId", device).put("printerId", "queue-id").put("title", "Synthetic job").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(StandardCharsets.UTF_8))).put("idempotencyKey", UUID.randomUUID().toString());
   }
}
