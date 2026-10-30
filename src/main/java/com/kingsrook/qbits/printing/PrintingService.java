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
   static final String CANCELED_BEFORE_DISPATCH = "Canceled by operator before dispatch";
   private static final int MAX_CONTENT_BYTES = 20 * 1024 * 1024;
   private static final String JOB_COLUMNS = "id, tenant_id, device_id, printer_id, title, format, content_hash, content_length, options_json, idempotency_key, created_at, status, message, spooler_job_id, printer_row_id";
   private final DataSource dataSource;
   private final String tenantId;
   private PrintingDocumentStorage documentStorage;
   private String storageTableName;
   private PrintingRetentionPolicy defaultRetentionPolicy = new PrintingRetentionPolicy(true, 365, 200);



   /***************************************************************************
    ** Bind a host-owned storage table before exposing this service to requests.
    ***************************************************************************/
   public void configureStorage(com.kingsrook.qqq.backend.core.model.metadata.QInstance instance, String table) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      try
      {
         configureStorage(new PrintingDocumentStorage(instance, tenantId), table);
      }
      catch(IllegalArgumentException exception)
      {
         throw new com.kingsrook.qqq.backend.core.exceptions.QException(exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** Package-scoped adapter seam supports storage failure tests without S3.
    ***************************************************************************/
   void configureStorage(PrintingDocumentStorage storage, String table)
   {
      if(table != null)
      {
         storage.binding(table);
      }
      documentStorage = storage;
      storageTableName = table;
   }



   /***************************************************************************
    ** Defaults are host-owned; a persisted administrator setting takes priority.
    ***************************************************************************/
   public PrintingService withRetentionPolicy(PrintingRetentionPolicy policy)
   {
      defaultRetentionPolicy = java.util.Objects.requireNonNull(policy);
      return (this);
   }



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
         "ALTER TABLE printing_enrollment ADD COLUMN IF NOT EXISTS consumed INTEGER DEFAULT 0 NOT NULL",
         "CREATE TABLE IF NOT EXISTS printing_device (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, facility_id VARCHAR(200) NOT NULL, name VARCHAR(200) NOT NULL, agent_version VARCHAR(100) NOT NULL, token_hash VARCHAR(64) UNIQUE NOT NULL, last_seen_at VARCHAR(40) NOT NULL, enabled INTEGER NOT NULL)",
         "CREATE TABLE IF NOT EXISTS printing_printer (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36) NOT NULL REFERENCES printing_device(id), printer_id VARCHAR(200) NOT NULL, name VARCHAR(300) NOT NULL, driver_name VARCHAR(300), port_name VARCHAR(300), status VARCHAR(40) NOT NULL, formats VARCHAR(100) NOT NULL, enabled INTEGER NOT NULL, last_seen_at VARCHAR(40) NOT NULL, UNIQUE(device_id, printer_id))",
         "ALTER TABLE printing_printer ADD COLUMN IF NOT EXISTS available INTEGER DEFAULT 0 NOT NULL",
         "CREATE TABLE IF NOT EXISTS printing_job (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36) NOT NULL REFERENCES printing_device(id), printer_id VARCHAR(200) NOT NULL, title VARCHAR(300) NOT NULL, format VARCHAR(10) NOT NULL, content_hash VARCHAR(64) NOT NULL, content_base64 TEXT NOT NULL, content_length INTEGER NOT NULL, options_json VARCHAR(2000) NOT NULL, idempotency_key VARCHAR(300) NOT NULL, created_at VARCHAR(40) NOT NULL, status VARCHAR(40) NOT NULL, message VARCHAR(2000), spooler_job_id VARCHAR(100), UNIQUE(tenant_id, idempotency_key))",
         "CREATE TABLE IF NOT EXISTS printing_event (id VARCHAR(100) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES printing_job(id), status VARCHAR(40) NOT NULL, event_json VARCHAR(4000) NOT NULL, created_at VARCHAR(40) NOT NULL)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS printer_row_id VARCHAR(36)",
         "ALTER TABLE printing_job ALTER COLUMN content_base64 DROP NOT NULL",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS content_available INTEGER DEFAULT 1 NOT NULL",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS completed_at VARCHAR(40)",
         "UPDATE printing_job SET completed_at=(SELECT MAX(e.created_at) FROM printing_event e WHERE e.job_id=printing_job.id AND e.status IN ('SPOOLED','SPOOLER_COMPLETED')) WHERE completed_at IS NULL AND status IN ('SPOOLED','SPOOLER_COMPLETED')",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS content_storage_table VARCHAR(200)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS content_reference VARCHAR(500)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS content_binding VARCHAR(64)",
         "CREATE TABLE IF NOT EXISTS printing_settings (tenant_id VARCHAR(200) PRIMARY KEY, retention_days INTEGER NOT NULL, retention_enabled INTEGER NOT NULL, last_run_at VARCHAR(40), last_run_count INTEGER, last_run_error VARCHAR(500))",
         "ALTER TABLE printing_settings ADD COLUMN IF NOT EXISTS lease_token VARCHAR(36)",
         "ALTER TABLE printing_settings ADD COLUMN IF NOT EXISTS lease_expires_at VARCHAR(40)",
         "CREATE TABLE IF NOT EXISTS printing_document_orphan (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, storage_table VARCHAR(200) NOT NULL, reference VARCHAR(500) NOT NULL, binding VARCHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL)",
         "CREATE INDEX IF NOT EXISTS printing_orphan_tenant_idx ON printing_document_orphan(tenant_id, created_at, id)",
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
         "CREATE INDEX IF NOT EXISTS printing_job_retention_completed_idx ON printing_job(tenant_id, content_available, status, completed_at, id)",
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
         widenColumn(connection, "printing_job", "message", 2048);
         widenColumn(connection, "printing_job", "spooler_job_id", 128);
         widenColumn(connection, "printing_event", "id", 128);
         widenColumn(connection, "printing_event", "event_json", 16000);
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
      return (createEnrollment(facilityId, deviceName, secret(), Instant.now().plusSeconds(900).toString()));
   }



   /***************************************************************************
    ** Native processes persist a prepared random code before the insert step.
    ** Replay reuses only an identical, unexpired, unconsumed hashed receipt.
    ***************************************************************************/
   JSONObject createEnrollment(String facilityId, String deviceName, String code, String expires) throws SQLException
   {
      required(facilityId, "facilityId");
      required(deviceName, "deviceName");
      try
      {
         if(code == null || !code.matches("[A-Za-z0-9_-]{43}") || Base64.getUrlDecoder().decode(code).length != 32
            || expires == null || !Instant.parse(expires).isAfter(Instant.now()) || Instant.parse(expires).isAfter(Instant.now().plusSeconds(901)))
         {
            throw new IllegalArgumentException("Invalid prepared enrollment");
         }
      }
      catch(IllegalArgumentException | java.time.format.DateTimeParseException exception)
      {
         throw new PrintingException(400, "Enrollment request is invalid or expired; restart enrollment");
      }
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> previous = query(connection, "SELECT * FROM printing_enrollment WHERE code_hash=?", hash(code));
         if(previous.isEmpty())
         {
            try
            {
               update(connection, "INSERT INTO printing_enrollment (code_hash, tenant_id, facility_id, device_name, expires_at) VALUES (?, ?, ?, ?, ?)", hash(code), tenantId, facilityId, deviceName, expires);
            }
            catch(SQLException exception)
            {
               if(!"23505".equals(exception.getSQLState()))
               {
                  throw exception;
               }
               previous = query(connection, "SELECT * FROM printing_enrollment WHERE code_hash=?", hash(code));
               if(previous.isEmpty())
               {
                  throw exception;
               }
            }
         }
         if(!previous.isEmpty())
         {
            JSONObject old = previous.getFirst();
            if(old.getInt("consumed") != 0 || !tenantId.equals(old.getString("tenant_id")) || !facilityId.equals(old.getString("facility_id"))
               || !deviceName.equals(old.getString("device_name")) || !expires.equals(old.getString("expires_at")))
            {
               throw new PrintingException(409, "Enrollment request was already consumed or changed; restart enrollment");
            }
         }
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
            if(codes.isEmpty() || codes.getFirst().getInt("consumed") != 0 || !Instant.parse(codes.getFirst().getString("expires_at")).isAfter(Instant.now()))
            {
               throw new PrintingException(401, "Enrollment code is invalid or expired");
            }
            JSONObject code = codes.getFirst();
            String id = UUID.randomUUID().toString();
            String token = secret();
            String computerName = required(request.optString("deviceName"), "deviceName");
            update(connection, "INSERT INTO printing_device (id, tenant_id, facility_id, name, agent_version, token_hash, last_seen_at, enabled, computer_name) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)", id, tenantId, code.getString("facility_id"), computerName, required(request.optString("agentVersion"), "agentVersion"), hash(token), Instant.now().toString(), computerName);
            update(connection, "UPDATE printing_enrollment SET consumed=1 WHERE code_hash=?", codeHash);
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
               List<Object> supported = printer.getJSONArray("formats").toList();
               if(supported.isEmpty() || supported.size() > 2 || new java.util.HashSet<>(supported).size() != supported.size()
                  || !Set.of("PDF", "ZPL").containsAll(supported) || !Set.of("READY", "OFFLINE", "PAUSED", "ERROR", "UNKNOWN").contains(status))
               {
                  throw new PrintingException(400, "Invalid printer status or supported formats");
               }
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
      JSONObject options = printOptions(request);
      String digest = hash(content);
      String id = UUID.randomUUID().toString();
      String reference = storageTableName == null ? null : documentStorage.prefix() + id + "." + format.toLowerCase(java.util.Locale.ROOT);
      String binding = storageTableName == null ? null : documentStorage.binding(storageTableName);
      String uploadedId = null;
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            if(storageTableName != null)
            {
               requireDevice(connection, deviceId);
               reserveUpload(connection, id, storageTableName, reference, binding);
               connection.commit();
               uploadedId = id;
               if(query(connection, "SELECT id FROM printing_document_orphan WHERE id=? FOR UPDATE", id).isEmpty())
               {
                  throw new PrintingException(409, "Upload reservation expired; retry submission");
               }
            }
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
               update(connection, "DELETE FROM printing_document_orphan WHERE id=? AND tenant_id=?", id, tenantId);
               connection.commit();
               return (job(old));
            }
            List<JSONObject> printers = query(connection, "SELECT * FROM printing_printer WHERE device_id=? AND printer_id=? AND enabled=1 AND available=1", deviceId, printerId);
            if(printers.isEmpty() || !new JSONArray(printers.getFirst().getString("formats")).toList().contains(format))
            {
               throw new PrintingException(400, "Printer is not enabled for this format");
            }
            if(storageTableName != null)
            {
               documentStorage.put(storageTableName, reference, format, content);
            }
            update(connection, "INSERT INTO printing_job (id, tenant_id, device_id, printer_id, title, format, content_hash, content_base64, content_length, options_json, idempotency_key, created_at, status, printer_row_id, content_storage_table, content_reference, content_binding) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?)", id, tenantId, deviceId, printerId, title, format, digest, storageTableName == null ? Base64.getEncoder().encodeToString(content) : null, content.length, options.toString(), key, Instant.now().toString(), printers.getFirst().getString("id"), storageTableName, reference, binding);
            update(connection, "DELETE FROM printing_document_orphan WHERE id=? AND tenant_id=?", id, tenantId);
            JSONObject result = job(query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE id=?", id).getFirst());
            connection.commit();
            return (result);
         }
         catch(Exception exception)
         {
            connection.rollback();
            if(uploadedId != null)
            {
               try
               {
                  cleanupOrphan(connection, uploadedId);
               }
               catch(Exception cleanupFailure)
               {
                  exception.addSuppressed(cleanupFailure);
               }
            }
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Preserve request intent: reject malformed types instead of coercing them.
    ***************************************************************************/
   private static JSONObject printOptions(JSONObject request)
   {
      if(request.has("options") && !(request.opt("options") instanceof JSONObject))
      {
         throw new PrintingException(400, "Print options must be an object");
      }
      JSONObject options = request.has("options") ? new JSONObject(request.getJSONObject("options").toString()) : new JSONObject();
      Object copies = options.opt("copies");
      int count = 1;
      if(options.has("copies"))
      {
         if(!(copies instanceof Number number))
         {
            throw new PrintingException(400, "Copies must be an integer between 1 and 100");
         }
         try
         {
            count = new java.math.BigDecimal(number.toString()).intValueExact();
         }
         catch(ArithmeticException | NumberFormatException exception)
         {
            throw new PrintingException(400, "Copies must be an integer between 1 and 100");
         }
      }
      Object scale = options.has("scale") ? options.opt("scale") : "ACTUAL_SIZE";
      if(count < 1 || count > 100 || !(scale instanceof String) || !Set.of("ACTUAL_SIZE", "FIT").contains(scale))
      {
         throw new PrintingException(400, "Invalid print options");
      }
      if(options.has("paperWidthMm") || options.has("paperHeightMm"))
      {
         throw new PrintingException(400, "Custom paper dimensions are not supported yet; configure the Windows queue");
      }
      options.put("copies", count).put("scale", scale);
      if(options.toString().length() > 2000)
      {
         throw new PrintingException(400, "Print options are too large");
      }
      return (options);
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
      boolean mayHavePrinted;
      String retryKey = "device-retry:" + deviceId + ":" + jobId + ":" + required(request.optString("requestId"), "requestId");
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         row = deviceJob(connection, deviceId, jobId, true);
         List<JSONObject> previous = query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=? AND device_id=? AND idempotency_key=?", tenantId, deviceId, retryKey);
         if(!previous.isEmpty())
         {
            return (job(previous.getFirst()));
         }
         if(row.getInt("content_available") == 0)
         {
            throw new PrintingException(410, "Print document expired under the host retention policy; submit a new document explicitly");
         }
         mayHavePrinted = !row.optString("spooler_job_id").isBlank()
            || !query(connection, "SELECT id FROM printing_event WHERE job_id=? AND status IN ('SUBMITTING','SPOOLED','SPOOLER_COMPLETED','OUTCOME_UNKNOWN') LIMIT 1", jobId).isEmpty();
      }
      String state = row.getString("status");
      if(!Set.of("FAILED", "OUTCOME_UNKNOWN", "SPOOLED", "SPOOLER_COMPLETED").contains(state))
      {
         throw new PrintingException(409, "Only a finished or uncertain job can be reprinted");
      }
      if((!"FAILED".equals(state) || mayHavePrinted) && !Boolean.TRUE.equals(request.opt("confirmDuplicateRisk")))
      {
         throw new PrintingException(409, "Inspect the printer and confirm the risk of duplicate output before reprinting");
      }
      return (submit(new JSONObject().put("deviceId", deviceId).put("printerId", row.getString("printer_id"))
         .put("title", row.getString("title")).put("format", row.getString("format")).put("contentBase64", Base64.getEncoder().encodeToString(documentBytes(row)))
         .put("options", new JSONObject(row.getString("options_json")))
         .put("idempotencyKey", retryKey)));
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
            if("FAILED".equals(row.getString("status")) && CANCELED_BEFORE_DISPATCH.equals(row.optString("message")))
            {
               connection.commit();
               return (new JSONObject().put("accepted", true));
            }
            if(!"QUEUED".equals(row.getString("status")))
            {
               throw new PrintingException(409, "Job already reached the device; inspect or cancel its Windows spooler job");
            }
            update(connection, "UPDATE printing_job SET status='FAILED', message=? WHERE id=?", CANCELED_BEFORE_DISPATCH, jobId);
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
            List<JSONObject> candidates = query(connection, "SELECT j." + JOB_COLUMNS.replace(", ", ", j.") + " FROM printing_job j JOIN printing_printer p ON p.device_id=j.device_id AND p.printer_id=j.printer_id WHERE j.tenant_id=? AND j.device_id=? AND p.enabled=1 AND p.available=1 AND j.status IN ('QUEUED','CLAIMED') AND ((j.format='PDF' AND p.formats LIKE '%\"PDF\"%') OR (j.format='ZPL' AND p.formats LIKE '%\"ZPL\"%')) ORDER BY j.created_at, j.id LIMIT 1", tenantId, deviceId);
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
         if(row.getInt("content_available") == 0)
         {
            throw new PrintingException(410, "Print document expired under the host retention policy");
         }
         return (documentBytes(row));
      }
   }



   /***************************************************************************
    ** Record a replay-safe lifecycle event without reprinting ambiguous jobs.
    ***************************************************************************/
   public void event(String deviceId, String jobId, JSONObject event) throws SQLException
   {
      String eventId = required(event.optString("eventId"), "eventId");
      String status = required(event.optString("status"), "status");
      String message = event.has("message") ? eventText(event, "message", 2048) : "";
      String spooler = event.has("spoolerJobId") ? eventText(event, "spoolerJobId", 128) : null;
      if(event.toString().length() > 16000)
      {
         throw new PrintingException(400, "Event is too large");
      }
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
            String eventTime = Instant.now().toString();
            update(connection, "INSERT INTO printing_event VALUES (?, ?, ?, ?, ?)", eventId, jobId, status, event.toString(), eventTime);
            if(Set.of("SPOOLED", "SPOOLER_COMPLETED").contains(status))
            {
               update(connection, "UPDATE printing_job SET completed_at=? WHERE id=?", eventTime, jobId);
            }
            update(connection, "UPDATE printing_job SET status=?, message=?, spooler_job_id=? WHERE id=?", status, message, spooler == null ? row.optString("spooler_job_id") : spooler, jobId);
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
    ** Host-authorized maintenance only, never called by device routes. Purge a
    ** bounded batch of old successful payloads while preserving all receipts,
    ** hashes and idempotency keys. Failed and uncertain jobs are never selected.
    ***************************************************************************/
   public int purgeCompletedContent(PrintingRetentionPolicy policy) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         return (purgeCompletedContent(connection, policy, new int[1], null));
      }
   }



   /***************************************************************************
    ** Commit each confirmed removal so a later failure cannot undo its receipt.
    ***************************************************************************/
   private int purgeCompletedContent(Connection connection, PrintingRetentionPolicy policy, int[] progress, String leaseToken) throws SQLException
   {
      java.util.Objects.requireNonNull(policy);
      if(!policy.enabled())
      {
         return (0);
      }
      String cutoff = Instant.now().minusSeconds(policy.days() * 86400L).toString();
      {
         connection.setAutoCommit(false);
         try
         {
            List<JSONObject> rows = query(connection, "SELECT id FROM printing_job WHERE tenant_id=? AND content_available=1 AND status IN ('SPOOLED','SPOOLER_COMPLETED') AND completed_at<? ORDER BY completed_at, id LIMIT ?", tenantId, cutoff, policy.batchSize());
            int purged = 0;
            for(JSONObject row : rows)
            {
               renewRetentionLease(connection, leaseToken);
               List<JSONObject> locked = query(connection, "SELECT id, format, content_storage_table, content_reference, content_binding FROM printing_job WHERE tenant_id=? AND id=? AND content_available=1 AND status IN ('SPOOLED','SPOOLER_COMPLETED') AND completed_at<? FOR UPDATE", tenantId, row.getString("id"), cutoff);
               if(locked.isEmpty())
               {
                  continue;
               }
               JSONObject document = locked.getFirst();
               if(!document.isNull("content_reference"))
               {
                  verifyStoredReference(document);
                  documentStorage.delete(document.getString("content_storage_table"), document.getString("content_reference"));
               }
               int changed = update(connection, "UPDATE printing_job SET content_base64=NULL, content_available=0 WHERE tenant_id=? AND id=? AND content_available=1 AND status IN ('SPOOLED','SPOOLER_COMPLETED') AND completed_at<?", tenantId, row.getString("id"), cutoff);
               connection.commit();
               purged += changed;
               progress[0] += changed;
            }
            connection.commit();
            return (purged);
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
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
    ** Tenant-scoped administrator settings and observable maintenance progress.
    ***************************************************************************/
   public JSONObject getRetentionSettings() throws SQLException
   {
      ensureRetentionSettings();
      try(Connection connection = dataSource.getConnection())
      {
         JSONObject row = query(connection, "SELECT * FROM printing_settings WHERE tenant_id=?", tenantId).getFirst();
         String cutoff = Instant.now().minusSeconds(row.getInt("retention_days") * 86400L).toString();
         long backlog = query(connection, "SELECT COUNT(*) AS total FROM printing_job WHERE tenant_id=? AND content_available=1 AND status IN ('SPOOLED','SPOOLER_COMPLETED') AND completed_at<?", tenantId, cutoff).getFirst().getLong("total");
         JSONObject result = new JSONObject().put("days", row.getInt("retention_days")).put("enabled", row.getInt("retention_enabled") == 1)
            .put("batchSize", defaultRetentionPolicy.batchSize()).put("lastRunAt", row.optString("last_run_at")).put("lastRunCount", row.optInt("last_run_count", 0))
            .put("lastRunError", row.optString("last_run_error")).put("eligibleBacklog", backlog);
         if(storageTableName != null)
         {
            result.put("storageTableName", storageTableName).put("storageBackendName", documentStorage.backendName(storageTableName))
               .put("bucketName", documentStorage.bucketName(storageTableName)).put("storagePrefix", documentStorage.prefix());
         }
         else
         {
            result.put("storageMode", "LEGACY_DATABASE");
         }
         return (result);
      }
   }



   /***************************************************************************
    ** Caller must enforce printing administrator and tenant permissions.
    ***************************************************************************/
   public void saveRetentionSettings(int days, boolean enabled) throws SQLException
   {
      new PrintingRetentionPolicy(enabled, days, defaultRetentionPolicy.batchSize());
      ensureRetentionSettings();
      try(Connection connection = dataSource.getConnection())
      {
         update(connection, "UPDATE printing_settings SET retention_days=?, retention_enabled=? WHERE tenant_id=?", days, enabled ? 1 : 0, tenantId);
      }
   }



   /***************************************************************************
    ** Insert defaults once without overwriting a concurrent administrator edit.
    ***************************************************************************/
   private void ensureRetentionSettings() throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         if(query(connection, "SELECT tenant_id FROM printing_settings WHERE tenant_id=?", tenantId).isEmpty())
         {
            try
            {
               update(connection, "INSERT INTO printing_settings (tenant_id, retention_days, retention_enabled) VALUES (?, ?, ?)", tenantId, defaultRetentionPolicy.days(), defaultRetentionPolicy.enabled() ? 1 : 0);
            }
            catch(SQLException exception)
            {
               if(!"23505".equals(exception.getSQLState()))
               {
                  throw exception;
               }
            }
         }
      }
   }



   /***************************************************************************
    ** Persist a renewable lease without holding a second pooled connection.
    ** A crashed worker's lease expires after an hour; exact rows remain locked
    ** during storage deletion even if a storage call outlives that lease.
    ***************************************************************************/
   public int runRetention() throws SQLException
   {
      ensureRetentionSettings();
      int[] progress = new int[1];
      String token = UUID.randomUUID().toString();
      try(Connection connection = dataSource.getConnection())
      {
         if(update(connection, "UPDATE printing_settings SET lease_token=?, lease_expires_at=? WHERE tenant_id=? AND (lease_expires_at IS NULL OR lease_expires_at<?)", token, Instant.now().plusSeconds(3600).toString(), tenantId, Instant.now().toString()) == 0)
         {
            return (0);
         }
         connection.setAutoCommit(false);
         try
         {
            JSONObject row = query(connection, "SELECT * FROM printing_settings WHERE tenant_id=?", tenantId).getFirst();
            PrintingRetentionPolicy policy = new PrintingRetentionPolicy(row.getInt("retention_enabled") == 1, row.getInt("retention_days"), defaultRetentionPolicy.batchSize());
            purgeCompletedContent(connection, policy, progress, token);
            if(policy.enabled())
            {
               List<JSONObject> orphans = query(connection, "SELECT id FROM printing_document_orphan WHERE tenant_id=? AND created_at<? ORDER BY created_at,id LIMIT ?", tenantId, Instant.now().minusSeconds(3600).toString(), Math.max(0, policy.batchSize() - progress[0]));
               for(JSONObject orphan : orphans)
               {
                  renewRetentionLease(connection, token);
                  progress[0] += cleanupOrphan(connection, orphan.getString("id"));
               }
            }
            update(connection, "UPDATE printing_settings SET last_run_at=?, last_run_count=?, last_run_error='', lease_token=NULL, lease_expires_at=NULL WHERE tenant_id=? AND lease_token=?", Instant.now().toString(), progress[0], tenantId, token);
            connection.commit();
            return (progress[0]);
         }
         catch(Exception exception)
         {
            connection.rollback();
            connection.setAutoCommit(true);
            update(connection, "UPDATE printing_settings SET last_run_at=?, last_run_count=?, last_run_error=?, lease_token=NULL, lease_expires_at=NULL WHERE tenant_id=? AND lease_token=?", Instant.now().toString(), progress[0], "Cleanup failed; retained references will be retried. Check host diagnostics.", tenantId, token);
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Stop safely if another worker acquired an expired maintenance lease.
    ***************************************************************************/
   private void renewRetentionLease(Connection connection, String token) throws SQLException
   {
      if(token != null)
      {
         if(update(connection, "UPDATE printing_settings SET lease_expires_at=? WHERE tenant_id=? AND lease_token=?", Instant.now().plusSeconds(3600).toString(), tenantId, token) == 0)
         {
            throw new SQLException("Printing cleanup lease was lost");
         }
         connection.commit();
      }
   }



   /***************************************************************************
    ** Preserve legacy inline documents and validate externally stored bytes.
    ***************************************************************************/
   private byte[] documentBytes(JSONObject row) throws SQLException
   {
      byte[] bytes;
      if(row.isNull("content_reference"))
      {
         bytes = Base64.getDecoder().decode(row.getString("content_base64"));
      }
      else
      {
         verifyStoredReference(row);
         bytes = documentStorage.get(row.getString("content_storage_table"), row.getString("content_reference"));
      }
      if(bytes.length != row.getInt("content_length") || !hash(bytes).equals(row.getString("content_hash")))
      {
         throw new PrintingException(502, "Stored print document integrity check failed");
      }
      return (bytes);
   }



   /***************************************************************************
    ** Historical table/bucket/namespace binding cannot be retargeted silently.
    ***************************************************************************/
   private void verifyStoredReference(JSONObject row)
   {
      if(documentStorage == null)
      {
         throw new PrintingException(503, "The host printing storage adapter is not configured");
      }
      String expected = documentStorage.prefix() + row.getString("id") + "." + row.getString("format").toLowerCase(java.util.Locale.ROOT);
      if(!expected.equals(row.optString("content_reference")))
      {
         throw new PrintingException(409, "Stored document reference does not match its tenant and job");
      }
      documentStorage.verifyBinding(row.getString("content_storage_table"), row.getString("content_binding"));
   }



   /***************************************************************************
    ** A committed upload intent survives crashes between object and SQL writes.
    ***************************************************************************/
   private void reserveUpload(Connection connection, String id, String table, String reference, String binding) throws SQLException
   {
      update(connection, "INSERT INTO printing_document_orphan (id, tenant_id, storage_table, reference, binding, created_at) VALUES (?, ?, ?, ?, ?, ?)", id, tenantId, table, reference, binding, Instant.now().toString());
   }



   /***************************************************************************
    ** Confirm no committed job owns an upload before removing its exact object.
    ** Failed deletion retains the intent so later maintenance can retry safely.
    ***************************************************************************/
   private int cleanupOrphan(Connection connection, String id) throws SQLException
   {
      {
         connection.setAutoCommit(false);
         try
         {
            List<JSONObject> rows = query(connection, "SELECT * FROM printing_document_orphan WHERE tenant_id=? AND id=? FOR UPDATE", tenantId, id);
            if(rows.isEmpty())
            {
               connection.commit();
               return (0);
            }
            JSONObject orphan = rows.getFirst();
            if(query(connection, "SELECT id FROM printing_job WHERE id=? AND tenant_id=?", id, tenantId).isEmpty())
            {
               if(documentStorage == null)
               {
                  throw new PrintingException(503, "The host printing storage adapter is not configured");
               }
               documentStorage.verifyBinding(orphan.getString("storage_table"), orphan.getString("binding"));
               String reference = orphan.getString("reference");
               String expected = documentStorage.prefix() + id;
               if(!reference.equals(expected + ".pdf") && !reference.equals(expected + ".zpl"))
               {
                  throw new PrintingException(409, "Orphan reference does not match its owned upload identity");
               }
               documentStorage.delete(orphan.getString("storage_table"), orphan.getString("reference"));
            }
            update(connection, "DELETE FROM printing_document_orphan WHERE id=? AND tenant_id=?", id, tenantId);
            connection.commit();
            return (1);
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
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
      List<JSONObject> rows = query(connection, "SELECT " + JOB_COLUMNS + (includeContent ? ", content_base64, content_available, content_storage_table, content_reference, content_binding" : "") + " FROM printing_job WHERE id=? AND tenant_id=? AND device_id=?", jobId, tenantId, deviceId);
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
         case "agentVersion" -> 100;
         case "eventId" -> 128;
         default -> 300;
      };
      if(value == null || value.isBlank() || value.length() > maximum)
      {
         throw new PrintingException(400, "Invalid " + name);
      }
      return (value);
   }



   /***************************************************************************
    ** Bound event fields before persistence without coercing invalid JSON types.
    ***************************************************************************/
   private static String eventText(JSONObject event, String name, int maximum)
   {
      if(!(event.opt(name) instanceof String value))
      {
         throw new PrintingException(400, "Invalid " + name);
      }
      return (optionalText(value, name, maximum));
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



