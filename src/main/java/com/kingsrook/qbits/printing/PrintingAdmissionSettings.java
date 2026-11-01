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


import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.json.JSONObject;


/*******************************************************************************
 ** Tenant admission overrides are read in the same transaction as admission.
 *******************************************************************************/
public class PrintingAdmissionSettings
{
   private static final List<String> FIELDS = List.of("deviceOutstandingJobs", "tenantOutstandingJobs", "deviceOutstandingBytes", "tenantOutstandingBytes",
      "deviceRetainedBytes", "tenantRetainedBytes", "deviceJobsPerHour", "tenantJobsPerHour", "devicePrinters", "tenantPrinters");
   private static final long MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991L;
   private final DataSource source;
   private final String tenant;
   private final PrintingAdmissionLimits defaults;



   /***************************************************************************
    ** The host supplies immutable defaults until an administrator saves an override.
    ***************************************************************************/
   public PrintingAdmissionSettings(DataSource source, String tenant, PrintingAdmissionLimits defaults)
   {
      this.source = Objects.requireNonNull(source);
      this.tenant = Objects.requireNonNull(tenant);
      this.defaults = Objects.requireNonNull(defaults);
   }



   /***************************************************************************
    ** The enclosing service ensures the tenant settings row before public reads.
    ***************************************************************************/
   public JSONObject get() throws SQLException
   {
      try(Connection connection = source.getConnection())
      {
         return json(effective(connection));
      }
   }



   /***************************************************************************
    ** Validate all limits before one atomic tenant-scoped update.
    ***************************************************************************/
   public JSONObject save(JSONObject requested) throws SQLException
   {
      PrintingAdmissionLimits limits = parse(requested);
      JSONObject value = json(limits);
      try(Connection connection = source.getConnection(); var update = connection.prepareStatement("UPDATE printing_settings SET admission_policy_json=? WHERE tenant_id=?"))
      {
         update.setString(1, value.toString());
         update.setString(2, tenant);
         if(update.executeUpdate() != 1)
         {
            throw new SQLException("The tenant admission settings row is unavailable");
         }
      }
      return value;
   }



   /***************************************************************************
    ** Do not open a nested connection while a queue transaction holds its locks.
    ***************************************************************************/
   public PrintingAdmissionLimits effective(Connection connection) throws SQLException
   {
      try(var query = connection.prepareStatement("SELECT admission_policy_json FROM printing_settings WHERE tenant_id=?"))
      {
         query.setString(1, tenant);
         try(var rows = query.executeQuery())
         {
            if(!rows.next())
            {
               return defaults;
            }
            String stored = rows.getString(1);
            return stored == null ? defaults : parse(new JSONObject(stored));
         }
      }
   }



   /***************************************************************************
    ** Every configured limit is explicit, positive and exactly representable.
    ***************************************************************************/
   private static PrintingAdmissionLimits parse(JSONObject value)
   {
      if(!value.keySet().equals(java.util.Set.copyOf(FIELDS)))
      {
         throw new IllegalArgumentException("Specify all admission limits without unknown fields");
      }
      try
      {
         return new PrintingAdmissionLimits(integer(value, "deviceOutstandingJobs"), integer(value, "tenantOutstandingJobs"),
            number(value, "deviceOutstandingBytes"), number(value, "tenantOutstandingBytes"), number(value, "deviceRetainedBytes"), number(value, "tenantRetainedBytes"),
            integer(value, "deviceJobsPerHour"), integer(value, "tenantJobsPerHour"), integer(value, "devicePrinters"), integer(value, "tenantPrinters"));
      }
      catch(ArithmeticException exception)
      {
         throw new IllegalArgumentException("Admission counts must fit the supported integer range", exception);
      }
   }



   /***************************************************************************
    ** Counts follow the database's integer representation without truncation.
    ***************************************************************************/
   private static int integer(JSONObject value, String field)
   {
      return Math.toIntExact(number(value, field));
   }



   /***************************************************************************
    ** Reject quoted numbers, fractions, overflow and disabled capacity boundaries.
    ***************************************************************************/
   private static long number(JSONObject value, String field)
   {
      if(!(value.opt(field) instanceof Number number))
      {
         throw new IllegalArgumentException(field + " must be a positive integer");
      }
      long parsed = new BigDecimal(number.toString()).longValueExact();
      if(parsed < 1 || parsed > MAX_EXACT_JSON_INTEGER)
      {
         throw new IllegalArgumentException(field + " is outside the supported positive range");
      }
      return parsed;
   }



   /***************************************************************************
    ** Native forms and host defaults share one stable field vocabulary.
    ***************************************************************************/
   private static JSONObject json(PrintingAdmissionLimits value)
   {
      return new JSONObject().put("deviceOutstandingJobs", value.deviceOutstandingJobs()).put("tenantOutstandingJobs", value.tenantOutstandingJobs())
         .put("deviceOutstandingBytes", value.deviceOutstandingBytes()).put("tenantOutstandingBytes", value.tenantOutstandingBytes())
         .put("deviceRetainedBytes", value.deviceRetainedBytes()).put("tenantRetainedBytes", value.tenantRetainedBytes())
         .put("deviceJobsPerHour", value.deviceJobsPerHour()).put("tenantJobsPerHour", value.tenantJobsPerHour())
         .put("devicePrinters", value.devicePrinters()).put("tenantPrinters", value.tenantPrinters());
   }
}
