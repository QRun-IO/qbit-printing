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


import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.json.JSONObject;


/*******************************************************************************
 ** Native administration controls use the caller's QQQ session and selected rows.
 ** Review is read-only; durable service requests execute only in the load step.
 *******************************************************************************/
public final class PrintingDeviceProcesses
{
   static final String REQUEST_ID = "printingControlRequestId";
   static final String ISSUED_AT = "printingControlIssuedAt";
   static final String CONFIRM = "confirmPrintingControl";



   /***************************************************************************
    ** Each action is associated with both record and table-selection menus.
    ***************************************************************************/
   public enum Action
   {
      PAUSE("printingPauseDevices", "Pause printing", "PAUSE_DISPATCH", false, false,
         "New work will stop being dispatched. Jobs already received by Windows may still print."),
      RESUME("printingResumeDevices", "Resume printing", "RESUME_DISPATCH", false, false,
         "Dispatch will resume for connected, authorized devices. Administrative disconnect remains unchanged."),
      DISCONNECT("printingDisconnectDevices", "Disconnect devices", "DISCONNECT", false, false,
         "Normal printing traffic will stop. The outbound control channel remains available for reconnect."),
      RECONNECT("printingReconnectDevices", "Reconnect devices", "RECONNECT", false, false,
         "Connected agents will be asked to resume normal traffic. An offline computer cannot be reached immediately."),
      REVOKE("printingRevokeDevices", "Revoke device access", "REVOKE", true, false,
         "Access will be revoked permanently. The old credential cannot be restored; local re-enrollment is required."),
      REFRESH("printingRefreshDevices", "Refresh printer inventory", "REFRESH_INVENTORY", false, false,
         "A bounded refresh request will wait for a compatible agent. Acceptance does not mean it has executed."),
      CLEAR("printingClearDeviceQueues", "Cancel queued jobs", "CLEAR_QUEUE", true, false,
         "Only jobs still queued in QQQ will be canceled. History remains and Windows jobs are not purged."),
      CANCEL_WINDOWS("printingCancelWindowsJobs", "Cancel Windows print job", "CANCEL_SPOOL", false, true,
         "The agent will request cancellation only with proof that the spool entry belongs to this job. Printed output cannot be recalled.");

      final String process;
      final String label;
      final String wire;
      final boolean confirmation;
      final boolean job;
      final String preview;



      /************************************************************************
       ** Keep labels separate from the closed wire action vocabulary.
       ************************************************************************/
      Action(String process, String label, String wire, boolean confirmation, boolean job, String preview)
      {
         this.process = process;
         this.label = label;
         this.wire = wire;
         this.confirmation = confirmation;
         this.job = job;
         this.preview = preview;
      }
   }



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingDeviceProcesses()
   {
   }



   /***************************************************************************
    ** Standard streamed review supports checked rows, records and query selection.
    ***************************************************************************/
   public static void register(QInstance instance)
   {
      for(Action action : Action.values())
      {
         String table = action.job ? PrintingTables.JOB : PrintingTables.DEVICE;
         Map<String, Serializable> values = new HashMap<>();
         values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
         values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
         values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
         values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
         values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, action.preview);
         QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, Transform.class, Load.class, values)
            .withName(action.process).withLabel(action.label).withTableName(table).withMinInputRecords(1)
            .withPermissionRules(PrintingProcesses.permissionRules());
         process.withStep(0, new QBackendStepMetaData().withName("prepareControl").withCode(new QCodeReference(Prepare.class)));
         if(action.confirmation)
         {
            process.withStep(1, new QFrontendStepMetaData().withName("confirmControl").withLabel(action.label)
               .withFormField(new QFieldMetaData(CONFIRM, QFieldType.BOOLEAN).withLabel("I understand and confirm this action").withDefaultValue(false))
               .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)));
         }
         instance.addProcess(process);
      }
   }



   /***************************************************************************
    ** Unknown names must never choose a more privileged default action.
    ***************************************************************************/
   static Action action(RunBackendStepInput input) throws QException
   {
      for(Action action : Action.values())
      {
         if(action.process.equals(input.getProcessName()))
         {
            return (action);
         }
      }
      throw new QException("Unknown printing control action");
   }



   /***************************************************************************
    ** Use a real authenticated user identifier, never a client-supplied actor.
    ***************************************************************************/
   static String actor(RunBackendStepInput input) throws QException
   {
      new PrintingAdminPermission().checkPermissionsThrowing(input, null);
      PrintingProcesses.currentService();
      if(QContext.getQSession() == null || QContext.getQSession().getUser() == null
         || QContext.getQSession().getUser().getIdReference() == null || QContext.getQSession().getUser().getIdReference().isBlank())
      {
         throw new QException("Sign in with an authorized QQQ user before controlling a print device");
      }
      return (QContext.getQSession().getUser().getIdReference());
   }



   /***************************************************************************
    ** Resolve current records through QQQ permissions and tenant row locks.
    ***************************************************************************/
   static QRecord resolve(RunBackendStepInput input, String id) throws QException
   {
      Action action = action(input);
      GetInput get = new GetInput();
      get.setTableName(action.job ? PrintingTables.JOB : PrintingTables.DEVICE);
      get.setPrimaryKey(id);
      get.setPermissionEnforcement(com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement.ENFORCE);
      QRecord record = new GetAction().execute(get).getRecord();
      if(record == null)
      {
         throw new QException("Choose an accessible print " + (action.job ? "job" : "device"));
      }
      if(action.job && "QUEUED".equals(record.getValueString("status")))
      {
         throw new QException("Use Cancel queued jobs for work that has not been dispatched to Windows");
      }
      return (record);
   }



   /***************************************************************************
    ** Keep request identity and issue time stable throughout review and replay.
    ***************************************************************************/
   public static class Prepare implements BackendStep
   {
      /************************************************************************
       ** No service mutation or command delivery occurs during preparation.
       ************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         actor(input);
         action(input);
         String request = input.getValueString(REQUEST_ID);
         String issued = input.getValueString(ISSUED_AT);
         if((request == null || request.isBlank()) != (issued == null || issued.isBlank()))
         {
            throw new QException("Restart this action to obtain a complete control request identity");
         }
         output.addValue(REQUEST_ID, request == null || request.isBlank() ? UUID.randomUUID().toString() : request);
         output.addValue(ISSUED_AT, issued == null || issued.isBlank() ? Instant.now().toString() : issued);
      }
   }



   /***************************************************************************
    ** The native preview rechecks scope without changing device or queue state.
    ***************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine ready = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularFutureMessage("record will receive the selected control action")
         .withPluralFutureMessage("records will receive the selected control action");



      /************************************************************************
       ** Never trust extracted rows after permissions or records have changed.
       ************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         actor(input);
         for(QRecord selected : input.getRecords())
         {
            QRecord current = resolve(input, selected.getValueString("id"));
            ready.incrementCountAndAddPrimaryKey(current.getValue("id"));
            output.getRecords().add(current);
         }
      }



      /************************************************************************
       ** Standard summary links preserve the native record/table workflow.
       ************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(List.of(ready)));
      }
   }



   /***************************************************************************
    ** Apply the same durable action service as the authenticated HTTP adapter.
    ***************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine applied = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularPastMessage("server control action was applied; device observation is shown separately")
         .withPluralPastMessage("server control actions were applied; device observations are shown separately");
      private final ProcessSummaryLine pending = new ProcessSummaryLine(Status.WARNING, 0, null)
         .withSingularPastMessage("agent request is pending; no remote execution is confirmed")
         .withPluralPastMessage("agent requests are pending; no remote execution is confirmed");
      private final ProcessSummaryLine unresolved = new ProcessSummaryLine(Status.WARNING, 0, null)
         .withSingularPastMessage("request did not complete; inspect command history before trying another request")
         .withPluralPastMessage("requests did not complete; inspect command history before trying another request");



      /************************************************************************
       ** Recheck current scope and consent immediately before each mutation.
       ************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         actor(input);
         Action action = action(input);
         if(action.confirmation && !Boolean.TRUE.equals(input.getValueBoolean(CONFIRM)))
         {
            throw new QException("Confirm the reviewed action before continuing");
         }
         String requestId = input.getValueString(REQUEST_ID);
         String issuedAt = input.getValueString(ISSUED_AT);
         if(requestId == null || requestId.isBlank() || issuedAt == null || issuedAt.isBlank())
         {
            throw new QException("Restart this action to obtain a stable control request identity");
         }
         for(QRecord selected : input.getRecords())
         {
            QRecord current = resolve(input, selected.getValueString("id"));
            String deviceId = current.getValueString(action.job ? "device_id" : "id");
            JSONObject request = new JSONObject().put("requestId", action.job ? requestId + "-" + current.getValueString("id") : requestId)
               .put("issuedAt", issuedAt).put("action", action.wire);
            if(action.job)
            {
               request.put("jobId", current.getValueString("id"));
            }
            try
            {
               JSONObject result = PrintingControlAuthorization.deviceAction(deviceId, request);
               String status = result.getString("status");
               ProcessSummaryLine summary = "PENDING".equals(status) ? pending : ("SUCCEEDED".equals(status) ? applied : unresolved);
               summary.incrementCountAndAddPrimaryKey(current.getValue("id"));
               output.getRecords().add(current);
            }
            catch(Exception exception)
            {
               throw new QException("Printing control could not be completed. Review command history before submitting a new action.", exception);
            }
         }
      }



      /************************************************************************
       ** Pending requests are never presented as completed Windows operations.
       ************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(List.of(applied, pending, unresolved)));
      }
   }
}
