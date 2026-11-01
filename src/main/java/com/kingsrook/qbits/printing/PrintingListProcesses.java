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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;


/*******************************************************************************
 ** Two print processes that are not bound to one table:
 **
 ** - printingPrintTableList: a table list (grid) of any readable table. Values:
 **   tableName, columns (comma separated, in order; default the visible
 **   fields), filterJSON (a QQueryFilter with orderBys; its skip/limit are the
 **   page), scope PAGE (the filter's skip/limit) or ALL (the whole result,
 **   read a thousand rows at a time, up to maxRows: default and most 10,000;
 **   a larger result prints its first rows and says so), columnWidths (the
 **   grid's widths in characters, comma separated, same order), pageSize
 **   LETTER/A4, orientation PORTRAIT/LANDSCAPE/AUTO (landscape when the
 **   columns need it; the default), title, filterDescription (the filter as
 **   the user sees it), then the printer and copies.
 ** - printingPrintText: any text as a monospace PDF (a capture of a terminal
 **   screen). Values: title, text, subtitle, pageSize, orientation, printer,
 **   copies.
 **
 ** Both ask for the printer on an options form (prefilled from the init
 ** values), queue one PDF job and return its id in printingJobIds. Reading the
 ** table enforces the user's table and record permissions.
 *******************************************************************************/
public final class PrintingListProcesses
{
   public static final String TABLE_LIST = "printingPrintTableList";
   public static final String TEXT = "printingPrintText";
   public static final int MAX_ROWS = 10_000;
   public static final int DEFAULT_MAX_ROWS = MAX_ROWS;
   static final int QUERY_PAGE = 1000;
   static final String REQUEST_ID = "printingListRequestId";



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingListProcesses()
   {
   }



   /***************************************************************************
    ** Register both processes and their choice lists.
    ***************************************************************************/
   public static void register(QInstance instance)
   {
      choices(instance, "printing_pageSize", List.of("LETTER", "A4"), List.of("Letter", "A4"));
      choices(instance, "printing_orientation", List.of("AUTO", "PORTRAIT", "LANDSCAPE"), List.of("Automatic (landscape when the columns need it)", "Portrait", "Landscape"));
      choices(instance, "printing_listScope", List.of("ALL", "PAGE"), List.of("All rows (current filter and sort)", "The current page"));
      instance.addProcess(process(TABLE_LIST, "Print a table list", List.of(
         new QFieldMetaData("tableName", QFieldType.STRING).withLabel("Table").withIsRequired(true),
         new QFieldMetaData("scope", QFieldType.STRING).withLabel("Rows").withPossibleValueSourceName("printing_listScope").withDefaultValue("ALL"),
         new QFieldMetaData("maxRows", QFieldType.INTEGER).withLabel("Most rows (all rows)").withDefaultValue(DEFAULT_MAX_ROWS),
         new QFieldMetaData("orientation", QFieldType.STRING).withLabel("Orientation").withPossibleValueSourceName("printing_orientation").withDefaultValue("AUTO"))));
      instance.addProcess(process(TEXT, "Print text", List.of(
         new QFieldMetaData("title", QFieldType.STRING).withLabel("Title"),
         new QFieldMetaData("orientation", QFieldType.STRING).withLabel("Orientation").withPossibleValueSourceName("printing_orientation").withDefaultValue("PORTRAIT"))));
   }



   /***************************************************************************
    ** prepare, options (printer and layout), execute, result.
    ***************************************************************************/
   private static QProcessMetaData process(String name, String label, List<QFieldMetaData> extra)
   {
      List<QFieldMetaData> fields = new ArrayList<>(extra);
      fields.add(PrintingRecordProcesses.printerField());
      fields.add(new QFieldMetaData(PrintingRecordProcesses.COPIES_FIELD, QFieldType.INTEGER).withLabel("Copies").withIsRequired(true).withDefaultValue(1));
      fields.add(new QFieldMetaData("pageSize", QFieldType.STRING).withLabel("Paper").withPossibleValueSourceName("printing_pageSize").withDefaultValue("LETTER"));
      return (new QProcessMetaData().withName(name).withLabel(label).withPermissionRules(PrintingProcesses.permissionRules())
         .withStep(new QBackendStepMetaData().withName("prepare").withCode(new QCodeReference(Prepare.class)))
         .withStep(new QFrontendStepMetaData().withName("options").withLabel("Choose a printer").withFormFields(fields)
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)))
         .withStep(new QBackendStepMetaData().withName("execute").withCode(new QCodeReference(Execute.class)))
         .withStep(new QFrontendStepMetaData().withName("result").withLabel("Result")
            .withViewFields(List.of(new QFieldMetaData("result", QFieldType.STRING).withLabel("Result")))
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM))));
   }



   /***************************************************************************
    ** A fixed enum choice list.
    ***************************************************************************/
   private static void choices(QInstance instance, String name, List<String> ids, List<String> labels)
   {
      ArrayList<QPossibleValue<?>> values = new ArrayList<>();
      for(int i = 0; i < ids.size(); i++)
      {
         values.add(new QPossibleValue<>(ids.get(i), labels.get(i)));
      }
      instance.addPossibleValueSource(new QPossibleValueSource().withName(name).withType(QPossibleValueSourceType.ENUM).withEnumValues(values));
   }



   /***************************************************************************
    ** The table a table list reads, checked readable.
    ***************************************************************************/
   static QTableMetaData table(RunBackendStepInput input) throws QException
   {
      String name = input.getValueString("tableName");
      QTableMetaData table = name == null ? null : QContext.getQInstance().getTable(name);
      if(table == null)
      {
         throw new QException("Choose a table to print (unknown table " + name + ")");
      }
      PrintingTableSettings settings = PrintingTableSettings.of(table);
      if(settings != null && !settings.getPrintable())
      {
         throw new QException(table.getLabel() + " is not printable");
      }
      return (table);
   }



   /***************************************************************************
    ** The filter from filterJSON (or everything), with the page or the row limit.
    ***************************************************************************/
   static QQueryFilter filter(RunBackendStepInput input) throws QException
   {
      String json = input.getValueString("filterJSON");
      QQueryFilter filter;
      try
      {
         filter = json == null || json.isBlank() ? new QQueryFilter() : JsonUtils.toObject(json, QQueryFilter.class);
      }
      catch(Exception exception)
      {
         throw new QException("The filter is not valid: " + exception.getMessage(), exception);
      }
      if("ALL".equalsIgnoreCase(input.getValueString("scope")))
      {
         Integer max = input.getValueInteger("maxRows");
         int limit = max == null ? DEFAULT_MAX_ROWS : max;
         if(limit < 1 || limit > MAX_ROWS)
         {
            throw new QException("Most rows must be between 1 and " + MAX_ROWS);
         }
         filter.setSkip(0);
         filter.setLimit(limit);
      }
      else
      {
         if(filter.getLimit() == null || filter.getLimit() > MAX_ROWS)
         {
            filter.setLimit(filter.getLimit() == null ? 50 : MAX_ROWS);
         }
      }
      return (filter);
   }



   /***************************************************************************
    ** The columns to print: given (unknown names refused), else visible fields.
    ***************************************************************************/
   static List<QFieldMetaData> columns(QTableMetaData table, String given) throws QException
   {
      List<QFieldMetaData> out = new ArrayList<>();
      if(given == null || given.isBlank())
      {
         table.getFields().values().stream().filter(PrintingRecordProcesses::printable).forEach(out::add);
         return (out);
      }
      for(String name : Arrays.stream(given.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList())
      {
         QFieldMetaData field = table.getFields().get(name);
         if(field == null)
         {
            throw new QException("Table " + table.getName() + " has no field " + name);
         }
         if(PrintingRecordProcesses.printable(field))
         {
            out.add(field);
         }
      }
      if(out.isEmpty())
      {
         throw new QException("None of the columns can be printed");
      }
      return (out);
   }



   /***************************************************************************
    ** The rows to print: the page as given, or the whole result read a page at
    ** a time up to the filter's limit (the print limit).
    ***************************************************************************/
   static List<QRecord> read(QTableMetaData table, QQueryFilter filter, boolean all) throws QException
   {
      List<QRecord> out = new ArrayList<>();
      int limit = filter.getLimit() == null ? 50 : filter.getLimit();
      int skip = filter.getSkip() == null ? 0 : filter.getSkip();
      while(out.size() < limit)
      {
         int size = all ? Math.min(QUERY_PAGE, limit - out.size()) : limit;
         QueryInput query = new QueryInput();
         query.setTableName(table.getName());
         query.setFilter(filter.clone().withSkip(skip + out.size()).withLimit(size));
         query.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         query.setShouldGenerateDisplayValues(true);
         query.setShouldTranslatePossibleValues(true);
         List<QRecord> page = new QueryAction().execute(query).getRecords();
         out.addAll(page);
         if(!all || page.size() < size)
         {
            break;
         }
      }
      return (out);
   }



   /***************************************************************************
    ** The sort as labels: "Last name, First name descending".
    ***************************************************************************/
   static String sortText(QTableMetaData table, QQueryFilter filter)
   {
      if(filter.getOrderBys() == null)
      {
         return ("");
      }
      List<String> parts = new ArrayList<>();
      for(var orderBy : filter.getOrderBys())
      {
         String name = orderBy.getFieldName();
         QFieldMetaData field = name == null ? null : table.getFields().get(name);
         parts.add((field == null || field.getLabel() == null ? name : field.getLabel()) + (orderBy.getIsAscending() ? "" : " descending"));
      }
      return (String.join(", ", parts));
   }



   /***************************************************************************
    ** The grid's column widths ("12,30,8"), or null.
    ***************************************************************************/
   static int[] widths(String text)
   {
      if(text == null || text.isBlank())
      {
         return (null);
      }
      try
      {
         return (Arrays.stream(text.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray());
      }
      catch(NumberFormatException exception)
      {
         return (null);
      }
   }



   /***************************************************************************
    ** AUTO unless PORTRAIT or LANDSCAPE was chosen.
    ***************************************************************************/
   static String orientation(RunBackendStepInput input)
   {
      String value = input.getValueString("orientation");
      return (value == null || value.isBlank() ? "AUTO" : value.toUpperCase(Locale.ROOT));
   }



   /***************************************************************************
    ** A rendered document: its title, the PDF, and a note (rows left out).
    ***************************************************************************/
   public record Rendered(String title, byte[] bytes, String message)
   {
   }



   /***************************************************************************
    ** Render a table list (list true) or a text page from the process values.
    ***************************************************************************/
   static Rendered render(RunBackendStepInput input, boolean list) throws QException
   {
      String pageSize = input.getValueString("pageSize") == null ? "LETTER" : input.getValueString("pageSize").toUpperCase(Locale.ROOT);
      if(!list)
      {
         String title = input.getValueString("title") == null || input.getValueString("title").isBlank() ? "Screen" : input.getValueString("title");
         String subtitle = input.getValueString("subtitle");
         boolean landscape = "LANDSCAPE".equalsIgnoreCase(input.getValueString("orientation"));
         PrintingChrome chrome = PrintingChrome.from(input, title);
         String sections = input.getValueString("sections");
         if(sections != null && !sections.isBlank())
         {
            return (new Rendered(title, PrintingTextDocuments.sectionsPdf(title, subtitle, sections(sections), pageSize, landscape, chrome), null));
         }
         return (new Rendered(title, PrintingTextDocuments.pdf(title, subtitle, input.getValueString("text"), pageSize, landscape, chrome), null));
      }
      QTableMetaData table = table(input);
      List<QFieldMetaData> columns = columns(table, input.getValueString("columns"));
      QQueryFilter filter = filter(input);
      boolean all = "ALL".equalsIgnoreCase(input.getValueString("scope"));
      List<QRecord> records = read(table, filter, all);
      List<List<String>> rows = new ArrayList<>();
      for(QRecord record : records)
      {
         List<String> row = new ArrayList<>();
         columns.forEach(field -> row.add(PrintingRecordProcesses.text(record, field)));
         rows.add(row);
      }
      String title = input.getValueString("title") != null ? input.getValueString("title") : table.getLabel();
      Integer total = input.getValueInteger("printingRowCount");
      if(total == null)
      {
         CountInput count = new CountInput();
         count.setTableName(table.getName());
         count.setFilter(filter.clone().withSkip(null).withLimit(null));
         count.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         total = new CountAction().execute(count).getCount();
      }
      int skip = filter.getSkip() == null ? 0 : filter.getSkip();
      String scope = all ? (total != null && total > records.size() ? "first " + records.size() + " of " + total + " rows (print limit " + filter.getLimit() + ")" : "all " + records.size() + " rows")
         : "rows " + (records.isEmpty() ? 0 : skip + 1) + "-" + (skip + records.size()) + (total != null ? " of " + total : "");
      String described = input.getValueString("filterDescription");
      String sort = sortText(table, filter);
      String subtitle = scope + " - " + (described != null && !described.isBlank() ? "filter " + described : "no filter") + (sort.isEmpty() ? "" : " - sort " + sort);
      String note = (described != null && !described.isBlank() ? "Filter: " + described : "No filter") + (sort.isEmpty() ? "" : " - sort: " + sort);
      byte[] bytes = PrintingTableDocuments.pdf(title, subtitle, note,
         columns.stream().map(field -> field.getLabel() == null ? field.getName() : field.getLabel()).toList(), rows, widths(input.getValueString("columnWidths")), pageSize, orientation(input),
         PrintingChrome.from(input, table.getLabel() == null ? table.getName() : table.getLabel()));
      String message = all && total != null && total > records.size()
         ? "Printed the first " + records.size() + " of " + total + " rows: the print limit is " + filter.getLimit() + " rows. Narrow the filter to print the rest." : null;
      return (new Rendered(title, bytes, message));
   }



   /***************************************************************************
    ** The "sections" value: a JSON array of {title, lines: [..]} (at most 500
    ** sections), as clients send a screen's content.
    ***************************************************************************/
   static List<PrintingTextDocuments.Section> sections(String json) throws QException
   {
      try
      {
         org.json.JSONArray array = new org.json.JSONArray(json);
         if(array.length() > 500)
         {
            throw new QException("Too many sections to print (at most 500)");
         }
         List<PrintingTextDocuments.Section> out = new ArrayList<>();
         for(int i = 0; i < array.length(); i++)
         {
            org.json.JSONObject section = array.getJSONObject(i);
            List<String> lines = new ArrayList<>();
            org.json.JSONArray given = section.optJSONArray("lines");
            for(int j = 0; given != null && j < given.length(); j++)
            {
               lines.add(given.optString(j, ""));
            }
            out.add(new PrintingTextDocuments.Section(section.optString("title", ""), lines));
         }
         return (out);
      }
      catch(org.json.JSONException exception)
      {
         throw new QException("The sections are not valid: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** Request identity, and for a table list the number of rows it would print.
    ***************************************************************************/
   public static class Prepare implements BackendStep
   {
      /***************************************************************************
       ** Check access early so the form is not shown to someone who may not print.
       ***************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         PrintingProcesses.currentService();
         String request = input.getValueString(REQUEST_ID);
         output.addValue(REQUEST_ID, request == null || request.isBlank() ? UUID.randomUUID().toString() : request);
         if(TABLE_LIST.equals(input.getProcessName()))
         {
            QTableMetaData table = table(input);
            CountInput count = new CountInput();
            count.setTableName(table.getName());
            count.setFilter(filter(input).clone().withSkip(null).withLimit(null));
            count.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
            output.addValue("printingRowCount", new CountAction().execute(count).getCount());
            if(input.getValueString("title") == null)
            {
               output.addValue("title", table.getLabel() == null ? table.getName() : table.getLabel());
            }
         }
      }
   }



   /***************************************************************************
    ** Render and queue.
    ***************************************************************************/
   public static class Execute implements BackendStep
   {
      /***************************************************************************
       ** One PDF job on the chosen printer.
       ***************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         QRecord printer = PrintingRecordProcesses.printer(input);
         int copies = PrintingRecordProcesses.copies(input);
         String request = input.getValueString(REQUEST_ID);
         if(request == null || request.isBlank())
         {
            throw new QException("Restart the print to obtain a safe request identity");
         }
         Rendered rendered = render(input, TABLE_LIST.equals(input.getProcessName()));
         if(rendered.message() != null)
         {
            output.addValue("printingTruncated", true);
            output.addValue("printingMessage", rendered.message());
         }
         String title = rendered.title();
         byte[] bytes = rendered.bytes();
         String job = PrintingRecordProcesses.queue(printer, title, bytes, copies, "list-print:" + request + ":" + input.getProcessName() + ":" + printer.getValueString("id"));
         output.addValue(PrintingRecordProcesses.JOB_IDS, job);
         output.addValue("result", "Queued print job " + job + ". Check Print jobs for its status." + (output.getValue("printingMessage") != null ? " " + output.getValue("printingMessage") : ""));
      }
   }
}
