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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.processes.QProcessCallback;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Record printing: the per-table Print process and the record sheet document.
 *******************************************************************************/
class PrintingRecordProcessesTest
{
   /***************************************************************************
    ** Clear the shared memory store and context between tests.
    ***************************************************************************/
   @AfterEach
   void clear()
   {
      MemoryRecordStore.getInstance().reset();
      QContext.clear();
   }



   /***************************************************************************
    ** Printer form, review, then one queued PDF job per selected record.
    ***************************************************************************/
   @Test
   void printsEachSelectedRecordOnceAndReturnsTheJobIds() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixtureWithPeople();
      String process = PrintingRecordProcesses.processName("person");
      assertEquals("printingPrintRecords_person", process);
      QFrontendStepMetaData printerStep = (QFrontendStepMetaData) fixture.instance().getProcess(process).getStep("printer");
      assertEquals(PrintingTables.PRINTER, printerStep.getFormField(PrintingRecordProcesses.PRINTER_FIELD).getPossibleValueSourceName());

      var form = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withValue("recordIds", "1,2"));
      assertFalse(form.getException().isPresent(), () -> form.getException().toString());
      assertEquals("printer", form.getProcessState().getNextStepName().orElseThrow());
      var review = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(form.getProcessUUID())
         .withStartAfterStep("printer").withValue(PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId()).withValue(PrintingRecordProcesses.COPIES_FIELD, 2));
      assertFalse(review.getException().isPresent(), () -> review.getException().toString());
      assertEquals(0, fixture.service().jobs().length(), "review must not print");
      var done = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(review.getProcessUUID())
         .withStartAfterStep(review.getProcessState().getNextStepName().orElseThrow()).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      assertFalse(done.getException().isPresent(), () -> done.getException().toString());

      JSONArray jobs = fixture.service().jobs();
      assertEquals(2, jobs.length());
      String ids = String.valueOf(done.getValues().get(PrintingRecordProcesses.JOB_IDS));
      assertEquals(2, ids.split(",").length);
      for(int i = 0; i < jobs.length(); i++)
      {
         JSONObject job = jobs.getJSONObject(i);
         assertTrue(ids.contains(job.getString("jobId")));
         assertEquals("PDF", job.getString("format"));
         assertTrue(job.getString("title").startsWith("Person: "));
         assertEquals(2, job.getJSONObject("options").getInt("copies"));
         fixture.service().claimForJob(fixture.device(), job.getString("jobId"));
         byte[] content = fixture.service().content(fixture.device(), job.getString("jobId"));
         try(PDDocument document = PDDocument.load(content))
         {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Person:"));
            assertTrue(text.contains("First name"));
            assertTrue(text.contains("Ada") || text.contains("Grace"));
            assertFalse(text.contains("Secret"), "hidden fields are not printed");
         }
      }
   }



   /***************************************************************************
    ** The UI API passes the selection only to init (as the callback filter);
    ** the selection must survive the printer form that comes before review.
    ***************************************************************************/
   @Test
   void keepsTheInitSelectionAcrossThePrinterForm() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixtureWithPeople();
      String process = PrintingRecordProcesses.processName("person");
      var form = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withCallback(new QProcessCallback()
      {
         @Override
         public QQueryFilter getQueryFilter()
         {
            return (new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.IN, List.of(2))));
         }



         @Override
         public Map<String, Serializable> getFieldValues(List<QFieldMetaData> fields)
         {
            return (Map.of());
         }
      }));
      var review = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(form.getProcessUUID())
         .withStartAfterStep("printer").withValue(PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId()));
      new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(review.getProcessUUID())
         .withStartAfterStep(review.getProcessState().getNextStepName().orElseThrow()).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      JSONArray jobs = fixture.service().jobs();
      assertEquals(1, jobs.length());
      assertTrue(jobs.getJSONObject(0).getString("title").contains("Grace"));
   }



   /***************************************************************************
    ** A printer that is not approved (or not exposed) is refused at review.
    ***************************************************************************/
   @Test
   void refusesAPrinterThatIsNotApproved() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixtureWithPeople();
      String process = PrintingRecordProcesses.processName("person");
      var device = fixture.service();
      device.enablePrinter(fixture.device(), "laser", false);
      var form = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withValue("recordIds", "1"));
      QException refused = assertThrows(QException.class, () -> new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(form.getProcessUUID())
         .withStartAfterStep("printer").withValue(PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId())));
      assertTrue(refused.getMessage().contains("approved"), refused::getMessage);
      assertEquals(0, fixture.service().jobs().length());
   }



   /***************************************************************************
    ** Unknown tables are refused at registration.
    ***************************************************************************/
   @Test
   void registerRefusesAnUnknownTable() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = PrintingProcessesTest.fixture();
      assertThrows(QException.class, () -> PrintingRecordProcesses.register(fixture.instance(), "nope"));
   }



   /***************************************************************************
    ** Long values wrap, long documents continue on more pages, unencodable
    ** characters are replaced rather than failing the render.
    ***************************************************************************/
   @Test
   void recordSheetWrapsAndContinuesAcrossPages() throws Exception
   {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("Notes", "word ".repeat(400));
      for(int i = 0; i < 80; i++)
      {
         fields.put("Field " + i, "value " + i + " 中文");
      }
      fields.put("Empty", null);
      byte[] bytes = PrintingRecordDocuments.pdf("Person: Ada Lovelace", "Person 1", fields);
      try(PDDocument document = PDDocument.load(bytes))
      {
         assertTrue(document.getNumberOfPages() > 1);
         String text = new PDFTextStripper().getText(document);
         assertTrue(text.contains("Field 79"));
         assertTrue(text.contains("continued, page 2"));
      }
      assertEquals(List.of("abc", "def"), PrintingRecordDocuments.wrap("abc def", 4));
      assertEquals(List.of("abcd", "ef"), PrintingRecordDocuments.wrap("abcdef", 4));
      assertEquals("ab~", PrintingRecordDocuments.truncate("abcdef", 3));
   }



   /***************************************************************************
    ** The process fixture plus a memory "person" table with two records.
    ***************************************************************************/
   private static PrintingProcessesTest.Fixture fixtureWithPeople() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = PrintingProcessesTest.fixture();
      QInstance instance = fixture.instance();
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.addTable(new QTableMetaData().withName("person").withLabel("Person").withBackendName("memory").withPrimaryKeyField("id")
         .withRecordLabelFormat("%s %s").withRecordLabelFields(List.of("firstName", "lastName"))
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withLabel("Id"))
         .withField(new QFieldMetaData("firstName", QFieldType.STRING).withLabel("First name"))
         .withField(new QFieldMetaData("lastName", QFieldType.STRING).withLabel("Last name"))
         .withField(new QFieldMetaData("secret", QFieldType.STRING).withLabel("Secret").withIsHidden(true)));
      PrintingRecordProcesses.register(instance, "person");
      new InsertAction().execute(new InsertInput("person").withRecords(List.of(
         new QRecord().withValue("id", 1).withValue("firstName", "Ada").withValue("lastName", "Lovelace").withValue("secret", "x"),
         new QRecord().withValue("id", 2).withValue("firstName", "Grace").withValue("lastName", "Hopper"))));
      return (fixture);
   }
}
