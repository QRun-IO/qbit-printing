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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    ** Standalone enrollment has a named default instead of accepting arbitrary raw IDs.
    ***************************************************************************/
   @Test
   void standaloneFacilitySelectorRejectsForgedValues() throws Exception
   {
      Fixture fixture = fixture();
      QFieldMetaData field = ((QFrontendStepMetaData) fixture.instance().getProcess("printingEnrollDevice").getStep("details")).getFormField("facilityId");
      assertEquals("printing_facility", field.getPossibleValueSourceName());
      assertEquals("default", field.getDefaultValue());
      assertEquals(field.getPossibleValueSourceName(), fixture.instance().getTable(PrintingTables.DEVICE).getField("facility_id").getPossibleValueSourceName());
      var choices = new SearchPossibleValueSourceAction().execute(new SearchPossibleValueSourceInput().withPossibleValueSourceName(field.getPossibleValueSourceName()));
      assertEquals(1, choices.getResults().size());
      assertEquals("Default facility", choices.getResults().getFirst().getLabel());
      assertEquals("default", PrintingFacilities.validate("default"));
      RunBackendStepInput forged = new RunBackendStepInput().withProcessName("printingEnrollDevice")
         .addValue("facilityId", "invented-id").addValue("deviceName", "station");
      assertThrows(QException.class, () -> new PrintingProcesses().run(forged, new RunBackendStepOutput()));
      assertThrows(QException.class, () -> PrintingFacilities.validate(null));
      assertThrows(QException.class, () -> PrintingFacilities.validate(""));
      assertThrows(QException.class, () -> PrintingFacilities.register(fixture.instance(), new PrintingQBitConfig().withFacilityPossibleValueSourceName("missing_source")));
   }



   /***************************************************************************
    ** A forged selection cannot bypass the host source's real tenant record lock.
    ***************************************************************************/
   @Test
   void configuredFacilitySelectorHonorsRecordSecurityAtEnrollment() throws Exception
   {
      Fixture fixture = fixture("host_facilities");
      assertEquals("host_facilities", PrintingFacilities.field(fixture.instance()).getPossibleValueSourceName());
      assertEquals("host_facilities", fixture.instance().getTable(PrintingTables.DEVICE).getField("facility_id").getPossibleValueSourceName());
      assertEquals(fixture.device(), PrintingFacilities.validate(fixture.device()));
      RunBackendStepInput input = new RunBackendStepInput().withProcessName("printingEnrollDevice")
         .addValue("facilityId", fixture.foreignDevice()).addValue("deviceName", "station");
      RunBackendStepOutput prepared = new RunBackendStepOutput();
      new PrintingProcesses.PrepareEnrollment().run(input, prepared);
      input.addValue("printingEnrollmentCode", prepared.getValueString("printingEnrollmentCode"))
         .addValue("printingEnrollmentExpires", prepared.getValueString("printingEnrollmentExpires"));
      assertThrows(QException.class, () -> new PrintingProcesses().run(input, new RunBackendStepOutput()));
      input.addValue("facilityId", fixture.device());
      RunBackendStepOutput accepted = new RunBackendStepOutput();
      new PrintingProcesses().run(input, accepted);
      assertTrue(accepted.getValueString("result").startsWith("Enrollment code:"));
   }



   /***************************************************************************
    ** The production QQQ host extension point mounts routes with host auth.
    ***************************************************************************/
   @Test
   void productionApplicationHostRegistersPrintingRoutesAndPreservesAdminGate() throws Exception
   {
      Fixture fixture = fixture();
      var application = new com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication()
      {
         @Override
         public QInstance defineQInstance()
         {
            return fixture.instance();
         }
      };
      var javalin = new java.util.concurrent.atomic.AtomicReference<io.javalin.Javalin>();
      var server = new com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer(application)
         .withPort(0).withServeFrontendNext(false).withServeFrontendMaterialDashboard(false)
         .withJavalinConfigCustomizer(config -> config.jetty.host = "127.0.0.1")
         .withJavalinConfigurationCustomizer(javalin::set)
         .withAdditionalRouteProviders(List.of(new PrintingRoutes(fixture.service(), context -> "allowed".equals(context.header("X-Test-Admin")))));
      try(java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient())
      {
         server.start();
         java.net.URI url = java.net.URI.create("http://127.0.0.1:" + java.util.Objects.requireNonNull(javalin.get(), "Test HTTP server was not started").port() + "/printing/v1/admin/devices");
         assertEquals(403, client.send(java.net.http.HttpRequest.newBuilder(url).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
         var response = client.send(java.net.http.HttpRequest.newBuilder(url).header("X-Test-Admin", "allowed").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
         assertEquals(200, response.statusCode());
         assertEquals(2, new JSONObject(response.body()).getJSONArray("devices").length());
         assertFalse(response.body().contains("token_hash"));
      }
      finally
      {
         server.stop();
      }
   }



   /***************************************************************************
    ** Real QQQ pause/resume phases review selections before any job is queued.
    ***************************************************************************/
   @Test
   void selectedTestPausesForProfileAndReviewThenSubmitsOnce() throws Exception
   {
      Fixture fixture = fixture();
      String process = "printingTestSelectedPdf";
      var profile = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withValue("recordIds", fixture.printerId()));
      assertFalse(profile.getException().isPresent(), () -> profile.getException().toString());
      assertEquals("testProfile", profile.getProcessState().getNextStepName().orElseThrow());
      assertEquals(0, fixture.service().jobs().length());
      var review = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(profile.getProcessUUID())
         .withStartAfterStep("testProfile").withValue("testProfile", "LETTER"));
      assertFalse(review.getException().isPresent(), () -> review.getException().toString());
      assertTrue(review.getProcessState().getNextStepName().isPresent());
      assertEquals(0, fixture.service().jobs().length());
      var submitted = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(review.getProcessUUID())
         .withStartAfterStep(review.getProcessState().getNextStepName().orElseThrow()).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      assertFalse(submitted.getException().isPresent(), () -> submitted.getException().toString());
      assertEquals(1, fixture.service().jobs().length());
   }



   /***************************************************************************
    ** Enrollment replay cannot issue extra codes or reactivate consumed ones.
    ***************************************************************************/
   @Test
   void nativeEnrollmentReplayRetainsCodeAndNeverReactivatesConsumedReceipt() throws Exception
   {
      Fixture fixture = fixture();
      RunBackendStepInput input = new RunBackendStepInput().withProcessName("printingEnrollDevice")
         .addValue("facilityId", "default").addValue("deviceName", "station");
      RunBackendStepOutput prepared = new RunBackendStepOutput();
      new PrintingProcesses.PrepareEnrollment().run(input, prepared);
      input.addValue("printingEnrollmentCode", prepared.getValueString("printingEnrollmentCode"))
         .addValue("printingEnrollmentExpires", prepared.getValueString("printingEnrollmentExpires"));
      RunBackendStepOutput first = new RunBackendStepOutput();
      RunBackendStepOutput second = new RunBackendStepOutput();
      new PrintingProcesses().run(input, first);
      new PrintingProcesses().run(input, second);
      assertEquals(first.getValueString("result"), second.getValueString("result"));
      JSONObject enrollment = new JSONObject().put("enrollmentCode", prepared.getValueString("printingEnrollmentCode")).put("deviceName", "station").put("agentVersion", "test");
      fixture.service().enroll(enrollment);
      assertThrows(QException.class, () -> new PrintingProcesses().run(input, new RunBackendStepOutput()));
      assertThrows(PrintingException.class, () -> fixture.service().enroll(enrollment));
      input.addValue("printingEnrollmentExpires", java.time.Instant.now().minusSeconds(1).toString());
      assertThrows(QException.class, () -> new PrintingProcesses().run(input, new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** A lost load response must not duplicate a physical test on process replay.
    ***************************************************************************/
   @Test
   void testActionsDeduplicateSingleAndSelectedLoadReplay() throws Exception
   {
      Fixture fixture = fixture();
      RunBackendStepInput single = new RunBackendStepInput().withProcessName("printingTestPdf")
         .addValue("deviceId", fixture.device()).addValue("printerId", fixture.printerId()).addValue("printingTestRequestId", "single-replay");
      for(int attempt = 0; attempt < 2; attempt++)
      {
         new PrintingProcesses().run(single, new RunBackendStepOutput());
      }
      assertEquals(1, fixture.service().jobs().length());
      RunBackendStepInput selected = new RunBackendStepInput().withProcessName("printingTestSelectedPdf")
         .withRecords(List.of(new QRecord().withValue("id", fixture.printerId()), new QRecord().withValue("id", fixture.otherPrinterId())))
         .addValue("printingTestRequestId", "selected-replay");
      for(int attempt = 0; attempt < 2; attempt++)
      {
         new PrintingProcesses.SelectedLoad().runOnePage(selected, new RunBackendStepOutput());
      }
      assertEquals(3, fixture.service().jobs().length());
   }



   /***************************************************************************
    ** Native actions and device requests use identical bundled label documents.
    ***************************************************************************/
   @Test
   void builtInTestEntryPointsShareProfilesAndRejectChangedIdempotentRequests() throws Exception
   {
      Fixture fixture = fixture();
      fixture.service().inventory(fixture.device(), new JSONArray().put(new JSONObject().put("printerId", "laser")
         .put("name", "Packing test printer").put("formats", new JSONArray(List.of("PDF", "ZPL")))));
      QRecord printer = PrintingProcesses.resolvePrinter(fixture.printerId(), fixture.device());
      String nativeJob = PrintingProcesses.submitTest(printer, false, "LABEL_4X6_300");
      JSONObject request = new JSONObject().put("printerId", "laser").put("format", "ZPL").put("testProfile", "LABEL_4X6_300").put("requestId", "label-profile-test");
      String deviceJob = fixture.service().testPrint(fixture.device(), request).getString("jobId");
      assertEquals(fixture.service().getJob(nativeJob).getString("contentSha256"), fixture.service().getJob(deviceJob).getString("contentSha256"));
      assertEquals(deviceJob, fixture.service().testPrint(fixture.device(), request).getString("jobId"));
      assertEquals(409, assertThrows(PrintingException.class, () -> fixture.service().testPrint(fixture.device(), request.put("testProfile", "LABEL_4X6_203"))).getStatus());
      assertEquals(nativeJob, fixture.service().claim(fixture.device()).getJSONObject("job").getString("jobId"));
      assertArrayEquals(PrintingTestDocuments.zpl(300), fixture.service().content(fixture.device(), nativeJob));
      assertEquals(400, assertThrows(PrintingException.class, () -> fixture.service().testPrint(fixture.device(), request.put("testProfile", "LETTER"))).getStatus());
      assertThrows(PrintingException.class, () -> fixture.service().testPrint(fixture.device(), request.put("testProfile", JSONObject.NULL)));
      assertThrows(PrintingException.class, () -> fixture.service().testPrint(fixture.device(), request.put("format", "RAW")));
      assertThrows(QException.class, () -> PrintingProcesses.submitTest(printer, false, "A4"));
      assertEquals(1, fixture.service().queuedJobs(fixture.device()).length());
   }



   /***************************************************************************
    ** Stock/resolution choices are present before both native review flows.
    ***************************************************************************/
   @Test
   void nativeFormsOfferStockAndResolutionBeforeSelectedPrinterReview() throws Exception
   {
      Fixture fixture = fixture();
      QFrontendStepMetaData single = (QFrontendStepMetaData) fixture.instance().getProcess("printingTestZpl").getStep("details");
      assertEquals("printing_testProfileZpl", single.getFormField("testProfile").getPossibleValueSourceName());
      var selected = fixture.instance().getProcess("printingTestSelectedPdf");
      assertEquals("prepareTest", selected.getStepList().getFirst().getName());
      assertEquals("testProfile", selected.getStepList().get(1).getName());
      QFrontendStepMetaData form = (QFrontendStepMetaData) selected.getStep("testProfile");
      assertEquals("printing_testProfilePdf", form.getFormField("testProfile").getPossibleValueSourceName());
      // 4 x 6 choices are unchanged; each short stock adds friendly choices without raw IDs or typed sizes.
      assertEquals(9, fixture.instance().getPossibleValueSource("printing_testProfileZpl").getEnumValues().size());
      var pdfChoices = fixture.instance().getPossibleValueSource("printing_testProfilePdf").getEnumValues();
      assertEquals(7, pdfChoices.size());
      assertEquals(List.of("LETTER", "A4", "LABEL_4X6_203", "LABEL_4X6_300", "LABEL_4X6_600", "LABEL_4X2_300", "LABEL_4X1_300"),
         pdfChoices.stream().map(choice -> String.valueOf(choice.getId())).toList());
      assertEquals("4 x 1 in label", pdfChoices.getLast().getLabel());
      assertEquals(TestPrintProfile.LABEL_4X6_203.name(), TestPrintProfile.resolve("ZPL", null).name());
   }



   /***************************************************************************
    ** Job paper is one complete positive size and never mixes with a profile.
    ***************************************************************************/
   @Test
   void jobPaperRequiresACompleteBoundedPairWithoutMediaProfile() throws Exception
   {
      Fixture fixture = fixture();
      fixture.service().inventory(fixture.device(), new JSONArray().put(new JSONObject().put("printerId", "laser")
         .put("name", "Packing test printer").put("formats", new JSONArray(List.of("PDF", "ZPL")))));
      String content = java.util.Base64.getEncoder().encodeToString(PrintingTestDocuments.pdf(TestPrintProfile.LABEL_4X1_300));
      JSONObject selection = new JSONObject().put("id", "default").put("revision", "a".repeat(64));
      List<JSONObject> rejected = List.of(
         new JSONObject().put("paperWidthMm", 101.6),
         new JSONObject().put("paperHeightMm", 25.4),
         new JSONObject().put("paperWidthMm", 0).put("paperHeightMm", 25.4),
         new JSONObject().put("paperWidthMm", 101.6).put("paperHeightMm", -25.4),
         new JSONObject().put("paperWidthMm", "101.6").put("paperHeightMm", 25.4),
         new JSONObject().put("paperWidthMm", 101.6).put("paperHeightMm", 2000.1),
         new JSONObject().put("paperWidthMm", 101.6).put("paperHeightMm", 25.4).put("mediaProfile", selection));
      for(int index = 0; index < rejected.size(); index++)
      {
         JSONObject request = new JSONObject().put("deviceId", fixture.device()).put("printerId", "laser").put("title", "paper").put("format", "PDF")
            .put("contentBase64", content).put("idempotencyKey", "job-paper-" + index).put("options", rejected.get(index));
         assertEquals(400, assertThrows(PrintingException.class, () -> fixture.service().submit(request)).getStatus(), rejected.get(index).toString());
      }
      assertEquals(0, fixture.service().queuedJobs(fixture.device()).length());
      JSONObject accepted = new JSONObject().put("deviceId", fixture.device()).put("printerId", "laser").put("title", "paper").put("format", "PDF")
         .put("contentBase64", content).put("idempotencyKey", "job-paper-valid").put("options", new JSONObject().put("paperWidthMm", 101.6).put("paperHeightMm", 25.4));
      JSONObject options = fixture.service().getJob(fixture.service().submit(accepted).getString("jobId")).getJSONObject("options");
      assertEquals(25.4, options.getDouble("paperHeightMm"), 0.0001);
   }



   /***************************************************************************
    ** Short PDF stock sends exact actual-size job paper and no media profile;
    ** legacy 4 x 6 and every ZPL label keep the queue/payload-defined path.
    ***************************************************************************/
   @Test
   void shortLabelPdfJobsCarryExactJobPaperOnly() throws Exception
   {
      Fixture fixture = fixture();
      fixture.service().inventory(fixture.device(), new JSONArray().put(new JSONObject().put("printerId", "laser")
         .put("name", "Packing test printer").put("formats", new JSONArray(List.of("PDF", "ZPL")))));
      QRecord printer = PrintingProcesses.resolvePrinter(fixture.printerId(), fixture.device());
      String[][] stocks = {{"LABEL_4X1_300", "25.4"}, {"LABEL_4X2_300", "50.8"}};
      for(String[] stock : stocks)
      {
         JSONObject options = fixture.service().getJob(PrintingProcesses.submitTest(printer, true, stock[0])).getJSONObject("options");
         assertEquals(101.6, options.getDouble("paperWidthMm"), 0.0001);
         assertEquals(Double.parseDouble(stock[1]), options.getDouble("paperHeightMm"), 0.0001);
         assertFalse(options.has("mediaProfile"));
         assertEquals(1, options.getInt("copies"));
         assertEquals("ACTUAL_SIZE", options.getString("scale"));
      }
      for(boolean pdf : new boolean[] {true, false})
      {
         for(String profile : pdf ? new String[] {"LABEL_4X6_300"} : new String[] {"LABEL_4X6_300", "LABEL_4X2_203", "LABEL_4X1_600"})
         {
            JSONObject options = fixture.service().getJob(PrintingProcesses.submitTest(printer, pdf, profile)).getJSONObject("options");
            assertFalse(options.has("paperWidthMm"));
            assertFalse(options.has("paperHeightMm"));
            assertFalse(options.has("mediaProfile"));
         }
      }
   }



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
      var replay = new RunProcessAction().execute(new RunProcessInput().withProcessName(PrintingJobProcesses.CANCEL)
         .withValue("recordIds", first + "," + second).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      assertFalse(replay.getException().isPresent(), () -> replay.getException().toString());
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
      RunBackendStepInput input = new RunBackendStepInput().withProcessName("printingEnrollDevice").addValue("facilityId", "default").addValue("deviceName", "station");
      QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("tenant-b")));
      assertThrows(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class, () -> new PrintingProcesses().run(input, new RunBackendStepOutput()));
      QContext.getQSession().setSecurityKeyValues(Map.of());
      assertThrows(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class, () -> new PrintingProcesses().run(input, new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** One database, two tenants and multiple devices exercise real query scoping.
    ***************************************************************************/
   static Fixture fixture() throws Exception
   {
      return fixture(null);
   }



   /***************************************************************************
    ** An optional host facility source reuses real tenant-scoped device rows as fixtures.
    ***************************************************************************/
   private static Fixture fixture(@Nullable String facilitySource) throws Exception
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
      if(facilitySource != null)
      {
         instance.addPossibleValueSource(com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource.newForTable(PrintingTables.DEVICE).withName(facilitySource));
      }
      new PrintingQBitProducer().withConfig(new PrintingQBitConfig().withBackendName(backendName).withService(service)
         .withFacilityPossibleValueSourceName(facilitySource)).produce(instance, "printing");
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
      return new Fixture(instance, service, device, otherDevice, printerId, otherPrinterId, foreignId, foreign);
   }



   /***************************************************************************
    ** Single-use enrollment helper.
    ***************************************************************************/
   private static String enroll(PrintingService service, String name) throws Exception
   {
      return service.enroll(service.createEnrollment("warehouse", name).put("deviceName", name).put("agentVersion", "test")).getString("deviceId");
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
   record Fixture(QInstance instance, PrintingService service, String device, String otherDevice, String printerId, String otherPrinterId, String foreignPrinterId, String foreignDevice)
   {
   }
}
