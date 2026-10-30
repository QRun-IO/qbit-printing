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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
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
 ** Selected job actions share native record/table review and permission boundaries.
 *******************************************************************************/
public class PrintingJobProcesses
{
   public static final String RETRY = "printingRetrySelectedJobs";
   public static final String CANCEL = "printingCancelSelectedJobs";
   private static final String REQUEST_ID = "printingRetryRequestId";



   /***************************************************************************
    ** Associate actions with job records and table selections, without raw IDs.
    ***************************************************************************/
   public static void register(QInstance instance)
   {
      for(String name : List.of(RETRY, CANCEL))
      {
         boolean retry = RETRY.equals(name);
         Map<String, Serializable> values = new HashMap<>();
         values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, PrintingTables.JOB);
         values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, PrintingTables.JOB);
         values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
         values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
         values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, retry
            ? "Each selected job will create a new print job. Previously spooled or uncertain output may print twice. Inspect the printer before continuing."
            : "Only selected jobs still queued in QQQ will be canceled. Jobs already received by a device must be inspected in the Windows companion.");
         QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, Transform.class, Load.class, values)
            .withName(name).withLabel(retry ? "Print again" : "Cancel queued jobs").withTableName(PrintingTables.JOB).withMinInputRecords(1)
            .withPermissionRules(PrintingProcesses.permissionRules());
         if(retry)
         {
            process.withStep(0, new QBackendStepMetaData().withName("prepareRetry").withCode(new QCodeReference(Prepare.class)));
            process.withStep(1, new QFrontendStepMetaData().withName("confirmDuplicateRisk").withLabel("Check for existing output")
               .withFormField(new QFieldMetaData("confirmDuplicateRisk", QFieldType.BOOLEAN)
                  .withLabel("I inspected the printer and accept that previously printed or uncertain jobs may print twice").withDefaultValue(false))
               .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)));
         }
         instance.addProcess(process);
      }
   }



   /***************************************************************************
    ** A stable process retry key makes replay of the same load idempotent.
    ***************************************************************************/
   public static class Prepare implements BackendStep
   {
      /***************************************************************************
       ** Keep the retry identity stable across review and load replay.
       ***************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         authorize(input);
         String id = input.getValueString(REQUEST_ID);
         output.addValue(REQUEST_ID, id == null || id.isBlank() ? UUID.randomUUID().toString() : id);
      }
   }



   /***************************************************************************
    ** Re-read selection under caller row security and validate current eligibility.
    ***************************************************************************/
   static QRecord resolve(RunBackendStepInput input, String id) throws QException
   {
      GetInput get = new GetInput();
      get.setTableName(PrintingTables.JOB);
      get.setPrimaryKey(id);
      QRecord job = new GetAction().execute(get).getRecord();
      if(job == null)
      {
         throw new QException("Choose an accessible print job");
      }
      String state = job.getValueString("status");
      boolean alreadyCanceled = "FAILED".equals(state) && PrintingService.CANCELED_BEFORE_DISPATCH.equals(job.getValueString("message"));
      if(CANCEL.equals(input.getProcessName()) ? (!"QUEUED".equals(state) && !alreadyCanceled) : !Set.of("FAILED", "OUTCOME_UNKNOWN", "SPOOLED", "SPOOLER_COMPLETED").contains(state))
      {
         throw new QException("Job " + job.getValueString("title") + " is " + state + " and cannot be processed by this action");
      }
      return (job);
   }



   /***************************************************************************
    ** Apply both administrator and fixed runtime tenant restrictions.
    ***************************************************************************/
   private static PrintingService authorize(RunBackendStepInput input) throws QException
   {
      new PrintingAdminPermission().checkPermissionsThrowing(input, null);
      if(!Set.of(RETRY, CANCEL).contains(input.getProcessName()))
      {
         throw new QException("Unknown print job action");
      }
      return (PrintingProcesses.currentService());
   }



   /***************************************************************************
    ** Review never mutates jobs; uncertain output receives an explicit warning.
    ***************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine ready = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularFutureMessage("job will be processed").withPluralFutureMessage("jobs will be processed");
      private final ProcessSummaryLine risk = new ProcessSummaryLine(Status.WARNING, 0, null)
         .withSingularFutureMessage("job may produce duplicate output; inspect the printer")
         .withPluralFutureMessage("jobs may produce duplicate output; inspect the printer");



      /***************************************************************************
       ** Resolve the authoritative current job state for every named selection.
       ***************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         authorize(input);
         for(QRecord selected : input.getRecords())
         {
            QRecord job = resolve(input, selected.getValueString("id"));
            ProcessSummaryLine summary = RETRY.equals(input.getProcessName()) && !"FAILED".equals(job.getValueString("status")) ? risk : ready;
            summary.incrementCountAndAddPrimaryKey(job.getValue("id"));
            output.getRecords().add(job);
         }
      }



      /***************************************************************************
       ** Standard QQQ preview links selected job titles to their records.
       ***************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(List.of(ready, risk)));
      }
   }



   /***************************************************************************
    ** Mutations only occur after review and re-check state, tenant and consent.
    ***************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine done = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularPastMessage("job was processed").withPluralPastMessage("jobs were processed");



      /***************************************************************************
       ** Never reset original jobs; retry creates separate durable jobs.
       ***************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         PrintingService service = authorize(input);
         for(QRecord selected : input.getRecords())
         {
            QRecord job = resolve(input, selected.getValueString("id"));
            try
            {
               if(RETRY.equals(input.getProcessName()))
               {
                  String requestId = input.getValueString(REQUEST_ID);
                  if(requestId == null || requestId.isBlank())
                  {
                     throw new QException("Restart Print again to obtain a safe retry request");
                  }
                  service.retry(job.getValueString("device_id"), job.getValueString("id"), new JSONObject()
                     .put("requestId", requestId).put("confirmDuplicateRisk", Boolean.TRUE.equals(input.getValueBoolean("confirmDuplicateRisk"))));
               }
               else
               {
                  service.cancel(job.getValueString("device_id"), job.getValueString("id"));
               }
               done.incrementCountAndAddPrimaryKey(job.getValue("id"));
               output.getRecords().add(job);
            }
            catch(java.sql.SQLException | PrintingException exception)
            {
               throw new QException("Print job action failed: " + exception.getMessage(), exception);
            }
         }
      }



      /***************************************************************************
       ** Standard QQQ results refer to the selected source job records.
       ***************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(List.of(done)));
      }
   }
}
