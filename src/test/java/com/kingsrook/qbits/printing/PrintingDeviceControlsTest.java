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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.h2.jdbcx.JdbcConnectionPool;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Real durable controls, replay boundaries, tenant isolation and competing dispatch. */
class PrintingDeviceControlsTest
{
   /** Regression: pauseDisconnectAndRevokeHaveDistinctDurableSemantics. */
   @Test
   void pauseDisconnectAndRevokeHaveDistinctDurableSemantics() throws Exception
   {
      Fixture f = new Fixture();
      String job = f.submit("first");
      JSONObject pause = action("PAUSE_DISPATCH");
      JSONObject receipt = f.service.deviceAction(f.device, pause, "admin");
      assertTrue(f.service.claim(f.device).isNull("job"));
      assertEquals(receipt.toString(), f.service.deviceAction(f.device, pause, "admin").toString());
      f.act("RESUME_DISPATCH");
      f.act("DISCONNECT");
      assertTrue(f.service.claim(f.device).isNull("job"));
      JSONObject state = f.service.deviceControlPoll(f.device, poll()).getJSONObject("desiredState");
      assertEquals("DISCONNECTED", state.getString("connectionState"));
      assertFalse(state.getBoolean("dispatchPaused"));
      f.act("RECONNECT");
      assertEquals(job, f.service.claim(f.device).getJSONObject("job").getString("jobId"));
      f.act("DISCONNECT");
      f.service.event(f.device, job, new JSONObject().put("eventId", "received").put("status", "RECEIVED"));
      assertEquals("RECEIVED", f.service.getJob(job).getString("status"));
      f.act("REVOKE");
      assertEquals(401, assertThrows(PrintingException.class, () -> f.service.authenticate(f.token)).getStatus());
      assertThrows(PrintingException.class, () -> f.service.claim(f.device));
      assertThrows(PrintingException.class, () -> f.service.deviceControlPoll(f.device, poll()));
      assertEquals(409, assertThrows(PrintingException.class, () -> f.act("RESUME_DISPATCH")).getStatus());
      assertEquals("RECEIVED", f.service.getJob(job).getString("status"));
      assertFalse(f.service.deviceCommandsPage(f.device, 50, null).getJSONArray("commands").isEmpty());
   }


   /** Regression: clearQueueReplayNeverCancelsNewJobsOrInflightWork. */
   @Test
   void clearQueueReplayNeverCancelsNewJobsOrInflightWork() throws Exception
   {
      Fixture f = new Fixture();
      String active = f.submit("active");
      f.service.claim(f.device);
      f.service.event(f.device, active, new JSONObject().put("eventId", "submitting").put("status", "SUBMITTING"));
      String queued = f.submit("queued");
      JSONObject request = action("CLEAR_QUEUE");
      JSONObject first = f.service.deviceAction(f.device, request, "admin");
      assertEquals(1, first.getInt("cancelledJobCount"));
      assertEquals("SUBMITTING", f.service.getJob(active).getString("status"));
      assertEquals("FAILED", f.service.getJob(queued).getString("status"));
      try(Connection c = f.source.getConnection())
      {
         assertEquals(2, PrintingService.query(c, "SELECT id FROM printing_job WHERE tenant_id='tenant' AND content_available=1").size());
         assertEquals(12, PrintingService.query(c, "SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id='tenant' AND device_id='' ").getFirst().getLong("retained_bytes"));
         assertEquals(0, PrintingService.query(c, "SELECT id FROM printing_document_orphan WHERE tenant_id='tenant'").size());
      }
      String later = f.submit("later");
      assertEquals(first.toString(), f.service.deviceAction(f.device, request, "admin").toString());
      assertEquals("QUEUED", f.service.getJob(later).getString("status"));
      assertEquals(409, assertThrows(PrintingException.class, () -> f.service.deviceAction(f.device, new JSONObject(request.toString()).put("action", "PAUSE_DISPATCH"), "admin")).getStatus());
   }


   /** Regression: remoteCommandsRequireCapabilitiesAndReceiptsAreImmutable. */
   @Test
   void remoteCommandsRequireCapabilitiesAndReceiptsAreImmutable() throws Exception
   {
      Fixture f = new Fixture();
      assertEquals(409, assertThrows(PrintingException.class, () -> f.act("REFRESH_INVENTORY")).getStatus());
      f.service.deviceControlPoll(f.device, poll());
      JSONObject command = f.act("REFRESH_INVENTORY");
      assertEquals("PENDING", command.getString("status"));
      f.act("DISCONNECT");
      assertTrue(f.service.deviceControlPoll(f.device, poll()).getJSONArray("commands").isEmpty());
      f.act("RECONNECT");
      assertEquals(command.getString("commandId"), f.service.deviceControlPoll(f.device, poll()).getJSONArray("commands").getJSONObject(0).getString("commandId"));
      JSONObject receipt = receipt("INVENTORY_REFRESHED");
      f.service.deviceControlReceipt(f.device, command.getString("commandId"), receipt);
      f.service.deviceControlReceipt(f.device, command.getString("commandId"), receipt);
      assertEquals(409, assertThrows(PrintingException.class, () -> f.service.deviceControlReceipt(f.device, command.getString("commandId"), new JSONObject(receipt.toString()).put("outcome", "FAILED"))).getStatus());
      assertEquals("SUCCEEDED", f.service.getDeviceCommand(f.device, command.getString("commandId")).getString("status"));
      assertTrue(f.service.deviceControlPoll(f.device, poll()).getJSONArray("commands").isEmpty());
   }


   /** Regression: cancelSpoolRequiresOwnedReceiptAndNeverReclassifiesJob. */
   @Test
   void cancelSpoolRequiresOwnedReceiptAndNeverReclassifiesJob() throws Exception
   {
      Fixture f = new Fixture();
      f.service.deviceControlPoll(f.device, poll());
      String job = f.submit("spool");
      assertEquals(409, assertThrows(PrintingException.class, () -> f.service.deviceAction(f.device, action("CANCEL_SPOOL").put("jobId", job), "admin")).getStatus());
      f.service.claim(f.device);
      f.service.event(f.device, job, new JSONObject().put("eventId", "spooled").put("status", "SPOOLED").put("spoolerJobId", "123"));
      JSONObject command = f.service.deviceAction(f.device, action("CANCEL_SPOOL").put("jobId", job), "admin");
      f.service.deviceControlReceipt(f.device, command.getString("commandId"), receipt("OWNERSHIP_MISMATCH"));
      assertEquals("FAILED", f.service.getDeviceCommand(f.device, command.getString("commandId")).getString("status"));
      assertEquals("SPOOLED", f.service.getJob(job).getString("status"));
      assertEquals(400, assertThrows(PrintingException.class, () -> f.service.deviceAction(f.device, action("CANCEL_SPOOL").put("jobId", job).put("spoolerJobId", "999"), "admin")).getStatus());
   }


   /** Regression: tenantDeviceCursorAndReceiptBoundariesAreEnforced. */
   @Test
   void tenantDeviceCursorAndReceiptBoundariesAreEnforced() throws Exception
   {
      Fixture f = new Fixture();
      String second = f.enroll().getString("deviceId");
      JSONObject first = f.act("PAUSE_DISPATCH");
      String id = first.getString("commandId");
      assertEquals(404, assertThrows(PrintingException.class, () -> f.service.getDeviceCommand(second, id)).getStatus());
      assertEquals(404, assertThrows(PrintingException.class, () -> f.service.deviceCommandsPage(second, 1, id)).getStatus());
      assertEquals(404, assertThrows(PrintingException.class, () -> f.service.deviceControlReceipt(second, id, receipt("FAILED"))).getStatus());
      PrintingService other = new PrintingService(f.source, "other-tenant");
      assertEquals(404, assertThrows(PrintingException.class, () -> other.deviceAction(f.device, action("REVOKE"), "other-admin")).getStatus());
      f.act("RESUME_DISPATCH");
      JSONObject page = f.service.deviceCommandsPage(f.device, 1, null);
      assertEquals(1, page.getJSONArray("commands").length());
      assertEquals(id, f.service.deviceCommandsPage(f.device, 1, page.getString("nextCursor")).getJSONArray("commands").getJSONObject(0).getString("commandId"));
   }


   /** Regression: expiredCommandsAcceptLateActualOutcomeAndStaleActionsNeverExecute. */
   @Test
   void expiredCommandsAcceptLateActualOutcomeAndStaleActionsNeverExecute() throws Exception
   {
      Fixture f = new Fixture();
      f.service.deviceControlPoll(f.device, poll());
      JSONObject command = f.act("REFRESH_INVENTORY");
      try(Connection c = f.source.getConnection())
      {
         PrintingService.update(c, "UPDATE printing_device_command SET expires_at=? WHERE id=?", Instant.now().minusSeconds(60).toString(), command.getString("commandId"));
      }
      assertTrue(f.service.deviceControlPoll(f.device, poll()).getJSONArray("commands").isEmpty());
      assertEquals("EXPIRED", f.service.getDeviceCommand(f.device, command.getString("commandId")).getString("status"));
      f.service.deviceControlReceipt(f.device, command.getString("commandId"), receipt("INVENTORY_REFRESHED"));
      assertEquals("SUCCEEDED", f.service.getDeviceCommand(f.device, command.getString("commandId")).getString("status"));
      JSONObject old = action("CLEAR_QUEUE").put("issuedAt", Instant.now().minusSeconds(901).toString());
      assertEquals(410, assertThrows(PrintingException.class, () -> f.service.deviceAction(f.device, old, "admin")).getStatus());
      JSONObject saved = action("PAUSE_DISPATCH");
      f.service.deviceAction(f.device, saved, "admin");
      saved.put("issuedAt", Instant.now().minusSeconds(8 * 86400).toString());
      try(Connection c = f.source.getConnection())
      {
         PrintingService.update(c, "UPDATE printing_device_command SET created_at=?,expires_at=?,request_json=? WHERE request_id=?", saved.getString("issuedAt"), Instant.parse(saved.getString("issuedAt")).plusSeconds(900).toString(), saved.toString(), saved.getString("requestId"));
      }
      assertEquals("SUCCEEDED", f.service.deviceAction(f.device, saved, "admin").getString("status"));
      f.act("RESUME_DISPATCH");
      assertEquals(410, assertThrows(PrintingException.class, () -> f.service.deviceAction(f.device, saved, "admin")).getStatus());
      assertFalse(f.service.deviceControlPoll(f.device, poll()).getJSONObject("desiredState").getBoolean("dispatchPaused"));
      assertEquals(400, assertThrows(PrintingException.class, () -> f.service.deviceAction(f.device, action("PAUSE_DISPATCH").put("issuedAt", Instant.now().plusSeconds(120).toString()), "admin")).getStatus());
   }


   /** Regression: concurrentClearAndClaimCannotBothOwnTheSameQueuedJob. */
   @Test
   void concurrentClearAndClaimCannotBothOwnTheSameQueuedJob() throws Exception
   {
      Fixture f = new Fixture();
      PrintingService second = new PrintingService(f.source, "tenant");
      String job = f.submit("race");
      CountDownLatch start = new CountDownLatch(1);
      try(var executor = Executors.newFixedThreadPool(2))
      {
         var clear = executor.submit(() ->
         {
            start.await();
            return f.act("CLEAR_QUEUE");
         });
         var claim = executor.submit(() ->
         {
            start.await();
            return second.claim(f.device);
         });
         start.countDown();
         int canceled = clear.get().getInt("cancelledJobCount");
         boolean claimed = !claim.get().isNull("job");
         assertEquals(canceled == 0, claimed);
         assertEquals(claimed ? "CLAIMED" : "FAILED", f.service.getJob(job).getString("status"));
      }
   }


   /** Regression: pendingAndHistoryBudgetsRetainEmergencyRevocation. */
   @Test
   void pendingAndHistoryBudgetsRetainEmergencyRevocation() throws Exception
   {
      Fixture f = new Fixture();
      f.service.deviceControlPoll(f.device, poll());
      for(int i = 0; i < 100; i++)
      {
         f.act("REFRESH_INVENTORY");
      }
      assertEquals(429, assertThrows(PrintingException.class, () -> f.act("REFRESH_INVENTORY")).getStatus());
      assertEquals(20, f.service.deviceControlPoll(f.device, poll()).getJSONArray("commands").length());
      for(int i = 100; i < 1000; i++)
      {
         f.act("PAUSE_DISPATCH");
      }
      assertEquals(429, assertThrows(PrintingException.class, () -> f.act("RESUME_DISPATCH")).getStatus());
      assertEquals("CREDENTIAL_REVOKED", f.act("REVOKE").getString("resultCode"));
      assertEquals(401, assertThrows(PrintingException.class, () -> f.service.authenticate(f.token)).getStatus());
   }


   /** All control paths work with a single real pooled connection and preserve escaped receipt data. */
   @Test
   void singleConnectionPreservesStateAndBoundedUnicodeReceipts() throws Exception
   {
      JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:controls-pool-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      pool.setMaxConnections(1);
      pool.setLoginTimeout(2);
      try
      {
         PrintingService first = new PrintingService(pool, "tenant");
         first.initialize();
         String device = first.enroll(first.createEnrollment("facility", "device").put("deviceName", "device").put("agentVersion", "test")).getString("deviceId");
         first.deviceControlPoll(device, poll());
         JSONObject command = first.deviceAction(device, action("REFRESH_INVENTORY"), "actor");
         JSONObject result = receipt("INVENTORY_REFRESHED").put("message", "\u0001".repeat(1024));
         first.deviceControlReceipt(device, command.getString("commandId"), result);
         PrintingService reopened = new PrintingService(pool, "tenant");
         assertTrue(reopened.getDeviceCommand(device, command.getString("commandId")).getJSONObject("receipt").similar(result));
         JSONObject paused = action("PAUSE_DISPATCH");
         first.deviceAction(device, paused, "actor");
         assertTrue(reopened.deviceControlPoll(device, poll()).getJSONObject("desiredState").getBoolean("dispatchPaused"));
         assertEquals(2, reopened.deviceCommandsPage(device, 10, null).getJSONArray("commands").length());
         assertEquals("SUCCEEDED", reopened.deviceAction(device, paused, "actor").getString("status"));
      }
      finally
      {
         pool.dispose();
      }
   }


   /** Explicit fresh action identity, retained unchanged by callers when retrying. */
   private static JSONObject action(String action)
   {
      return new JSONObject().put("requestId", UUID.randomUUID().toString()).put("issuedAt", Instant.now().toString()).put("action", action);
   }


   /** Advertise only the two implemented remote operations. */
   private static JSONObject poll()
   {
      return new JSONObject().put("controlVersion", 1).put("agentVersion", "test").put("lastAppliedRevision", 0)
         .put("capabilities", new JSONArray(List.of("REFRESH_INVENTORY", "CANCEL_SPOOL")));
   }


   /** A synthetic command observation carries no arbitrary executable data. */
   private static JSONObject receipt(String outcome)
   {
      return new JSONObject().put("receiptId", UUID.randomUUID().toString()).put("outcome", outcome).put("completedAt", Instant.now().toString());
   }


   /** Real isolated database and approved synthetic printer for each test. */
   private static final class Fixture
   {
      private final DriverDataSource source = new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      private final PrintingService service = new PrintingService(source, "tenant");
      private final String device;
      private final String token;

      /** Initialize once; new service instances must observe the same durable state. */
      private Fixture() throws Exception
      {
         service.initialize();
         JSONObject enrolled = enroll();
         device = enrolled.getString("deviceId");
         token = enrolled.getString("deviceToken");
         service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "zebra").put("name", "Zebra").put("formats", new JSONArray(List.of("ZPL")))));
         service.enablePrinter(device, "zebra", true);
      }

      /** Pair without sharing another device's credential. */
      private JSONObject enroll() throws Exception
      {
         return service.enroll(service.createEnrollment("warehouse", "station").put("deviceName", "station").put("agentVersion", "test"));
      }

      /** Use normal admission and retained document accounting. */
      private String submit(String key) throws Exception
      {
         return service.submit(new JSONObject().put("deviceId", device).put("printerId", "zebra").put("title", "Synthetic")
            .put("format", "ZPL").put("contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(StandardCharsets.UTF_8))).put("idempotencyKey", key)).getString("jobId");
      }

      /** Apply through the same facade used by authenticated native and HTTP adapters. */
      private JSONObject act(String action) throws Exception
      {
         return service.deviceAction(device, action(action), "admin");
      }
   }
}
