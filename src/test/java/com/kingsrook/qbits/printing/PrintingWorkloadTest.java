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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcConnectionPool;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Opt-in service workload: real file-backed SQL and receipts, never a printer.
 *******************************************************************************/
@EnabledIfSystemProperty(named = "printing.qualification.load", matches = "true")
class PrintingWorkloadTest
{
   private static final int LANES = 8;
   private static final int JOBS_PER_LANE = 1250;
   private static final byte[] CONTENT = ("^XA^FO20,20^FDSYNTHETIC - NOT SENT TO A PRINTER^FS" + " ".repeat(970) + "^XZ").getBytes(StandardCharsets.UTF_8);

   @TempDir
   Path directory;


   /***************************************************************************
    ** Run a day's sizing target through actual admission, claim and persistence.
    ***************************************************************************/
   @Test
   @Timeout(value = 10, unit = TimeUnit.MINUTES)
   void completesTenThousandJobsWithReplayAndColdReopen() throws Exception
   {
      String jdbc = "jdbc:h2:file:" + directory.resolve("workload").toAbsolutePath().toString().replace('\\', '/');
      JdbcConnectionPool pool = JdbcConnectionPool.create(jdbc, "sa", "");
      pool.setMaxConnections(4);
      pool.setLoginTimeout(15);
      List<Set<String>> expected = List.of(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
      List<Callable<long[]>> lanes = new ArrayList<>();
      CountDownLatch start = new CountDownLatch(1);
      new PrintingService(pool, "tenant-0").initialize();
      for(int lane = 0; lane < LANES; lane++)
      {
         String tenant = "tenant-" + (lane % 2);
         PrintingService service = new PrintingService(pool, tenant);
         String device = enroll(service, lane);
         int laneIndex = lane;
         lanes.add(() ->
         {
            start.await();
            return runLane(pool, tenant, device, laneIndex, expected.get(laneIndex % 2));
         });
      }
      long began = System.nanoTime();
      List<Long> durations = new ArrayList<>();
      try
      {
         try(var executor = Executors.newFixedThreadPool(LANES))
         {
            var futures = lanes.stream().map(executor::submit).toList();
            start.countDown();
            for(var future : futures)
            {
               for(long duration : future.get(8, TimeUnit.MINUTES))
               {
                  durations.add(duration);
               }
            }
         }
         for(int tenant = 0; tenant < 2; tenant++)
         {
            verifyHistory(new PrintingService(pool, "tenant-" + tenant), expected.get(tenant));
         }
         verifyAccounting(pool);
         assertEquals(0, pool.getActiveConnections());
      }
      finally
      {
         pool.dispose();
      }
      // All pooled connections close before reopening. No application initialize repairs the database.
      JdbcConnectionPool reopened = JdbcConnectionPool.create(jdbc, "sa", "");
      try
      {
         verifyAccounting(reopened);
         for(int tenant = 0; tenant < 2; tenant++)
         {
            verifyHistory(new PrintingService(reopened, "tenant-" + tenant), expected.get(tenant));
         }
      }
      finally
      {
         reopened.dispose();
      }
      double seconds = (System.nanoTime() - began) / 1_000_000_000.0;
      long[] sorted = durations.stream().mapToLong(Long::longValue).sorted().toArray();
      JSONObject report = new JSONObject().put("completedAt", Instant.now().toString()).put("jobs", sorted.length)
         .put("tenants", 2).put("devices", LANES).put("poolConnections", 4).put("bytesPerDocument", CONTENT.length)
         .put("elapsedSeconds", seconds).put("jobsPerSecond", sorted.length / seconds)
         .put("p50LifecycleMillis", sorted[sorted.length / 2] / 1_000_000.0)
         .put("p95LifecycleMillis", sorted[(int) (sorted.length * 0.95)] / 1_000_000.0)
         .put("p99LifecycleMillis", sorted[(int) (sorted.length * 0.99)] / 1_000_000.0)
         .put("replayedSubmissions", LANES * (JOBS_PER_LANE / 25)).put("replayedReceipts", sorted.length)
         .put("coldReopenVerified", true).put("physicalPrinting", false).put("httpOrS3Throughput", false)
         .put("javaVersion", System.getProperty("java.version")).put("os", System.getProperty("os.name"));
      Path reportPath = Path.of("target", "qualification", "service-workload.json");
      Files.createDirectories(reportPath.getParent());
      Files.writeString(reportPath, report.toString(2));
   }


   /***************************************************************************
    ** Each lane represents one device with serial physical submission semantics.
    ***************************************************************************/
   private static long[] runLane(JdbcConnectionPool pool, String tenant, String device, int lane, Set<String> expected) throws Exception
   {
      PrintingService service = new PrintingService(pool, tenant);
      long[] durations = new long[JOBS_PER_LANE];
      for(int index = 0; index < JOBS_PER_LANE; index++)
      {
         long began = System.nanoTime();
         JSONObject request = new JSONObject().put("deviceId", device).put("printerId", "synthetic")
            .put("title", "Synthetic workload " + index).put("format", "ZPL").put("contentBase64", Base64.getEncoder().encodeToString(CONTENT))
            .put("idempotencyKey", "lane-" + lane + "-job-" + index);
         String job = service.submit(request).getString("jobId");
         assertTrue(expected.add(job));
         if(index % 25 == 0)
         {
            assertEquals(job, service.submit(request).getString("jobId"));
         }
         assertEquals(job, service.claim(device).getJSONObject("job").getString("jobId"));
         assertArrayEquals(CONTENT, service.content(device, job));
         for(String status : List.of("RECEIVED", "SUBMITTING", "SPOOLED"))
         {
            JSONObject event = new JSONObject().put("eventId", "lane-" + lane + "-job-" + index + "-" + status).put("status", status);
            if("SPOOLED".equals(status))
            {
               event.put("spoolerJobId", "synthetic-" + index);
            }
            service.event(device, job, event);
            if("SPOOLED".equals(status))
            {
               service.event(device, job, event);
            }
         }
         durations[index] = System.nanoTime() - began;
      }
      assertTrue(service.claim(device).isNull("job"));
      return (durations);
   }


   /***************************************************************************
    ** Service pagination must return every completed job once, without bytes.
    ***************************************************************************/
   private static void verifyHistory(PrintingService service, Set<String> expected) throws Exception
   {
      Set<String> actual = new HashSet<>();
      String cursor = null;
      do
      {
         JSONObject page = service.jobsPage(137, cursor);
         JSONArray jobs = page.getJSONArray("jobs");
         assertTrue(jobs.length() <= 137);
         for(int index = 0; index < jobs.length(); index++)
         {
            JSONObject job = jobs.getJSONObject(index);
            assertTrue(actual.add(job.getString("jobId")));
            assertEquals("SPOOLED", job.getString("status"));
            assertFalse(job.has("contentBase64"));
         }
         cursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
      }
      while(cursor != null);
      assertEquals(expected, actual);
      assertEquals(LANES * JOBS_PER_LANE / 2, actual.size());
   }


   /***************************************************************************
    ** Replays do not inflate stored receipts, retained bytes or queue size.
    ***************************************************************************/
   private static void verifyAccounting(JdbcConnectionPool pool) throws Exception
   {
      try(var connection = pool.getConnection(); var statement = connection.createStatement())
      {
         for(String sql : List.of("SELECT COUNT(*) FROM printing_job WHERE status='SPOOLED'", "SELECT COUNT(*) FROM printing_event",
            "SELECT SUM(retained_bytes) / " + CONTENT.length + " FROM printing_resource_usage WHERE device_id=''"))
         {
            try(var rows = statement.executeQuery(sql))
            {
               assertTrue(rows.next());
               assertEquals((long) LANES * JOBS_PER_LANE * (sql.contains("printing_event") ? 3 : 1), rows.getLong(1));
            }
         }
         try(var rows = statement.executeQuery("SELECT COUNT(*) FROM printing_document_orphan"))
         {
            assertTrue(rows.next());
            assertEquals(0, rows.getLong(1));
         }
      }
   }


   /***************************************************************************
    ** Explicit synthetic enrollment and approval never target any Windows queue.
    ***************************************************************************/
   private static String enroll(PrintingService service, int lane) throws Exception
   {
      String device = service.enroll(service.createEnrollment("fixture", "lane-" + lane)
         .put("deviceName", "lane-" + lane).put("agentVersion", "qualification")).getString("deviceId");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "synthetic").put("name", "Synthetic only")
         .put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "synthetic", true);
      return (device);
   }
}