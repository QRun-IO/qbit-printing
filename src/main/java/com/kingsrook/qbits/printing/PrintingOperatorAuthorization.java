/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2026.  Kingsrook, LLC
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Current-user catalog and submission boundary shared by HTTP and host processes.
 *******************************************************************************/
public final class PrintingOperatorAuthorization
{
   /***************************************************************************
    ** No cached user authority or alternate queue.
    ***************************************************************************/
   private PrintingOperatorAuthorization()
   {
   }


   /***************************************************************************
    ** Resolve the authenticated actor and current tenant before every operation.
    ***************************************************************************/
   public static String principal() throws QException
   {
      if(QContext.getQSession() == null || QContext.getQSession().getUser() == null
         || QContext.getQSession().getUser().getIdReference() == null || QContext.getQSession().getUser().getIdReference().isBlank())
      {
         throw new QAuthenticationException("Sign in to continue");
      }
      new PrintingOperatorPermission().checkPermissionsThrowing(new RunBackendStepInput(), null);
      PrintingProcesses.currentService();
      return QContext.getQSession().getUser().getIdReference();
   }


   /***************************************************************************
    ** Native table and record checks apply even inside another backend process.
    ***************************************************************************/
   private static QRecord record(String table, String id) throws QException
   {
      CallContext caller = capture();
      QueryInput scoped = new QueryInput().withTableName(table).withFilter(new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, id))
         .withCriteria(new QFilterCriteria("tenant_id", QCriteriaOperator.EQUALS, PrintingProcesses.currentService().getTenantId())).withLimit(1));
      scoped.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      if(new QueryAction().execute(scoped).getRecords().isEmpty())
      {
         throw new QPermissionDeniedException("Print destination is not accessible");
      }
      caller.check();
      GetInput input = new GetInput();
      input.setTableName(table);
      input.setPrimaryKey(id);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      QRecord row = new GetAction().execute(input).getRecord();
      PermissionsHelper.checkTablePermissionThrowing(input, TablePermissionSubType.READ);
      caller.check();
      if(row == null || !id.equals(row.getValueString("id")))
      {
         throw new QPermissionDeniedException("Print destination is not accessible");
      }
      return row;
   }


   /***************************************************************************
    ** Authorize the exact native printer row and its enabled device each time.
    ** Callers use the returned wire printer identity only for this operation.
    ***************************************************************************/
   public static QRecord destination(String printerRowId) throws QException
   {
      CallContext caller = capture();
      QRecord printer = record(PrintingTables.PRINTER, printerRowId);
      QRecord device = record(PrintingTables.DEVICE, printer.getValueString("device_id"));
      if(!Integer.valueOf(1).equals(printer.getValueInteger("enabled")) || !Integer.valueOf(1).equals(printer.getValueInteger("available"))
         || !Integer.valueOf(1).equals(device.getValueInteger("enabled")))
      {
         throw new QPermissionDeniedException("Print destination is not approved and exposed");
      }
      requireDestinationRead();
      caller.check();
      return printer;
   }


   /***************************************************************************
    ** Shared retained-source boundary also requires the exact supported format.
    ***************************************************************************/
   public static QRecord printer(String printerRowId, String format) throws QException
   {
      QRecord printer = destination(printerRowId);
      if(!Set.of("PDF", "ZPL").contains(format) || !new JSONArray(printer.getValueString("formats")).toList().contains(format))
      {
         throw new PrintingException(400, "Printer does not support this document format");
      }
      return printer;
   }


   /***************************************************************************
    ** Reconciliation still checks current row authority after exposure changes.
    ** The caller must first establish its own accepted request/source binding.
    ***************************************************************************/
   public static QRecord authorizeDestinationHistory(String printerRowId) throws QException
   {
      CallContext caller = capture();
      QRecord printer = record(PrintingTables.PRINTER, printerRowId);
      record(PrintingTables.DEVICE, printer.getValueString("device_id"));
      requireDestinationRead();
      caller.check();
      return printer;
   }


   /***************************************************************************
    ** Page through authorized rows; hidden parent devices never leak through joins.
    ***************************************************************************/
   public static JSONObject catalog(int limit, String after) throws QException
   {
      CallContext caller = capture();
      if(limit < 1 || limit > 200 || (after != null && !after.matches("[A-Za-z0-9._-]{1,200}")))
      {
         throw new PrintingException(400, "Invalid catalog page");
      }
      QQueryFilter filter = new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, 1))
         .withCriteria(new QFilterCriteria("available", QCriteriaOperator.EQUALS, 1))
         .withCriteria(new QFilterCriteria("tenant_id", QCriteriaOperator.EQUALS, PrintingProcesses.currentService().getTenantId()))
         .withOrderBy(new QFilterOrderBy("id", true)).withLimit(limit + 1);
      if(after != null)
      {
         filter.withCriteria(new QFilterCriteria("id", QCriteriaOperator.GREATER_THAN, after));
      }
      QueryInput input = new QueryInput().withTableName(PrintingTables.PRINTER).withFilter(filter);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      List<QRecord> rows = new QueryAction().execute(input).getRecords();
      caller.check();
      JSONArray printers = new JSONArray();
      int consumed = Math.min(limit, rows.size());
      for(QRecord candidate : rows.subList(0, consumed))
      {
         try
         {
            QRecord printer = destination(candidate.getValueString("id"));
            QRecord device = record(PrintingTables.DEVICE, printer.getValueString("device_id"));
            requireDestinationRead();
            caller.check();
            printers.put(new JSONObject().put("printerRowId", printer.getValueString("id")).put("deviceId", printer.getValueString("device_id"))
               .put("printerId", printer.getValueString("printer_id")).put("name", label(printer, "display_name", "name", "queue_name", "id"))
               .put("deviceName", label(device, "display_name", "name", "computer_name", "id")).put("description", printer.getValueString("description")).put("location", printer.getValueString("location"))
               .put("status", printer.getValueString("status")).put("formats", new JSONArray(printer.getValueString("formats")))
               .put("mediaProfiles", new JSONArray(printer.getValueString("media_profiles_json") == null ? "[]" : printer.getValueString("media_profiles_json"))));
         }
         catch(QPermissionDeniedException hidden)
         {
            // Native parent-row security can legitimately hide a candidate.
         }
         caller.check();
      }
      caller.check();
      return new JSONObject().put("printers", printers)
         .put("nextCursor", rows.size() > limit ? rows.get(consumed - 1).getValueString("id") : JSONObject.NULL);
   }


   /***************************************************************************
    ** The process permission remains authoritative for all byte submissions.
    ***************************************************************************/
   public static JSONObject submit(JSONObject request) throws Exception
   {
      CallContext caller = capture();
      requireSubmission();
      request = new JSONObject(request.toString());
      if(!Set.of("requestId", "printerRowId", "title", "format", "contentBase64", "options").containsAll(request.keySet()))
      {
         throw new PrintingException(400, "Unknown operator print request field");
      }
      QRecord printer = printer(request.getString("printerRowId"), request.getString("format"));
      caller.check();
      JSONObject prepared = new JSONObject(request.toString());
      prepared.put("deviceId", printer.getValueString("device_id")).put("printerId", printer.getValueString("printer_id"))
         .put("idempotencyKey", requestKey(caller.actor(), request.getString("requestId")));
      try
      {
         return caller.service().submitAuthorized(prepared, request.getString("printerRowId"), () ->
         {
            caller.check();
            requireSubmission();
            QRecord current = printer(prepared.getString("printerRowId"), prepared.getString("format"));
            if(!prepared.getString("deviceId").equals(current.getValueString("device_id")) || !prepared.getString("printerId").equals(current.getValueString("printer_id")))
            {
               throw new QPermissionDeniedException("Print destination changed during submission");
            }
            requireSubmission();
            caller.check();
         });
      }
      catch(java.sql.SQLException exception)
      {
         if(exception.getCause() instanceof QException denied)
         {
            throw denied;
         }
         throw exception;
      }
   }


   /***************************************************************************
    ** Only the current actor's exact request can be reconciled; no job enumeration.
    ***************************************************************************/
   public static JSONObject observe(String requestId) throws Exception
   {
      CallContext caller = capture();
      requireStorage(false);
      PrintingService.OperatorAcceptance accepted = caller.service().findOperatorAcceptance(requestKey(caller.actor(), requestId));
      if(accepted == null)
      {
         throw new PrintingException(404, "Print request not found");
      }
      JSONObject job = accepted.job();
      // Historical acceptance remains observable if exposure changes, but current row authority is mandatory.
      QRecord printer = authorizeDestinationHistory(accepted.printerRowId());
      if(!job.getString("deviceId").equals(printer.getValueString("device_id")) || !job.getString("printerId").equals(printer.getValueString("printer_id")))
      {
         throw new QPermissionDeniedException("Accepted print destination changed");
      }
      requireDestinationRead();
      requireStorage(false);
      caller.check();
      return job;
   }


   /** Recheck table, credential and channel permissions after both row customizers. */
   private static void requireDestinationRead() throws QException
   {
      for(String table : List.of(PrintingTables.PRINTER, PrintingTables.DEVICE))
      {
         GetInput input = new GetInput();
         input.setTableName(table);
         input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         PermissionsHelper.checkTablePermissionThrowing(input, TablePermissionSubType.READ);
      }
   }


   /***************************************************************************
    ** Snapshot only authority and routing context, never session credentials.
    ***************************************************************************/
   private static CallContext capture() throws QException
   {
      String actor = principal();
      QSession session = QContext.getQSession();
      Map<String, List<Serializable>> keys = new HashMap<>();
      if(session.getSecurityKeyValues() != null)
      {
         session.getSecurityKeyValues().forEach((key, values) -> keys.put(key, values == null ? null : new ArrayList<>(values)));
      }
      return new CallContext(QContext.getQInstance(), session, session.getUser(), actor, PrintingProcesses.currentService(), keys,
         session.getBackendVariants() == null ? null : new HashMap<>(session.getBackendVariants()));
   }


   /***************************************************************************
    ** Native customizers cannot replace the caller or its row-security scope.
    ** Permissions and credential/channel ceilings are separately checked live.
    ***************************************************************************/
   private record CallContext(QInstance instance, QSession session, QUser user, String actor, PrintingService service,
      Map<String, List<Serializable>> keys, Map<String, Serializable> variants)
   {
      /** Fail before consuming callback results under a different native authority. */
      void check() throws QException
      {
         if(instance != QContext.getQInstance() || session != QContext.getQSession() || user != session.getUser()
            || !actor.equals(principal()) || service != PrintingProcesses.currentService()
            || !keys.equals(session.getSecurityKeyValues() == null ? Map.of() : session.getSecurityKeyValues())
            || !Objects.equals(variants, session.getBackendVariants()))
         {
            throw new QPermissionDeniedException("Printing authority changed during the operation");
         }
      }
   }


   /***************************************************************************
    ** Trusted domain writes retain process, credential and channel ceilings.
    ***************************************************************************/
   public static void requireSubmission() throws QException
   {
      principal();
      PermissionsHelper.checkProcessPermissionThrowing(new RunBackendStepInput().withProcessName(PrintingOperatorProcesses.SUBMIT), PrintingOperatorProcesses.SUBMIT);
      requireStorage(true);
   }


   /***************************************************************************
    ** Scope ceilings do not grant generic job table reads or writes.
    ***************************************************************************/
   private static void requireStorage(boolean mutation) throws QException
   {
      var instance = QContext.getQInstance();
      var table = instance.getTable(PrintingTables.JOB);
      if(table == null)
      {
         throw new QException("Printing job metadata is unavailable");
      }
      var rules = PermissionsHelper.getEffectivePermissionRules(table, instance);
      if(PermissionsHelper.isDeniedForClientChannel(table, rules)
         || (mutation && PermissionsHelper.isWriteDeniedForClientChannel(table, rules))
         || PermissionsHelper.isOutsideCredentialScope(table, mutation ? TablePermissionSubType.INSERT : TablePermissionSubType.READ))
      {
         throw new QPermissionDeniedException("Current credential or client channel cannot access print acceptance");
      }
   }


   /***************************************************************************
    ** Stable actor namespace protects request lookup without storing user credentials.
    ***************************************************************************/
   private static String requestKey(String actor, String requestId) throws Exception
   {
      if(!requestId.matches("[A-Za-z0-9._:-]{1,128}"))
      {
         throw new PrintingException(400, "Invalid print request identity");
      }
      return "operator:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actor.getBytes(StandardCharsets.UTF_8))) + ":" + requestId;
   }


   /***************************************************************************
    ** Legacy unnamed rows retain an honest identity fallback, never guessed media.
    ***************************************************************************/
   private static String label(QRecord record, String... fields)
   {
      for(String field : fields)
      {
         String value = record.getValueString(field);
         if(value != null && !value.isBlank())
         {
            return value;
         }
      }
      throw new PrintingException(409, "Print destination has no stable identity");
   }
}
