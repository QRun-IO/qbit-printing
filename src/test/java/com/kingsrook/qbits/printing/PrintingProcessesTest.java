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


import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.values.SearchPossibleValueSourceAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueSearchFilterUseCase;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Real RDBMS selectors and native table/record process boundaries.
 *******************************************************************************/
class PrintingProcessesTest
{
   /***************************************************************************
    ** Native record selection requires duplicate consent and preserves the source.
    ***************************************************************************/
   @Test
   void selectedJobRetryReviewsRiskRequiresConsentAndDeduplicatesReplay() throws Exception
   {
      Fixture fixture = fixture();
      String original = PrintingProcesses.submitTest(PrintingProcesses.resolvePrinter(fixture.printerId(), fixture.device()), true);
      fixture.service().claim(fixture.device());
      for(String status : List.of("RECEIVED", "SUBMITTING", "SPOOLED"))
      {
         fixture.service().event(fixture.device(), original, new JSONObject().put("eventId", UUID.randomUUID().toString()).put("status", status));
      }
      var process = fixture.instance().getProcess(PrintingJobProcesses.RETRY);
      assertEquals(PrintingTables.JOB, process.getTableName());
      assertEquals(1, process.getMinInputRecords());
      assertTrue(process.getStep("confirmDuplicateRisk") instanceof QFrontendStepMetaData);
      RunBackendStepInput selection = new RunBackendStepInput().withProcessName(PrintingJobProcesses.RETRY)
         .withRecords(List.of(new QRecord().withValue("id", original))).addValue("printingRetryRequestId", "process-retry");
      RunBackendStepOutput preview = new RunBackendStepOutput();
      new PrintingJobProcesses.Transform().runOnePage(selection, preview);
      assertEquals("QQQ print test", preview.getRecords().getFirst().getValueString("title"));
      assertTrue(fixture.service().claim(fixture.device()).isNull("job"));
      assertThrows(QException.class, () -> new PrintingJobProcesses.Load().runOnePage(selection, new RunBackendStepOutput()));
      for(int replay = 0; replay < 2; replay++)
      {
         var result = new RunProcessAction().execute(new RunProcessInput().withProcessName(PrintingJobProcesses.RETRY)
            .withValue("recordIds", original).withValue("printingRetryRequestId", "process-retry").withValue("confirmDuplicateRisk", true)
            .withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
         assertFalse(result.getException().isPresent(), () -> result.getException().toString());
      }
      QueryInput query = new QueryInput();
      query.setTableName(PrintingTables.JOB);
      List<QRecord> jobs = new QueryAction().execute(query).getRecords();
      assertEquals(2, jobs.size());
      assertEquals("SPOOLED", jobs.stream().filter(job -> original.equals(job.getValueString("id"))).findFirst().orElseThrow().getValueString("status"));
   }



   /***************************************************************************
    ** Cancel supports table selections but cannot cancel work already claimed.
    ***************************************************************************/
   @Test
   void selectedJobCancellationRechecksStateAndTenant() throws Exception
   {
      Fixture fixture = fixture();
      String first = PrintingProcesses.submitTest(PrintingProcesses.resolvePrinter(fixture.printerId(), fixture.device()), true);
      String second = PrintingProcesses.submitTest(PrintingProcesses.resolvePrinter(fixture.otherPrinterId(), fixture.otherDevice()), true);
      var result = new RunProcessAction().execute(new RunProcessInput().withProcessName(PrintingJobProcesses.CANCEL)
         .withValue("recordIds", first + "," + second).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      assertFalse(result.getException().isPresent(), () -> result.getException().toString());
      assertTrue(fixture.service().claim(fixture.device()).isNull("job"));
      assertTrue(fixture.service().claim(fixture.otherDevice()).isNull("job"));
      String claimed = PrintingProcesses.submitTest(PrintingProcesses.resolvePrinter(fixture.printerId(), fixture.device()), true);
      fixture.service().claim(fixture.device());
      RunBackendStepInput selection = new RunBackendStepInput().withProcessName(PrintingJobProcesses.CANCEL)
         .withRecords(List.of(new QRecord().withValue("id", claimed).withValue("status", "QUEUED")));
      assertThrows(QException.class, () -> new PrintingJobProcesses.Load().runOnePage(selection, new RunBackendStepOutput()));
      QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("tenant-b")));
      assertThrows(QException.class, () -> new PrintingJobProcesses.Transform().runOnePage(selection, new RunBackendStepOutput()));
      QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("tenant-a")));
      QContext.getQSession().setPermissions(java.util.Set.of());
      assertThrows(QException.class, () -> new PrintingJobProcesses.Load().runOnePage(selection, new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** Do not carry identity into another test.
    ***************************************************************************/
   @AfterEach
   void clearContext()
   {
      QContext.clear();
   }



   /***************************************************************************
    ** Device filtering and tenant security both apply to named printer choices.
    ***************************************************************************/
   @Test
   void selectorsAreNamedDependentAndTenantScoped() throws Exception
   {
      Fixture fixture = fixture();
      QFieldMetaData field = ((QFrontendStepMetaData) fixture.instance().getProcess("printingTestPdf").getStep("details")).getFormField("printerId");
      assertEquals(PrintingTables.PRINTER, field.getPossibleValueSourceName());
      assertEquals(List.of("deviceId"), field.getPossibleValueSourceDependsOn());
      var filter = field.getPossibleValueSourceFilter().clone();
      filter.interpretValues(Map.of("input", Map.of("deviceId", fixture.device())), PossibleValueSearchFilterUseCase.FORM);
      var choices = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput()
         .withPossibleValueSourceName(PrintingTables.PRINTER).withDefaultQueryFilter(filter)).getResults();
      assertEquals(1, choices.size());
      assertEquals("Packing laser", choices.getFirst().getLabel());
      assertEquals(fixture.printerId(), choices.getFirst().getId());
      var devices = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput().withPossibleValueSourceName(PrintingTables.DEVICE)).getResults();
      assertEquals(2, devices.size());
      assertTrue(devices.stream().noneMatch(value -> value.getLabel().contains("Other tenant")));
      assertThrows(QException.class, () -> PrintingProcesses.resolvePrinter(fixture.printerId(), fixture.otherDevice()));
      assertThrows(QException.class, () -> PrintingProcesses.resolvePrinter(fixture.foreignPrinterId(), null));
      assertTrue(fixture.instance().getTables().keySet().stream().allMatch(name -> name.startsWith("printing_")));
      assertTrue(fixture.instance().getProcesses().keySet().stream().allMatch(name -> name.startsWith("printing")));
      assertTrue(fixture.instance().getPossibleValueSources().keySet().containsAll(List.of(PrintingTables.DEVICE, PrintingTables.PRINTER, "printing_enabled", "printing_approved", "printing_available", "printing_format", "printing_jobStatus", "printing_printerStatus")));
      assertEquals(PrintingTables.DEVICE, fixture.instance().getProcess("printingEnrollDevice").getTableName());
      var columns = (PrintingQBitProducer.DefaultColumns) fixture.instance().getTable(PrintingTables.PRINTER).getSupplementalMetaData("nextUi");
      assertTrue(columns.getDefaultColumns().containsAll(List.of("status", "enabled", "available")));
      assertTrue(columns.getDefaultColumns().contains("location"));
      assertFalse(columns.getDefaultColumns().contains("description"));
      assertEquals(4096, fixture.instance().getTable(PrintingTables.PRINTER).getField("description").getMaxLength());
      assertEquals(1024, fixture.instance().getTable(PrintingTables.PRINTER).getField("queue_name").getMaxLength());
      fixture.service().inventory(fixture.device(), new JSONArray().put(new JSONObject().put("printerId", "laser").put("name", "Packing laser").put("displayName", "Shipping desk").put("description", "Shared dispatch printer").put("location", "Dock 2").put("formats", new JSONArray(List.of("PDF")))));
      var labeledChoices = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput()
         .withPossibleValueSourceName(PrintingTables.PRINTER).withDefaultQueryFilter(filter)).getResults();
      assertTrue(labeledChoices.stream().anyMatch(value -> value.getLabel().equals("Shipping desk")));
      assertTrue(fixture.instance().getTable(PrintingTables.PRINTER).getDisabledCapabilities().containsAll(com.kingsrook.qqq.backend.core.model.metadata.tables.Capability.allWriteCapabilities()));
      assertTrue(fixture.instance().getTable(PrintingTables.PRINTER).getField("tenant_id").getIsHidden());
   }



   /***************************************************************************
    ** Real record IDs enter through QQQ extraction; preview has no side effects.
    ***************************************************************************/
   @Test
   void selectedPrinterActionsPreviewThenQueueAndDisable() throws Exception
   {
      Fixture fixture = fixture();
      var process = fixture.instance().getProcess("printingTestSelectedPdf");
      assertEquals(PrintingTables.PRINTER, process.getTableName());
      assertEquals(1, process.getMinInputRecords());
      RunBackendStepOutput preview = new RunBackendStepOutput();
      new PrintingProcesses.SelectedTransform().runOnePage(new RunBackendStepInput().withProcessName(process.getName())
         .withRecords(List.of(new QRecord().withValue("id", fixture.printerId()))), preview);
      assertEquals(1, preview.getRecords().size());
      assertTrue(fixture.service().claim(fixture.device()).isNull("job"));
      var result = new RunProcessAction().execute(new RunProcessInput().withProcessName(process.getName())
         .withValue("recordIds", fixture.printerId() + "," + fixture.otherPrinterId()).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      assertFalse(result.getException().isPresent(), () -> result.getException().toString());
      JSONObject job = fixture.service().claim(fixture.device()).getJSONObject("job");
      assertEquals("PDF", job.getString("format"));
      assertEquals("laser", job.getString("printerId"));
      assertEquals("PDF", fixture.service().claim(fixture.otherDevice()).getJSONObject("job").getString("format"));
      var disabled = new RunProcessAction().execute(new RunProcessInput().withProcessName("printingDisableSelectedPrinters")
         .withValue("recordIds", fixture.printerId()).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      assertFalse(disabled.getException().isPresent(), () -> disabled.getException().toString());
      assertFalse(fixture.service().printers(fixture.device()).getJSONObject(0).getBoolean("enabled"));
      assertThrows(QException.class, () -> new PrintingProcesses.SelectedTransform().runOnePage(new RunBackendStepInput().withProcessName(process.getName())
         .withRecords(List.of(new QRecord().withValue("id", fixture.printerId()))), new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** An altered record payload cannot select another tenant's queue.
    ***************************************************************************/
   @Test
   void selectedLoadRechecksPermissionsAndRecordOwnership() throws Exception
   {
      Fixture fixture = fixture();
      RunBackendStepInput forged = new RunBackendStepInput().withProcessName("printingTestSelectedPdf")
         .withRecords(List.of(new QRecord().withValue("id", fixture.foreignPrinterId()).withValue("device_id", fixture.device()).withValue("printer_id", "laser")));
      assertThrows(QException.class, () -> new PrintingProcesses.SelectedLoad().runOnePage(forged, new RunBackendStepOutput()));
      QContext.getQSession().setPermissions(java.util.Set.of());
      assertThrows(QException.class, () -> new PrintingProcesses.SelectedLoad().runOnePage(new RunBackendStepInput().withProcessName("printingTestSelectedPdf")
         .withRecords(List.of(new QRecord().withValue("id", fixture.printerId()))), new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** Enrollment has no input record, so it must bind session scope explicitly.
    ***************************************************************************/
   @Test
   void enrollmentRejectsMissingAndWrongTenantDespiteAdminPermission() throws Exception
   {
      fixture();
      RunBackendStepInput input = new RunBackendStepInput().withProcessName("printingEnrollDevice").addValue("facilityId", "warehouse").addValue("deviceName", "station");
      QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("tenant-b")));
      assertThrows(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class, () -> new PrintingProcesses().run(input, new RunBackendStepOutput()));
      QContext.getQSession().setSecurityKeyValues(Map.of());
      assertThrows(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class, () -> new PrintingProcesses().run(input, new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** One database, two tenants and multiple devices exercise real query scoping.
    ***************************************************************************/
   private static Fixture fixture() throws Exception
   {
      String database = "printing_process_" + UUID.randomUUID();
      String jdbc = "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE";
      DriverDataSource source = new DriverDataSource(jdbc, "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      String device = enroll(service, "Packing station");
      String otherDevice = enroll(service, "Other station");
      configure(service, device, "Packing laser");
      configure(service, otherDevice, "Other laser");
      PrintingService otherTenant = new PrintingService(source, "tenant-b");
      String foreign = enroll(otherTenant, "Other tenant station");
      configure(otherTenant, foreign, "Other tenant laser");
      String backendName = "printing-db-" + UUID.randomUUID();
      QInstance instance = new QInstance();
      instance.addBackend(new RDBMSBackendMetaData().withName(backendName).withVendor("h2").withHostName("mem").withDatabaseName(database).withJdbcUrl(jdbc).withUsername("sa").withPassword(""));
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.MOCK));
      new PrintingQBitProducer().withConfig(new PrintingQBitConfig().withBackendName(backendName).withService(service)).produce(instance, "printing");
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference("operator")).withPermissions("printing_admin").withSecurityKeyValue("printing_tenant", "tenant-a"));
      QueryInput query = new QueryInput();
      query.setTableName(PrintingTables.PRINTER);
      List<QRecord> rows = new QueryAction().execute(query).getRecords();
      String printerId = rows.stream().filter(row -> device.equals(row.getValueString("device_id"))).findFirst().orElseThrow().getValueString("id");
      String otherPrinterId = rows.stream().filter(row -> otherDevice.equals(row.getValueString("device_id"))).findFirst().orElseThrow().getValueString("id");
      String foreignId;
      try(var connection = source.getConnection(); var statement = connection.prepareStatement("SELECT id FROM printing_printer WHERE device_id=?"))
      {
         statement.setString(1, foreign);
         try(var result = statement.executeQuery())
         {
            result.next();
            foreignId = result.getString(1);
         }
      }
      return (new Fixture(instance, service, device, otherDevice, printerId, otherPrinterId, foreignId));
   }



   /***************************************************************************
    ** Single-use enrollment helper.
    ***************************************************************************/
   private static String enroll(PrintingService service, String name) throws Exception
   {
      return (service.enroll(service.createEnrollment("warehouse", name).put("deviceName", name).put("agentVersion", "test")).getString("deviceId"));
   }



   /***************************************************************************
    ** Synthetic printer only; tests never contact Windows or real hardware.
    ***************************************************************************/
   private static void configure(PrintingService service, String device, String name) throws Exception
   {
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "laser").put("name", name).put("formats", new JSONArray(List.of("PDF")))));
      service.enablePrinter(device, "laser", true);
   }



   /***************************************************************************
    ** Per-test handles, without exposed device tokens.
    ***************************************************************************/
   private record Fixture(QInstance instance, PrintingService service, String device, String otherDevice, String printerId, String otherPrinterId, String foreignPrinterId)
   {
   }
}
