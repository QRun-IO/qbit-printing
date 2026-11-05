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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.ChildRecordListRenderer;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.ChildRecordListData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Native control actions preserve identity, live permissions and row security.
 ** Fixtures use in-memory SQL and never contact Windows or physical printers.
 *******************************************************************************/
class PrintingDeviceProcessesTest
{
   /***************************************************************************
    ** Prevent one test's authority from leaking into another test.
    ***************************************************************************/
   @AfterEach
   void clearContext()
   {
      QContext.clear();
   }



   /***************************************************************************
    ** Every device control and late-job action is available on native selections.
    ***************************************************************************/
   @Test
   void metadataIncludesControlsHistoryAndDeviceQueue() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      for(var action : PrintingDeviceProcesses.Action.values())
      {
         var process = fixture.instance().getProcess(action.process);
         assertNotNull(process);
         assertEquals(action.job ? PrintingTables.JOB : PrintingTables.DEVICE, process.getTableName());
         assertNotNull(process.getStep("prepareControl"));
      }
      assertNotNull(fixture.instance().getWidget("printing_device_queue"));
      assertNotNull(fixture.instance().getWidget("printing_printer_queue"));
      assertTrue(fixture.instance().getTables().keySet().stream().allMatch(name -> name.startsWith("printing_")));
      assertFalse(fixture.instance().getTable(PrintingTables.COMMAND).getFields().containsKey("request_json"));
      assertFalse(fixture.instance().getTable(PrintingTables.COMMAND).getFields().containsKey("receipt_json"));
      assertEquals(PrintingTables.DEVICE, fixture.instance().getTable(PrintingTables.COMMAND).getField("device_id").getPossibleValueSourceName());
   }



   /***************************************************************************
    ** Preparation/review cannot change a device, and revoked permissions block load.
    ***************************************************************************/
   @Test
   void previewDoesNotMutateAndPermissionRemovalBlocksExecution() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      RunBackendStepInput input = prepared(PrintingDeviceProcesses.Action.PAUSE, fixture.device());
      RunBackendStepOutput review = new RunBackendStepOutput();
      new PrintingDeviceProcesses.Transform().runOnePage(input, review);
      assertEquals(1, review.getRecords().size());
      assertEquals(0, fixture.service().deviceCommandsPage(fixture.device(), 50, null).getJSONArray("commands").length());
      QContext.getQSession().setPermissions(Set.of());
      assertThrows(QException.class, () -> new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** Untrusted extracted values cannot select a foreign device or spoof the actor.
    ***************************************************************************/
   @Test
   void loadRechecksRowsAndRecordsTheAuthenticatedActor() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      RunBackendStepInput forged = prepared(PrintingDeviceProcesses.Action.PAUSE, fixture.foreignDevice());
      assertThrows(QException.class, () -> new PrintingDeviceProcesses.Load().runOnePage(forged, new RunBackendStepOutput()));
      RunBackendStepInput input = prepared(PrintingDeviceProcesses.Action.PAUSE, fixture.device());
      input.addValue("actor", "forged administrator");
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      assertEquals(1, get(PrintingTables.DEVICE, fixture.device()).getValueInteger("dispatch_paused"));
      var command = fixture.service().deviceCommandsPage(fixture.device(), 50, null).getJSONArray("commands").getJSONObject(0);
      assertEquals("operator", get(PrintingTables.COMMAND, command.getString("commandId")).getValueString("actor"));
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      assertEquals(1, fixture.service().deviceCommandsPage(fixture.device(), 50, null).getJSONArray("commands").length());
   }



   /***************************************************************************
    ** Remove device revokes through the existing control, archives the device and
    ** its printers, keeps job history readable, and replays without a new command.
    ***************************************************************************/
   @Test
   void removeDeviceRevokesArchivesAndKeepsHistory() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      var process = fixture.instance().getProcess(PrintingDeviceProcesses.Action.REMOVE.process);
      assertEquals("Remove device", process.getLabel());
      assertTrue(process.getStep("confirmControl") instanceof com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData);
      assertTrue(PrintingDeviceProcesses.Action.REMOVE.preview.contains("history is kept"));
      String job = submit(fixture.service(), fixture.device());
      RunBackendStepInput input = prepared(PrintingDeviceProcesses.Action.REMOVE, fixture.device());
      assertThrows(QException.class, () -> new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput()));
      assertEquals(2, fixture.service().devices().length());
      input.addValue(PrintingDeviceProcesses.CONFIRM, true);
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      QRecord device = get(PrintingTables.DEVICE, fixture.device());
      assertFalse(device.getValueString("revoked_at").isBlank());
      assertFalse(device.getValueString("removed_at").isBlank());
      assertEquals(0, device.getValueInteger("enabled"));
      assertEquals(List.of(fixture.otherDevice()), fixture.service().devices().toList().stream().map(row -> ((Map<?, ?>) row).get("deviceId")).toList());
      QRecord printer = get(PrintingTables.PRINTER, fixture.printerId());
      assertFalse(printer.getValueString("removed_at").isBlank());
      assertEquals(0, printer.getValueInteger("enabled"));
      assertEquals(0, printer.getValueInteger("available"));
      assertEquals(404, assertThrows(PrintingException.class, () -> fixture.service().printers(fixture.device())).getStatus());
      assertEquals(404, assertThrows(PrintingException.class, () -> fixture.service().claim(fixture.device())).getStatus());
      assertEquals("QUEUED", fixture.service().getJob(job).getString("status"));
      assertEquals(job, get(PrintingTables.JOB, job).getValueString("id"));
      var commands = fixture.service().deviceCommandsPage(fixture.device(), 50, null).getJSONArray("commands");
      assertEquals(1, commands.length());
      assertEquals("REVOKE", commands.getJSONObject(0).getString("action"));
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      assertEquals(1, fixture.service().deviceCommandsPage(fixture.device(), 50, null).getJSONArray("commands").length());
      assertEquals(device.getValueString("removed_at"), get(PrintingTables.DEVICE, fixture.device()).getValueString("removed_at"));
      QueryInput visible = new QueryInput().withTableName(PrintingTables.DEVICE).withFilter(new QQueryFilter().withCriteria("removed_at", QCriteriaOperator.IS_BLANK));
      assertEquals(List.of(fixture.otherDevice()), new QueryAction().execute(visible).getRecords().stream().map(row -> row.getValueString("id")).toList());
      RunBackendStepInput revokedOnly = prepared(PrintingDeviceProcesses.Action.REVOKE, fixture.otherDevice());
      revokedOnly.addValue(PrintingDeviceProcesses.CONFIRM, true);
      new PrintingDeviceProcesses.Load().runOnePage(revokedOnly, new RunBackendStepOutput());
      assertEquals(1, fixture.service().devices().length());
      RunBackendStepInput removeRevoked = prepared(PrintingDeviceProcesses.Action.REMOVE, fixture.otherDevice());
      removeRevoked.addValue(PrintingDeviceProcesses.CONFIRM, true);
      new PrintingDeviceProcesses.Load().runOnePage(removeRevoked, new RunBackendStepOutput());
      assertEquals(0, fixture.service().devices().length());
      assertEquals(1, fixture.service().deviceCommandsPage(fixture.otherDevice(), 50, null).getJSONArray("commands").length());
   }



   /***************************************************************************
    ** Selected late jobs produce valid, stable wire request IDs and one command.
    ***************************************************************************/
   @Test
   void nativeWindowsCancellationReplaysTheSameOwnedJobCommand() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      fixture.service().deviceControlPoll(fixture.device(), new JSONObject().put("controlVersion", 1).put("agentVersion", "test")
         .put("capabilities", new org.json.JSONArray(List.of("CANCEL_SPOOL"))).put("lastAppliedRevision", 0));
      String job = submit(fixture.service(), fixture.device());
      fixture.service().claim(fixture.device());
      fixture.service().event(fixture.device(), job, new JSONObject().put("eventId", "test-spooled").put("status", "SPOOLED").put("spoolerJobId", "42"));
      RunBackendStepInput input = prepared(PrintingDeviceProcesses.Action.CANCEL_WINDOWS, job);
      input.addValue(PrintingDeviceProcesses.CONFIRM, true);
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      var commands = fixture.service().deviceCommandsPage(fixture.device(), 50, null).getJSONArray("commands");
      assertEquals(1, commands.length());
      assertEquals(job, commands.getJSONObject(0).getString("jobId"));
      assertEquals("CANCEL_SPOOL", commands.getJSONObject(0).getString("action"));
      assertEquals("PENDING", commands.getJSONObject(0).getString("status"));
      assertEquals("SPOOLED", fixture.service().getJob(job).getString("status"));
   }



   /***************************************************************************
    ** Replaying a native clear action never cancels work added after its receipt.
    ***************************************************************************/
   @Test
   void nativeQueueClearReplayPreservesLaterWorkAndRequiresConsent() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      String first = submit(fixture.service(), fixture.device());
      RunBackendStepInput input = prepared(PrintingDeviceProcesses.Action.CLEAR, fixture.device());
      assertThrows(QException.class, () -> new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput()));
      assertEquals("QUEUED", fixture.service().getJob(first).getString("status"));
      input.addValue(PrintingDeviceProcesses.CONFIRM, true);
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      String later = submit(fixture.service(), fixture.device());
      new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput());
      assertEquals("FAILED", fixture.service().getJob(first).getString("status"));
      assertEquals("QUEUED", fixture.service().getJob(later).getString("status"));
      assertEquals(1, fixture.service().deviceCommandsPage(fixture.device(), 50, null).getJSONArray("commands").length());
   }



   /***************************************************************************
    ** Short projections must use current scoped dependencies, without returning them.
    ***************************************************************************/
   @Test
   void deviceStatusProjectionReflectsPauseWithoutLeakingExtraColumns() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      new PrintingDeviceProcesses.Load().runOnePage(prepared(PrintingDeviceProcesses.Action.PAUSE, fixture.device()), new RunBackendStepOutput());
      QueryInput query = new QueryInput().withTableName(PrintingTables.DEVICE)
         .withFieldNamesToInclude(Set.of("id", "name", "device_status", "control_status"))
         .withFilter(new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, fixture.device()));
      QRecord row = new QueryAction().execute(query).getRecords().getFirst();
      assertEquals("PAUSED", row.getValueString("device_status"));
      assertEquals("UNKNOWN", row.getValueString("control_status"));
      assertFalse(row.getValues().containsKey("dispatch_paused"));
      assertFalse(row.getValues().containsKey("control_revision"));
      query.setFieldNamesToInclude(Set.of("name", "device_status"));
      assertThrows(QException.class, () -> new QueryAction().execute(query));
   }



   /***************************************************************************
    ** The record's queue widget is scoped to that device and excludes old outcomes.
    ***************************************************************************/
   @Test
   void deviceQueueWidgetShowsOnlyItsPendingWork() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      String queued = submit(fixture.service(), fixture.device());
      String canceled = submit(fixture.service(), fixture.device());
      fixture.service().cancel(fixture.device(), canceled);
      submit(fixture.service(), fixture.otherDevice());
      RenderWidgetInput input = new RenderWidgetInput().withWidgetMetaData(fixture.instance().getWidget("printing_device_queue"));
      input.setQueryParams(Map.of("id", fixture.device(), "joinName", java.util.Objects.requireNonNull(input.getWidgetMetaData().getDefaultValues().get("joinName")).toString()));
      var data = (ChildRecordListData) new ChildRecordListRenderer().render(input).getWidgetData();
      assertEquals(List.of(queued), data.getQueryOutput().getRecords().stream().map(row -> row.getValueString("id")).toList());
      input.setQueryParams(Map.of("id", fixture.foreignDevice(), "joinName", java.util.Objects.requireNonNull(input.getWidgetMetaData().getDefaultValues().get("joinName")).toString()));
      assertThrows(QException.class, () -> new ChildRecordListRenderer().render(input));
   }



   /***************************************************************************
    ** State and observation differ; malformed/future timestamps never imply online.
    ***************************************************************************/
   @Test
   void statusDistinguishesPresenceDesiredStateAndObservedRevision()
   {
      Instant now = Instant.parse("2026-10-31T12:00:00Z");
      QRecord row = new QRecord().withValue("enabled", 1).withValue("last_seen_at", now.minusSeconds(120).toString())
         .withValue("control_last_poll_at", now.minusSeconds(2).toString()).withValue("control_version", 1)
         .withValue("control_revision", 3L).withValue("control_applied_revision", 2L);
      assertEquals("ONLINE", PrintingDeviceStatus.presence(row, now));
      assertEquals("PENDING", PrintingDeviceStatus.application(row));
      row.setValue("control_applied_revision", 3L);
      assertEquals("APPLIED", PrintingDeviceStatus.application(row));
      row.setValue("dispatch_paused", 1);
      assertEquals("PAUSED", PrintingDeviceStatus.presence(row, now));
      row.setValue("connection_state", "DISCONNECTED");
      assertEquals("DISCONNECTED", PrintingDeviceStatus.presence(row, now));
      row.setValue("enabled", 0);
      assertEquals("REVOKED", PrintingDeviceStatus.presence(row, now));
      row.setValue("enabled", 1);
      row.setValue("connection_state", "CONNECTED");
      row.setValue("control_last_poll_at", "malformed");
      assertEquals("OFFLINE", PrintingDeviceStatus.presence(row, now));
      row.setValue("last_seen_at", now.plusSeconds(120).toString());
      assertEquals("UNKNOWN", PrintingDeviceStatus.presence(row, now));
   }



   /***************************************************************************
    ** Persist both identity fields; an old review cannot acquire a fresh timestamp.
    ***************************************************************************/
   @Test
   void preparationPreservesIdentityAndRejectsIncompleteOrExpiredRequests() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      RunBackendStepInput input = prepared(PrintingDeviceProcesses.Action.PAUSE, fixture.device());
      RunBackendStepOutput again = new RunBackendStepOutput();
      new PrintingDeviceProcesses.Prepare().run(input, again);
      assertEquals(input.getValueString(PrintingDeviceProcesses.REQUEST_ID), again.getValueString(PrintingDeviceProcesses.REQUEST_ID));
      assertEquals(input.getValueString(PrintingDeviceProcesses.ISSUED_AT), again.getValueString(PrintingDeviceProcesses.ISSUED_AT));
      input.addValue(PrintingDeviceProcesses.ISSUED_AT, Instant.now().minusSeconds(901).toString());
      assertThrows(QException.class, () -> new PrintingDeviceProcesses.Load().runOnePage(input, new RunBackendStepOutput()));
      input.addValue(PrintingDeviceProcesses.ISSUED_AT, null);
      assertThrows(QException.class, () -> new PrintingDeviceProcesses.Prepare().run(input, new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** Synthetic jobs never leave the in-memory server fixture.
    ***************************************************************************/
   private static String submit(PrintingService service, String device) throws Exception
   {
      return service.submit(new JSONObject().put("deviceId", device).put("printerId", "laser").put("title", "Control fixture")
         .put("format", "PDF").put("contentBase64", Base64.getEncoder().encodeToString("%PDF-1.4 fixture".getBytes(StandardCharsets.UTF_8)))
         .put("idempotencyKey", UUID.randomUUID().toString())).getString("jobId");
   }



   /***************************************************************************
    ** Real native request preparation keeps tests independent of UUID generation.
    ***************************************************************************/
   private static RunBackendStepInput prepared(PrintingDeviceProcesses.Action action, String id) throws QException
   {
      RunBackendStepInput input = new RunBackendStepInput().withProcessName(action.process).withRecords(List.of(new QRecord().withValue("id", id)));
      RunBackendStepOutput output = new RunBackendStepOutput();
      new PrintingDeviceProcesses.Prepare().run(input, output);
      input.addValue(PrintingDeviceProcesses.REQUEST_ID, output.getValueString(PrintingDeviceProcesses.REQUEST_ID));
      input.addValue(PrintingDeviceProcesses.ISSUED_AT, output.getValueString(PrintingDeviceProcesses.ISSUED_AT));
      return input;
   }



   /***************************************************************************
    ** Metadata reads are subject to normal native permission/record checks.
    ***************************************************************************/
   private static QRecord get(String table, String id) throws QException
   {
      GetInput input = new GetInput();
      input.setTableName(table);
      input.setPrimaryKey(id);
      return new GetAction().execute(input).getRecord();
   }
}
