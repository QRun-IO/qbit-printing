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


import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Security regressions use isolated SQL and synthetic object storage only.
 *******************************************************************************/
class PrintingAdmissionTest
{
   private static final byte[] DOCUMENT = new byte[1024];



   /***************************************************************************
    ** Tiny retry requests cannot accumulate unlimited retained terminal copies.
    ***************************************************************************/
   @Test
   void retainedRetryBudgetRejectsBeforeUploadAndPreservesReplayAfterCleanup() throws Exception
   {
      Fixture fixture = fixture(limits(10, 20, 100_000, 200_000, 2048, 4096, 100, 200, 10, 20));
      String original = fixture.service.submit(request(fixture.first, "original")).getString("jobId");
      spool(fixture.service, fixture.first, original);
      JSONObject retryRequest = retry("same-retry");
      String copy = fixture.service.retry(fixture.first, original, retryRequest).getString("jobId");
      spool(fixture.service, fixture.first, copy);
      int readsBeforeRejection = fixture.documents.gets.get();
      assertQuota(() -> fixture.service.retry(fixture.first, original, retry("fresh-retry")));
      assertEquals(readsBeforeRejection, fixture.documents.gets.get());
      assertEquals(copy, fixture.service.retry(fixture.first, original, retryRequest).getString("jobId"));
      assertEquals(2, fixture.documents.puts.get());
      assertEquals(2, count(fixture.source, "printing_job"));
      execute(fixture.source, "UPDATE printing_job SET completed_at=? WHERE id=?", Instant.now().minusSeconds(400L * 86400).toString(), original);
      assertEquals(1, fixture.service.runRetention());
      assertEquals(copy, fixture.service.retry(fixture.first, original, retryRequest).getString("jobId"));
      assertEquals(410, assertThrows(PrintingException.class, () -> fixture.service.retry(fixture.first, original, retry("expired-source"))).getStatus());
      fixture.service.submit(request(fixture.first, "after-cleanup"));
      assertEquals(3, fixture.documents.puts.get());
      assertEquals(2, fixture.documents.objects.size());
   }



   /***************************************************************************
    ** Tenant count and byte budgets serialize across service instances/devices.
    ***************************************************************************/
   @Test
   void concurrentDevicesCannotExceedTenantCountOrByteBudgets() throws Exception
   {
      List<PrintingAdmissionLimits> budgets = List.of(
         limits(10, 1, 100_000, 200_000, 100_000, 200_000, 100, 200, 10, 20),
         limits(10, 20, 100_000, DOCUMENT.length, 100_000, 200_000, 100, 200, 10, 20),
         limits(10, 20, 100_000, 200_000, 100_000, DOCUMENT.length, 100, 200, 10, 20));
      for(int attempt = 0; attempt < 10; attempt++)
      {
         for(PrintingAdmissionLimits budget : budgets)
         {
            Fixture fixture = fixture(budget);
            PrintingService other = sibling(fixture, budget);
            concurrentOneAccepted(() -> fixture.service.submit(request(fixture.first, "first")), () -> other.submit(request(fixture.second, "second")));
            assertEquals(1, fixture.documents.puts.get());
            assertEquals(1, count(fixture.source, "printing_job"));
            assertEquals(0, count(fixture.source, "printing_document_orphan"));
         }
      }
   }



   /***************************************************************************
    ** Device budgets remain effective even when tenant capacity is available.
    ***************************************************************************/
   @Test
   void outstandingDeviceCountAndBytesReleaseOnlyWhenWorkFinishes() throws Exception
   {
      for(PrintingAdmissionLimits budget : List.of(
         limits(1, 20, 100_000, 200_000, 100_000, 200_000, 100, 200, 10, 20),
         limits(10, 20, DOCUMENT.length, 200_000, 100_000, 200_000, 100, 200, 10, 20)))
      {
         Fixture fixture = fixture(budget);
         String original = fixture.service.submit(request(fixture.first, "original")).getString("jobId");
         assertQuota(() -> fixture.service.submit(request(fixture.first, "overflow")));
         assertEquals(original, fixture.service.submit(request(fixture.first, "original")).getString("jobId"));
         fixture.service.submit(request(fixture.second, "other-device"));
         assertEquals(2, fixture.documents.puts.get());
         spool(fixture.service, fixture.first, original);
         fixture.service.submit(request(fixture.first, "finished-slot"));
         assertEquals(3, fixture.documents.puts.get());
      }
   }



   /***************************************************************************
    ** Failed upload compensation keeps its full reservation until exact cleanup.
    ***************************************************************************/
   @Test
   void failedUploadIntentConsumesCapacityUntilCleanupSucceeds() throws Exception
   {
      Fixture fixture = fixture(limits(10, 20, 100_000, 200_000, DOCUMENT.length, DOCUMENT.length, 100, 200, 10, 20));
      fixture.documents.failUpload = true;
      fixture.documents.failDelete = true;
      assertThrows(SQLException.class, () -> fixture.service.submit(request(fixture.first, "failed-upload")));
      assertEquals(1, count(fixture.source, "printing_document_orphan"));
      assertEquals(0, count(fixture.source, "printing_job"));
      assertQuota(() -> fixture.service.submit(request(fixture.second, "blocked-by-reservation")));
      assertEquals(1, fixture.documents.puts.get());
      fixture.documents.failUpload = false;
      fixture.documents.failDelete = false;
      execute(fixture.source, "UPDATE printing_document_orphan SET created_at=?", Instant.now().minusSeconds(7200).toString());
      assertEquals(1, fixture.service.runRetention());
      fixture.service.submit(request(fixture.second, "after-recovery"));
      assertEquals(2, fixture.documents.puts.get());
      assertEquals(1, fixture.documents.objects.size());
      assertEquals(0, count(fixture.source, "printing_document_orphan"));
   }



   /***************************************************************************
    ** Legacy upload intents with unknown size reserve the maximum allowed bytes.
    ***************************************************************************/
   @Test
   void legacyUnknownUploadChargesTenantCapacityUntilExactCleanup() throws Exception
   {
      long unknownBytes = 20L * 1024 * 1024;
      Fixture fixture = fixture(limits(10, 20, 100_000_000, 200_000_000, 100_000_000, unknownBytes, 100, 200, 10, 20));
      String orphan = UUID.randomUUID().toString();
      String reference = fixture.documents.prefix() + orphan + ".zpl";
      fixture.documents.objects.put(reference, DOCUMENT.clone());
      execute(fixture.source, "INSERT INTO printing_document_orphan (id,tenant_id,storage_table,reference,binding,created_at) VALUES (?,?,?,?,?,?)",
         orphan, "tenant-a", "printing_documents", reference, "quota-fixture", Instant.now().minusSeconds(7200).toString());
      // Only isolated test accounting tables are removed to exercise upgrade backfill.
      execute(fixture.source, "DROP TABLE printing_resource_usage");
      execute(fixture.source, "DROP TABLE printing_admission_event");
      fixture.service.initialize();
      assertEquals(Long.toString(unknownBytes), scalar(fixture.source, "SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id=? AND device_id=''", "tenant-a"));
      assertEquals("0", scalar(fixture.source, "SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id=? AND device_id=?", "tenant-a", fixture.first));
      assertQuota(() -> fixture.service.submit(request(fixture.first, "blocked-by-legacy-upload")));
      assertEquals(0, fixture.documents.puts.get());
      assertEquals(0, fixture.documents.gets.get());
      assertEquals(1, fixture.documents.objects.size());
      assertTrue(fixture.documents.objects.containsKey(reference));
      assertEquals(1, count(fixture.source, "printing_document_orphan"));
      assertEquals(0, count(fixture.source, "printing_job"));
      assertEquals(1, fixture.service.runRetention());
      assertFalse(fixture.documents.objects.containsKey(reference));
      assertEquals("0", scalar(fixture.source, "SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id=? AND device_id=''", "tenant-a"));
      fixture.service.submit(request(fixture.second, "after-legacy-cleanup"));
      assertEquals(1, fixture.documents.puts.get());
      assertEquals(1, fixture.documents.objects.size());
      assertEquals(0, count(fixture.source, "printing_document_orphan"));
   }



   /***************************************************************************
    ** Finished jobs do not permit unlimited small-request amplification per hour.
    ***************************************************************************/
   @Test
   void hourlyCreationBudgetIncludesFinishedRetriesAndDirectSubmission() throws Exception
   {
      Fixture fixture = fixture(limits(10, 20, 100_000, 200_000, 100_000, 200_000, 2, 100, 10, 20));
      String original = fixture.service.submit(request(fixture.first, "original")).getString("jobId");
      spool(fixture.service, fixture.first, original);
      JSONObject same = retry("first-copy");
      String copy = fixture.service.retry(fixture.first, original, same).getString("jobId");
      spool(fixture.service, fixture.first, copy);
      int readsBeforeRejection = fixture.documents.gets.get();
      assertQuota(() -> fixture.service.retry(fixture.first, original, retry("second-copy")));
      assertEquals(readsBeforeRejection, fixture.documents.gets.get());
      assertQuota(() -> fixture.service.submit(request(fixture.first, "direct-bypass")));
      assertEquals(copy, fixture.service.retry(fixture.first, original, same).getString("jobId"));
      assertEquals(2, fixture.documents.puts.get());
      fixture.service.submit(request(fixture.second, "independent-device"));
      assertEquals(3, fixture.documents.puts.get());
   }



   /***************************************************************************
    ** Rotated IDs cannot erase history or bypass cumulative device capacity.
    ***************************************************************************/
   @Test
   void inventoryRotationRejectsAtomicallyAndPreservesApprovalAndJobReference() throws Exception
   {
      Fixture fixture = fixture(limits(10, 20, 100_000, 200_000, 100_000, 200_000, 100, 200, 3, 20));
      fixture.service.inventory(fixture.first, new JSONArray().put(printer("queue").put("displayName", "Approved queue").put("description", "Historical description")));
      String job = fixture.service.submit(request(fixture.first, "history")).getString("jobId");
      String rowId = scalar(fixture.source, "SELECT printer_row_id FROM printing_job WHERE id=?", job);
      fixture.service.inventory(fixture.first, new JSONArray().put(printer("new-a")).put(printer("new-b")));
      assertQuota(() ->
      {
         fixture.service.inventory(fixture.first, new JSONArray().put(printer("overflow")));
         return null;
      });
      assertEquals("1", scalar(fixture.source, "SELECT available FROM printing_printer WHERE device_id=? AND printer_id=?", fixture.first, "new-a"));
      assertEquals("1", scalar(fixture.source, "SELECT enabled FROM printing_printer WHERE id=?", rowId));
      assertEquals("Historical description", scalar(fixture.source, "SELECT description FROM printing_printer WHERE id=?", rowId));
      assertEquals(rowId, scalar(fixture.source, "SELECT printer_row_id FROM printing_job WHERE id=?", job));
      fixture.service.inventory(fixture.first, new JSONArray().put(printer("queue").put("displayName", "Updated approved queue")));
      assertEquals("Updated approved queue", scalar(fixture.source, "SELECT name FROM printing_printer WHERE id=?", rowId));
      assertEquals("1", scalar(fixture.source, "SELECT enabled FROM printing_printer WHERE id=?", rowId));
      assertEquals(4, count(fixture.source, "printing_printer"));
   }



   /***************************************************************************
    ** Two devices cannot simultaneously claim the last tenant inventory slot.
    ***************************************************************************/
   @Test
   void concurrentInventoriesEnforceTenantCapacityAcrossServiceInstances() throws Exception
   {
      PrintingAdmissionLimits budget = limits(10, 20, 100_000, 200_000, 100_000, 200_000, 100, 200, 10, 3);
      Fixture fixture = fixture(budget);
      PrintingService other = sibling(fixture, budget);
      concurrentOneAccepted(() ->
      {
         fixture.service.inventory(fixture.first, new JSONArray().put(printer("new-first")));
         return null;
      }, () ->
      {
         other.inventory(fixture.second, new JSONArray().put(printer("new-second")));
         return null;
      });
      assertEquals(3, count(fixture.source, "printing_printer"));
      assertEquals("2", scalar(fixture.source, "SELECT COUNT(*) FROM printing_printer WHERE enabled=1"));
   }



   /***************************************************************************
    ** Legacy over-budget history remains accessible through bounded pages.
    ***************************************************************************/
   @Test
   void existingOversizedInventoryIsPagedWithoutDeletingHistoricalRows() throws Exception
   {
      Fixture fixture = fixture(limits(10, 20, 100_000, 200_000, 100_000, 200_000, 100, 200, 1000, 2000));
      for(int batch = 0; batch < 2; batch++)
      {
         JSONArray printers = new JSONArray();
         for(int index = 0; index < 150; index++)
         {
            printers.put(printer("historic-" + (batch * 150 + index)).put("name", "倉庫".repeat(510) + String.format(java.util.Locale.ROOT, "%04d", batch * 150 + index)));
         }
         fixture.service.inventory(fixture.first, printers);
      }
      fixture.service.withAdmissionLimits(limits(10, 20, 100_000, 200_000, 100_000, 200_000, 100, 200, 3, 5));
      assertEquals(200, fixture.service.printers(fixture.first).length());
      Set<String> ids = new HashSet<>();
      String cursor = null;
      int pages = 0;
      do
      {
         JSONObject page = fixture.service.printersPage(fixture.first, 73, cursor);
         JSONArray rows = page.getJSONArray("printers");
         assertTrue(rows.length() <= 73);
         for(int index = 0; index < rows.length(); index++)
         {
            assertTrue(ids.add(rows.getJSONObject(index).getString("printerId")));
         }
         cursor = page.optString("nextCursor", "");
         assertTrue(++pages <= 10);
      }
      while(!cursor.isBlank());
      assertEquals(301, ids.size());
      assertEquals("0", scalar(fixture.source, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS WHERE UPPER(TABLE_NAME)='PRINTING_PRINTER' AND UPPER(COLUMN_NAME)='NAME'"));
      fixture.service.inventory(fixture.first, new JSONArray().put(printer("queue")));
      assertEquals(302, count(fixture.source, "printing_printer"));
      assertQuota(() ->
      {
         fixture.service.inventory(fixture.first, new JSONArray().put(printer("extra")));
         return null;
      });
   }



   /***************************************************************************
    ** Exercise two independent service objects against one tenant database.
    ***************************************************************************/
   private static PrintingService sibling(Fixture fixture, PrintingAdmissionLimits limits)
   {
      PrintingService service = new PrintingService(fixture.source, "tenant-a").withAdmissionLimits(limits);
      service.configureStorage(fixture.documents, "printing_documents");
      return (service);
   }



   /***************************************************************************
    ** Synthetic storage and isolated SQL never reach an installed print agent.
    ***************************************************************************/
   private static Fixture fixture(PrintingAdmissionLimits limits) throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:admission-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a").withAdmissionLimits(limits);
      service.initialize();
      CountingDocuments documents = new CountingDocuments();
      service.configureStorage(documents, "printing_documents");
      return (new Fixture(source, service, documents, enroll(service, "first"), enroll(service, "second")));
   }



   /***************************************************************************
    ** Every printer approval remains explicit in the fixture.
    ***************************************************************************/
   private static String enroll(PrintingService service, String name) throws Exception
   {
      String device = service.enroll(service.createEnrollment("warehouse", name).put("deviceName", name).put("agentVersion", "test")).getString("deviceId");
      service.inventory(device, new JSONArray().put(printer("queue")));
      service.enablePrinter(device, "queue", true);
      return (device);
   }



   /***************************************************************************
    ** A current queue identity, distinct from its display metadata.
    ***************************************************************************/
   private static JSONObject printer(String id)
   {
      return (new JSONObject().put("printerId", id).put("name", "Synthetic " + id).put("status", "READY").put("formats", new JSONArray(List.of("ZPL"))));
   }



   /***************************************************************************
    ** Exact synthetic bytes make byte budgets independent of transport encoding.
    ***************************************************************************/
   private static JSONObject request(String device, String key)
   {
      return (new JSONObject().put("deviceId", device).put("printerId", "queue").put("title", "Admission regression").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString(DOCUMENT)).put("idempotencyKey", key));
   }



   /***************************************************************************
    ** Reprints retain explicit operator duplicate-risk consent.
    ***************************************************************************/
   private static JSONObject retry(String key)
   {
      return (new JSONObject().put("requestId", key).put("confirmDuplicateRisk", true));
   }



   /***************************************************************************
    ** Use real event transitions so accounting sees the same production path.
    ***************************************************************************/
   private static void spool(PrintingService service, String device, String job) throws Exception
   {
      assertEquals(job, service.claim(device).getJSONObject("job").getString("jobId"));
      service.event(device, job, new JSONObject().put("eventId", UUID.randomUUID().toString()).put("status", "SPOOLED").put("spoolerJobId", "synthetic"));
   }



   /***************************************************************************
    ** Over-budget requests must fail as operator-visible backpressure.
    ***************************************************************************/
   private static void assertQuota(Callable<?> operation)
   {
      PrintingException exception = assertThrows(PrintingException.class, operation::call);
      assertEquals(429, exception.getStatus());
      assertFalse(exception.getMessage().isBlank());
   }



   /***************************************************************************
    ** Start both calls together; only one may consume the final shared slot.
    ***************************************************************************/
   private static void concurrentOneAccepted(Callable<?> first, Callable<?> second) throws Exception
   {
      CountDownLatch start = new CountDownLatch(1);
      try(var pool = Executors.newFixedThreadPool(2))
      {
         var a = pool.submit(() -> outcome(start, first));
         var b = pool.submit(() -> outcome(start, second));
         start.countDown();
         assertEquals(List.of("accepted", "quota"), List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)).stream().sorted().toList());
      }
   }



   /***************************************************************************
    ** Unexpected exceptions remain test failures rather than rejected admissions.
    ***************************************************************************/
   private static String outcome(CountDownLatch start, Callable<?> operation) throws Exception
   {
      start.await();
      try
      {
         operation.call();
         return ("accepted");
      }
      catch(PrintingException exception)
      {
         assertEquals(429, exception.getStatus());
         return ("quota");
      }
   }



   /***************************************************************************
    ** Explicit arguments keep each boundary under test visible at its call site.
    ***************************************************************************/
   private static PrintingAdmissionLimits limits(int deviceJobs, int tenantJobs, long deviceBytes, long tenantBytes, long deviceRetained,
      long tenantRetained, int deviceRate, int tenantRate, int devicePrinters, int tenantPrinters)
   {
      return (new PrintingAdmissionLimits(deviceJobs, tenantJobs, deviceBytes, tenantBytes, deviceRetained, tenantRetained, deviceRate, tenantRate, devicePrinters, tenantPrinters));
   }



   /***************************************************************************
    ** Count only fixed test-owned tables.
    ***************************************************************************/
   private static int count(DriverDataSource source, String table) throws Exception
   {
      return (Integer.parseInt(scalar(source, "SELECT COUNT(*) FROM " + table)));
   }



   /***************************************************************************
    ** Parameterized inspection avoids document reads when checking receipts.
    ***************************************************************************/
   private static String scalar(DriverDataSource source, String sql, Object... values) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.prepareStatement(sql))
      {
         for(int index = 0; index < values.length; index++)
         {
            statement.setObject(index + 1, values[index]);
         }
         try(var rows = statement.executeQuery())
         {
            assertTrue(rows.next());
            return (rows.getString(1));
         }
      }
   }



   /***************************************************************************
    ** Age only isolated fixture rows to exercise retention recovery.
    ***************************************************************************/
   private static void execute(DriverDataSource source, String sql, Object... values) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.prepareStatement(sql))
      {
         for(int index = 0; index < values.length; index++)
         {
            statement.setObject(index + 1, values[index]);
         }
         statement.executeUpdate();
      }
   }



   /***************************************************************************
    ** Isolated state shared only by one test and its two service instances.
    ***************************************************************************/
   private record Fixture(DriverDataSource source, PrintingService service, CountingDocuments documents, String first, String second)
   {
   }



   /***************************************************************************
    ** Count object writes and inject ambiguous storage failures without network.
    ***************************************************************************/
   private static class CountingDocuments extends PrintingDocumentStorage
   {
      private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
      private final AtomicInteger puts = new AtomicInteger();
      private final AtomicInteger gets = new AtomicInteger();
      private boolean failUpload;
      private boolean failDelete;



      /***********************************************************************
       ** Reference ownership uses the same tenant prefix as production.
       ***********************************************************************/
      CountingDocuments()
      {
         super(new QInstance(), "tenant-a", true);
      }



      /***********************************************************************
       ** Metadata validation belongs to the separate native storage tests.
       ***********************************************************************/
      @Override
      public String binding(String table)
      {
         return ("quota-fixture");
      }



      /***********************************************************************
       ** A failing upload can still have created the entire remote object.
       ***********************************************************************/
      @Override
      public void put(String table, String reference, String format, byte[] content) throws SQLException
      {
         puts.incrementAndGet();
         objects.put(reference, content.clone());
         if(failUpload)
         {
            throw new SQLException("Synthetic upload acknowledgement failure");
         }
      }



      /***********************************************************************
       ** Read immutable synthetic bytes for the retry path.
       ***********************************************************************/
      @Override
      public byte[] get(String table, String reference) throws SQLException
      {
         gets.incrementAndGet();
         byte[] content = objects.get(reference);
         if(content == null)
         {
            throw new SQLException("Synthetic object was removed");
         }
         return (content.clone());
      }



      /***********************************************************************
       ** Failed cleanup must keep its reservation; successful deletion is exact.
       ***********************************************************************/
      @Override
      public void delete(String table, String reference) throws SQLException
      {
         if(failDelete)
         {
            throw new SQLException("Synthetic delete failure");
         }
         objects.remove(reference);
      }
   }
}
