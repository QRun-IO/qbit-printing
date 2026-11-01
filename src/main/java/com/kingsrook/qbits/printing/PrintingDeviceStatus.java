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
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Presence and control application are observations, separate from desired state.
 *******************************************************************************/
public class PrintingDeviceStatus implements TableCustomizerInterface
{
   private static final Set<String> DEPENDENCIES = Set.of("id", "enabled", "connection_state", "last_seen_at", "control_last_poll_at", "dispatch_paused", "control_version", "control_revision", "control_applied_revision");



   /***************************************************************************
    ** Query/Get already applied native user permissions and tenant row security.
    ***************************************************************************/
   @Override
   public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records) throws QException
   {
      Set<String> selected = input instanceof QueryInput query ? query.getFieldNamesToInclude() : null;
      if(selected != null && !selected.contains("device_status") && !selected.contains("control_status"))
      {
         return records;
      }
      if(selected != null && !selected.contains("id"))
      {
         throw new QException("Select id together with derived device status fields");
      }
      Map<String, QRecord> dependencies = dependencies(records);
      Instant now = Instant.now();
      for(QRecord record : records)
      {
         QRecord values = dependencies.getOrDefault(record.getValueString("id"), record);
         if(selected == null || selected.contains("device_status"))
         {
            record.setValue("device_status", presence(values, now));
         }
         if(selected == null || selected.contains("control_status"))
         {
            record.setValue("control_status", application(values));
         }
      }
      return records;
   }



   /***************************************************************************
    ** Visible-column projections still need authoritative inputs. Fetch bounded
    ** batches through the same native permissions, never raw SQL or one query
    ** per row. Dependency-only queries do not invoke this enrichment again.
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
         QueryInput input = new QueryInput().withTableName(PrintingTables.DEVICE).withFieldNamesToInclude(DEPENDENCIES)
            .withFilter(new QQueryFilter().withCriteria("id", QCriteriaOperator.IN, page).withLimit(page.size()));
         input.setPermissionEnforcement(com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement.ENFORCE);
         for(QRecord row : new QueryAction().execute(input).getRecords())
         {
            result.put(row.getValueString("id"), row);
         }
      }
      return result;
   }



   /***************************************************************************
    ** A recently seen control poll still proves liveness while printing is paused.
    ***************************************************************************/
   static String presence(QRecord record, Instant now)
   {
      if(record.getValueInteger("enabled") != null && record.getValueInteger("enabled") == 0)
      {
         return "REVOKED";
      }
      if("DISCONNECTED".equals(record.getValueString("connection_state")))
      {
         return "DISCONNECTED";
      }
      Instant heartbeat = timestamp(record.getValueString("last_seen_at"));
      Instant control = timestamp(record.getValueString("control_last_poll_at"));
      Instant seen = heartbeat == null || (control != null && control.isAfter(heartbeat)) ? control : heartbeat;
      if(seen == null || seen.isAfter(now.plusSeconds(30)))
      {
         return "UNKNOWN";
      }
      if(seen.isBefore(now.minusSeconds(90)))
      {
         return "OFFLINE";
      }
      return Integer.valueOf(1).equals(record.getValueInteger("dispatch_paused")) ? "PAUSED" : "ONLINE";
   }



   /***************************************************************************
    ** A server-side successful action is not proof that the agent applied it.
    ***************************************************************************/
   static String application(QRecord record)
   {
      if(!Integer.valueOf(1).equals(record.getValueInteger("control_version")))
      {
         return "UNKNOWN";
      }
      Long desired = record.getValueLong("control_revision");
      Long observed = record.getValueLong("control_applied_revision");
      return desired != null && observed != null && desired.equals(observed) ? "APPLIED" : "PENDING";
   }



   /***************************************************************************
    ** Bad observations must remain unknown rather than imply a healthy device.
    ***************************************************************************/
   private static @Nullable Instant timestamp(@Nullable String value)
   {
      if(value == null || value.isBlank())
      {
         return null;
      }
      try
      {
         return Instant.parse(value);
      }
      catch(DateTimeParseException exception)
      {
         return null;
      }
   }
}
