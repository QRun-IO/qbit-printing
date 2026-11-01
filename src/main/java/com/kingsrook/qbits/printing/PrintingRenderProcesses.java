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


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.processes.ProcessFileDownload;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
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


/*******************************************************************************
 ** printingRenderToPdf: render the same document a print job would carry - a
 ** record sheet, a table list or a text page - and hand it to the caller as a
 ** download instead of queueing it on a printer ("Save as PDF").
 **
 ** Values: kind RECORD (tableName, recordId), LIST (the printingPrintTableList
 ** values: tableName, columns, columnWidths, filterJSON, scope, maxRows,
 ** pageSize, orientation, title, filterDescription) or TEXT (title, subtitle,
 ** text, orientation, pageSize). The result carries downloadFileName and
 ** serverFilePath for GET /download/{file} (the standard process download),
 ** and printingMessage when a list left rows out. No printer is needed; reads
 ** enforce the caller's table and record permissions.
 *******************************************************************************/
public final class PrintingRenderProcesses
{
   public static final String RENDER = "printingRenderToPdf";



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingRenderProcesses()
   {
   }



   /***************************************************************************
    ** Register the process.
    ***************************************************************************/
   public static void register(QInstance instance)
   {
      instance.addProcess(new QProcessMetaData().withName(RENDER).withLabel("Save as PDF").withPermissionRules(PrintingProcesses.permissionRules())
         .withStep(new QBackendStepMetaData().withName("render").withCode(new QCodeReference(Render.class)))
         .withStep(new QFrontendStepMetaData().withName("download").withLabel("Download")
            .withViewFields(List.of(new QFieldMetaData("downloadFileName", QFieldType.STRING).withLabel("File")))
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.DOWNLOAD_FORM))));
   }



   /***************************************************************************
    ** The download name: the document title made file-safe, with .pdf.
    ***************************************************************************/
   static String fileName(String title)
   {
      String stem = (title == null ? "" : title).replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|-+$", "");
      return ((stem.isEmpty() ? "document" : PrintingRecordDocuments.truncate(stem, 80).replace("~", "")) + ".pdf");
   }



   /***************************************************************************
    ** Render and register the file for download.
    ***************************************************************************/
   public static class Render implements BackendStep
   {
      /***************************************************************************
       ** One PDF, delivered through the standard process download.
       ***************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         PrintingProcesses.currentService();
         String kind = input.getValueString("kind") == null ? "" : input.getValueString("kind").toUpperCase(Locale.ROOT);
         PrintingListProcesses.Rendered rendered = switch(kind)
         {
            case "RECORD" ->
            {
               QTableMetaData table = PrintingListProcesses.table(input);
               String id = input.getValueString("recordId");
               if(id == null || id.isBlank())
               {
                  throw new QException("Give the record to save (recordId)");
               }
               yield PrintingRecordProcesses.render(table, com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsFieldType(table.getField(table.getPrimaryKeyField()).getType(), id));
            }
            case "LIST" -> PrintingListProcesses.render(input, true);
            case "TEXT" -> PrintingListProcesses.render(input, false);
            default -> throw new QException("kind must be RECORD, LIST or TEXT");
         };
         try
         {
            File file = Files.createTempFile("qqq-print-", ".pdf").toFile();
            file.deleteOnExit();
            Files.write(file.toPath(), rendered.bytes());
            output.addValue("downloadFileName", fileName(rendered.title()));
            output.addValue("serverFilePath", ProcessFileDownload.register(file));
            output.addValue("printingTitle", rendered.title());
            output.addValue("printingBytes", rendered.bytes().length);
            if(rendered.message() != null)
            {
               output.addValue("printingTruncated", true);
               output.addValue("printingMessage", rendered.message().replace("Printed the first", "Saved the first").replace("the print limit", "the limit").replace("to print the rest", "to save the rest"));
            }
         }
         catch(IOException exception)
         {
            throw new QException("Could not write the PDF for download", exception);
         }
      }
   }
}
