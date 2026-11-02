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


import java.util.Set;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONObject;


/** Common current-user authorization for native UI and semantic API management. */
public final class PrintingControlAuthorization
{
   /** Static authorization facade. */
   private PrintingControlAuthorization()
   {
   }


   /** Core user references, never emails or request-body identity, determine attribution. */
   public static String principal() throws QException
   {
      if(QContext.getQSession() == null || QContext.getQSession().getUser() == null
         || QContext.getQSession().getUser().getIdReference() == null || QContext.getQSession().getUser().getIdReference().isBlank())
      {
         throw new QAuthenticationException("Sign in to continue");
      }
      new PrintingAdminPermission().checkPermissionsThrowing(new RunBackendStepInput(), null);
      PrintingProcesses.currentService();
      return (QContext.getQSession().getUser().getIdReference());
   }


   /** Native GetAction applies table access, field protection, and current record security. */
   public static QRecord record(String table, String id) throws QException
   {
      principal();
      if(id == null || id.isBlank())
      {
         throw new QPermissionDeniedException("Record is not accessible");
      }
      GetInput input = new GetInput();
      input.setTableName(table);
      input.setPrimaryKey(id);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      QRecord row = new GetAction().execute(input).getRecord();
      if(row == null)
      {
         throw new QPermissionDeniedException("Record is not accessible");
      }
      return (row);
   }


   /** Operations without an existing row still require native table read authority. */
   public static void table(String table) throws QException
   {
      principal();
      query(new QueryInput().withTableName(table).withFilter(new QQueryFilter().withLimit(1)));
   }


   /** Explicit enforcement remains effective inside native process action scopes. */
   static QueryOutput query(QueryInput input) throws QException
   {
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      return (new QueryAction().execute(input));
   }


   /** A job operation must also authorize its device and exact printer record. */
   public static QRecord job(String deviceId, String jobId) throws QException
   {
      QRecord row = record(PrintingTables.JOB, jobId);
      if(!deviceId.equals(row.getValueString("device_id")))
      {
         throw new QPermissionDeniedException("Job is not assigned to this device");
      }
      record(PrintingTables.DEVICE, deviceId);
      QRecord printer = record(PrintingTables.PRINTER, row.getValueString("printer_row_id"));
      if(!deviceId.equals(printer.getValueString("device_id")))
      {
         throw new QPermissionDeniedException("Printer is not assigned to this device");
      }
      return (row);
   }


   /** The native process permission remains the domain write authority; generic table writes stay disabled. */
   public static void process(String name) throws QException
   {
      principal();
      PermissionsHelper.checkProcessPermissionThrowing(new RunBackendStepInput().withProcessName(name), name);
   }


   /** Current users can manage authorized devices without impersonating the enrolled agent owner. */
   public static JSONObject deviceAction(String deviceId, JSONObject request) throws Exception
   {
      String actor = principal();
      record(PrintingTables.DEVICE, deviceId);
      PrintingDeviceProcesses.Action selected = null;
      for(PrintingDeviceProcesses.Action action : PrintingDeviceProcesses.Action.values())
      {
         if(action.wire.equals(request.optString("action")))
         {
            selected = action;
         }
      }
      if(selected == null)
      {
         throw new PrintingException(400, "Invalid device action");
      }
      process(selected.process);
      PrintingService service = PrintingProcesses.currentService();
      String existing = service.existingDeviceAction(deviceId, request.optString("requestId"));
      if(existing != null)
      {
         QRecord receipt = record(PrintingTables.COMMAND, existing);
         if(receipt.getValueString("job_id") != null)
         {
            job(deviceId, receipt.getValueString("job_id"));
         }
         return (service.deviceAction(deviceId, request, actor));
      }
      Set<String> queue = null;
      if("CLEAR_QUEUE".equals(selected.wire))
      {
         queue = service.queuedJobIdsForControl(deviceId);
         Set<String> printers = new java.util.HashSet<>();
         for(String id : queue)
         {
            QRecord queued = record(PrintingTables.JOB, id);
            if(!deviceId.equals(queued.getValueString("device_id")))
            {
               throw new QPermissionDeniedException("Job is not assigned to this device");
            }
            printers.add(queued.getValueString("printer_row_id"));
         }
         for(String printerId : printers)
         {
            QRecord printer = record(PrintingTables.PRINTER, printerId);
            if(!deviceId.equals(printer.getValueString("device_id")))
            {
               throw new QPermissionDeniedException("Printer is not assigned to this device");
            }
         }
      }
      if("CANCEL_SPOOL".equals(selected.wire))
      {
         job(deviceId, request.optString("jobId"));
      }
      return (service.deviceAction(deviceId, request, actor, queue));
   }
}
