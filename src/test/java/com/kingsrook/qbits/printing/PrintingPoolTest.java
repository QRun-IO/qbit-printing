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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.h2.jdbcx.JdbcConnectionPool;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** A bounded real JDBC pool exposes nested-acquisition deadlocks hidden by unpooled fixtures.
 *******************************************************************************/
class PrintingPoolTest
{
   /***************************************************************************
    ** A second scheduler invocation skips promptly while the first is deleting an object.
    ***************************************************************************/
   @Test
   void cleanupLeasePreventsOverlappingStorageDeletionAndReleasesAfterSuccess() throws Exception
   {
      JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:printing-lease-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      pool.setMaxConnections(2);
      pool.setLoginTimeout(2);
      BlockingDocuments documents = new BlockingDocuments();
      try
      {
         PrintingService service = new PrintingService(pool, "tenant-a");
         service.initialize();
         service.configureStorage(documents, "printing_documents");
         String job = service.submit(request(enroll(service, "station"), "old")).getString("jobId");
         try(var connection = pool.getConnection(); var statement = connection.prepareStatement("UPDATE printing_job SET status='SPOOLED',completed_at=? WHERE id=?"))
         {
            statement.setString(1, Instant.now().minusSeconds(400L * 86400).toString());
            statement.setString(2, job);
            statement.executeUpdate();
         }
         try(var executor = Executors.newFixedThreadPool(2))
         {
            var active = executor.submit(service::runRetention);
            try
            {
               assertTrue(documents.entered.await(5, TimeUnit.SECONDS));
               assertEquals(0, executor.submit(service::runRetention).get(3, TimeUnit.SECONDS));
               assertEquals(1, documents.deletions.get());
            }
            finally
            {
               documents.release.countDown();
            }
            assertEquals(1, active.get(5, TimeUnit.SECONDS));
         }
         try(var connection = pool.getConnection(); var statement = connection.prepareStatement("SELECT lease_token,last_run_count FROM printing_settings WHERE tenant_id='tenant-a'"); var rows = statement.executeQuery())
         {
            assertTrue(rows.next());
            assertNull(rows.getString("lease_token"));
            assertEquals(1, rows.getInt("last_run_count"));
         }
         assertEquals(0, service.runRetention());
      }
      finally
      {
         documents.release.countDown();
         pool.dispose();
      }
   }



   /***************************************************************************
    ** A single connection must suffice even for the durable external-upload intent.
    ***************************************************************************/
   @Test
   void singleConnectionSupportsConcurrentSubmissionsAndMaintenance() throws Exception
   {
      verifyBoundedPool(1);
   }



   /***************************************************************************
    ** Two concurrent devices must not each hold one connection while waiting for another.
    ***************************************************************************/
   @Test
   void twoConnectionsSupportConcurrentSubmissionsAndMaintenance() throws Exception
   {
      verifyBoundedPool(2);
   }



   /***************************************************************************
    ** Exercise external-storage submission and cleanup concurrently, without network or printing.
    ***************************************************************************/
   private static void verifyBoundedPool(int capacity) throws Exception
   {
      JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:printing-pool-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      pool.setMaxConnections(capacity);
      pool.setLoginTimeout(2);
      try
      {
         PrintingService service = new PrintingService(pool, "tenant-a");
         service.initialize();
         MemoryDocuments documents = new MemoryDocuments();
         service.configureStorage(documents, "printing_documents");
         String first = enroll(service, "first");
         String second = enroll(service, "second");
         String old = service.submit(request(first, "old")).getString("jobId");
         try(var connection = pool.getConnection(); var statement = connection.prepareStatement("UPDATE printing_job SET status='SPOOLED',completed_at=? WHERE id=?"))
         {
            statement.setString(1, Instant.now().minusSeconds(400L * 86400).toString());
            statement.setString(2, old);
            assertEquals(1, statement.executeUpdate());
         }
         CountDownLatch start = new CountDownLatch(1);
         try(var executor = Executors.newFixedThreadPool(3))
         {
            var a = executor.submit(() ->
            {
               start.await();
               return service.submit(request(first, "new-a")).getString("jobId");
            });
            var b = executor.submit(() ->
            {
               start.await();
               return service.submit(request(second, "new-b")).getString("jobId");
            });
            var cleanup = executor.submit(() ->
            {
               start.await();
               return service.runRetention();
            });
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, cleanup.get(10, TimeUnit.SECONDS));
         }
         assertEquals(3, service.jobs().length());
         assertEquals(2, documents.objects.size());
         assertEquals(0, service.runRetention());
         assertEquals(0, pool.getActiveConnections());
      }
      finally
      {
         pool.dispose();
      }
   }



   /***************************************************************************
    ** Synthetic devices have independent database locks and explicitly approved queues.
    ***************************************************************************/
   private static String enroll(PrintingService service, String name) throws Exception
   {
      String device = service.enroll(service.createEnrollment("warehouse", name).put("deviceName", name).put("agentVersion", "test")).getString("deviceId");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "queue", true);
      return device;
   }



   /***************************************************************************
    ** Valid synthetic bytes exercise storage writes but never reach a physical printer.
    ***************************************************************************/
   private static JSONObject request(String device, String key)
   {
      return new JSONObject().put("deviceId", device).put("printerId", "queue").put("title", "Pool regression").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString(new byte[] { 1 })).put("idempotencyKey", key);
   }



   /***************************************************************************
    ** Only document I/O is fake; SQL pooling, transactions, intents and leases are real.
    ***************************************************************************/
   private static class MemoryDocuments extends PrintingDocumentStorage
   {
      private final Map<String, byte[]> objects = new ConcurrentHashMap<>();



      /***********************************************************************
       ** Bind the same tenant prefix used by production reference verification.
       ***********************************************************************/
      MemoryDocuments()
      {
         super(new QInstance(), "tenant-a", true);
      }



      /***********************************************************************
       ** This test does not exercise S3 metadata validation; separate adapter tests do.
       ***********************************************************************/
      @Override
      public String binding(String table)
      {
         return "fixture-binding";
      }



      /***********************************************************************
       ** Retain exact bytes by the generated object reference.
       ***********************************************************************/
      @Override
      public void put(String table, String reference, String format, byte[] content)
      {
         assertTrue(reference.startsWith(prefix()));
         objects.put(reference, content.clone());
      }



      /***********************************************************************
       ** Deletion is idempotent, matching the external-storage contract.
       ***********************************************************************/
      @Override
      public void delete(String table, String reference)
      {
         objects.remove(reference);
      }
   }



   /***************************************************************************
    ** Hold one real cleanup transaction at the external-storage boundary.
    ***************************************************************************/
   private static class BlockingDocuments extends MemoryDocuments
   {
      private final CountDownLatch entered = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
      private final java.util.concurrent.atomic.AtomicInteger deletions = new java.util.concurrent.atomic.AtomicInteger();



      /***********************************************************************
       ** A second deletion would reveal an ineffective cross-worker lease.
       ***********************************************************************/
      @Override
      public void delete(String table, String reference)
      {
         deletions.incrementAndGet();
         entered.countDown();
         try
         {
            if(!release.await(10, TimeUnit.SECONDS))
            {
               throw new IllegalStateException("Fixture deletion was not released");
            }
         }
         catch(InterruptedException exception)
         {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
         }
         super.delete(table, reference);
      }
   }
}
