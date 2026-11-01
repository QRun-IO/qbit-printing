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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
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
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
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
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.json.JSONObject;


/*******************************************************************************
 ** "Print" on the records of a host table: choose an approved, exposed PDF
 ** printer and a copy count, review the selection, then each selected record is
 ** rendered as a record sheet (PrintingRecordDocuments) and queued as its own
 ** print job through the ordinary durable queue. The queued job IDs are returned
 ** in the process value printingJobIds (comma separated) so clients can follow
 ** each job's status in Print jobs.
 **
 ** Register per table with register(instance, tableName); the process is named
 ** printingPrintRecords_{tableName} (processName(tableName)).
 *******************************************************************************/
public final class PrintingRecordProcesses
{
   public static final String PREFIX = "printingPrintRecords_";
   public static final String PRINTER_FIELD = "printingPrinterId";
   public static final String COPIES_FIELD = "printingCopies";
   public static final String JOB_IDS = "printingJobIds";
   static final String REQUEST_ID = "printingRecordRequestId";



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingRecordProcesses()
   {
   }



   /***************************************************************************
    ** The record-print process name of a table.
    ***************************************************************************/
   public static String processName(String tableName)
   {
      return (PREFIX + tableName);
   }



   /***************************************************************************
    ** Add the Print action to a table's records and selections.
    ***************************************************************************/
   public static void register(QInstance instance, String tableName) throws QException
   {
      QTableMetaData table = instance.getTable(tableName);
      if(table == null)
      {
         throw new QException("Cannot add printing to unknown table " + tableName);
      }
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, tableName);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, tableName);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, "Each selected record will be printed as its own job on the chosen printer after you continue.");
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(PrintingExtract.class, Transform.class, Load.class, values)
         .withName(processName(tableName)).withLabel("Print").withTableName(tableName).withMinInputRecords(1)
         .withPermissionRules(PrintingProcesses.permissionRules());
      process.withStep(0, new QFrontendStepMetaData().withName("printer").withLabel("Choose a printer")
         .withFormFields(List.of(printerField(), new QFieldMetaData(COPIES_FIELD, QFieldType.INTEGER).withLabel("Copies").withIsRequired(true).withDefaultValue(1)))
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)));
      process.withStep(0, new QBackendStepMetaData().withName("preparePrint").withCode(new QCodeReference(Prepare.class)));
      instance.addProcess(process);
   }



   /***************************************************************************
    ** Only approved printers that the device currently exposes and that take PDF.
    ***************************************************************************/
   static QFieldMetaData printerField()
   {
      QQueryFilter filter = new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, 1))
         .withCriteria(new QFilterCriteria("available", QCriteriaOperator.EQUALS, 1))
         .withCriteria(new QFilterCriteria("formats", QCriteriaOperator.CONTAINS, "PDF"));
      return (new QFieldMetaData(PRINTER_FIELD, QFieldType.STRING).withLabel("Printer").withIsRequired(true)
         .withPossibleValueSourceName(PrintingTables.PRINTER).withPossibleValueSourceFilter(filter));
   }



   /***************************************************************************
    ** The chosen printer, re-read with the caller's permissions and checked.
    ***************************************************************************/
   static QRecord printer(RunBackendStepInput input) throws QException
   {
      String id = input.getValueString(PRINTER_FIELD);
      if(id == null || id.isBlank())
      {
         throw new QException("Choose a printer");
      }
      QRecord printer = PrintingProcesses.resolvePrinter(id, null);
      if(!Integer.valueOf(1).equals(printer.getValueInteger("enabled")) || !Integer.valueOf(1).equals(printer.getValueInteger("available"))
         || printer.getValueString("formats") == null || !new org.json.JSONArray(printer.getValueString("formats")).toList().contains("PDF"))
      {
         throw new QException("Printer " + printer.getValueString("name") + " must be exposed by its device, approved, and support PDF");
      }
      return (printer);
   }



   /***************************************************************************
    ** 1 when unset; 1 to 100 otherwise (the protocol's copies range).
    ***************************************************************************/
   static int copies(RunBackendStepInput input) throws QException
   {
      Integer copies = input.getValueInteger(COPIES_FIELD);
      if(copies == null)
      {
         return (1);
      }
      if(copies < 1 || copies > 100)
      {
         throw new QException("Copies must be between 1 and 100");
      }
      return (copies);
   }



   /***************************************************************************
    ** Render one record (display values, visible non-binary fields) and queue it.
    ***************************************************************************/
   static String submit(QTableMetaData table, QRecord selected, QRecord printer, int copies, String request) throws QException
   {
      Serializable primaryKey = selected.getValue(table.getPrimaryKeyField());
      GetInput get = new GetInput();
      get.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      get.setTableName(table.getName());
      get.setPrimaryKey(primaryKey);
      get.setShouldGenerateDisplayValues(true);
      get.setShouldTranslatePossibleValues(true);
      QRecord record = new GetAction().execute(get).getRecord();
      if(record == null)
      {
         throw new QException("Record " + primaryKey + " of " + table.getLabel() + " is not accessible");
      }
      Map<String, String> fields = new LinkedHashMap<>();
      for(QFieldMetaData field : table.getFields().values())
      {
         if(Boolean.TRUE.equals(field.getIsHidden()) || field.getType() == QFieldType.BLOB)
         {
            continue;
         }
         String display = record.getDisplayValue(field.getName());
         Serializable raw = record.getValue(field.getName());
         fields.put(field.getLabel() == null ? field.getName() : field.getLabel(), display != null ? display : raw == null ? null : String.valueOf(raw));
      }
      String label = record.getRecordLabel() != null ? record.getRecordLabel() : String.valueOf(primaryKey);
      String tableLabel = table.getLabel() == null ? table.getName() : table.getLabel();
      String user = QContext.getQSession() == null || QContext.getQSession().getUser() == null ? null : QContext.getQSession().getUser().getFullName();
      String subtitle = tableLabel + " " + primaryKey + " - printed " + Instant.now().truncatedTo(ChronoUnit.SECONDS) + (user == null ? "" : " by " + user);
      byte[] bytes = PrintingRecordDocuments.pdf(tableLabel + ": " + label, subtitle, fields);
      try
      {
         JSONObject job = PrintingProcesses.currentService().submit(new JSONObject()
            .put("deviceId", printer.getValueString("device_id")).put("printerId", printer.getValueString("printer_id"))
            .put("title", PrintingRecordDocuments.truncate(tableLabel + ": " + label, 200)).put("format", "PDF")
            .put("contentBase64", Base64.getEncoder().encodeToString(bytes))
            .put("idempotencyKey", "record-print:" + request + ":" + table.getName() + ":" + primaryKey + ":" + printer.getValueString("id"))
            .put("options", new JSONObject().put("copies", copies).put("scale", "FIT")));
         return (job.getString("jobId"));
      }
      catch(java.sql.SQLException | PrintingException exception)
      {
         throw new QException("Could not queue the print job: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** One request identity per process run, so a replayed load does not print twice.
    ***************************************************************************/
   public static class Prepare implements BackendStep
   {
      /***************************************************************************
       ** Require printing access before any form is shown.
       ***************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         PrintingProcesses.currentService();
         String request = input.getValueString(REQUEST_ID);
         output.addValue(REQUEST_ID, request == null || request.isBlank() ? UUID.randomUUID().toString() : request);
         PrintingProcesses.keepSelection(input, output);
      }
   }



   /***************************************************************************
    ** Review: the printer is still usable; the selected records pass through.
    ***************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine ready = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularFutureMessage("record will be printed").withPluralFutureMessage("records will be printed");



      /***************************************************************************
       ** Check the printer and copies once per page; keep the records.
       ***************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         printer(input);
         copies(input);
         QTableMetaData table = QContext.getQInstance().getTable(input.getValueString(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE));
         for(QRecord record : input.getRecords())
         {
            ready.incrementCountAndAddPrimaryKey(record.getValue(table.getPrimaryKeyField()));
            output.getRecords().add(record);
         }
      }



      /***************************************************************************
       ** Standard review summary.
       ***************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(List.of(ready)));
      }
   }



   /***************************************************************************
    ** Execute: render and queue each record; report the job IDs.
    ***************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine done = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularPastMessage("record was sent to the printer").withPluralPastMessage("records were sent to the printer");
      private final List<String> jobIds = new ArrayList<>();



      /***************************************************************************
       ** Resolve the printer again (access may have changed since review).
       ***************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         QRecord printer = printer(input);
         int copies = copies(input);
         String request = input.getValueString(REQUEST_ID);
         if(request == null || request.isBlank())
         {
            throw new QException("Restart Print to obtain a safe request identity");
         }
         QTableMetaData table = QContext.getQInstance().getTable(input.getValueString(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE));
         for(QRecord record : input.getRecords())
         {
            jobIds.add(submit(table, record, printer, copies, request));
            done.incrementCountAndAddPrimaryKey(record.getValue(table.getPrimaryKeyField()));
            output.getRecords().add(record);
         }
         output.addValue(JOB_IDS, String.join(",", jobIds));
      }



      /***************************************************************************
       ** Result summary: what was sent, and the job IDs to follow.
       ***************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>(List.of(done));
         if(!jobIds.isEmpty())
         {
            lines.add(new ProcessSummaryLine(Status.INFO, null, "Print job" + (jobIds.size() == 1 ? " " : "s ") + String.join(", ", jobIds) + " queued; follow them in Print jobs."));
         }
         return (lines);
      }
   }
}
