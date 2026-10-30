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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Durable device registration and print queue. Each instance has one tenant.
 ** Host applications must authorize callers before invoking management methods.
 *******************************************************************************/
public class PrintingService
{
   private static final int MAX_CONTENT_BYTES = 20 * 1024 * 1024;
   private static final String JOB_COLUMNS = "id, tenant_id, device_id, printer_id, title, format, content_hash, content_length, options_json, idempotency_key, created_at, status, message, spooler_job_id, printer_row_id";
   private final DataSource dataSource;
   private final String tenantId;



   /***************************************************************************
    ** Construct the service against the application's printing database.
    ***************************************************************************/
   public PrintingService(DataSource dataSource, String tenantId)
   {
      this.dataSource = dataSource;
      this.tenantId = required(tenantId, "tenantId");
   }



   /***************************************************************************
    ** Install the initial schema; production hosts may run this at deployment.
    ***************************************************************************/
   public void initialize() throws SQLException
   {
      String[] statements = {
         "CREATE TABLE IF NOT EXISTS printing_enrollment (code_hash VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, facility_id VARCHAR(200) NOT NULL, device_name VARCHAR(200) NOT NULL, expires_at VARCHAR(40) NOT NULL)",
         "CREATE TABLE IF NOT EXISTS printing_device (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, facility_id VARCHAR(200) NOT NULL, name VARCHAR(200) NOT NULL, agent_version VARCHAR(100) NOT NULL, token_hash VARCHAR(64) UNIQUE NOT NULL, last_seen_at VARCHAR(40) NOT NULL, enabled INTEGER NOT NULL)",
         "CREATE TABLE IF NOT EXISTS printing_printer (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36) NOT NULL REFERENCES printing_device(id), printer_id VARCHAR(200) NOT NULL, name VARCHAR(300) NOT NULL, driver_name VARCHAR(300), port_name VARCHAR(300), status VARCHAR(40) NOT NULL, formats VARCHAR(100) NOT NULL, enabled INTEGER NOT NULL, last_seen_at VARCHAR(40) NOT NULL, UNIQUE(device_id, printer_id))",
         "ALTER TABLE printing_printer ADD COLUMN IF NOT EXISTS available INTEGER DEFAULT 0 NOT NULL",
         "CREATE TABLE IF NOT EXISTS printing_job (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36) NOT NULL REFERENCES printing_device(id), printer_id VARCHAR(200) NOT NULL, title VARCHAR(300) NOT NULL, format VARCHAR(10) NOT NULL, content_hash VARCHAR(64) NOT NULL, content_base64 TEXT NOT NULL, content_length INTEGER NOT NULL, options_json VARCHAR(2000) NOT NULL, idempotency_key VARCHAR(300) NOT NULL, created_at VARCHAR(40) NOT NULL, status VARCHAR(40) NOT NULL, message VARCHAR(2000), spooler_job_id VARCHAR(100), UNIQUE(tenant_id, idempotency_key))",
         "CREATE TABLE IF NOT EXISTS printing_event (id VARCHAR(100) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES printing_job(id), status VARCHAR(40) NOT NULL, event_json VARCHAR(4000) NOT NULL, created_at VARCHAR(40) NOT NULL)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS printer_row_id VARCHAR(36)",
         "UPDATE printing_job SET printer_row_id=(SELECT p.id FROM printing_printer p WHERE p.device_id=printing_job.device_id AND p.printer_id=printing_job.printer_id AND p.tenant_id=printing_job.tenant_id) WHERE printer_row_id IS NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS computer_name VARCHAR(1024)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS display_name VARCHAR(512)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS description VARCHAR(4096)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS location VARCHAR(512)",
         "ALTER TABLE printing_printer ADD COLUMN IF NOT EXISTS queue_name VARCHAR(1024)",
         "ALTER TABLE printing_printer ADD COLUMN IF NOT EXISTS display_name VARCHAR(512)",
         "ALTER TABLE printing_printer ADD COLUMN IF NOT EXISTS description VARCHAR(4096)",
         "ALTER TABLE printing_printer ADD COLUMN IF NOT EXISTS location VARCHAR(512)",
         "UPDATE printing_device SET computer_name=name WHERE computer_name IS NULL",
         "UPDATE printing_printer SET queue_name=name WHERE queue_name IS NULL",
         "CREATE INDEX IF NOT EXISTS printing_job_dispatch_idx ON printing_job(tenant_id, device_id, status, created_at, id)",
         "CREATE INDEX IF NOT EXISTS printing_job_history_idx ON printing_job(tenant_id, created_at, id)",
         "CREATE INDEX IF NOT EXISTS printing_event_job_idx ON printing_event(job_id)",
         "CREATE INDEX IF NOT EXISTS printing_device_tenant_idx ON printing_device(tenant_id, last_seen_at)",
         "CREATE INDEX IF NOT EXISTS printing_printer_tenant_idx ON printing_printer(tenant_id, status)"
      };
      try(Connection connection = dataSource.getConnection())
      {
         migratePrototypeTableNames(connection);
         for(String sql : statements)
         {
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
               statement.execute();
            }
         }
         widenColumn(connection, "printing_device", "name", 1024);
         widenColumn(connection, "printing_enrollment", "device_name", 1024);
         widenColumn(connection, "printing_printer", "name", 1024);
         widenColumn(connection, "printing_printer", "driver_name", 1024);
         widenColumn(connection, "printing_printer", "port_name", 1024);
         widenColumn(connection, "printing_job", "title", 1024);
      }
   }



   /***************************************************************************
    ** Widen fixed application columns without changing identifiers or content.
    ***************************************************************************/
   private static void widenColumn(Connection connection, String table, String column, int maximum) throws SQLException
   {
      boolean widen = false;
      try(ResultSet columns = connection.getMetaData().getColumns(null, null, "%", "%"))
      {
         while(columns.next())
         {
            if(table.equalsIgnoreCase(columns.getString("TABLE_NAME")) && column.equalsIgnoreCase(columns.getString("COLUMN_NAME")) && columns.getInt("COLUMN_SIZE") < maximum)
            {
               widen = true;
            }
         }
      }
      if(widen)
      {
         try(PreparedStatement statement = connection.prepareStatement("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE VARCHAR(" + maximum + ")"))
         {
            statement.execute();
         }
      }
   }



   /***************************************************************************
    ** The immutable tenant scope used by host authorization and native actions.
    ***************************************************************************/
   public String getTenantId()
   {
      return (tenantId);
   }



   /***************************************************************************
    ** Preserve local prototype data while adopting the QBit table namespace.
    ** Only these fixed names may be renamed; conflicting schemas fail closed.
    ***************************************************************************/
   private void migratePrototypeTableNames(Connection connection) throws SQLException
   {
      Set<String> tables = new java.util.HashSet<>();
      try(ResultSet rows = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"}))
      {
         while(rows.next())
         {
            tables.add(rows.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
         }
      }
      for(String suffix : List.of("enrollment", "device", "printer", "job", "event"))
      {
         if(tables.contains("print_" + suffix) && tables.contains("printing_" + suffix))
         {
            throw new SQLException("Both prototype and namespaced printing tables exist; review the schema before starting");
         }
      }
      for(String suffix : List.of("enrollment", "device", "printer", "job", "event"))
      {
         if(tables.contains("print_" + suffix))
         {
            try(PreparedStatement statement = connection.prepareStatement("ALTER TABLE print_" + suffix + " RENAME TO printing_" + suffix))
            {
               statement.execute();
            }
         }
      }
   }



   /***************************************************************************
    ** Issue a single-use enrollment code. Plaintext is returned only here.
    ***************************************************************************/
   public JSONObject createEnrollment(String facilityId, String deviceName) throws SQLException
   {
      String code = secret();
      String expires = Instant.now().plusSeconds(900).toString();
      try(Connection connection = dataSource.getConnection())
      {
         update(connection, "INSERT INTO printing_enrollment VALUES (?, ?, ?, ?, ?)", hash(code), tenantId, required(facilityId, "facilityId"), required(deviceName, "deviceName"), expires);
      }
      return (new JSONObject().put("enrollmentCode", code).put("expiresAt", expires));
   }



   /***************************************************************************
    ** Consume the enrollment code atomically and issue a device credential.
    ***************************************************************************/
   public JSONObject enroll(JSONObject request) throws SQLException
   {
      String codeHash = hash(required(request.optString("enrollmentCode"), "enrollmentCode"));
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            List<JSONObject> codes = query(connection, "SELECT * FROM printing_enrollment WHERE code_hash=? AND tenant_id=? FOR UPDATE", codeHash, tenantId);
            if(codes.isEmpty() || Instant.parse(codes.getFirst().getString("expires_at")).isBefore(Instant.now()))
            {
               throw new PrintingException(401, "Enrollment code is invalid or expired");
            }
            JSONObject code = codes.getFirst();
            String id = UUID.randomUUID().toString();
            String token = secret();
            String computerName = required(request.optString("deviceName"), "deviceName");
            update(connection, "INSERT INTO printing_device (id, tenant_id, facility_id, name, agent_version, token_hash, last_seen_at, enabled, computer_name) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)", id, tenantId, code.getString("facility_id"), computerName, required(request.optString("agentVersion"), "agentVersion"), hash(token), Instant.now().toString(), computerName);
            update(connection, "DELETE FROM printing_enrollment WHERE code_hash=?", codeHash);
            connection.commit();
            return (new JSONObject().put("protocolVersion", 1).put("deviceId", id).put("deviceToken", token).put("pollIntervalSeconds", 5));
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Resolve an active device credential without exposing its verifier.
    ***************************************************************************/
   public String authenticate(String token) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> devices = query(connection, "SELECT id FROM printing_device WHERE tenant_id=? AND token_hash=? AND enabled=1", tenantId, hash(token == null ? "" : token));
         if(devices.isEmpty())
         {
            throw new PrintingException(401, "Invalid device credential");
         }
         return (devices.getFirst().getString("id"));
      }
   }



   /***************************************************************************
    ** Record reachability; queue/hardware health remains a separate observation.
    ***************************************************************************/
   public JSONObject heartbeat(String deviceId, JSONObject request) throws SQLException
   {
      String now = Instant.now().toString();
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            requireDevice(connection, deviceId);
            update(connection, "UPDATE printing_device SET last_seen_at=?, agent_version=? WHERE id=?", now, required(request.optString("agentVersion"), "agentVersion"), deviceId);
            JSONObject profile = request.optJSONObject("profile");
            if(!request.isNull("profile") && profile == null)
            {
               throw new PrintingException(400, "Device profile must be an object");
            }
            if(profile != null)
            {
               updateProfile(connection, "printing_device", deviceId, profile, "name", "computer_name");
            }
            connection.commit();
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
      return (new JSONObject().put("serverTime", now));
   }



   /***************************************************************************
    ** Replace observed inventory while retaining administrator approval.
    ***************************************************************************/
   public void inventory(String deviceId, JSONArray printers) throws SQLException
   {
      if(printers.length() > 200)
      {
         throw new PrintingException(400, "Too many printers");
      }
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            requireDevice(connection, deviceId);
            update(connection, "UPDATE printing_printer SET status='UNKNOWN', available=0 WHERE device_id=?", deviceId);
            for(int index = 0; index < printers.length(); index++)
            {
               JSONObject printer = printers.getJSONObject(index);
               String printerId = required(printer.optString("printerId"), "printerId");
               String name = required(printer.optString("name"), "name");
               String formats = printer.getJSONArray("formats").toString();
               String status = printer.optString("status", "UNKNOWN");
               String now = Instant.now().toString();
               String driver = optionalText(printer.optString("driverName"), "driverName", 1024);
               String port = optionalText(printer.optString("portName"), "portName", 1024);
               int changed = update(connection, "UPDATE printing_printer SET queue_name=?, name=COALESCE(NULLIF(display_name,''),?), driver_name=?, port_name=?, status=?, formats=?, last_seen_at=?, available=1 WHERE device_id=? AND printer_id=?", name, name, driver, port, status, formats, now, deviceId, printerId);
               if(changed == 0)
               {
                  update(connection, "INSERT INTO printing_printer (id, tenant_id, device_id, printer_id, name, driver_name, port_name, status, formats, enabled, last_seen_at, available, queue_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 1, ?)", UUID.randomUUID().toString(), tenantId, deviceId, printerId, name, driver, port, status, formats, now, name);
               }
               String rowId = query(connection, "SELECT id FROM printing_printer WHERE device_id=? AND printer_id=?", deviceId, printerId).getFirst().getString("id");
               updateProfile(connection, "printing_printer", rowId, printer, "displayName", "queue_name");
            }
            update(connection, "UPDATE printing_device SET last_seen_at=? WHERE id=?", Instant.now().toString(), deviceId);
            connection.commit();
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Enable a queue explicitly; discovery alone never grants print access.
    ***************************************************************************/
   public void enablePrinter(String deviceId, String printerId, boolean enabled) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         if(update(connection, "UPDATE printing_printer SET enabled=? WHERE device_id=? AND printer_id=?", enabled ? 1 : 0, deviceId, printerId) == 0)
         {
            throw new PrintingException(404, "Printer not found");
         }
      }
   }



   /***************************************************************************
    ** Durably enqueue an immutable document. Repeated identical keys return it.
    ***************************************************************************/
   public JSONObject submit(JSONObject request) throws SQLException
   {
      String deviceId = required(request.optString("deviceId"), "deviceId");
      String printerId = required(request.optString("printerId"), "printerId");
      String key = required(request.optString("idempotencyKey"), "idempotencyKey");
      String title = required(request.optString("title"), "title");
      String format = required(request.optString("format"), "format");
      if(!Set.of("PDF", "ZPL").contains(format))
      {
         throw new PrintingException(400, "Only PDF and ZPL are supported");
      }
      byte[] content;
      try
      {
         content = Base64.getDecoder().decode(request.getString("contentBase64"));
      }
      catch(IllegalArgumentException exception)
      {
         throw new PrintingException(400, "Invalid document encoding");
      }
      if(content.length > MAX_CONTENT_BYTES)
      {
         throw new PrintingException(413, "Document exceeds 20971520 bytes");
      }
      if(content.length == 0)
      {
         throw new PrintingException(400, "Document must contain 1 to 20971520 bytes");
      }
      if("PDF".equals(format) && !new String(content, 0, Math.min(5, content.length), StandardCharsets.US_ASCII).equals("%PDF-"))
      {
         throw new PrintingException(400, "PDF header is missing");
      }
      JSONObject options = request.optJSONObject("options");
      if(options == null)
      {
         options = new JSONObject();
      }
      options = new JSONObject(options.toString());
      options.put("copies", options.optInt("copies", 1));
      options.put("scale", options.optString("scale", "ACTUAL_SIZE"));
      if(options.has("paperWidthMm") || options.has("paperHeightMm"))
      {
         throw new PrintingException(400, "Custom paper dimensions are not supported yet; configure the Windows queue");
      }
      if(options.getInt("copies") < 1 || options.getInt("copies") > 100 || !Set.of("ACTUAL_SIZE", "FIT").contains(options.getString("scale")))
      {
         throw new PrintingException(400, "Invalid print options");
      }
      String digest = hash(content);
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            // Serialize submissions per device; the unique key also protects cross-device races.
            requireDevice(connection, deviceId);
            query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
            List<JSONObject> previous = query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=? AND idempotency_key=?", tenantId, key);
            if(!previous.isEmpty())
            {
               JSONObject old = previous.getFirst();
               if(!old.getString("device_id").equals(deviceId) || !old.getString("printer_id").equals(printerId) || !old.getString("content_hash").equals(digest) || !old.getString("format").equals(format) || !new JSONObject(old.getString("options_json")).similar(options))
               {
                  throw new PrintingException(409, "Idempotency key was already used for a different request");
               }
               connection.commit();
               return (job(old));
            }
            List<JSONObject> printers = query(connection, "SELECT * FROM printing_printer WHERE device_id=? AND printer_id=? AND enabled=1 AND available=1", deviceId, printerId);
            if(printers.isEmpty() || !new JSONArray(printers.getFirst().getString("formats")).toList().contains(format))
            {
               throw new PrintingException(400, "Printer is not enabled for this format");
            }
            String id = UUID.randomUUID().toString();
            update(connection, "INSERT INTO printing_job (id, tenant_id, device_id, printer_id, title, format, content_hash, content_base64, content_length, options_json, idempotency_key, created_at, status, printer_row_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)", id, tenantId, deviceId, printerId, title, format, digest, Base64.getEncoder().encodeToString(content), content.length, options.toString(), key, Instant.now().toString(), printers.getFirst().getString("id"));
            JSONObject result = job(query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE id=?", id).getFirst());
            connection.commit();
            return (result);
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Claim one job; replay the same assignment until the device advances it.
    ***************************************************************************/
   public JSONObject testPrint(String deviceId, JSONObject request) throws Exception
   {
      String format = required(request.optString("format"), "format");
      TestPrintProfile profile = TestPrintProfile.resolve(format, request.has("testProfile") ? request.optString("testProfile") : null);
      byte[] bytes = "PDF".equals(format) ? PrintingTestDocuments.pdf(profile) : PrintingTestDocuments.zpl(profile);
      return (submit(new JSONObject().put("deviceId", deviceId).put("printerId", required(request.optString("printerId"), "printerId"))
         .put("title", "QQQ print test").put("format", format).put("contentBase64", Base64.getEncoder().encodeToString(bytes))
         .put("idempotencyKey", "device-test:" + deviceId + ":" + required(request.optString("requestId"), "requestId"))));
   }



   /***************************************************************************
    ** Bounded device-scoped pending work for local inspection and cancellation.
    ***************************************************************************/
   public JSONArray queuedJobs(String deviceId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         JSONArray result = new JSONArray();
         for(JSONObject row : query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=? AND device_id=? AND status='QUEUED' ORDER BY created_at, id LIMIT 50", tenantId, deviceId))
         {
            result.put(job(row));
         }
         return (result);
      }
   }



   /***************************************************************************
    ** Explicitly create a new job; never reset an ambiguous original receipt.
    ***************************************************************************/
   public JSONObject retry(String deviceId, String jobId, JSONObject request) throws SQLException
   {
      JSONObject row;
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         row = deviceJob(connection, deviceId, jobId, true);
      }
      String state = row.getString("status");
      if(!Set.of("FAILED", "OUTCOME_UNKNOWN", "SPOOLED", "SPOOLER_COMPLETED").contains(state))
      {
         throw new PrintingException(409, "Only a finished or uncertain job can be reprinted");
      }
      if(!"FAILED".equals(state) && !request.optBoolean("confirmDuplicateRisk", false))
      {
         throw new PrintingException(409, "Inspect the printer and confirm the risk of duplicate output before reprinting");
      }
      return (submit(new JSONObject().put("deviceId", deviceId).put("printerId", row.getString("printer_id"))
         .put("title", row.getString("title")).put("format", row.getString("format")).put("contentBase64", row.getString("content_base64"))
         .put("options", new JSONObject(row.getString("options_json")))
         .put("idempotencyKey", "device-retry:" + deviceId + ":" + jobId + ":" + required(request.optString("requestId"), "requestId"))));
   }



   /***************************************************************************
    ** Cancel only work that has never been claimed, serialized against claim.
    ***************************************************************************/
   public JSONObject cancel(String deviceId, String jobId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            requireDevice(connection, deviceId);
            query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
            JSONObject row = deviceJob(connection, deviceId, jobId);
            if(!"QUEUED".equals(row.getString("status")))
            {
               throw new PrintingException(409, "Job already reached the device; inspect or cancel its Windows spooler job");
            }
            update(connection, "UPDATE printing_job SET status='FAILED', message='Canceled by operator before dispatch' WHERE id=?", jobId);
            connection.commit();
            return (new JSONObject().put("accepted", true));
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Claim one job; replay the same assignment until the device advances it.
    ***************************************************************************/
   public JSONObject claim(String deviceId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            requireDevice(connection, deviceId);
            query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
            List<JSONObject> candidates = query(connection, "SELECT j." + JOB_COLUMNS.replace(", ", ", j.") + " FROM printing_job j JOIN printing_printer p ON p.device_id=j.device_id AND p.printer_id=j.printer_id WHERE j.tenant_id=? AND j.device_id=? AND p.enabled=1 AND p.available=1 AND j.status IN ('QUEUED','CLAIMED') ORDER BY j.created_at, j.id LIMIT 1", tenantId, deviceId);
            if(candidates.isEmpty())
            {
               connection.commit();
               return (new JSONObject().put("job", JSONObject.NULL));
            }
            JSONObject selected = candidates.getFirst();
            if("QUEUED".equals(selected.getString("status")))
            {
               update(connection, "UPDATE printing_job SET status='CLAIMED' WHERE id=?", selected.getString("id"));
               selected.put("status", "CLAIMED");
            }
            connection.commit();
            return (new JSONObject().put("job", job(selected)));
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Fetch content only for this device's assigned job.
    ***************************************************************************/
   public byte[] content(String deviceId, String jobId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         JSONObject row = deviceJob(connection, deviceId, jobId, true);
         if("QUEUED".equals(row.getString("status")))
         {
            throw new PrintingException(409, "Claim the job before downloading");
         }
         return (Base64.getDecoder().decode(row.getString("content_base64")));
      }
   }



   /***************************************************************************
    ** Record a replay-safe lifecycle event without reprinting ambiguous jobs.
    ***************************************************************************/
   public void event(String deviceId, String jobId, JSONObject event) throws SQLException
   {
      String eventId = required(event.optString("eventId"), "eventId");
      String status = required(event.optString("status"), "status");
      List<String> states = List.of("CLAIMED", "RECEIVED", "SUBMITTING", "SPOOLED", "SPOOLER_COMPLETED");
      if(!Set.of("RECEIVED", "SUBMITTING", "SPOOLED", "SPOOLER_COMPLETED", "FAILED", "OUTCOME_UNKNOWN").contains(status))
      {
         throw new PrintingException(400, "Unsupported job status");
      }
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            requireDevice(connection, deviceId);
            deviceJob(connection, deviceId, jobId);
            query(connection, "SELECT id FROM printing_job WHERE id=? FOR UPDATE", jobId);
            JSONObject row = deviceJob(connection, deviceId, jobId);
            List<JSONObject> existing = query(connection, "SELECT * FROM printing_event WHERE id=?", eventId);
            if(!existing.isEmpty())
            {
               if(!existing.getFirst().getString("job_id").equals(jobId) || !new JSONObject(existing.getFirst().getString("event_json")).similar(event))
               {
                  throw new PrintingException(409, "Event ID was already used");
               }
               connection.commit();
               return;
            }
            String current = row.getString("status");
            if("QUEUED".equals(current) || Set.of("FAILED", "OUTCOME_UNKNOWN", "SPOOLER_COMPLETED").contains(current) || (states.contains(status) && states.indexOf(status) < states.indexOf(current)))
            {
               throw new PrintingException(409, "Invalid state transition");
            }
            update(connection, "INSERT INTO printing_event VALUES (?, ?, ?, ?, ?)", eventId, jobId, status, event.toString(), Instant.now().toString());
            update(connection, "UPDATE printing_job SET status=?, message=?, spooler_job_id=? WHERE id=?", status, event.optString("message"), event.optString("spoolerJobId", row.optString("spooler_job_id")), jobId);
            connection.commit();
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Return public device inventory without credentials.
    ***************************************************************************/
   public JSONArray devices() throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         JSONArray result = new JSONArray();
         for(JSONObject row : query(connection, "SELECT * FROM printing_device WHERE tenant_id=? ORDER BY name", tenantId))
         {
            result.put(new JSONObject().put("deviceId", row.getString("id")).put("facilityId", row.getString("facility_id")).put("deviceName", row.getString("name")).put("computerName", row.getString("computer_name"))
               .put("profile", new JSONObject().put("name", row.optString("display_name")).put("description", row.optString("description")).put("location", row.optString("location")))
               .put("agentVersion", row.getString("agent_version")).put("lastSeenAt", row.getString("last_seen_at")).put("enabled", row.getInt("enabled") == 1));
         }
         return (result);
      }
   }



   /***************************************************************************
    ** Return printers for a device owned by this tenant.
    ***************************************************************************/
   public JSONArray printers(String deviceId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         JSONArray result = new JSONArray();
         for(JSONObject row : query(connection, "SELECT * FROM printing_printer WHERE device_id=? ORDER BY name", deviceId))
         {
            result.put(new JSONObject().put("printerId", row.getString("printer_id")).put("name", row.getString("queue_name")).put("displayName", row.optString("display_name")).put("description", row.optString("description")).put("location", row.optString("location"))
               .put("driverName", row.optString("driver_name")).put("portName", row.optString("port_name")).put("status", row.getString("status")).put("formats", new JSONArray(row.getString("formats"))).put("enabled", row.getInt("enabled") == 1).put("available", row.getInt("available") == 1).put("lastSeenAt", row.getString("last_seen_at")));
         }
         return (result);
      }
   }



   /***************************************************************************
    ** Return recent jobs; content is excluded from management lists.
    ***************************************************************************/
   public JSONArray jobs() throws SQLException
   {
      return (jobsPage(200, null).getJSONArray("jobs"));
   }



   /***************************************************************************
    ** Keyset pagination stays bounded as history grows, without document reads.
    ***************************************************************************/
   public JSONObject jobsPage(int limit, String before) throws SQLException
   {
      if(limit < 1 || limit > 200)
      {
         throw new PrintingException(400, "Job page limit must be between 1 and 200");
      }
      String created = null;
      String id = null;
      if(before != null && !before.isEmpty())
      {
         if(before.length() > 512)
         {
            throw new PrintingException(400, "Invalid job cursor");
         }
         try
         {
            JSONObject cursor = new JSONObject(new String(Base64.getUrlDecoder().decode(before), StandardCharsets.UTF_8));
            created = cursor.getString("createdAt");
            Instant.parse(created);
            id = UUID.fromString(cursor.getString("jobId")).toString();
         }
         catch(RuntimeException exception)
         {
            throw new PrintingException(400, "Invalid job cursor");
         }
      }
      try(Connection connection = dataSource.getConnection())
      {
         JSONArray result = new JSONArray();
         String sql = "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=?";
         List<JSONObject> rows = created == null
            ? query(connection, sql + " ORDER BY created_at DESC, id DESC LIMIT ?", tenantId, limit + 1)
            : query(connection, sql + " AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC, id DESC LIMIT ?", tenantId, created, created, id, limit + 1);
         for(JSONObject row : rows.subList(0, Math.min(limit, rows.size())))
         {
            result.put(job(row));
         }
         JSONObject response = new JSONObject().put("jobs", result).put("nextCursor", JSONObject.NULL);
         if(rows.size() > limit)
         {
            JSONObject last = rows.get(limit - 1);
            JSONObject cursor = new JSONObject().put("createdAt", last.getString("created_at")).put("jobId", last.getString("id"));
            response.put("nextCursor", Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.toString().getBytes(StandardCharsets.UTF_8)));
         }
         return (response);
      }
   }



   /***************************************************************************
    ** Fetch one tenant-scoped job.
    ***************************************************************************/
   public JSONObject getJob(String id) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> rows = query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE id=? AND tenant_id=?", id, tenantId);
         if(rows.isEmpty())
         {
            throw new PrintingException(404, "Job not found");
         }
         return (job(rows.getFirst()));
      }
   }



   /***************************************************************************
    ** Find an accepted request during durable-effect reconciliation.
    ***************************************************************************/
   public JSONObject findByIdempotencyKey(String key) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> rows = query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=? AND idempotency_key=?", tenantId, key);
         return (rows.isEmpty() ? null : job(rows.getFirst()));
      }
   }



   /***************************************************************************
    ** Enforce tenant ownership even for direct Java service callers.
    ***************************************************************************/
   private void requireDevice(Connection connection, String deviceId) throws SQLException
   {
      if(query(connection, "SELECT id FROM printing_device WHERE id=? AND tenant_id=? AND enabled=1", deviceId, tenantId).isEmpty())
      {
         throw new PrintingException(404, "Device not found");
      }
   }



   /***************************************************************************
    ** Restrict content and event access to the assigned device.
    ***************************************************************************/
   private JSONObject deviceJob(Connection connection, String deviceId, String jobId) throws SQLException
   {
      return (deviceJob(connection, deviceId, jobId, false));
   }



   /***************************************************************************
    ** Only content download and explicit retry need the stored document bytes.
    ***************************************************************************/
   private JSONObject deviceJob(Connection connection, String deviceId, String jobId, boolean includeContent) throws SQLException
   {
      List<JSONObject> rows = query(connection, "SELECT " + JOB_COLUMNS + (includeContent ? ", content_base64" : "") + " FROM printing_job WHERE id=? AND tenant_id=? AND device_id=?", jobId, tenantId, deviceId);
      if(rows.isEmpty())
      {
         throw new PrintingException(404, "Job not found");
      }
      return (rows.getFirst());
   }



   /***************************************************************************
    ** Translate durable columns to the versioned protocol representation.
    ***************************************************************************/
   private static JSONObject job(JSONObject row)
   {
      return (new JSONObject().put("jobId", row.getString("id")).put("deviceId", row.getString("device_id")).put("printerId", row.getString("printer_id")).put("title", row.getString("title")).put("format", row.getString("format")).put("contentSha256", row.getString("content_hash")).put("contentLength", row.getInt("content_length")).put("options", new JSONObject(row.getString("options_json"))).put("createdAt", row.getString("created_at")).put("status", row.getString("status")).put("message", row.optString("message")).put("spoolerJobId", row.optString("spooler_job_id")));
   }



   /***************************************************************************
    ** Run a bounded, parameterized query with lower-case column names.
    ***************************************************************************/
   private static List<JSONObject> query(Connection connection, String sql, Object... values) throws SQLException
   {
      try(PreparedStatement statement = connection.prepareStatement(sql))
      {
         bind(statement, values);
         try(ResultSet rows = statement.executeQuery())
         {
            List<JSONObject> result = new ArrayList<>();
            while(rows.next())
            {
               JSONObject row = new JSONObject();
               for(int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
               {
                  row.put(rows.getMetaData().getColumnLabel(index).toLowerCase(java.util.Locale.ROOT), rows.getObject(index));
               }
               result.add(row);
            }
            return (result);
         }
      }
   }



   /***************************************************************************
    ** Execute a parameterized mutation.
    ***************************************************************************/
   private static int update(Connection connection, String sql, Object... values) throws SQLException
   {
      try(PreparedStatement statement = connection.prepareStatement(sql))
      {
         bind(statement, values);
         return (statement.executeUpdate());
      }
   }



   /***************************************************************************
    ** Bind values without SQL interpolation.
    ***************************************************************************/
   private static void bind(PreparedStatement statement, Object[] values) throws SQLException
   {
      for(int index = 0; index < values.length; index++)
      {
         statement.setObject(index + 1, values[index]);
      }
   }



   /***************************************************************************
    ** Reject missing caller data before persistence.
    ***************************************************************************/
   private static String required(String value, String name)
   {
      int maximum = switch(name)
      {
         case "name", "deviceName", "title" -> 1024;
         case "tenantId", "facilityId", "printerId" -> 200;
         case "agentVersion", "eventId" -> 100;
         default -> 300;
      };
      if(value == null || value.isBlank() || value.length() > maximum)
      {
         throw new PrintingException(400, "Invalid " + name);
      }
      return (value);
   }



   /***************************************************************************
    ** Client-owned metadata synchronizes without changing technical identity.
    ***************************************************************************/
   private void updateProfile(Connection connection, String table, String id, JSONObject profile, String nameKey, String physicalColumn) throws SQLException
   {
      String name = profile.isNull(nameKey) ? null : optionalText(profile.getString(nameKey), nameKey, 512).strip();
      String description = profile.isNull("description") ? null : optionalText(profile.getString("description"), "description", 4096);
      String location = profile.isNull("location") ? null : optionalText(profile.getString("location"), "location", 512).strip();
      update(connection, "UPDATE " + table + " SET display_name=COALESCE(?,display_name), description=COALESCE(?,description), location=COALESCE(?,location), name=COALESCE(NULLIF(COALESCE(?,display_name),'')," + physicalColumn + ") WHERE id=? AND tenant_id=?", name, description, location, name, id, tenantId);
   }



   /***************************************************************************
    ** Reject oversized text before persistence; explicit empty values may clear.
    ***************************************************************************/
   private static String optionalText(String value, String name, int maximum)
   {
      if(value.length() > maximum || value.indexOf('\0') >= 0)
      {
         throw new PrintingException(400, "Invalid " + name + "; maximum " + maximum + " characters");
      }
      return (value);
   }



   /***************************************************************************
    ** Generate independent, unguessable enrollment and device credentials.
    ***************************************************************************/
   private static String secret()
   {
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      return (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
   }



   /***************************************************************************
    ** Hash credential text; plaintext credentials never enter the database.
    ***************************************************************************/
   private static String hash(String value)
   {
      return (hash(value.getBytes(StandardCharsets.UTF_8)));
   }



   /***************************************************************************
    ** Produce the content integrity digest required by protocol v1.
    ***************************************************************************/
   private static String hash(byte[] value)
   {
      try
      {
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
      }
      catch(java.security.NoSuchAlgorithmException exception)
      {
         throw new IllegalStateException(exception);
      }
   }
}



