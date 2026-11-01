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


import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Isolated maintenance and concurrency regressions never use live print state.
 *******************************************************************************/
class PrintingMaintenanceTest
{
   /***************************************************************************
    ** Retention is bounded, tenant-scoped and preserves replay protection.
    ***************************************************************************/
   @Test
   void retentionPurgesOnlyOldSuccessAndKeepsIdempotencyReceipts() throws Exception
   {
      DriverDataSource source = source();
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      String device = enroll(service);
      Map<String, JSONObject> submitted = new HashMap<>();
      for(String status : List.of("QUEUED", "CLAIMED", "RECEIVED", "SUBMITTING", "SPOOLED", "SPOOLER_COMPLETED", "FAILED", "OUTCOME_UNKNOWN"))
      {
         JSONObject request = request(device, status);
         String id = service.submit(request).getString("jobId");
         submitted.put(id, request);
         try(var connection = source.getConnection(); var statement = connection.prepareStatement("UPDATE printing_job SET status=?,completed_at=? WHERE id=?"))
         {
            statement.setString(1, status);
            statement.setString(2, Instant.now().minusSeconds(40 * 86400L).toString());
            statement.setString(3, id);
            statement.executeUpdate();
         }
      }
      assertEquals(0, service.purgeCompletedContent(new PrintingQBitConfig().getRetentionPolicy()));
      PrintingRetentionPolicy enabled = new PrintingRetentionPolicy(true, 30, 1);
      PrintingService other = new PrintingService(source, "tenant-b");
      assertEquals(0, other.purgeCompletedContent(enabled));
      assertEquals(1, service.purgeCompletedContent(enabled));
      String purged;
      try(var connection = source.getConnection(); var statement = connection.prepareStatement("SELECT id FROM printing_job WHERE content_available=0"); var rows = statement.executeQuery())
      {
         assertTrue(rows.next());
         purged = rows.getString(1);
      }
      assertEquals(purged, service.submit(java.util.Objects.requireNonNull(submitted.get(purged), "Purged job must be one of the submitted fixtures")).getString("jobId"));
      assertEquals(410, assertThrows(PrintingException.class, () -> service.content(device, purged)).getStatus());
      assertEquals(410, assertThrows(PrintingException.class, () -> service.retry(device, purged, new JSONObject().put("requestId", "retry").put("confirmDuplicateRisk", true))).getStatus());
      assertEquals(1, service.purgeCompletedContent(enabled));
      assertEquals(0, service.purgeCompletedContent(enabled));
      assertEquals(8, service.jobs().length());
      assertThrows(IllegalArgumentException.class, () -> new PrintingRetentionPolicy(true, 0, 100));
      assertThrows(IllegalArgumentException.class, () -> new PrintingRetentionPolicy(true, 30, 201));
   }



   /***************************************************************************
    ** Concurrent enrollment consumes one code; matching submissions deduplicate.
    ***************************************************************************/
   @Test
   void concurrentEnrollmentAndSubmissionPreserveSingleOwnership() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      JSONObject code = service.createEnrollment("warehouse", "station");
      Callable<String> enroll = () ->
      {
         try
         {
            return service.enroll(new JSONObject(code.toString()).put("deviceName", "station").put("agentVersion", "test")).getString("deviceId");
         }
         catch(PrintingException exception)
         {
            assertEquals(401, exception.getStatus());
            return "rejected";
         }
      };
      try(var pool = Executors.newFixedThreadPool(2))
      {
         var outcomes = pool.invokeAll(List.of(enroll, enroll));
         assertNotEquals(outcomes.get(0).get(), outcomes.get(1).get());
         assertEquals(1, service.devices().length());
         String device = service.devices().getJSONObject(0).getString("deviceId");
         configure(service, device);
         Callable<String> submit = () -> service.submit(request(device, "same-key")).getString("jobId");
         var jobs = pool.invokeAll(List.of(submit, submit));
         assertEquals(jobs.get(0).get(), jobs.get(1).get());
         assertEquals(1, service.jobs().length());
      }
   }



   /***************************************************************************
    ** In-memory data only; no production credentials or printer connections.
    ***************************************************************************/
   private static DriverDataSource source()
   {
      return new DriverDataSource("jdbc:h2:mem:audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
   }



   /***************************************************************************
    ** Register an isolated synthetic queue.
    ***************************************************************************/
   private static String enroll(PrintingService service) throws Exception
   {
      String device = service.enroll(service.createEnrollment("warehouse", "station").put("deviceName", "station").put("agentVersion", "test")).getString("deviceId");
      configure(service, device);
      return device;
   }



   /***************************************************************************
    ** Approval is explicit even in the test fixture.
    ***************************************************************************/
   private static void configure(PrintingService service, String device) throws Exception
   {
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "queue", true);
   }



   /***************************************************************************
    ** A minimal synthetic document that is never dispatched to Windows.
    ***************************************************************************/
   private static JSONObject request(String device, String key)
   {
      return new JSONObject().put("deviceId", device).put("printerId", "queue").put("title", "Synthetic").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(java.nio.charset.StandardCharsets.US_ASCII))).put("idempotencyKey", key);
   }
}

