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


import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.CompositeWidgetData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.QWidgetData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.StatisticsData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.blocks.BlockLink;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.blocks.text.TextBlockData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.blocks.text.TextValues;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;


/*******************************************************************************
 ** Printing administration home rendered by stock QQQ typed widgets.
 ** All reads retain the caller's permission and tenant context.
 *******************************************************************************/
public class PrintingHome extends AbstractWidgetRenderer
{
   private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
   private static final String DEVICE = "printing_device";
   private static final String PRINTER = "printing_printer";
   private static final String JOB = "printing_job";
   private static final List<String> FAILURES = List.of("FAILED", "OUTCOME_UNKNOWN");
   private static final List<String> PENDING = List.of("QUEUED", "CLAIMED", "RECEIVED", "SUBMITTING");



   /***************************************************************************
    ** Register the fixed admin profile: purpose, attention, health, then work.
    ***************************************************************************/
   public static void register(QInstance instance, QAppMetaData app) throws QException
   {
      add(instance, app, "printingHomeHeader", "Printing operations", WidgetType.COMPOSITE, "header", 0, 12);
      add(instance, app, "printingHomeAttention", "Needs attention", WidgetType.TABLE, "systemAttention", 10, 12);
      add(instance, app, "printingHomeOffline", "Disconnected devices", WidgetType.STATISTICS, "systemHealth", 20, 3);
      add(instance, app, "printingHomePrinters", "Printers needing attention", WidgetType.STATISTICS, "systemHealth", 21, 3);
      add(instance, app, "printingHomeFailures", "Failed or uncertain jobs", WidgetType.STATISTICS, "systemHealth", 22, 3);
      add(instance, app, "printingHomePending", "Pending print jobs", WidgetType.STATISTICS, "systemHealth", 23, 3);
      add(instance, app, "printingHomeWork", "Pending and recent print work", WidgetType.TABLE, "queue", 30, 12);
   }



   /***************************************************************************
    ** RC.20 carries role/priority hints through defaultValues, not typed fields.
    ***************************************************************************/
   private static void add(QInstance instance, QAppMetaData app, String name, String label, WidgetType type, String role, int priority, int width) throws QException
   {
      QWidgetMetaData widget = new QWidgetMetaData().withName(name).withLabel(label).withType(type.getType()).withGridColumns(width).withShowReloadButton(true)
         .withCodeReference(new QCodeReference(PrintingHome.class)).withDefaultValue("role", role).withDefaultValue("priority", priority)
         .withDefaultValue("refreshSeconds", 60).withDefaultValue("staleAfter", 120).withDefaultValue("storeDropdownSelections", false)
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withCustomPermissionChecker(new QCodeReference(PrintingAdminPermission.class)));
      instance.addWidget(widget);
      if(app.getWidgets() == null)
      {
         app.setWidgets(new ArrayList<>());
      }
      app.getWidgets().add(name);
   }



   /***************************************************************************
    ** Every render independently checks authorization and preserves row locks.
    ***************************************************************************/
   @Override
   public RenderWidgetOutput render(RenderWidgetInput input) throws QException
   {
      new PrintingAdminPermission().checkPermissionsThrowing(input, null);
      if(QContext.getQSession().getSecurityKeyValues("printing_tenant") == null || QContext.getQSession().getSecurityKeyValues("printing_tenant").isEmpty())
      {
         throw new QPermissionDeniedException("A printing tenant is required");
      }
      Instant now = Instant.now();
      QWidgetData data = switch(input.getWidgetMetaData().getName())
      {
         case "printingHomeHeader" -> header();
         case "printingHomeAttention" -> attention(now);
         case "printingHomeOffline" -> statistic(DEVICE, offline(now), "Devices · now · target 0 without heartbeat for 90 seconds");
         case "printingHomePrinters" -> statistic(PRINTER, printerIssues(), "Enabled printers · now · target 0 needing attention");
         case "printingHomeFailures" -> statistic(JOB, failures(now), "Jobs created in last 24 hours · target 0 failed or uncertain");
         case "printingHomePending" -> statistic(JOB, statuses(PENDING), "Jobs · now · target queue drains after printing");
         case "printingHomeWork" -> work(now);
         default -> throw new QException("Unknown printing home widget");
      };
      data.setSublabel("Checked " + DISPLAY_TIME.format(now) + " · current tenant · refresh every 60 seconds");
      return (new RenderWidgetOutput(data));
   }



   /***************************************************************************
    ** Explain purpose, scope, first setup and help without process navigation.
    ***************************************************************************/
   private static CompositeWidgetData header() throws QException
   {
      CompositeWidgetData data = column().withBlock(text("Keep packing slips and shipping labels moving by resolving disconnected devices, printer issues and print failures."))
         .withBlock(text("Scope: current tenant. Devices/printers: now. Job exceptions/recent work: last 24 hours."))
         .withBlock(text("Enroll a print device").withLink(new BlockLink("/app/printingEnrollDevice/?returnTo=%2Fapp%2Fprinting%2F")))
         .withBlock(text("Printing retention settings").withLink(new BlockLink("/app/printingRetentionSettings/?returnTo=%2Fapp%2Fprinting%2F")))
         .withBlock(text("Run retention cleanup").withLink(new BlockLink("/app/printingRunRetention/?returnTo=%2Fapp%2Fprinting%2F")))
         .withBlock(text("Printing setup and help").withLink(new BlockLink("https://github.com/QRun-IO/qbit-printing/blob/main/docs/OPERATIONS.md")));
      if(count(DEVICE, new QQueryFilter()) == 0)
      {
         data.withBlock(text("No print devices are connected. Open Print devices and use Enroll a print device; then connect the Windows agent and expose its printers.").withLink(new BlockLink(tableUrl(DEVICE, new QQueryFilter()))));
      }
      return (data);
   }



   /***************************************************************************
    ** Severity order: failed work, disconnected devices, then printer issues.
    ***************************************************************************/
   private static TableData attention(Instant now) throws QException
   {
      List<Map<String, Object>> rows = new ArrayList<>();
      for(QRecord job : query(JOB, failures(now).withOrderBy(new QFilterOrderBy("created_at", true)).withLimit(10)))
      {
         rows.add(issue(JOB, job, job.getValueString("title"), job.getValueString("status"), "Check the local queue and physical output before retrying an uncertain job.", "created_at"));
      }
      for(QRecord device : query(DEVICE, offline(now).withOrderBy(new QFilterOrderBy("last_seen_at", true)).withLimit(10)))
      {
         rows.add(issue(DEVICE, device, device.getValueString("name"), "No heartbeat for 90 seconds", "Open the Windows agent and check its service and outbound connection.", "last_seen_at"));
      }
      for(QRecord printer : query(PRINTER, printerIssues().withOrderBy(new QFilterOrderBy("last_seen_at", true)).withLimit(10)))
      {
         rows.add(issue(PRINTER, printer, printer.getValueString("name"), printer.getValueString("status"), "Check paper, paused/error state and whether this queue is still exposed by the agent.", "last_seen_at"));
      }
      return (table(rows, "No issues in this scope. If no devices are enrolled, start from Print devices.",
         new TableData.Column("composite", "Issue / record", "issue", null, "left"),
         new TableData.Column("default", "Next step", "action", null, "left"),
         new TableData.Column("default", "Observed at", "observed", null, "left")));
   }



   /***************************************************************************
    ** A bounded full-width view of outstanding work and recent submissions.
    ***************************************************************************/
   private static TableData work(Instant now) throws QException
   {
      QQueryFilter filter = new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR)
         .withSubFilter(statuses(PENDING)).withSubFilter(new QQueryFilter().withCriteria("created_at", QCriteriaOperator.GREATER_THAN_OR_EQUALS, now.minusSeconds(86400).toString()));
      List<Map<String, Object>> rows = new ArrayList<>();
      for(QRecord job : query(JOB, filter.withOrderBy(new QFilterOrderBy("created_at", false)).withLimit(25)))
      {
         rows.add(Map.of("job", column().withBlock(text(job.getValueString("title")).withLink(new BlockLink(recordUrl(JOB, job)))),
            "status", job.getValueString("status"), "format", job.getValueString("format"), "created", displayTime(job.getValueString("created_at"))));
      }
      return (table(rows, "No pending or recent print work. Open Printers to enable an exposed queue and run its test-print action.",
         new TableData.Column("composite", "Print job", "job", null, "left"),
         new TableData.Column("default", "Status", "status", null, "left"),
         new TableData.Column("default", "Format", "format", null, "left"),
         new TableData.Column("default", "Created at", "created", null, "left"))
         .withLinkText("Open all pending and recent jobs").withLinkURL(tableUrl(JOB, filter.withLimit(null))));
   }



   /***************************************************************************
    ** Count and drill-through share the exact same filter and caller context.
    ***************************************************************************/
   private static StatisticsData statistic(String table, QQueryFilter filter, String context) throws QException
   {
      return (new StatisticsData().withCount(count(table, filter)).withCountContext(context).withCountURL(tableUrl(table, filter)));
   }



   /***************************************************************************
    ** Read through QQQ so backend tenant locks and permissions remain active.
    ***************************************************************************/
   private static List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      return (new QueryAction().execute(new QueryInput().withTableName(table).withFilter(filter)).getRecords());
   }



   /***************************************************************************
    ** Count without replacing the current session with a system identity.
    ***************************************************************************/
   private static int count(String table, QQueryFilter filter) throws QException
   {
      return (new CountAction().execute(new CountInput(table).withFilter(filter)).getCount());
   }



   /***************************************************************************
    ** A heartbeat older than 90 seconds means disconnected, not printer failure.
    ***************************************************************************/
   private static QQueryFilter offline(Instant now)
   {
      return (new QQueryFilter().withCriteria("enabled", QCriteriaOperator.EQUALS, 1).withCriteria("last_seen_at", QCriteriaOperator.LESS_THAN, now.minusSeconds(90).toString()));
   }



   /***************************************************************************
    ** Only enabled queues can block configured printing work.
    ***************************************************************************/
   private static QQueryFilter printerIssues()
   {
      return (new QQueryFilter().withCriteria("enabled", QCriteriaOperator.EQUALS, 1).withSubFilter(new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR)
         .withCriteria("status", QCriteriaOperator.NOT_EQUALS, "READY").withCriteria("available", QCriteriaOperator.NOT_EQUALS, 1)));
   }



   /***************************************************************************
    ** Explicit job-state group shared by count, attention and drill-through.
    ***************************************************************************/
   private static QQueryFilter statuses(List<String> states)
   {
      return (new QQueryFilter().withCriteria("status", QCriteriaOperator.IN, states));
   }



   /***************************************************************************
    ** The 24-hour window is based on creation, which v1 persists for every job.
    ***************************************************************************/
   private static QQueryFilter failures(Instant now)
   {
      return (statuses(FAILURES).withCriteria("created_at", QCriteriaOperator.GREATER_THAN_OR_EQUALS, now.minusSeconds(86400).toString()));
   }



   /***************************************************************************
    ** Each attention item links to the specific affected record.
    ***************************************************************************/
   private static Map<String, Object> issue(String table, QRecord record, String label, String status, String action, String timestamp) throws QException
   {
      return (Map.of("issue", column().withBlock(text(label + " — " + status).withLink(new BlockLink(recordUrl(table, record)))),
         "action", action, "observed", displayTime(record.getValueString(timestamp))));
   }



   /***************************************************************************
    ** Presentation precision is seconds; query boundaries retain exact instants.
    ***************************************************************************/
   private static String displayTime(String value)
   {
      if(value == null)
      {
         return ("Unknown time");
      }
      try
      {
         return (DISPLAY_TIME.format(Instant.parse(value)));
      }
      catch(DateTimeParseException exception)
      {
         return ("Unknown time");
      }
   }



   /***************************************************************************
    ** Standard typed table; the legacy empty-message property contains plain text.
    ***************************************************************************/
   private static TableData table(List<Map<String, Object>> rows, String empty, TableData.Column... columns)
   {
      return (new TableData(null, List.of(columns), rows).withRowsPerPage(25).withHidePaginationDropdown(true).withNoRowsFoundHTML(empty));
   }



   /***************************************************************************
    ** Native composite block layout, never custom HTML.
    ***************************************************************************/
   private static CompositeWidgetData column()
   {
      return (new CompositeWidgetData().withLayout(CompositeWidgetData.Layout.FLEX_COLUMN));
   }



   /***************************************************************************
    ** Native text block escapes dynamic record labels in the frontend.
    ***************************************************************************/
   private static TextBlockData text(String value)
   {
      return (new TextBlockData().withValues(new TextValues(value)));
   }



   /***************************************************************************
    ** Record URLs are derived from the host's actual app/table hierarchy.
    ***************************************************************************/
   private static String recordUrl(String table, QRecord record) throws QException
   {
      return (QContext.getQInstance().getTablePath(table) + "/" + URLEncoder.encode(record.getValueString("id"), StandardCharsets.UTF_8));
   }



   /***************************************************************************
    ** Native table filtering keeps each KPI and queue link reproducible.
    ***************************************************************************/
   private static String tableUrl(String table, QQueryFilter filter) throws QException
   {
      return (QContext.getQInstance().getTablePath(table) + "?filter=" + URLEncoder.encode(JsonUtils.toJson(filter), StandardCharsets.UTF_8));
   }
}

