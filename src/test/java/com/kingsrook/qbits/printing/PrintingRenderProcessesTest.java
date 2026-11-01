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


import java.io.InputStream;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.processes.ProcessFileDownload;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Save as PDF: the record sheet, table list and text page rendered for the
 ** caller as a process download, with no printer and no print job.
 *******************************************************************************/
class PrintingRenderProcessesTest
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
    ** A record sheet comes back as a download named after the record.
    ***************************************************************************/
   @Test
   void recordSheetIsADownloadNotAJob() throws Exception
   {
      PrintingProcessesTest.Fixture fixture = PrintingListProcessesTest.fixture();
      RunProcessOutput output = PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "RECORD", "tableName", "person", "recordId", "1"));
      assertEquals("Person-Ada-Lovelace.pdf", output.getValues().get("downloadFileName"));
      String text = text(output);
      assertTrue(text.contains("Person: Ada Lovelace") && text.contains("Pets (2)"), text);
      assertEquals(0, fixture.service().jobs().length(), "saving does not queue a print job");
   }



   /***************************************************************************
    ** A table list and a text page render the same way their prints do.
    ***************************************************************************/
   @Test
   void tableListAndTextPage() throws Exception
   {
      PrintingListProcessesTest.fixture();
      RunProcessOutput list = PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "LIST", "tableName", "person", "columns", "lastName,firstName", "scope", "ALL",
         "filterJSON", "{\"criteria\":[],\"orderBys\":[{\"fieldName\":\"lastName\",\"isAscending\":true}]}"));
      assertEquals("Person.pdf", list.getValues().get("downloadFileName"));
      String rows = text(list);
      assertTrue(rows.contains("all 2 rows of 2") || rows.contains("all 2 rows"), rows);
      assertTrue(rows.indexOf("Hopper") < rows.indexOf("Lovelace"), rows);
      RunProcessOutput page = PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "TEXT", "title", "Home / Screen", "text", "| hello |"));
      assertEquals("Home-Screen.pdf", page.getValues().get("downloadFileName"));
      assertTrue(text(page).contains("| hello |"));
   }



   /***************************************************************************
    ** Every document carries the app's header and footer from the client's
    ** values: app, breadcrumb, server, user, printed-at; footer and page n of m;
    ** a list adds its filter and sort above the footer.
    ***************************************************************************/
   @Test
   void headerAndFooterOnEveryDocument() throws Exception
   {
      PrintingListProcessesTest.fixture();
      Map<String, java.io.Serializable> chrome = Map.of("headerApp", "QQQ All", "headerTitle", "Sales › Customer › Ada Lovelace", "headerServer", "local (localhost:8078)",
         "headerUser", "Admin", "headerPrintedAt", "2026-11-01 12:03", "footerLeft", "qqq ui 1.0.1 · local");
      for(Map<String, java.io.Serializable> values : java.util.List.<Map<String, java.io.Serializable>>of(
         Map.of("kind", "RECORD", "tableName", "person", "recordId", "1"),
         Map.of("kind", "LIST", "tableName", "person", "filterDescription", "lastName = Hopper", "filterJSON", "{\"criteria\":[],\"orderBys\":[{\"fieldName\":\"id\",\"isAscending\":false}]}"),
         Map.of("kind", "TEXT", "title", "Home", "text", "hello")))
      {
         java.util.HashMap<String, java.io.Serializable> all = new java.util.HashMap<>(values);
         all.putAll(chrome);
         String text = text(PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, all));
         for(String expected : java.util.List.of("QQQ All", "Sales › Customer › Ada Lovelace", "local (localhost:8078) · Admin · 2026-11-01 12:03", "qqq ui 1.0.1 · local", "Page 1 of 1"))
         {
            assertTrue(text.contains(expected), values.get("kind") + " lacks " + expected + ":\n" + text);
         }
         if("LIST".equals(values.get("kind")))
         {
            assertTrue(text.contains("Filter: lastName = Hopper - sort: Id descending"), text);
         }
      }
      String defaults = text(PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "RECORD", "tableName", "person", "recordId", "2")));
      assertTrue(defaults.contains("Person › Grace Hopper") && defaults.contains("QQQ printing"), defaults);
   }



   /***************************************************************************
    ** Content documents: each section's title as a heading and its lines,
    ** long lines wrapped, no borders.
    ***************************************************************************/
   @Test
   void contentSectionsDocument() throws Exception
   {
      PrintingListProcessesTest.fixture();
      String sections = "[{\"title\":\"\",\"lines\":[\"Good morning, Admin\"]},{\"title\":\"Recently viewed\",\"lines\":[\"Ada Lovelace   Customer · 1m ago\"]},{\"title\":\"Quick actions\",\"lines\":[\"" + "word ".repeat(60) + "\"]}]";
      String text = text(PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "TEXT", "title", "Home", "sections", sections)));
      assertTrue(text.contains("Good morning, Admin") && text.contains("Recently viewed") && text.contains("Quick actions"), text);
      assertTrue(text.indexOf("Recently viewed") < text.indexOf("Ada Lovelace"), text);
      assertTrue(!text.contains("│") && !text.contains("┌"), text);
      assertThrows(QException.class, () -> PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "TEXT", "title", "Home", "sections", "[]")));
      assertThrows(QException.class, () -> PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "TEXT", "title", "Home", "sections", "{nope")));
   }



   /***************************************************************************
    ** Unknown kinds, missing records and opted-out tables are refused.
    ***************************************************************************/
   @Test
   void refusals() throws Exception
   {
      PrintingListProcessesTest.fixture();
      assertThrows(QException.class, () -> PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "NOPE")));
      assertThrows(QException.class, () -> PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "RECORD", "tableName", "person", "recordId", "99")));
      assertThrows(QException.class, () -> PrintingListProcessesTest.run(PrintingRenderProcesses.RENDER, Map.of("kind", "LIST", "tableName", "secretThing")));
      assertEquals("document.pdf", PrintingRenderProcesses.fileName("///"));
   }



   /***************************************************************************
    ** The PDF text of a render's download.
    ***************************************************************************/
   private static String text(RunProcessOutput output) throws Exception
   {
      try(InputStream stream = ProcessFileDownload.open(String.valueOf(output.getValues().get("serverFilePath"))); PDDocument document = PDDocument.load(stream))
      {
         return new PDFTextStripper().getText(document);
      }
   }
}
