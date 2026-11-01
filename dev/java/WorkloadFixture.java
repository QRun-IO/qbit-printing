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

// SPDX-License-Identifier: Apache-2.0

package com.kingsrook.qbits.printing;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncher;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncherConfig;
import io.javalin.Javalin;
import org.json.JSONArray;
import org.json.JSONObject;


/***************************************************************************
 ** Fixture only: real checkout host, synthetic SDK producer, no physical printer.
 ***************************************************************************/
public final class WorkloadFixture
{
   /***************************************************************************
    ** Drive two synthetic tenants through real network workers and verify durable receipts and accounting.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      Path dir = Path.of(args[0]).toAbsolutePath();
      if(!dir.getFileName().toString().startsWith("run-"))
      {
         throw new IllegalArgumentException("Owned run directory required");
      }
      String                     jdbc     = "jdbc:h2:file:" + dir.resolve("synthetic").toString().replace('\\', '/') + ";DB_CLOSE_DELAY=-1";
      String                     password = "synthetic-" + UUID.randomUUID();
      List<QApplicationLauncher> launches = new ArrayList<>();
      List<PrintingService>      services = new ArrayList<>();
      List<String>               devices  = new ArrayList<>();
      JSONArray                  inputs   = new JSONArray();
      boolean                    idle     = args.length > 1 && args[1].equals("idle");
      int                        total    = idle ? 40 : 10000;
      long                       started  = System.nanoTime();
      int                        exit     = 1;
      try
      {
         for(int tenant = 0; tenant < 2; tenant++)
         {
            PrintingLocalHost        host   = new PrintingLocalHost(new PrintingLocalSettings(jdbc, "sa", "", "tenant-" + tenant, 0, "fixture-only-management-token-123456", "operator", password));
            AtomicReference<Javalin> app    = new AtomicReference<>();
            QApplicationLauncher     launch = QApplicationLauncher.run(host, new QApplicationLauncherConfig().withRegisterShutdownHook(false).withServerCustomizer(server ->
            {
               host.configure(server, false);
               server.withJavalinConfigurationCustomizer(app::set);
            }));
            launches.add(launch);
            PrintingService service = launch.getQInstance().getQBits().values().stream().map(v -> v.getConfig()).filter(PrintingQBitConfig.class::isInstance).map(PrintingQBitConfig.class::cast).findFirst().orElseThrow().getService();
            services.add(service);
            for(int lane = 0; lane < 4; lane++)
            {
               JSONObject enrolled = service.enrollForUser(service.createEnrollment("default", "Synthetic lane").put("deviceName", "Synthetic lane").put("agentVersion", "qualification"), "operator");
               String     device   = enrolled.getString("deviceId");
               devices.add(device);
               service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "synthetic").put("name", "No physical printer").put("status", "READY").put("formats", new JSONArray().put("ZPL"))));
               service.enablePrinter(device, "synthetic", true);
               inputs.put(new JSONObject().put("origin", "http://127.0.0.1:" + app.get().port() + "/").put("username", "operator").put("password", password).put("tenant", "tenant-" + tenant).put("deviceId", device).put("deviceToken", enrolled.getString("deviceToken")));
            }
         }
         // Only synthetic credentials inside the already protected run directory.
         Files.writeString(dir.resolve("input.json"), new JSONObject().put("lanes", inputs).put("idle", idle).put("jobsPerLane", total / 8).toString());
         await(dir.resolve("ready"), Duration.ofMinutes(2));
         byte[] payload   = ("^XA^FO20,20^FDSYNTHETIC NOT PRINTED^FS" + " ".repeat(980) + "^XZ").getBytes(StandardCharsets.US_ASCII);
         String encoded   = Base64.getEncoder().encodeToString(payload);
         long   producing = System.nanoTime();
         if(idle)
         {
            for(int job = 0; job < total; job++)
            {
               int             lane    = job % 8;
               PrintingService service = services.get(lane / 4);
               JSONObject      request = new JSONObject().put("deviceId", devices.get(lane)).put("printerId", "synthetic").put("title", "Synthetic idle workload").put("format", "ZPL").put("contentBase64", encoded).put("idempotencyKey", "idle-" + job);
               service.submit(request);
               await(dir.resolve("completed-" + (job + 1)), Duration.ofSeconds(30));
               Thread.sleep(200);
            }
         }
         else
         {
            try(var executor = Executors.newFixedThreadPool(8))
            {
               List<Future<?>> futures = new ArrayList<>();
               for(int lane = 0; lane < 8; lane++)
               {
                  final int index = lane;
                  futures.add(executor.submit(() ->
                  {
                     try
                     {
                        PrintingService service = services.get(index / 4);
                        for(int job = 0; job < 1250; job++)
                        {
                           JSONObject request   = new JSONObject().put("deviceId", devices.get(index)).put("printerId", "synthetic").put("title", "Synthetic workload " + job).put("format", "ZPL").put("contentBase64", encoded).put("idempotencyKey", "lane-" + index + "-" + job);
                           JSONObject submitted = service.submit(request);
                           if(job % 25 == 0 && !submitted.getString("jobId").equals(service.submit(request).getString("jobId")))
                           {
                              throw new IllegalStateException("Replay duplicated job");
                           }
                        }
                     }
                     catch(Exception e)
                     {
                        throw new RuntimeException(e);
                     }
                  }));
               }
               for(var future : futures)
               {
                  future.get(15, TimeUnit.MINUTES);
               }
            }
         }
         double producerSeconds = (System.nanoTime() - producing) / 1e9;
         Files.writeString(dir.resolve("producer-done"), "done");
         await(dir.resolve("done"), Duration.ofMinutes(15));
         JSONObject client = new JSONObject(Files.readString(dir.resolve("result.json")));
         if(!client.getBoolean("success"))
         {
            throw new IllegalStateException("Client reported failure");
         }
         JSONObject report = new JSONObject().put("success", true).put("jobs", total).put("receipts", total * 3).put("idle", idle).put("producerSeconds", producerSeconds).put("elapsedSeconds", (System.nanoTime() - started) / 1e9).put("realPrinterCalls", 0);
         try(Connection connection = DriverManager.getConnection(jdbc, "sa", ""); Statement query = connection.createStatement())
         {
            require(query, "SELECT COUNT(*) FROM printing_job", total);
            require(query, "SELECT COUNT(*) FROM printing_job WHERE status='SPOOLED'", total);
            require(query, "SELECT COUNT(*) FROM printing_event", total * 3);
            require(query, "SELECT COUNT(*) FROM printing_document_orphan", 0);
            for(int tenant = 0; tenant < 2; tenant++)
            {
               require(query, "SELECT COUNT(*) FROM printing_job WHERE tenant_id='tenant-" + tenant + "'", total / 2);
               require(query, "SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id='tenant-" + tenant + "' AND device_id=''", (total / 2L) * payload.length);
            }
            JSONArray cross = new JSONArray();
            for(int tenant = 0; tenant < 2; tenant++)
            {
               String foreign = services.get(1 - tenant).jobsPage(1, null).getJSONArray("jobs").getJSONObject(0).getString("jobId");
               try
               {
                  services.get(tenant).getJob(foreign);
                  throw new IllegalStateException("Foreign job visible");
               }
               catch(PrintingException denied)
               {
                  if(denied.getStatus() != 404)
                  {
                     throw denied;
                  }
               }
               cross.put(true);
            }
            report.put("tenantIsolation", cross).put("retainedAccountingVerified", true).put("bytesPerDocument", payload.length);
         }
         Files.writeString(dir.resolve("server-result.json"), report.toString(2));
         exit = 0;
      }
      catch(Throwable failure)
      {
         Files.writeString(dir.resolve("server-error.json"), new JSONObject().put("error", failure.getClass().getName()).put("cause", failure.getCause() == null ? "" : failure.getCause().getClass().getName()).toString());
      }
      finally
      {
         for(int i = launches.size() - 1; i >= 0; i--)
         {
            launches.get(i).stop();
         }
         try(Connection connection = DriverManager.getConnection(jdbc, "sa", ""); Statement query = connection.createStatement())
         {
            query.execute("SHUTDOWN");
         }
         Files.writeString(dir.resolve("stopped.json"), new JSONObject().put("normalStop", true).put("exitCode", exit).toString());
      }
      System.exit(exit);
   }



   /***************************************************************************
    ** Wait for a private coordination marker with a monotonic deadline.
    ***************************************************************************/
   static void await(Path file, Duration limit) throws Exception
   {
      long deadline = System.nanoTime() + limit.toNanos();
      while(!Files.exists(file))
      {
         if(System.nanoTime() > deadline)
         {
            throw new TimeoutException("Fixture deadline");
         }
         Thread.sleep(100);
      }
   }



   /***************************************************************************
    ** Verify a synthetic database aggregate without emitting row values.
    ***************************************************************************/
   static void require(Statement query, String sql, long expected) throws Exception
   {
      try(ResultSet rows = query.executeQuery(sql))
      {
         if(!rows.next() || rows.getLong(1) != expected)
         {
            throw new IllegalStateException("Synthetic count/accounting mismatch");
         }
      }
   }
}
