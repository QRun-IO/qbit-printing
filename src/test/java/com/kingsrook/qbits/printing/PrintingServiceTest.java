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


import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Persistence, deduplication, tenant isolation and native process integration.
 *******************************************************************************/
class PrintingServiceTest
{
   /***************************************************************************
    ** Protocol maximum event fields persist; malformed/oversized values fail400.
    ***************************************************************************/
   @Test
   void eventBoundsMatchProtocolAndInventoryRejectsUnsupportedEnums() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      String device = enroll(service);
      configure(service, device);
      String job = service.submit(request(device)).getString("jobId");
      service.claim(device);
      for(JSONObject bad : List.of(new JSONObject().put("message", "m".repeat(2049)), new JSONObject().put("spoolerJobId", "s".repeat(129)), new JSONObject().put("message", JSONObject.NULL)))
      {
         assertEquals(400, assertThrows(PrintingException.class, () -> service.event(device, job, bad.put("eventId", "invalid").put("status", "RECEIVED"))).getStatus());
      }
      service.event(device, job, new JSONObject().put("eventId", "e".repeat(128)).put("status", "SPOOLED").put("message", "m".repeat(2048)).put("spoolerJobId", "s".repeat(128)));
      assertEquals(2048, service.getJob(job).getString("message").length());
      assertEquals(128, service.getJob(job).getString("spoolerJobId").length());
      for(JSONArray formats : List.of(new JSONArray(), new JSONArray(List.of("RAW")), new JSONArray(List.of("PDF", "PDF"))))
      {
         assertEquals(400, assertThrows(PrintingException.class, () -> service.inventory(device, new JSONArray().put(inventory().getJSONObject(0).put("formats", formats)))).getStatus());
      }
      assertTrue(service.printers(device).getJSONObject(0).getBoolean("available"));
   }



   /***************************************************************************
    ** Changing a driver's capabilities must block previously queued formats.
    ***************************************************************************/
   @Test
   void claimRechecksCurrentPrinterFormat() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      String device = enroll(service);
      configure(service, device);
      String job = service.submit(request(device)).getString("jobId");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "zebra").put("name", "New driver")
         .put("formats", new JSONArray(List.of("PDF")))));
      assertTrue(service.claim(device).isNull("job"));
      assertEquals("QUEUED", service.getJob(job).getString("status"));
      service.inventory(device, inventory());
      assertEquals(job, service.claim(device).getJSONObject("job").getString("jobId"));
   }



   /***************************************************************************
    ** Invalid JSON option types must not silently change the requested output.
    ***************************************************************************/
   @Test
   void malformedPrintOptionsAreRejectedBeforeQueueing() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      String device = enroll(service);
      configure(service, device);
      for(Object options : List.of("invalid", new JSONArray(), JSONObject.NULL,
         new JSONObject().put("copies", 1.5), new JSONObject().put("copies", "2"), new JSONObject().put("copies", JSONObject.NULL)))
      {
         assertEquals(400, assertThrows(PrintingException.class, () -> service.submit(request(device).put("options", options))).getStatus());
      }
      assertEquals(0, service.jobs().length());
   }



   /***************************************************************************
    ** A failed status cannot erase evidence that a job may already have printed.
    ***************************************************************************/
   @Test
   void failureAfterSubmissionStillRequiresDuplicateConsent() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      String device = enroll(service);
      configure(service, device);
      String id = service.submit(request(device)).getString("jobId");
      service.claim(device);
      service.event(device, id, new JSONObject().put("eventId", "spooled-before-failure").put("status", "SPOOLED").put("spoolerJobId", "42"));
      service.event(device, id, new JSONObject().put("eventId", "later-failure").put("status", "FAILED"));
      assertEquals(409, assertThrows(PrintingException.class, () -> service.retry(device, id, new JSONObject().put("requestId", "retry"))).getStatus());
      assertEquals(1, service.jobs().length());
      service.retry(device, id, new JSONObject().put("requestId", "retry").put("confirmDuplicateRisk", true));
      assertEquals(2, service.jobs().length());
   }



   /***************************************************************************
    ** An independently reconstructed runtime reads durable jobs and credentials.
    ***************************************************************************/
   @Test
   void restartRetainsQueueAndSingleUseEnrollment() throws Exception
   {
      DriverDataSource source = source();
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      JSONObject code = service.createEnrollment("warehouse", "station");
      JSONObject enrollment = code.put("deviceName", "station").put("agentVersion", "1");
      JSONObject device = service.enroll(enrollment);
      assertThrows(PrintingException.class, () -> service.enroll(enrollment));
      String deviceId = device.getString("deviceId");
      configure(service, deviceId);
      JSONObject request = request(deviceId);
      JSONObject job = service.submit(request);
      PrintingService restarted = new PrintingService(source, "tenant-a");
      assertEquals(deviceId, restarted.authenticate(device.getString("deviceToken")));
      assertEquals(job.getString("jobId"), restarted.claim(deviceId).getJSONObject("job").getString("jobId"));
      assertEquals(job.getString("jobId"), restarted.submit(request).getString("jobId"));
      request.put("contentBase64", Base64.getEncoder().encodeToString("changed".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      assertEquals(409, assertThrows(PrintingException.class, () -> restarted.submit(request)).getStatus());
   }



   /***************************************************************************
    ** Inventory cannot approve printers and devices cannot read other jobs.
    ***************************************************************************/
   @Test
   void tenantAndDeviceIsolationAndApprovalPersist() throws Exception
   {
      DriverDataSource source = source();
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      String device = enroll(service);
      configure(service, device);
      String otherDevice = enroll(service);
      JSONObject job = service.submit(request(device));
      service.claim(device);
      assertEquals(404, assertThrows(PrintingException.class, () -> service.content(otherDevice, job.getString("jobId"))).getStatus());
      PrintingService otherTenant = new PrintingService(source, "tenant-b");
      assertEquals(0, otherTenant.devices().length());
      assertEquals(404, assertThrows(PrintingException.class, () -> otherTenant.getJob(job.getString("jobId"))).getStatus());
      service.enablePrinter(device, "zebra", false);
      service.inventory(device, inventory());
      assertFalse(service.printers(device).getJSONObject(0).getBoolean("enabled"));
      assertThrows(PrintingException.class, () -> service.submit(request(device).put("idempotencyKey", "new")));
   }



   /***************************************************************************
    ** Replayed status events are harmless and submitted work is never reclaimed.
    ***************************************************************************/
   @Test
   void eventsAreIdempotentAndCannotRegress() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      String device = enroll(service);
      configure(service, device);
      String job = service.submit(request(device)).getString("jobId");
      service.claim(device);
      JSONObject event = new JSONObject().put("eventId", "event-1").put("status", "RECEIVED");
      service.event(device, job, event);
      service.event(device, job, event);
      assertTrue(service.claim(device).isNull("job"));
      service.event(device, job, new JSONObject().put("eventId", "event-2").put("status", "SUBMITTING"));
      service.event(device, job, new JSONObject().put("eventId", "event-3").put("status", "OUTCOME_UNKNOWN"));
      assertEquals("OUTCOME_UNKNOWN", service.getJob(job).getString("status"));
      assertThrows(PrintingException.class, () -> service.event(device, job, new JSONObject().put("eventId", "event-4").put("status", "RECEIVED")));
      assertTrue(service.claim(device).isNull("job"));
   }



   /***************************************************************************
    ** Withdrawing local exposure blocks new work without deleting approval.
    ***************************************************************************/
   @Test
   void withdrawnPrinterCannotReceiveWork() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      String device = enroll(service);
      configure(service, device);
      JSONObject queued = service.submit(request(device));
      service.inventory(device, new JSONArray());
      JSONObject printer = service.printers(device).getJSONObject(0);
      assertTrue(printer.getBoolean("enabled"));
      assertFalse(printer.getBoolean("available"));
      assertTrue(service.claim(device).isNull("job"));
      assertThrows(PrintingException.class, () -> service.submit(request(device).put("idempotencyKey", "after-withdrawal")));
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "zebra").put("name", "Zebra").put("formats", new JSONArray(List.of("ZPL")))));
      assertEquals(queued.getString("jobId"), service.claim(device).getJSONObject("job").getString("jobId"));
   }



   /***************************************************************************
    ** QBit metadata exposes safe fields and real setup/test-print processes.
    ***************************************************************************/
   @Test
   void nativeQbitProcessesCreateEnrollmentAndPdf() throws Exception
   {
      String jdbc = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE";
      PrintingService service = new PrintingService(new DriverDataSource(jdbc, "sa", ""), "tenant-a");
      service.initialize();
      String backendName = "printing-db-" + UUID.randomUUID();
      QInstance instance = new QInstance();
      instance.addBackend(new com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData().withName(backendName).withVendor("h2").withJdbcUrl(jdbc).withUsername("sa").withPassword(""));
      instance.withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData().withName("mock").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.MOCK));
      new PrintingQBitProducer().withConfig(new PrintingQBitConfig().withBackendName(backendName).withService(service)).produce(instance, "printing");
      assertNotNull(instance.getApp("printing"));
      assertNotNull(instance.getProcess("printingEnrollDevice"));
      assertFalse(instance.getTable(PrintingTables.DEVICE).getFields().containsKey("token_hash"));
      assertFalse(instance.getTable(PrintingTables.JOB).getFields().containsKey("content_base64"));
      QContext.init(instance, new QSystemUserSession(instance).withSecurityKeyValue("printing_tenant", "tenant-a"));
      try
      {
         var metadata = new com.kingsrook.qqq.backend.core.actions.metadata.MetaDataAction().execute(new com.kingsrook.qqq.backend.core.model.actions.metadata.MetaDataInput());
         assertTrue(metadata.getTables().containsKey(PrintingTables.DEVICE));
         new PrintingReadPermission().checkPermissionsThrowing(new com.kingsrook.qqq.backend.core.model.actions.metadata.TableMetaDataInput(), instance.getTable(PrintingTables.DEVICE));
         assertThrows(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class,
            () -> new PrintingReadPermission().checkPermissionsThrowing(new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput(), instance.getTable(PrintingTables.DEVICE)));
         RunBackendStepOutput enrollment = new RunBackendStepOutput();
         RunBackendStepInput enrollmentInput = new RunBackendStepInput().withProcessName("printingEnrollDevice").addValue("facilityId", "default").addValue("deviceName", "station");
         RunBackendStepOutput enrollmentPrepared = new RunBackendStepOutput();
         new PrintingProcesses.PrepareEnrollment().run(enrollmentInput, enrollmentPrepared);
         enrollmentInput.addValue("printingEnrollmentCode", enrollmentPrepared.getValueString("printingEnrollmentCode"))
            .addValue("printingEnrollmentExpires", enrollmentPrepared.getValueString("printingEnrollmentExpires"));
         new PrintingProcesses().run(enrollmentInput, enrollment);
         assertTrue(enrollment.getValueString("result").startsWith("Enrollment code:"));
         String device = enroll(service);
         service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "laser").put("name", "Laser").put("formats", new JSONArray(List.of("PDF")))));
         service.enablePrinter(device, "laser", true);
         var query = new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput();
         query.setTableName(PrintingTables.PRINTER);
         String printerRow = new com.kingsrook.qqq.backend.core.actions.tables.QueryAction().execute(query).getRecords().getFirst().getValueString("id");
         RunBackendStepOutput result = new RunBackendStepOutput();
         RunBackendStepInput printInput = new RunBackendStepInput().withProcessName("printingTestPdf").addValue("deviceId", device).addValue("printerId", printerRow);
         RunBackendStepOutput prepared = new RunBackendStepOutput();
         new PrintingProcesses.PrepareTest().run(printInput, prepared);
         printInput.addValue("printingTestRequestId", prepared.getValueString("printingTestRequestId"));
         new PrintingProcesses().run(printInput, result);
         assertTrue(result.getValueString("result").startsWith("Queued print job"));
         JSONObject job = service.claim(device).getJSONObject("job");
         assertEquals("PDF", job.getString("format"));
         assertTrue(service.content(device, job.getString("jobId")).length > 500);
      }
      finally
      {
         QContext.clear();
      }
   }



   /***************************************************************************
    ** Independent database per test, retained across individual connections.
    ***************************************************************************/
   private static DriverDataSource source()
   {
      return (new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
   }



   /***************************************************************************
    ** Operator actions retain approval, device isolation, and uncertain receipts.
    ***************************************************************************/
   @Test
   void operatorActionsAreScopedExplicitAndIdempotent() throws Exception
   {
      PrintingService service = new PrintingService(source(), "tenant-a");
      service.initialize();
      String device = enroll(service);
      String other = enroll(service);
      configure(service, device);
      JSONObject action = new JSONObject().put("printerId", "zebra").put("format", "ZPL").put("requestId", "test-1");
      String job = service.testPrint(device, action).getString("jobId");
      assertEquals(1, service.queuedJobs(device).length());
      assertEquals(0, service.queuedJobs(other).length());
      assertEquals(job, service.testPrint(device, action).getString("jobId"));
      assertEquals(404, assertThrows(PrintingException.class, () -> service.cancel(other, job)).getStatus());
      service.cancel(device, job);
      assertTrue(service.claim(device).isNull("job"));
      assertEquals("FAILED", service.getJob(job).getString("status"));
      JSONObject retry = new JSONObject().put("requestId", "retry-1");
      String newJob = service.retry(device, job, retry).getString("jobId");
      assertFalse(job.equals(newJob));
      assertEquals(newJob, service.retry(device, job, retry).getString("jobId"));
      assertEquals(newJob, service.claim(device).getJSONObject("job").getString("jobId"));
      assertEquals(409, assertThrows(PrintingException.class, () -> service.cancel(device, newJob)).getStatus());
      service.event(device, newJob, new JSONObject().put("eventId", "received").put("status", "RECEIVED"));
      service.event(device, newJob, new JSONObject().put("eventId", "submitting").put("status", "SUBMITTING"));
      service.event(device, newJob, new JSONObject().put("eventId", "unknown").put("status", "OUTCOME_UNKNOWN"));
      JSONObject uncertainRetry = new JSONObject().put("requestId", "retry-2");
      assertEquals(409, assertThrows(PrintingException.class, () -> service.retry(device, newJob, uncertainRetry)).getStatus());
      assertEquals(404, assertThrows(PrintingException.class, () -> service.retry(other, newJob, uncertainRetry)).getStatus());
      service.retry(device, newJob, uncertainRetry.put("confirmDuplicateRisk", true));
      assertEquals("OUTCOME_UNKNOWN", service.getJob(newJob).getString("status"));
      service.enablePrinter(device, "zebra", false);
      assertThrows(PrintingException.class, () -> service.testPrint(device, action.put("requestId", "test-disabled")));
   }



   /***************************************************************************
    ** Enroll a new device using a single-use code.
    ***************************************************************************/
   private static String enroll(PrintingService service) throws Exception
   {
      JSONObject code = service.createEnrollment("warehouse", "station");
      return (service.enroll(code.put("deviceName", "station").put("agentVersion", "1")).getString("deviceId"));
   }



   /***************************************************************************
    ** Approve the synthetic printer explicitly.
    ***************************************************************************/
   private static void configure(PrintingService service, String device) throws Exception
   {
      service.inventory(device, inventory());
      service.enablePrinter(device, "zebra", true);
   }



   /***************************************************************************
    ** Synthetic queue inventory.
    ***************************************************************************/
   private static JSONArray inventory()
   {
      return (new JSONArray().put(new JSONObject().put("printerId", "zebra").put("name", "Zebra").put("formats", new JSONArray(List.of("ZPL")))));
   }



   /***************************************************************************
    ** Small valid print request for the synthetic queue.
    ***************************************************************************/
   private static JSONObject request(String device)
   {
      return (new JSONObject().put("deviceId", device).put("printerId", "zebra").put("title", "Test label").put("format", "ZPL").put("contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("idempotencyKey", "business-key"));
   }
}

