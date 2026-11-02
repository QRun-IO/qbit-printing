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


import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import org.json.JSONObject;


/*******************************************************************************
 ** Native tenant settings use human units without exposing connection secrets.
 *******************************************************************************/
public class PrintingRealtimeProcesses implements BackendStep
{
   public static final String SETTINGS = "printingRealtimeSettings";
   public static final String ADMISSION = "printingAdmissionSettings";
   private static final long MEBIBYTE = 1024L * 1024;
   private static final List<Setting> DOCUMENTS = List.of(
      new Setting("maxPdfMiB", "maxPdfBytes", "Maximum PDF size (MiB)", MEBIBYTE),
      new Setting("maxZplMiB", "maxZplBytes", "Maximum ZPL size (MiB)", MEBIBYTE),
      new Setting("maxCopies", "maxCopies", "Maximum copies per job", 1),
      new Setting("maxStagedMiB", "maxStagedBytes", "Maximum staged documents on each device (MiB)", MEBIBYTE));
   private static final List<Setting> TRANSFERS = List.of(
      new Setting("maxConcurrentDownloads", "maxConcurrentDownloads", "Concurrent document downloads", 1),
      new Setting("maxConcurrentPrints", "maxConcurrentPrints", "Concurrent printer submissions", 1),
      new Setting("downloadKiBPerSecond", "maxDownloadBytesPerSecond", "Download bandwidth (KiB/second; 0 means unlimited)", 1024),
      new Setting("requestTimeoutSeconds", "requestTimeoutMillis", "Request timeout (seconds)", 1000),
      new Setting("downloadConnectSeconds", "downloadConnectTimeoutMillis", "Download connection timeout (seconds)", 1000),
      new Setting("downloadIdleSeconds", "downloadIdleTimeoutMillis", "Download idle timeout (seconds)", 1000),
      new Setting("downloadTotalSeconds", "downloadTimeoutMillis", "Total download timeout (seconds)", 1000),
      new Setting("nativeSubmissionSeconds", "nativeSubmissionTimeoutMillis", "Native printer submission timeout (seconds)", 1000));
   private static final List<Setting> CONNECTION = List.of(
      new Setting("handshakeSeconds", "webSocketHandshakeTimeoutMillis", "WebSocket connection timeout (seconds)", 1000),
      new Setting("heartbeatIntervalSeconds", "heartbeatIntervalSeconds", "Connection heartbeat interval (seconds)", 1),
      new Setting("heartbeatTimeoutSeconds", "heartbeatTimeoutSeconds", "Unresponsive connection timeout (seconds)", 1),
      new Setting("reconnectMinSeconds", "reconnectMinDelayMillis", "Initial reconnect delay (seconds)", 1000),
      new Setting("reconnectMaxSeconds", "reconnectMaxDelayMillis", "Maximum reconnect delay (seconds)", 1000),
      new Setting("reconnectJitterPercent", "reconnectJitterPercent", "Reconnect jitter (%)", 1),
      new Setting("ackTimeoutSeconds", "ackTimeoutMillis", "Acknowledgment timeout (seconds)", 1000));
   private static final List<Setting> CONTROL = List.of(
      new Setting("maxFrameKiB", "maxFrameBytes", "Maximum control message (KiB)", 1024),
      new Setting("maxQueuedFrames", "maxQueuedFrames", "Maximum queued control messages", 1),
      new Setting("maxQueuedControlMiB", "maxQueuedFrameBytes", "Maximum queued control message data (MiB)", MEBIBYTE));
   private static final List<Setting> ADMISSION_COUNTS = List.of(
      new Setting("deviceOutstandingJobs", "deviceOutstandingJobs", "Outstanding jobs per device", 1),
      new Setting("tenantOutstandingJobs", "tenantOutstandingJobs", "Outstanding jobs across this tenant", 1),
      new Setting("deviceJobsPerHour", "deviceJobsPerHour", "Jobs allocated per device per hour", 1),
      new Setting("tenantJobsPerHour", "tenantJobsPerHour", "Jobs allocated across this tenant per hour", 1),
      new Setting("devicePrinters", "devicePrinters", "Printers per device", 1),
      new Setting("tenantPrinters", "tenantPrinters", "Printers across this tenant", 1));
   private static final List<Setting> ADMISSION_STORAGE = List.of(
      new Setting("deviceOutstandingMiB", "deviceOutstandingBytes", "Outstanding document data per device (MiB)", MEBIBYTE),
      new Setting("tenantOutstandingMiB", "tenantOutstandingBytes", "Outstanding document data across this tenant (MiB)", MEBIBYTE),
      new Setting("deviceRetainedGiB", "deviceRetainedBytes", "Retained document data per device (GiB)", 1024 * MEBIBYTE),
      new Setting("tenantRetainedGiB", "tenantRetainedBytes", "Retained document data across this tenant (GiB)", 1024 * MEBIBYTE));



   /***************************************************************************
    ** Typed native forms group limits by purpose and reuse normal QQQ permissions.
    ***************************************************************************/
   public static void register(QInstance instance, QAppMetaData app)
   {
      QProcessMetaData process = new QProcessMetaData().withName(SETTINGS).withLabel("Printing connection and limits")
         .withPermissionRules(PrintingProcesses.permissionRules())
         .withStep(backend("prepare"))
         .withStep(new QFrontendStepMetaData().withName("review").withLabel("Current connection policy")
            .withViewFields(List.of(text("transport", "Connection"), text("scope", "Settings scope"), text("revisionLabel", "Current policy")))
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)))
         .withStep(form("documents", "Document limits", DOCUMENTS))
         .withStep(form("transfers", "Document transfers", TRANSFERS))
         .withStep(form("connection", "Connection recovery", CONNECTION))
         .withStep(form("control", "Control channel capacity", CONTROL))
         .withStep(backend("execute"))
         .withStep(new QFrontendStepMetaData().withName("result").withLabel("Result").withViewFields(List.of(text("result", "Result")))
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)));
      instance.addProcess(process);
      app.withChild(process);
      QProcessMetaData admission = new QProcessMetaData().withName(ADMISSION).withLabel("Printing queue and storage limits")
         .withPermissionRules(PrintingProcesses.permissionRules()).withStep(backend("prepare"))
         .withStep(new QFrontendStepMetaData().withName("review").withLabel("Current admission policy")
            .withViewFields(List.of(text("scope", "Settings scope"))).withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)))
         .withStep(form("counts", "Queue, rate and printer limits", ADMISSION_COUNTS))
         .withStep(form("capacity", "Document storage capacity", ADMISSION_STORAGE))
         .withStep(backend("execute"))
         .withStep(new QFrontendStepMetaData().withName("result").withLabel("Result").withViewFields(List.of(text("result", "Result")))
            .withComponent(new QFrontendComponentMetaData().withType(QComponentType.VIEW_FORM)));
      instance.addProcess(admission);
      app.withChild(admission);
   }



   /***************************************************************************
    ** Each backend entrypoint independently checks the current user and tenant.
    ***************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      PrintingControlAuthorization.principal();
      boolean admission = ADMISSION.equals(input.getProcessName());
      if(!SETTINGS.equals(input.getProcessName()) && !admission)
      {
         throw new QException("Unknown printing connection settings action");
      }
      PrintingService service = PrintingProcesses.currentService();
      try
      {
         if("prepare".equals(input.getStepName()))
         {
            JSONObject policy = admission ? service.getAdmissionSettings() : service.getRealtimeSettings();
            for(List<Setting> group : groups(admission))
            {
               for(Setting setting : group)
               {
                  output.addValue(setting.input(), BigDecimal.valueOf(policy.getLong(setting.policy())).divide(BigDecimal.valueOf(setting.unit())));
               }
            }
            output.addValue("transport", "The device opens a two-way WebSocket to this server. Documents use separate HTTPS transfers; job and command polling is not used.");
            output.addValue("scope", admission ? "These limits apply to this tenant. Capacity is checked when jobs and printers are admitted. Changing a limit does not remove existing jobs, documents or history."
               : "These settings apply to this tenant. Saved changes are sent to connected devices. Unsupported device limits require an explicit configuration correction; they are not silently reduced.");
            output.addValue("revisionLabel", admission ? "Tenant admission policy" : "Policy revision " + policy.getLong("revision"));
         }
         else if("execute".equals(input.getStepName()))
         {
            JSONObject requested = new JSONObject();
            for(List<Setting> group : groups(admission))
            {
               for(Setting setting : group)
               {
                  requested.put(setting.policy(), number(input, setting));
               }
            }
            JSONObject saved = admission ? service.saveAdmissionSettings(requested) : service.saveRealtimeSettings(requested);
            output.addValue("result", admission ? "Queue, rate, printer and storage limits saved for this tenant. Existing jobs and history were preserved."
               : "Connection and document limits saved as policy revision " + saved.getLong("revision") + ". Connected devices receive the update over their existing WebSocket; no documents or job history were removed.");
         }
         else
         {
            throw new QException("Unknown printing connection settings step");
         }
      }
      catch(SQLException | PrintingException | IllegalArgumentException exception)
      {
         throw new QException("Connection settings were not saved: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** Numeric conversions reject missing, rounded and overflowing input values.
    ***************************************************************************/
   private static long number(RunBackendStepInput input, Setting setting) throws QException
   {
      Object value = input.getValue(setting.input());
      if(value == null)
      {
         throw new QException("Specify " + setting.label().toLowerCase(java.util.Locale.ROOT));
      }
      try
      {
         return (new BigDecimal(value.toString()).multiply(BigDecimal.valueOf(setting.unit())).longValueExact());
      }
      catch(NumberFormatException | ArithmeticException exception)
      {
         throw new QException(setting.label() + " must be a valid number within the supported range", exception);
      }
   }



   /***************************************************************************
    ** Keep reading and writing the same complete set of advertised policy fields.
    ***************************************************************************/
   private static List<List<Setting>> groups(boolean admission)
   {
      return (admission ? List.of(ADMISSION_COUNTS, ADMISSION_STORAGE) : List.of(DOCUMENTS, TRANSFERS, CONNECTION, CONTROL));
   }



   /***************************************************************************
    ** Human-readable units remain local to the form, never part of wire identity.
    ***************************************************************************/
   private static QFrontendStepMetaData form(String name, String label, List<Setting> settings)
   {
      return (new QFrontendStepMetaData().withName(name).withLabel(label)
         .withFormFields(settings.stream().map(setting -> new QFieldMetaData(setting.input(), setting.unit() == 1 ? QFieldType.INTEGER : QFieldType.DECIMAL)
            .withLabel(setting.label()).withIsRequired(true)).toList())
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM)));
   }



   /***************************************************************************
    ** Every step uses the same native backend and read-only result widgets.
    ***************************************************************************/
   private static QBackendStepMetaData backend(String name)
   {
      return (new QBackendStepMetaData().withName(name).withCode(new QCodeReference(PrintingRealtimeProcesses.class)));
   }



   /***************************************************************************
    ** View fields contain policy descriptions rather than editable identifiers.
    ***************************************************************************/
   private static QFieldMetaData text(String name, String label)
   {
      return (new QFieldMetaData(name, QFieldType.STRING).withLabel(label));
   }



   /***************************************************************************
    ** Unit factors make the form reversible without losing byte precision.
    ***************************************************************************/
   private record Setting(String input, String policy, String label, long unit)
   {
   }
}
