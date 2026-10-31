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


import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Effective command status never requires an offline device to poll again.
 ** Recorded status and durable receipts remain unchanged by native reads.
 *******************************************************************************/
public class PrintingCommandStatus implements TableCustomizerInterface
{
   private static final Set<String> DEPENDENCIES = Set.of("id", "status", "expires_at");
   private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "EXPIRED", "OUTCOME_UNKNOWN");



   /***************************************************************************
    ** Enrich only the requested virtual field, preserving the caller's projection.
    ***************************************************************************/
   @Override
   public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records) throws QException
   {
      Set<String> selected = input instanceof QueryInput query ? query.getFieldNamesToInclude() : null;
      if(selected != null && !selected.contains("command_status"))
      {
         return (records);
      }
      if(selected != null && !selected.contains("id"))
      {
         throw new QException("Select id with derived command status");
      }
      Map<String, QRecord> dependencies = dependencies(records);
      Instant now = Instant.now();
      for(QRecord record : records)
      {
         QRecord values = dependencies.getOrDefault(record.getValueString("id"), record);
         record.setValue("command_status", effective(values, now));
      }
      return (records);
   }



   /***************************************************************************
    ** Dependency-only native queries preserve permissions and tenant locks.
    ** They are bounded by the selected IDs and cannot recurse into enrichment.
    ***************************************************************************/
   private static Map<String, QRecord> dependencies(List<QRecord> records) throws QException
   {
      ArrayList<String> ids = new ArrayList<>();
      for(QRecord record : records)
      {
         if(!record.getValues().keySet().containsAll(DEPENDENCIES) && record.getValueString("id") != null)
         {
            ids.add(record.getValueString("id"));
         }
      }
      Map<String, QRecord> result = new HashMap<>();
      for(int first = 0; first < ids.size(); first += 200)
      {
         List<String> page = ids.subList(first, Math.min(first + 200, ids.size()));
         QueryInput input = new QueryInput().withTableName(PrintingTables.COMMAND).withFieldNamesToInclude(DEPENDENCIES)
            .withFilter(new QQueryFilter().withCriteria("id", QCriteriaOperator.IN, page).withLimit(page.size()));
         input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
         for(QRecord row : new QueryAction().execute(input).getRecords())
         {
            result.put(row.getValueString("id"), row);
         }
      }
      return (result);
   }



   /***************************************************************************
    ** A terminal receipt wins; a pending command needs a valid expiry observation.
    ***************************************************************************/
   static String effective(QRecord record, Instant now)
   {
      String status = record.getValueString("status");
      if(status != null && TERMINAL.contains(status))
      {
         return (status);
      }
      if(!"PENDING".equals(status))
      {
         return ("UNKNOWN");
      }
      String expiresAt = record.getValueString("expires_at");
      if(expiresAt == null || expiresAt.isBlank())
      {
         return ("UNKNOWN");
      }
      try
      {
         return (Instant.parse(expiresAt).isBefore(now) ? "EXPIRED" : "PENDING");
      }
      catch(DateTimeParseException exception)
      {
         return ("UNKNOWN");
      }
   }
}
