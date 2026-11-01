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
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Association;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Print anything: Print on every table (registerAll, opt-out), the metadata
 ** record sheet with sections and child lists, the table list and text prints.
 *******************************************************************************/
class PrintingListProcessesTest
{
   /***************************************************************************
    ** Clear the memory store and context.
    ***************************************************************************/
   @AfterEach
   void clear()
   {
      MemoryRecordStore.getInstance().reset();
      QContext.clear();
   }



   /***************************************************************************
    ** registerAll adds Print to readable tables and honors the opt-out.
    ***************************************************************************/
   @Test
   void registerAllAddsPrintToEveryTableButHiddenAndOptedOut() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixture();
      List<String> added = PrintingRecordProcesses.registerAll(fixture.instance());
      assertTrue(added.contains("pet"), added::toString);
      assertFalse(added.contains("person"), "person already had Print");
      assertFalse(added.contains("hiddenThing"));
      assertFalse(added.contains("secretThing"));
      assertTrue(fixture.instance().getProcess("printingPrintRecords_pet") != null);
      assertTrue(fixture.instance().getProcess("printingPrintRecords_secretThing") == null);
      assertEquals(List.of(), PrintingRecordProcesses.registerAll(fixture.instance()), "a second call adds nothing");
      assertTrue(fixture.instance().getProcess(PrintingListProcesses.TABLE_LIST) != null);
      assertTrue(fixture.instance().getProcess(PrintingListProcesses.TEXT) != null);
   }



   /***************************************************************************
    ** The record sheet follows the sections in tier order and summarizes child lists.
    ***************************************************************************/
   @Test
   void recordSheetHasSectionsAndChildLists() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixture();
      String process = PrintingRecordProcesses.processName("person");
      var form = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withValue("recordIds", "1"));
      var review = new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(form.getProcessUUID())
         .withStartAfterStep("printer").withValue(PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId()));
      new RunProcessAction().execute(new RunProcessInput().withProcessName(process).withProcessUUID(review.getProcessUUID())
         .withStartAfterStep(review.getProcessState().getNextStepName().orElseThrow()).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      String text = jobText(fixture, 0);
      assertTrue(text.indexOf("Identity") < text.indexOf("Contact"), text);
      assertTrue(text.contains("Email"), text);
      assertTrue(text.contains("ada@example.test"), text);
      assertTrue(text.contains("Other fields"), text);
      assertTrue(text.contains("Pets (2)"), text);
      assertTrue(text.contains("Rex") && text.contains("Tom"), text);
      assertFalse(text.contains("Secret"), text);
   }



   /***************************************************************************
    ** All rows, in the given column order and sort, with the count.
    ***************************************************************************/
   @Test
   void tableListOfAllRowsInColumnOrder() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixture();
      RunProcessOutput output = run(PrintingListProcesses.TABLE_LIST, Map.of("tableName", "person", "columns", "lastName,firstName", "scope", "ALL",
         "filterJSON", "{\"criteria\":[],\"orderBys\":[{\"fieldName\":\"lastName\",\"isAscending\":true}]}", "orientation", "PORTRAIT",
         PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId()));
      assertEquals(1, String.valueOf(output.getValues().get(PrintingRecordProcesses.JOB_IDS)).split(",").length);
      String text = jobText(fixture, 0);
      assertTrue(text.contains("Last name"), text);
      assertTrue(text.indexOf("Last name") < text.indexOf("First name"), text);
      assertTrue(text.indexOf("Hopper") < text.indexOf("Lovelace"), text);
      assertTrue(text.contains("all 2 rows"), text);
      assertTrue(text.contains("sort Last name"), text);
      assertTrue(text.contains("No filter - sort: Last name"), "the footer note carries the filter and sort");
      assertTrue(text.contains("QQQ printing"), "the default footer");
      assertTrue(text.contains("2 rows"), text);
      assertTrue(fixture.service().jobs().getJSONObject(0).getString("title").startsWith("Person"));
   }



   /***************************************************************************
    ** The current page only: the filter's skip and limit.
    ***************************************************************************/
   @Test
   void tableListOfThePageOnly() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixture();
      run(PrintingListProcesses.TABLE_LIST, Map.of("tableName", "person", "scope", "PAGE", "title", "People page 2",
         "filterJSON", "{\"criteria\":[],\"orderBys\":[{\"fieldName\":\"id\",\"isAscending\":true}],\"skip\":1,\"limit\":1}",
         PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId()));
      String text = jobText(fixture, 0);
      assertTrue(text.contains("People page 2"), text);
      assertTrue(text.contains("rows 2-2 of 2"), text);
      assertTrue(text.contains("Grace") && !text.contains("Lovelace"), text);
      assertTrue(text.contains("Email"), "default columns are the visible fields");
      assertFalse(text.contains("Secret"), text);
   }



   /***************************************************************************
    ** All rows of a result larger than the limit: the first rows, read a page
    ** at a time, with a message saying how many were left out; the grid's
    ** widths are used and AUTO turns wide lists landscape.
    ***************************************************************************/
   @Test
   void tableListOfAllRowsBeyondTheLimitSaysSo() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixture();
      List<QRecord> more = new java.util.ArrayList<>();
      for(int i = 3; i <= 2500; i++)
      {
         more.add(new QRecord().withValue("id", i).withValue("firstName", "First" + i).withValue("lastName", "Last" + i).withValue("email", "p" + i + "@example.test").withValue("note", "note " + i));
      }
      new InsertAction().execute(new InsertInput("person").withRecords(more));
      RunProcessOutput output = run(PrintingListProcesses.TABLE_LIST, Map.of("tableName", "person", "scope", "ALL", "maxRows", 2100,
         "columnWidths", "4,40,40,40,40", "filterDescription", "id > 0", PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId()));
      assertEquals(true, output.getValues().get("printingTruncated"));
      assertTrue(String.valueOf(output.getValues().get("printingMessage")).contains("first 2100 of 2500 rows"), () -> String.valueOf(output.getValues()));
      String job = fixture.service().jobs().getJSONObject(0).getString("jobId");
      fixture.service().claimForJob(fixture.device(), job);
      try(PDDocument document = PDDocument.load(fixture.service().content(fixture.device(), job)))
      {
         assertTrue(document.getPage(0).getMediaBox().getWidth() > document.getPage(0).getMediaBox().getHeight(), "AUTO chose landscape");
         assertTrue(document.getNumberOfPages() > 30, "pages: " + document.getNumberOfPages());
         String text = new PDFTextStripper().getText(document);
         assertTrue(text.contains("first 2100 of 2500 rows (print limit 2100)"), text.substring(0, 400));
         assertTrue(text.contains("filter id > 0"));
         assertTrue(text.contains("2100 rows"));
         assertTrue(text.contains("First2100") && !text.contains("First2101 "), "rows stop at the limit");
         PDFTextStripper last = new PDFTextStripper();
         last.setStartPage(document.getNumberOfPages());
         String lastPage = last.getText(document);
         assertTrue(lastPage.contains("Id") && lastPage.contains("First name"), "the header repeats on every page");
      }
   }



   /***************************************************************************
    ** Unknown columns, opted-out tables and too many rows are refused.
    ***************************************************************************/
   @Test
   void tableListRefusals() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixture();
      assertThrows(QException.class, () -> run(PrintingListProcesses.TABLE_LIST, Map.of("tableName", "person", "columns", "nope", PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId())));
      assertThrows(QException.class, () -> run(PrintingListProcesses.TABLE_LIST, Map.of("tableName", "secretThing", PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId())));
      assertThrows(QException.class, () -> run(PrintingListProcesses.TABLE_LIST, Map.of("tableName", "person", "scope", "ALL", "maxRows", 999999, PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId())));
      assertEquals(0, fixture.service().jobs().length());
   }



   /***************************************************************************
    ** Text prints monospace, wraps long lines and breaks pages.
    ***************************************************************************/
   @Test
   void textPrintsAScreenCapture() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = fixture();
      StringBuilder screen = new StringBuilder("+-- Dashboard --+\n| sales  1,234 |\n" + "x".repeat(300) + "\n");
      for(int i = 0; i < 150; i++)
      {
         screen.append("line ").append(i).append('\n');
      }
      run(PrintingListProcesses.TEXT, Map.of("title", "Dashboard", "subtitle", "qqq ui", "text", screen.toString(), PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId()));
      JSONArray jobs = fixture.service().jobs();
      assertEquals("Dashboard", jobs.getJSONObject(0).getString("title"));
      fixture.service().claimForJob(fixture.device(), jobs.getJSONObject(0).getString("jobId"));
      try(PDDocument document = PDDocument.load(fixture.service().content(fixture.device(), jobs.getJSONObject(0).getString("jobId"))))
      {
         assertTrue(document.getNumberOfPages() > 1);
         String text = new PDFTextStripper().getText(document);
         assertTrue(text.contains("| sales  1,234 |"), text);
         assertTrue(text.contains("line 149"), text);
      }
      assertThrows(QException.class, () -> run(PrintingListProcesses.TEXT, Map.of("text", "", PrintingRecordProcesses.PRINTER_FIELD, fixture.printerId())));
   }



   /***************************************************************************
    ** A 150-column terminal capture keeps its lines on a landscape page (the
    ** font shrinks to fit); much wider text still wraps at the smallest size.
    ***************************************************************************/
   @Test
   void wideScreenCaptureFitsWithoutWrapping() throws Exception
   {
      String wide = "| " + "y".repeat(146) + " |";
      try(PDDocument document = PDDocument.load(PrintingTextDocuments.pdf("Screen", "", wide + "\n" + wide, "LETTER", true)))
      {
         assertTrue(new PDFTextStripper().getText(document).contains(wide));
      }
      assertEquals(8f, PrintingTextDocuments.fontSize(new String[] {"short"}, 1000), 0.01);
      assertEquals(PrintingTextDocuments.MIN_FONT_SIZE, PrintingTextDocuments.fontSize(new String[] {"z".repeat(5000)}, 1000), 0.01);
   }



   /***************************************************************************
    ** Column widths shrink widest-first to fit, never below four.
    ***************************************************************************/
   @Test
   void columnWidthsFitTheLine()
   {
      int[] widths = PrintingLayout.columnWidths(List.of("Id", "Name", "Notes"), List.of(List.of("1", "Ada", "x".repeat(200))), 40);
      assertTrue(widths[0] + widths[1] + widths[2] + 4 <= 40);
      assertEquals(4, widths[0]);
      assertArrayEquals(new int[] {4, 4}, PrintingLayout.columnWidths(List.of("A", "B"), List.of(), 3));
      assertEquals("1     Ada", PrintingLayout.row(List.of("1", "Ada"), new int[] {4, 4}));
   }



   /***************************************************************************
    ** Run a process straight through with values.
    ***************************************************************************/
   static RunProcessOutput run(String process, Map<String, Serializable> values) throws QException
   {
      RunProcessInput input = new RunProcessInput().withProcessName(process).withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      // as the UI API runs it: user input, so server-configured inputs would be refused
      input.setInputSource(com.kingsrook.qqq.backend.core.model.actions.tables.QInputSource.USER);
      values.forEach(input::withValue);
      RunProcessOutput output = new RunProcessAction().execute(input);
      if(output.getException().isPresent())
      {
         throw new QException(output.getException().get());
      }
      return output;
   }



   /***************************************************************************
    ** The text of the n-th newest job's PDF.
    ***************************************************************************/
   private static String jobText(PrintingProcessesTest.Fixture fixture, int index) throws Exception
   {
      String job = fixture.service().jobs().getJSONObject(index).getString("jobId");
      fixture.service().claimForJob(fixture.device(), job);
      try(PDDocument document = PDDocument.load(fixture.service().content(fixture.device(), job)))
      {
         return new PDFTextStripper().getText(document);
      }
   }



   /***************************************************************************
    ** The process fixture plus person (sections, pets), pet, a hidden and an
    ** opted-out table, on the memory backend.
    ***************************************************************************/
   static PrintingProcessesTest.Fixture fixture() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = PrintingProcessesTest.fixture();
      QInstance instance = fixture.instance();
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      instance.addTable(new QTableMetaData().withName("person").withLabel("Person").withBackendName("memory").withPrimaryKeyField("id")
         .withRecordLabelFormat("%s %s").withRecordLabelFields(List.of("firstName", "lastName"))
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withLabel("Id"))
         .withField(new QFieldMetaData("firstName", QFieldType.STRING).withLabel("First name"))
         .withField(new QFieldMetaData("lastName", QFieldType.STRING).withLabel("Last name"))
         .withField(new QFieldMetaData("email", QFieldType.STRING).withLabel("Email"))
         .withField(new QFieldMetaData("note", QFieldType.STRING).withLabel("Note"))
         .withField(new QFieldMetaData("secret", QFieldType.STRING).withLabel("Secret").withIsHidden(true))
         .withSection(new QFieldSection("contact", "Contact", null, Tier.T2, List.of("email")))
         .withSection(new QFieldSection("identity", "Identity", null, Tier.T1, List.of("id", "firstName", "lastName")))
         .withAssociation(new Association().withName("pets").withAssociatedTableName("pet").withJoinName("personPet")));
      instance.addTable(new QTableMetaData().withName("pet").withLabel("Pet").withBackendName("memory").withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withLabel("Id"))
         .withField(new QFieldMetaData("personId", QFieldType.INTEGER).withLabel("Owner"))
         .withField(new QFieldMetaData("name", QFieldType.STRING).withLabel("Name")));
      instance.addJoin(new QJoinMetaData().withName("personPet").withLeftTable("person").withRightTable("pet").withType(JoinType.ONE_TO_MANY).withJoinOn(new JoinOn("id", "personId")));
      instance.addTable(new QTableMetaData().withName("hiddenThing").withBackendName("memory").withPrimaryKeyField("id").withIsHidden(true)
         .withField(new QFieldMetaData("id", QFieldType.INTEGER)));
      instance.addTable(new QTableMetaData().withName("secretThing").withLabel("Secret thing").withBackendName("memory").withPrimaryKeyField("id")
         .withSupplementalMetaData(new PrintingTableSettings().withPrintable(false))
         .withField(new QFieldMetaData("id", QFieldType.INTEGER)));
      PrintingRecordProcesses.register(instance, "person");
      new InsertAction().execute(new InsertInput("person").withRecords(List.of(
         new QRecord().withValue("id", 1).withValue("firstName", "Ada").withValue("lastName", "Lovelace").withValue("email", "ada@example.test").withValue("note", "n").withValue("secret", "x"),
         new QRecord().withValue("id", 2).withValue("firstName", "Grace").withValue("lastName", "Hopper").withValue("email", "grace@example.test"))));
      new InsertAction().execute(new InsertInput("pet").withRecords(List.of(
         new QRecord().withValue("id", 1).withValue("personId", 1).withValue("name", "Rex"),
         new QRecord().withValue("id", 2).withValue("personId", 1).withValue("name", "Tom"),
         new QRecord().withValue("id", 3).withValue("personId", 2).withValue("name", "Kit"))));
      return fixture;
   }
}
