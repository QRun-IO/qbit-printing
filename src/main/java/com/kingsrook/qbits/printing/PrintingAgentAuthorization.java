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
import java.util.Set;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryJoin;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONArray;
import org.json.JSONObject;


/** Secondary proof binds a device to the already authenticated native QQQ principal. */
public final class PrintingAgentAuthorization
{
   public static final String DEVICE_HEADER = "X-QQQ-Device-Id";
   public static final String TOKEN_HEADER = "X-QQQ-Device-Token";


   /** No independent authentication module or permanent service user. */
   private PrintingAgentAuthorization()
   {
   }


   /** Bind only on the explicit endpoint; all ordinary requests require the existing owner. */
   public static String device(String id, String token, boolean bind) throws Exception
   {
      String principal = PrintingControlAuthorization.principal();
      PrintingControlAuthorization.record(PrintingTables.DEVICE, id);
      PrintingProcesses.currentService().authorizeAgent(id, token, principal, bind);
      return (id);
   }


   /** Resolve a wire printer identity through native row security, not a raw SQL lookup. */
   static QRecord printer(String device, String printer) throws QException
   {
      List<QRecord> rows = PrintingControlAuthorization.query(new QueryInput().withTableName(PrintingTables.PRINTER)
         .withFilter(new QQueryFilter(new QFilterCriteria("device_id", QCriteriaOperator.EQUALS, device))
            .withCriteria(new QFilterCriteria("printer_id", QCriteriaOperator.EQUALS, printer)).withLimit(1))).getRecords();
      if(rows.isEmpty())
      {
         throw new QPermissionDeniedException("Printer is not accessible");
      }
      return (PrintingControlAuthorization.record(PrintingTables.PRINTER, rows.getFirst().getValueString("id")));
   }


   /** Inventory absence updates existing rows too, so authorize the complete bounded inventory. */
   static Set<String> inventory(String device) throws Exception
   {
      Set<String> authorized = new java.util.HashSet<>();
      String cursor = null;
      do
      {
         JSONObject page = PrintingProcesses.currentService().printersPage(device, 200, cursor);
         JSONArray printers = page.getJSONArray("printers");
         for(int index = 0; index < printers.length(); index++)
         {
            authorized.add(printer(device, printers.getJSONObject(index).getString("printerId")).getValueString("id"));
            if(authorized.size() > 10000)
            {
               throw new PrintingException(429, "Inventory exceeds the bounded native review limit");
            }
         }
         cursor = page.isNull("nextCursor") ? null : page.getString("nextCursor");
      }
      while(cursor != null);
      // Even an empty inventory must have native table READ access.
      PrintingControlAuthorization.query(new QueryInput().withTableName(PrintingTables.PRINTER)
         .withFilter(new QQueryFilter(new QFilterCriteria("device_id", QCriteriaOperator.EQUALS, device)).withLimit(1)));
      return (authorized);
   }


   /** Select with native row security, then dispatch only that exact authorized identity. */
   static JSONObject claim(String device) throws Exception
   {
      QQueryFilter formats = new QQueryFilter().withBooleanOperator(QQueryFilter.BooleanOperator.OR);
      for(String format : List.of("PDF", "ZPL"))
      {
         formats.withSubFilter(new QQueryFilter(new QFilterCriteria("format", QCriteriaOperator.EQUALS, format))
            .withCriteria(new QFilterCriteria(PrintingTables.PRINTER + ".formats", QCriteriaOperator.CONTAINS, "\"" + format + "\"")));
      }
      List<QRecord> rows = PrintingControlAuthorization.query(new QueryInput().withTableName(PrintingTables.JOB)
         .withQueryJoin(new QueryJoin(PrintingTables.PRINTER))
         .withFilter(new QQueryFilter(new QFilterCriteria("device_id", QCriteriaOperator.EQUALS, device))
            .withCriteria(new QFilterCriteria("status", QCriteriaOperator.IN, List.of("QUEUED", "CLAIMED")))
            .withCriteria(new QFilterCriteria(PrintingTables.PRINTER + ".enabled", QCriteriaOperator.EQUALS, 1))
            .withCriteria(new QFilterCriteria(PrintingTables.PRINTER + ".available", QCriteriaOperator.EQUALS, 1))
            .withSubFilter(formats)
            .withOrderBy(new QFilterOrderBy("created_at", true)).withOrderBy(new QFilterOrderBy("id", true)).withLimit(20))).getRecords();
      for(QRecord row : rows)
      {
         String id = row.getValueString("id");
         PrintingControlAuthorization.job(device, id);
         JSONObject result = PrintingProcesses.currentService().claimForJob(device, id);
         if(!result.isNull("job"))
         {
            return (result);
         }
      }
      return (new JSONObject().put("job", JSONObject.NULL));
   }
}
