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


import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Tenant-serialized, payload-free resource accounting. Callers use one JDBC
 ** connection and acquire this lock before device, document or inventory locks.
 *******************************************************************************/
final class PrintingAdmission
{
   static final long UNKNOWN_UPLOAD_BYTES = 20L * 1024 * 1024;
   private static final String ACTIVE = "('QUEUED','CLAIMED','RECEIVED','SUBMITTING')";



   /***************************************************************************
    ** Static SQL helpers only.
    ***************************************************************************/
   private PrintingAdmission()
   {
   }



   /***************************************************************************
    ** Add accounting without purging old payloads or assuming unknown bytes zero.
    ***************************************************************************/
   static void initialize(Connection connection, String tenant) throws SQLException
   {
      for(String sql : new String[] {
         "ALTER TABLE printing_document_orphan ADD COLUMN IF NOT EXISTS device_id VARCHAR(36)",
         "ALTER TABLE printing_document_orphan ADD COLUMN IF NOT EXISTS content_length BIGINT",
         "ALTER TABLE printing_document_orphan ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(300)",
         "CREATE TABLE IF NOT EXISTS printing_resource_usage (tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36) NOT NULL, retained_bytes BIGINT NOT NULL, PRIMARY KEY(tenant_id,device_id))",
         "CREATE TABLE IF NOT EXISTS printing_admission_event (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36), created_at VARCHAR(40) NOT NULL)",
         "CREATE INDEX IF NOT EXISTS printing_admission_tenant_time_idx ON printing_admission_event(tenant_id,created_at)",
         "CREATE INDEX IF NOT EXISTS printing_admission_device_time_idx ON printing_admission_event(tenant_id,device_id,created_at)",
         "CREATE INDEX IF NOT EXISTS printing_job_outstanding_tenant_idx ON printing_job(tenant_id,status,content_length)",
         "CREATE INDEX IF NOT EXISTS printing_job_outstanding_device_idx ON printing_job(tenant_id,device_id,status,content_length)",
         "CREATE INDEX IF NOT EXISTS printing_orphan_device_idx ON printing_document_orphan(tenant_id,device_id)",
         "CREATE INDEX IF NOT EXISTS printing_orphan_key_idx ON printing_document_orphan(tenant_id,idempotency_key)",
         "CREATE INDEX IF NOT EXISTS printing_printer_inventory_idx ON printing_printer(device_id,available)",
         "CREATE INDEX IF NOT EXISTS printing_printer_owner_idx ON printing_printer(tenant_id,device_id,id)"
      })
      {
         execute(connection, sql);
      }
      initializeTenant(connection, tenant);
   }



   /***************************************************************************
    ** Existing schema may serve newly configured tenants without another DDL run.
    ***************************************************************************/
   static void initializeTenant(Connection connection, String tenant) throws SQLException
   {
      connection.setAutoCommit(false);
      if(number(connection, "SELECT COUNT(*) FROM printing_resource_usage WHERE tenant_id=? AND device_id=''", tenant) == 0)
      {
         try
         {
            execute(connection, "INSERT INTO printing_resource_usage VALUES (?, '', 0)", tenant);
         }
         catch(SQLException exception)
         {
            if(!"23505".equals(exception.getSQLState()))
            {
               throw exception;
            }
            connection.rollback();
            connection.setAutoCommit(true);
            return;
         }
         try
         {
            lock(connection, tenant);
            long bytes = number(connection, "SELECT COALESCE(SUM(content_length),0) FROM printing_job WHERE tenant_id=? AND content_available=1", tenant)
               + orphanBytes(connection, tenant, null);
            execute(connection, "UPDATE printing_resource_usage SET retained_bytes=? WHERE tenant_id=? AND device_id=''", bytes, tenant);
            execute(connection, "INSERT INTO printing_resource_usage (tenant_id,device_id,retained_bytes) SELECT d.tenant_id,d.id,COALESCE((SELECT SUM(j.content_length) FROM printing_job j WHERE j.tenant_id=d.tenant_id AND j.device_id=d.id AND j.content_available=1),0)+COALESCE((SELECT SUM(COALESCE(o.content_length,20971520)) FROM printing_document_orphan o WHERE o.tenant_id=d.tenant_id AND o.device_id=d.id AND NOT EXISTS(SELECT 1 FROM printing_job j WHERE j.id=o.id)),0) FROM printing_device d WHERE d.tenant_id=?", tenant);
            String cutoff = Instant.now().minusSeconds(3600).toString();
            execute(connection, "INSERT INTO printing_admission_event SELECT id,tenant_id,device_id,created_at FROM printing_job WHERE tenant_id=? AND created_at>=?", tenant, cutoff);
            execute(connection, "INSERT INTO printing_admission_event SELECT o.id,o.tenant_id,o.device_id,o.created_at FROM printing_document_orphan o WHERE o.tenant_id=? AND o.created_at>=? AND NOT EXISTS(SELECT 1 FROM printing_admission_event a WHERE a.id=o.id)", tenant, cutoff);
            connection.commit();
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
         finally
         {
            connection.setAutoCommit(true);
         }
      }
      else
      {
         connection.commit();
         connection.setAutoCommit(true);
      }
   }



   /***************************************************************************
    ** Shared lock serializes capacity decisions across devices and processes.
    ***************************************************************************/
   static void lock(Connection connection, String tenant) throws SQLException
   {
      try(var statement = connection.prepareStatement("SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id=? AND device_id='' FOR UPDATE"))
      {
         statement.setString(1, tenant);
         try(var rows = statement.executeQuery())
         {
            if(!rows.next())
            {
               throw new SQLException("Printing admission accounting has not been initialized");
            }
         }
      }
   }



   /***************************************************************************
    ** Admission precedes every upload/inline insert; rollback restores all usage.
    ***************************************************************************/
   static void reserve(Connection connection, String tenant, String device, String id, int bytes, PrintingAdmissionLimits limits) throws SQLException
   {
      if(number(connection, "SELECT COUNT(*) FROM printing_document_orphan WHERE tenant_id=? AND content_length IS NULL", tenant) != 0)
      {
         throw new PrintingException(429, "An old upload has an unknown document size; complete exact orphan cleanup before admitting new documents");
      }
      if(number(connection, "SELECT COUNT(*) FROM printing_resource_usage WHERE tenant_id=? AND device_id=?", tenant, device) == 0)
      {
         execute(connection, "INSERT INTO printing_resource_usage VALUES (?, ?, 0)", tenant, device);
      }
      String cutoff = Instant.now().minusSeconds(3600).toString();
      execute(connection, "DELETE FROM printing_admission_event WHERE tenant_id=? AND created_at<?", tenant, cutoff);
      checkScope(connection, tenant, null, bytes, limits.tenantOutstandingJobs(), limits.tenantOutstandingBytes(), limits.tenantRetainedBytes(), limits.tenantJobsPerHour(), cutoff);
      checkScope(connection, tenant, device, bytes, limits.deviceOutstandingJobs(), limits.deviceOutstandingBytes(), limits.deviceRetainedBytes(), limits.deviceJobsPerHour(), cutoff);
      execute(connection, "UPDATE printing_resource_usage SET retained_bytes=retained_bytes+? WHERE tenant_id=? AND device_id IN ('',?)", bytes, tenant, device);
      execute(connection, "INSERT INTO printing_admission_event VALUES (?,?,?,?)", id, tenant, device, Instant.now().toString());
   }



   /***************************************************************************
    ** SQL aggregates stay bounded by active capacity and the rolling hour index.
    ***************************************************************************/
   private static void checkScope(Connection connection, String tenant, @Nullable String device, int bytes, long jobLimit, long activeByteLimit, long retainedLimit, long hourlyLimit, String cutoff) throws SQLException
   {
      String predicate = "tenant_id=?" + (device == null ? "" : " AND device_id=?");
      String outstanding = "SELECT content_length FROM printing_job WHERE " + predicate + " AND status IN " + ACTIVE
         + " UNION ALL SELECT COALESCE(content_length,20971520) FROM printing_document_orphan o WHERE " + predicate + " AND NOT EXISTS(SELECT 1 FROM printing_job j WHERE j.id=o.id)";
      Object[] bothScopes = device == null ? new Object[] {tenant, tenant} : new Object[] {tenant, device, tenant, device};
      long jobs = number(connection, "SELECT COUNT(*) FROM (" + outstanding + ") outstanding", bothScopes);
      long activeBytes = number(connection, "SELECT COALESCE(SUM(content_length),0) FROM (" + outstanding + ") outstanding", bothScopes);
      long retained = number(connection, "SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id=? AND device_id=?", tenant, device == null ? "" : device);
      Object[] rateScope = device == null ? new Object[] {tenant, cutoff} : new Object[] {tenant, device, cutoff};
      long rate = number(connection, "SELECT COUNT(*) FROM printing_admission_event WHERE " + predicate + " AND created_at>=?", rateScope);
      String scopeName = device == null ? "tenant" : "device";
      if(jobs >= jobLimit || bytes > activeByteLimit || activeBytes > activeByteLimit - bytes)
      {
         throw new PrintingException(429, "The " + scopeName + " outstanding print-job capacity is full; wait for existing work or ask an administrator to adjust admission limits");
      }
      if(bytes > retainedLimit || retained > retainedLimit - bytes)
      {
         throw new PrintingException(429, "The " + scopeName + " retained print-document capacity is full; review retention and storage capacity before retrying");
      }
      if(rate >= hourlyLimit)
      {
         throw new PrintingException(429, "The " + scopeName + " hourly print-job admission limit is reached; retry later using the same request identity");
      }
   }



   /***************************************************************************
    ** Failed upload reservations stay charged until exact confirmed removal.
    ***************************************************************************/
   private static long orphanBytes(Connection connection, String tenant, @Nullable String device) throws SQLException
   {
      String sql = "SELECT COALESCE(SUM(COALESCE(content_length,20971520)),0) FROM printing_document_orphan o WHERE tenant_id=?"
         + (device == null ? "" : " AND device_id=?") + " AND NOT EXISTS(SELECT 1 FROM printing_job j WHERE j.id=o.id)";
      return device == null ? number(connection, sql, tenant) : number(connection, sql, tenant, device);
   }



   /***************************************************************************
    ** Release only after deletion; an inconsistent counter fails closed.
    ***************************************************************************/
   static void release(Connection connection, String tenant, @Nullable String device, long bytes) throws SQLException
   {
      int expected = device == null ? 1 : 2;
      String predicate = device == null ? "device_id=''" : "device_id IN ('',?)";
      Object[] values = device == null ? new Object[] {bytes, tenant, bytes} : new Object[] {bytes, tenant, device, bytes};
      if(execute(connection, "UPDATE printing_resource_usage SET retained_bytes=retained_bytes-? WHERE tenant_id=? AND " + predicate + " AND retained_bytes>=?", values) != expected)
      {
         throw new SQLException("Printing retained-byte accounting requires administrator reconciliation");
      }
   }



   /***************************************************************************
    ** Cumulative IDs cannot bypass limits by rotating successive snapshots.
    ***************************************************************************/
   static void inventory(Connection connection, String tenant, String device, Set<String> incoming, PrintingAdmissionLimits limits) throws SQLException
   {
      int additions = 0;
      for(String id : incoming)
      {
         if(number(connection, "SELECT COUNT(*) FROM printing_printer WHERE tenant_id=? AND device_id=? AND printer_id=?", tenant, device, id) == 0)
         {
            additions++;
         }
      }
      if(additions > 0 && (number(connection, "SELECT COUNT(*) FROM printing_printer WHERE tenant_id=? AND device_id=?", tenant, device) > limits.devicePrinters() - additions
         || number(connection, "SELECT COUNT(*) FROM printing_printer WHERE tenant_id=?", tenant) > limits.tenantPrinters() - additions))
      {
         throw new PrintingException(429, "Printer history capacity is full; existing printers remain available to update. Ask an administrator to review the cumulative inventory limit");
      }
   }



   /***************************************************************************
    ** Read one scalar; never materialize payloads or an unbounded row list.
    ***************************************************************************/
   private static long number(Connection connection, String sql, @Nullable Object... values) throws SQLException
   {
      try(var statement = connection.prepareStatement(sql))
      {
         for(int index = 0; index < values.length; index++)
         {
            statement.setObject(index + 1, values[index]);
         }
         try(var rows = statement.executeQuery())
         {
            if(!rows.next())
            {
               throw new SQLException("Missing printing resource accounting row");
            }
            return rows.getLong(1);
         }
      }
   }



   /***************************************************************************
    ** All identifiers are fixed SQL; only values come from callers.
    ***************************************************************************/
   private static int execute(Connection connection, String sql, @Nullable Object... values) throws SQLException
   {
      try(var statement = connection.prepareStatement(sql))
      {
         for(int index = 0; index < values.length; index++)
         {
            statement.setObject(index + 1, values[index]);
         }
         return statement.executeUpdate();
      }
   }
}
