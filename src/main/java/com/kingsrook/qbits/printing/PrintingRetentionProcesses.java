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


import java.util.List;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
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
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.json.JSONObject;


/*******************************************************************************
 ** Tenant-authorized retention settings and bounded document cleanup using QQQ.
 *******************************************************************************/
public class PrintingRetentionProcesses implements BackendStep
{
   public static final String SETTINGS = "printingRetentionSettings";
   public static final String RUN_NOW = "printingRunRetention";
   public static final String SCHEDULED = "printingScheduledRetention";



   /***************************************************************************
    ** Register native settings and confirmation forms; schedule only on a host scheduler.
    ***************************************************************************/
   public static void register(QInstance instance, PrintingQBitConfig config)
   {
      QProcessMetaData settings = manual(SETTINGS, "Printing retention settings")
         .withStep(review("Current retention and storage"))
         .withStep(new QFrontendStepMetaData().withName("settings").withLabel("Edit retention")
            .withFormFields(List.of(new QFieldMetaData("days", QFieldType.INTEGER).withLabel("Keep successful job documents for days (1–36500)").withIsRequired(true),
               new QFieldMetaData("enabled", QFieldType.BOOLEAN).withLabel("Enable document retention cleanup").withIsRequired(true)))
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)))
         .withStep(backend("execute")).withStep(result());
      instance.addProcess(settings);
      instance.addProcess(manual(RUN_NOW, "Run printing retention cleanup")
         .withStep(review("Review cleanup before continuing")).withStep(backend("execute")).withStep(result()));
      if(config.getSchedulerName() != null && !config.getSchedulerName().isBlank())
      {
         instance.addProcess(new QProcessMetaData().withName(SCHEDULED).withLabel("Scheduled printing retention cleanup").withIsHidden(true)
            .withPermissionRules(PrintingProcesses.permissionRules()).withStep(backend("execute"))
            .withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName())
               .withCronExpression("0 */5 * * * ?").withCronTimeZoneId("UTC")
               .withDescription("Remove one bounded batch of eligible successful print documents using the tenant retention policy.")));
      }
   }



   /***************************************************************************
    ** Prepare each form from live tenant settings rather than stale metadata defaults.
    ***************************************************************************/
   private static QProcessMetaData manual(String name, String label)
   {
      return (new QProcessMetaData().withName(name).withLabel(label).withPermissionRules(PrintingProcesses.permissionRules()).withStep(backend("prepare")));
   }



   /***************************************************************************
    ** All step entrypoints repeat permission checks, including direct backend invocation.
    ***************************************************************************/
   private static QBackendStepMetaData backend(String name)
   {
      return (new QBackendStepMetaData().withName(name).withCode(new QCodeReference(PrintingRetentionProcesses.class)));
   }



   /***************************************************************************
    ** Read-only deployment details intentionally expose no editable backend IDs or credentials.
    ***************************************************************************/
   private static QFrontendStepMetaData review(String label)
   {
      return (new QFrontendStepMetaData().withName("review").withLabel(label)
         .withViewFields(List.of(text("policy", "Retention policy"), text("storage", "Document storage"), text("schedule", "Automatic schedule"), text("progress", "Cleanup progress"), text("safety", "What cleanup does")))
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)));
   }



   /***************************************************************************
    ** Keep native result rendering consistent with other printing processes.
    ***************************************************************************/
   private static QFrontendStepMetaData result()
   {
      return (new QFrontendStepMetaData().withName("result").withLabel("Result").withViewFields(List.of(text("result", "Result")))
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)));
   }



   /***************************************************************************
    ** Plain text view fields cannot act as arbitrary storage selectors.
    ***************************************************************************/
   private static QFieldMetaData text(String name, String label)
   {
      return (new QFieldMetaData(name, QFieldType.STRING).withLabel(label));
   }



   /***************************************************************************
    ** Scheduled system execution stays bound to this QQQ instance's configured tenant.
    ***************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      boolean scheduled = SCHEDULED.equals(input.getProcessName());
      PrintingService service;
      if(scheduled)
      {
         if(!(QContext.getQSession() instanceof QSystemUserSession))
         {
            throw new QPermissionDeniedException("The scheduled retention entrypoint requires the system session; use Run retention cleanup instead");
         }
         service = configuration().getService();
      }
      else
      {
         new PrintingAdminPermission().checkPermissionsThrowing(input, null);
         service = PrintingProcesses.currentService();
      }
      try
      {
         if("prepare".equals(input.getStepName()) && (SETTINGS.equals(input.getProcessName()) || RUN_NOW.equals(input.getProcessName())))
         {
            prepare(service, output);
         }
         else if("execute".equals(input.getStepName()) && SETTINGS.equals(input.getProcessName()))
         {
            Integer days = input.getValueInteger("days");
            Boolean enabled = input.getValueBoolean("enabled");
            if(days == null || enabled == null)
            {
               throw new QException("Specify retention days and whether cleanup is enabled");
            }
            service.saveRetentionSettings(days, enabled);
            output.addValue("result", "Printing retention settings saved for this tenant. Changing the policy does not run cleanup immediately.");
         }
         else if("execute".equals(input.getStepName()) && (RUN_NOW.equals(input.getProcessName()) || scheduled))
         {
            int removed = service.runRetention();
            output.addValue("result", "Removed " + removed + " eligible documents or abandoned uploads. Job receipts and uncertain or active work were preserved. Cleanup processes one bounded batch per run.");
         }
         else
         {
            throw new QException("Unknown printing retention action");
         }
      }
      catch(java.sql.SQLException | PrintingException | IllegalArgumentException exception)
      {
         throw new QException("Printing retention action failed: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** Show effective policy and resolved storage scope without exposing secrets.
    ***************************************************************************/
   private static void prepare(PrintingService service, RunBackendStepOutput output) throws java.sql.SQLException, QException
   {
      JSONObject settings = service.getRetentionSettings();
      output.addValue("days", settings.getInt("days"));
      output.addValue("enabled", settings.getBoolean("enabled"));
      output.addValue("policy", (settings.getBoolean("enabled") ? "Enabled" : "Disabled") + ": retain successful job documents for " + settings.getInt("days") + " days; at most " + settings.getInt("batchSize") + " documents per run.");
      String table = settings.optString("storageTableName", "");
      String label = QContext.getQInstance().getTable(table) == null ? table : QContext.getQInstance().getTable(table).getLabel();
      output.addValue("storage", table.isBlank() ? "Dedicated printing storage is not configured. Ask the application administrator to bind an existing QQQ storage table and dedicated printing bucket."
         : label + "; bucket: " + settings.optString("bucketName", "Configured by the host") + "; tenant prefix: " + settings.optString("storagePrefix", "Scoped by the service"));
      String scheduler = configuration().getSchedulerName();
      output.addValue("schedule", scheduler == null || scheduler.isBlank() ? "Not scheduled. Configure a host QQQ scheduler to enable automatic cleanup; Run cleanup remains available." : "Every five minutes (UTC) using the host QQQ scheduler; overlapping runs are prevented.");
      String lastRun = settings.optString("lastRunAt", "");
      output.addValue("progress", "Eligible documents waiting: " + settings.optLong("eligibleBacklog", 0)
         + "; last run: " + (lastRun.isBlank() ? "Not run yet" : lastRun) + "; removed: " + settings.optInt("lastRunCount", 0)
         + (settings.optString("lastRunError", "").isBlank() ? "" : "; last run problem: " + settings.optString("lastRunError")));
      output.addValue("safety", "Cleanup removes only old successful-terminal document content. Active, failed and uncertain jobs, receipt metadata and deduplication records remain. Removed documents cannot be reprinted from history. Continue to edit the policy or run the selected cleanup action.");
   }



   /***************************************************************************
    ** Avoid static service caches and prevent a scheduler from crossing instance boundaries.
    ***************************************************************************/
   private static PrintingQBitConfig configuration() throws QException
   {
      for(QBitMetaData metadata : QContext.getQInstance().getQBits().values())
      {
         if(metadata.getConfig() instanceof PrintingQBitConfig config && config.getService() != null)
         {
            return (config);
         }
      }
      throw new QException("Printing QBit is not configured");
   }
}
