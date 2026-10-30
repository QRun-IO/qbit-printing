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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.json.JSONObject;


/*******************************************************************************
 ** Native QQQ setup forms and backend steps for the initial printing workflow.
 *******************************************************************************/
public class PrintingProcesses implements BackendStep
{
   /***************************************************************************
    ** Register the setup and test actions in the Printing app.
    ***************************************************************************/
   public static void register(QInstance instance, QAppMetaData app)
   {
      PrintingJobProcesses.register(instance);
      add(instance, "printingEnrollDevice", "Enroll a print device", List.of(field("facilityId", "Facility"), field("deviceName", "Device name")));
      add(instance, "printingEnablePrinter", "Set printer approval", List.of(deviceField(), printerField(null), new QFieldMetaData("enabled", QFieldType.BOOLEAN).withLabel("Approved for printing").withIsRequired(true)));
      add(instance, "printingTestZpl", "Print a test label", List.of(deviceField(), printerField("ZPL")));
      add(instance, "printingTestPdf", "Print a test page", List.of(deviceField(), printerField("PDF")));
      selected(instance, "printingTestSelectedPdf", "Print a test page", "Each selected PDF printer will receive one test page after you continue.");
      selected(instance, "printingTestSelectedZpl", "Print a test label", "Each selected ZPL printer will receive one test label after you continue.");
      selected(instance, "printingEnableSelectedPrinters", "Approve printers", "The selected printers will be approved for printing after you continue.");
      selected(instance, "printingDisableSelectedPrinters", "Disable printers", "The selected printers will stop receiving new print jobs after you continue.");
   }



   /***************************************************************************
    ** Shared administrator permission for printing apps and processes.
    ***************************************************************************/
   public static QPermissionRules permissionRules()
   {
      return (new QPermissionRules().withLevel(com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel.HAS_ACCESS_PERMISSION)
         .withCustomPermissionChecker(new QCodeReference(PrintingAdminPermission.class)));
   }



   /***************************************************************************
    ** The user's tenant-scoped device labels replace raw identifiers.
    ***************************************************************************/
   private static QFieldMetaData deviceField()
   {
      return (field("deviceId", "Print device").withPossibleValueSourceName(PrintingTables.DEVICE));
   }



   /***************************************************************************
    ** Queue row IDs resolve to the agent's printer ID only after authorization.
    ***************************************************************************/
   private static QFieldMetaData printerField(String format)
   {
      QQueryFilter filter = new QQueryFilter(new QFilterCriteria("device_id", QCriteriaOperator.EQUALS, "${input.deviceId}"));
      if(format != null)
      {
         filter.withCriteria(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, 1));
         filter.withCriteria(new QFilterCriteria("available", QCriteriaOperator.EQUALS, 1));
         filter.withCriteria(new QFilterCriteria("formats", QCriteriaOperator.CONTAINS, format));
      }
      return (field("printerId", "Printer").withPossibleValueSourceName(PrintingTables.PRINTER)
         .withPossibleValueSourceFilter(filter).withPossibleValueSourceDependsOn(List.of("deviceId")));
   }



   /***************************************************************************
    ** Build standard forms; actions belong on dashboards, not navigation menus.
    ***************************************************************************/
   private static void add(QInstance instance, String name, String label, List<QFieldMetaData> fields)
   {
      QProcessMetaData process = new QProcessMetaData().withName(name).withLabel(label).withPermissionRules(permissionRules())
         .withStep(new QFrontendStepMetaData().withName("details").withLabel(label).withFormFields(fields).withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)))
         .withStep(new QBackendStepMetaData().withName("execute").withCode(new QCodeReference(PrintingProcesses.class)))
         .withStep(new QFrontendStepMetaData().withName("result").withLabel("Result").withViewFields(List.of(field("result", "Result"))).withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)));
      if("printingEnrollDevice".equals(name))
      {
         process.withTableName(PrintingTables.DEVICE).withMinInputRecords(0);
      }
      instance.addProcess(process);
   }



   /***************************************************************************
    ** Native ETL supports both table selections and individual record actions.
    ***************************************************************************/
   private static void selected(QInstance instance, String name, String label, String preview)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, PrintingTables.PRINTER);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, PrintingTables.PRINTER);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, preview);
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, SelectedTransform.class, SelectedLoad.class, values)
         .withName(name).withLabel(label).withTableName(PrintingTables.PRINTER).withMinInputRecords(1).withPermissionRules(permissionRules());
      instance.addProcess(process);
   }



   /***************************************************************************
    ** Required text field for setup forms.
    ***************************************************************************/
   private static QFieldMetaData field(String name, String label)
   {
      return (new QFieldMetaData(name, QFieldType.STRING).withLabel(label).withIsRequired(true).withMaxLength("deviceName".equals(name) ? 1024 : 200));
   }



   /***************************************************************************
    ** Find the current instance's runtime, never a shared global cached service.
    ***************************************************************************/
   public static PrintingService currentService() throws QException
   {
      for(QBitMetaData metadata : QContext.getQInstance().getQBits().values())
      {
         if(metadata.getConfig() instanceof PrintingQBitConfig config && config.getService() != null)
         {
            Map<String, List<Serializable>> keys = QContext.getQSession() == null ? null : QContext.getQSession().getSecurityKeyValues();
            List<Serializable> tenants = keys == null ? null : keys.get("printing_tenant");
            if(tenants == null || !tenants.contains(config.getService().getTenantId()))
            {
               throw new com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException("The current session cannot manage this printing tenant");
            }
            return (config.getService());
         }
      }
      throw new QException("Printing QBit is not configured");
   }



   /***************************************************************************
    ** Execute an authorized setup action and return its user-facing result.
    ***************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      new PrintingAdminPermission().checkPermissionsThrowing(input, null);
      PrintingService service = currentService();
      try
      {
         switch(input.getProcessName())
         {
            case "printingEnrollDevice" ->
            {
               JSONObject enrollment = service.createEnrollment(input.getValueString("facilityId"), input.getValueString("deviceName"));
               output.addValue("result", "Enrollment code: " + enrollment.getString("enrollmentCode") + " (expires " + enrollment.getString("expiresAt") + ")");
            }
            case "printingEnablePrinter" ->
            {
               QRecord printer = resolvePrinter(input.getValueString("printerId"), input.getValueString("deviceId"));
               service.enablePrinter(printer.getValueString("device_id"), printer.getValueString("printer_id"), Boolean.TRUE.equals(input.getValueBoolean("enabled")));
               output.addValue("result", "Printer configuration saved.");
            }
            case "printingTestZpl", "printingTestPdf" ->
            {
               QRecord printer = resolvePrinter(input.getValueString("printerId"), input.getValueString("deviceId"));
               String job = submitTest(printer, "printingTestPdf".equals(input.getProcessName()));
               output.addValue("result", "Queued print job " + job + ". Check Print jobs for submission status.");
            }
            default -> throw new QException("Unknown printing action");
         }
      }
      catch(java.sql.SQLException | PrintingException exception)
      {
         throw new QException("Printing action failed: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** Re-read records with the caller's permissions; form values are not trusted.
    ***************************************************************************/
   static QRecord resolvePrinter(String id, String deviceId) throws QException
   {
      GetInput get = new GetInput();
      get.setTableName(PrintingTables.PRINTER);
      get.setPrimaryKey(id);
      QRecord printer = new GetAction().execute(get).getRecord();
      if(printer == null || (deviceId != null && !deviceId.equals(printer.getValueString("device_id"))))
      {
         throw new QException("Choose an accessible printer belonging to the selected device");
      }
      return (printer);
   }



   /***************************************************************************
    ** Send one native test document through the normal durable queue.
    ***************************************************************************/
   static String submitTest(QRecord printer, boolean pdf) throws QException
   {
      try
      {
         byte[] bytes = pdf ? testPdf() : "^XA^FO30,30^A0N,35,35^FDQQQ cloud printing test^FS^FO30,85^A0N,25,25^FDTest label - not for shipping^FS^XZ".getBytes(StandardCharsets.UTF_8);
         JSONObject job = currentService().submit(new JSONObject().put("deviceId", printer.getValueString("device_id")).put("printerId", printer.getValueString("printer_id"))
            .put("title", "QQQ print test").put("format", pdf ? "PDF" : "ZPL").put("contentBase64", Base64.getEncoder().encodeToString(bytes)).put("idempotencyKey", "test:" + UUID.randomUUID()));
         return (job.getString("jobId"));
      }
      catch(java.sql.SQLException | PrintingException exception)
      {
         throw new QException("Could not queue test print: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** Preview selected queues without printing or changing their approval.
    ***************************************************************************/
   public static class SelectedTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine ready = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularFutureMessage("printer will be processed").withPluralFutureMessage("printers will be processed");



      /***************************************************************************
       ** Check the current queue state before showing the review screen.
       ***************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         for(QRecord selected : input.getRecords())
         {
            QRecord printer = resolvePrinter(selected.getValueString("id"), null);
            if(input.getProcessName().startsWith("printingTest"))
            {
               String format = input.getProcessName().endsWith("Pdf") ? "PDF" : "ZPL";
               if(!Integer.valueOf(1).equals(printer.getValueInteger("enabled")) || !Integer.valueOf(1).equals(printer.getValueInteger("available"))
                  || !new org.json.JSONArray(printer.getValueString("formats")).toList().contains(format))
               {
                  throw new QException("Printer " + printer.getValueString("name") + " must be exposed, approved, and support " + format);
               }
            }
            ready.incrementCountAndAddPrimaryKey(printer.getValue("id"));
            output.getRecords().add(printer);
         }
      }



      /***************************************************************************
       ** Standard QQQ review summary.
       ***************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(List.of(ready)));
      }
   }



   /***************************************************************************
    ** Apply selected actions only after QQQ's standard review confirmation.
    ***************************************************************************/
   public static class SelectedLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine done = new ProcessSummaryLine(Status.OK, 0, null)
         .withSingularPastMessage("printer was processed").withPluralPastMessage("printers were processed");



      /***************************************************************************
       ** Resolve each selection again so withdrawn access is respected.
       ***************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         for(QRecord selected : input.getRecords())
         {
            QRecord printer = resolvePrinter(selected.getValueString("id"), null);
            try
            {
               switch(input.getProcessName())
               {
                  case "printingTestSelectedPdf" -> submitTest(printer, true);
                  case "printingTestSelectedZpl" -> submitTest(printer, false);
                  case "printingEnableSelectedPrinters", "printingDisableSelectedPrinters" -> currentService().enablePrinter(printer.getValueString("device_id"), printer.getValueString("printer_id"), "printingEnableSelectedPrinters".equals(input.getProcessName()));
                  default -> throw new QException("Unknown selected printing action");
               }
               done.incrementCountAndAddPrimaryKey(printer.getValue("id"));
               output.getRecords().add(printer);
            }
            catch(java.sql.SQLException | PrintingException exception)
            {
               throw new QException("Selected printing action failed: " + exception.getMessage(), exception);
            }
         }
      }



      /***************************************************************************
       ** Standard QQQ result summary links back to the selected queues.
       ***************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(List.of(done)));
      }
   }



   /***************************************************************************
    ** Generate a small valid PDF test page using the existing QQQ PDF dependency.
    ***************************************************************************/
   static byte[] testPdf() throws QException
   {
      try(org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument())
      {
         org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
         document.addPage(page);
         try(org.apache.pdfbox.pdmodel.PDPageContentStream content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page))
         {
            content.beginText();
            content.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 24);
            content.newLineAtOffset(72, 700);
            content.showText("QQQ cloud printing test");
            content.endText();
         }
         java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
         document.save(bytes);
         return (bytes.toByteArray());
      }
      catch(java.io.IOException exception)
      {
         throw new QException("Could not generate test PDF", exception);
      }
   }
}




