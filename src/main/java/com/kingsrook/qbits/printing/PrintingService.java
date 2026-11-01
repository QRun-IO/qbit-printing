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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.uber.nullaway.annotations.EnsuresNonNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Durable device registration and print queue. Each instance has one tenant.
 ** Host applications must authorize callers before invoking management methods.
 *******************************************************************************/
public class PrintingService
{
   static final String CANCELED_BEFORE_DISPATCH = "Canceled by operator before dispatch";
   private static final String JOB_COLUMNS = "id, tenant_id, device_id, printer_id, title, format, content_hash, content_length, options_json, idempotency_key, created_at, status, message, spooler_job_id, printer_row_id";
   private final DataSource dataSource;
   private final String tenantId;
   private @Nullable PrintingDocumentStorage documentStorage;
   private PrintingAdmissionLimits admissionLimits = PrintingAdmissionLimits.defaults();
   private @Nullable String storageTableName;
   private PrintingRetentionPolicy defaultRetentionPolicy = new PrintingRetentionPolicy(true, 365, 200);
   private PrintingRealtimeBus realtimeBus = new PrintingRealtimeBus();
   private RealtimeClaimGuard realtimeClaimGuard = device ->
   {
      throw new PrintingException(503, "The host has not registered the realtime printing route provider");
   };


   /** Native claims require a synchronized current-user channel, not a polling fallback. */
   @FunctionalInterface
   public interface RealtimeClaimGuard
   {
      /** Check this connection's authoritative applied state. */
      void requireReady(String device) throws SQLException;
   }


   /** Route-provider lifecycle owns the readiness guard. */
   void withRealtimeClaimGuard(RealtimeClaimGuard guard)
   {
      realtimeClaimGuard = guard;
   }


   /** Enforce readiness on native HTTP claims before selecting any job. */
   public void requireRealtimeReady(String device) throws SQLException
   {
      realtimeClaimGuard.requireReady(device);
   }


   /** Persist application observations before the WebSocket acknowledgment is emitted. */
   void applyRealtimeState(String device, JSONObject control, long settings) throws SQLException
   {
      if(settings > getRealtimeSettings().getLong("revision"))
      {
         throw new PrintingException(409, "Realtime settings changed before acknowledgment");
      }
      deviceControlPoll(device, control);
      try(Connection connection = dataSource.getConnection())
      {
         if(update(connection, "UPDATE printing_device SET realtime_applied_revision=CASE WHEN realtime_applied_revision<? THEN ? ELSE realtime_applied_revision END WHERE tenant_id=? AND id=? AND EXISTS (SELECT 1 FROM printing_settings WHERE tenant_id=? AND realtime_revision>=?)", settings, settings, tenantId, device, tenantId, settings) != 1)
         {
            throw new PrintingException(409, "Realtime settings changed before acknowledgment");
         }
      }
   }


   /** Authenticated transport contact does not imply applied controls or normal device traffic. */
   void realtimeContact(String device) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, device);
         update(connection, "UPDATE printing_device SET control_last_poll_at=? WHERE tenant_id=? AND id=? AND enabled=1", Instant.now().toString(), tenantId, device);
      }
   }


   /** A host may share this event bus across services using the same database. */
   public PrintingService withRealtimeBus(PrintingRealtimeBus bus)
   {
      realtimeBus = java.util.Objects.requireNonNull(bus);
      return this;
   }


   /** Instance-owned notifications never contain credentials or document bytes. */
   public PrintingRealtimeBus getRealtimeBus()
   {
      return realtimeBus;
   }


   /** Complete persisted tenant settings; defaults apply until the first administrator edit. */
   public JSONObject getRealtimeSettings() throws SQLException
   {
      ensureRetentionSettings();
      try(Connection connection = dataSource.getConnection())
      {
         JSONObject row = query(connection, "SELECT realtime_policy_json, realtime_revision FROM printing_settings WHERE tenant_id=?", tenantId).getFirst();
         PrintingRealtimePolicy policy = row.isNull("realtime_policy_json") ? PrintingRealtimePolicy.defaults()
            : PrintingRealtimePolicy.fromJson(new JSONObject(row.getString("realtime_policy_json")));
         return policy.toJson(row.getLong("realtime_revision"));
      }
   }


   /** The native adapter authorizes the administrator before this atomic update. */
   public JSONObject saveRealtimeSettings(JSONObject request) throws SQLException
   {
      PrintingRealtimePolicy policy = PrintingRealtimePolicy.fromJson(request);
      ensureRetentionSettings();
      JSONObject result;
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            long revision = query(connection, "SELECT realtime_revision FROM printing_settings WHERE tenant_id=? FOR UPDATE", tenantId).getFirst().getLong("realtime_revision") + 1;
            if(revision < 1 || revision > 9007199254740991L)
            {
               throw new PrintingException(409, "Realtime settings revision capacity is exhausted");
            }
            update(connection, "UPDATE printing_settings SET realtime_policy_json=?, realtime_revision=? WHERE tenant_id=?", request.toString(), revision, tenantId);
            connection.commit();
            result = policy.toJson(revision);
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
      realtimeBus.publish(tenantId, null);
      return result;
   }


   /** Resolve immutable validated policy values for one request. */
   public PrintingRealtimePolicy realtimePolicy() throws SQLException
   {
      JSONObject settings = getRealtimeSettings();
      settings.remove("revision");
      return PrintingRealtimePolicy.fromJson(settings);
   }



   /***************************************************************************
    ** Deployment-owned limits apply equally to native, HTTP and outbox callers.
    ***************************************************************************/
   public PrintingService withAdmissionLimits(PrintingAdmissionLimits limits)
   {
      admissionLimits = java.util.Objects.requireNonNull(limits);
      return this;
   }


   /** Native tenant administrator admission settings. */
   public JSONObject getAdmissionSettings() throws SQLException
   {
      ensureRetentionSettings();
      return new PrintingAdmissionSettings(dataSource, tenantId, admissionLimits).get();
   }


   /** Save validated tenant overrides without changing retained job receipts. */
   public JSONObject saveAdmissionSettings(JSONObject request) throws SQLException
   {
      ensureRetentionSettings();
      JSONObject result = new PrintingAdmissionSettings(dataSource, tenantId, admissionLimits).save(request);
      realtimeBus.publish(tenantId, null);
      return result;
   }



   /***************************************************************************
    ** Bind a host-owned storage table before exposing this service to requests.
    ***************************************************************************/
   public void configureStorage(com.kingsrook.qqq.backend.core.model.metadata.QInstance instance, @Nullable String table) throws com.kingsrook.qqq.backend.core.exceptions.QException
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
   void configureStorage(PrintingDocumentStorage storage, @Nullable String table)
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
      return this;
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
         "ALTER TABLE printing_printer ADD COLUMN IF NOT EXISTS media_profiles_json TEXT DEFAULT '[]' NOT NULL",
         "CREATE TABLE IF NOT EXISTS printing_job (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36) NOT NULL REFERENCES printing_device(id), printer_id VARCHAR(200) NOT NULL, title VARCHAR(300) NOT NULL, format VARCHAR(10) NOT NULL, content_hash VARCHAR(64) NOT NULL, content_base64 TEXT NOT NULL, content_length INTEGER NOT NULL, options_json VARCHAR(2000) NOT NULL, idempotency_key VARCHAR(300) NOT NULL, created_at VARCHAR(40) NOT NULL, status VARCHAR(40) NOT NULL, message VARCHAR(2000), spooler_job_id VARCHAR(100), UNIQUE(tenant_id, idempotency_key))",
         "CREATE TABLE IF NOT EXISTS printing_event (id VARCHAR(100) PRIMARY KEY, job_id VARCHAR(36) NOT NULL REFERENCES printing_job(id), status VARCHAR(40) NOT NULL, event_json VARCHAR(4000) NOT NULL, created_at VARCHAR(40) NOT NULL)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS printer_row_id VARCHAR(36)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS retained_actor_id VARCHAR(200)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS retained_binding_json VARCHAR(16000)",
         "ALTER TABLE printing_job ADD COLUMN IF NOT EXISTS accepted_event_id VARCHAR(128)",
         "ALTER TABLE printing_event ADD COLUMN IF NOT EXISTS event_origin VARCHAR(40)",
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
         "ALTER TABLE printing_settings ADD COLUMN IF NOT EXISTS realtime_policy_json VARCHAR(8000)",
         "ALTER TABLE printing_settings ADD COLUMN IF NOT EXISTS realtime_revision BIGINT DEFAULT 0 NOT NULL",
         "ALTER TABLE printing_settings ADD COLUMN IF NOT EXISTS admission_policy_json VARCHAR(8000)",
         "ALTER TABLE printing_settings ADD COLUMN IF NOT EXISTS retention_batch_size INTEGER",
         "CREATE TABLE IF NOT EXISTS printing_document_orphan (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, storage_table VARCHAR(200) NOT NULL, reference VARCHAR(500) NOT NULL, binding VARCHAR(64) NOT NULL, created_at VARCHAR(40) NOT NULL)",
         "CREATE INDEX IF NOT EXISTS printing_orphan_tenant_idx ON printing_document_orphan(tenant_id, created_at, id)",
         "UPDATE printing_job SET printer_row_id=(SELECT p.id FROM printing_printer p WHERE p.device_id=printing_job.device_id AND p.printer_id=printing_job.printer_id AND p.tenant_id=printing_job.tenant_id) WHERE printer_row_id IS NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS computer_name VARCHAR(1024)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS display_name VARCHAR(512)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS description VARCHAR(4096)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS location VARCHAR(512)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS realtime_applied_revision BIGINT DEFAULT -1 NOT NULL",
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
         PrintingAdmission.initialize(connection, tenantId);
         PrintingDeviceControls.initialize(connection);
         update(connection, "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS bound_user_id VARCHAR(200)");
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
    ** Apply one audited action after the adapter authorizes the current actor.
    ***************************************************************************/
   public JSONObject deviceAction(String deviceId, JSONObject request, String actor) throws SQLException
   {
      JSONObject result = new PrintingDeviceControls(dataSource, tenantId).action(deviceId, request, actor);
      realtimeBus.publish(tenantId, deviceId);
      return result;
   }


   /** Native authorization supplies the exact reviewed queue identity set, never a predicate executed inside SQL locks. */
   JSONObject deviceAction(String deviceId, JSONObject request, String actor, @Nullable Set<String> authorizedQueue) throws SQLException
   {
      JSONObject result = new PrintingDeviceControls(dataSource, tenantId).action(deviceId, request, actor, authorizedQueue);
      realtimeBus.publish(tenantId, deviceId);
      return result;
   }


   /** Resolve a retained action receipt without treating new queue contents as part of a replay. */
   @Nullable String existingDeviceAction(String deviceId, String requestId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> rows = query(connection, "SELECT id FROM printing_device_command WHERE tenant_id=? AND device_id=? AND request_id=?", tenantId, deviceId, requestId);
         return rows.isEmpty() ? null : rows.getFirst().getString("id");
      }
   }


   /** Bounded identifiers permit native row authorization before bulk queue cancellation. */
   Set<String> queuedJobIdsForControl(String deviceId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         Set<String> ids = new java.util.HashSet<>();
         for(JSONObject row : query(connection, "SELECT id FROM printing_job WHERE tenant_id=? AND device_id=? AND status='QUEUED' LIMIT 10001", tenantId, deviceId))
         {
            ids.add(row.getString("id"));
         }
         if(ids.size() > 10000)
         {
            throw new PrintingException(429, "The queue exceeds the bounded native review limit");
         }
         return ids;
      }
   }


   /** Independently poll desired state and pending commands using device authentication. */
   public JSONObject deviceControlPoll(String deviceId, JSONObject request) throws SQLException
   {
      return new PrintingDeviceControls(dataSource, tenantId).poll(deviceId, request);
   }


   /** Persist one immutable device observation without inferring physical output. */
   public JSONObject deviceControlReceipt(String deviceId, String commandId, JSONObject receipt) throws SQLException
   {
      return new PrintingDeviceControls(dataSource, tenantId).receipt(deviceId, commandId, receipt);
   }


   /** Tenant/device scoped history remains visible after credential revocation. */
   public JSONObject deviceCommandsPage(String deviceId, int limit, @Nullable String before) throws SQLException
   {
      return new PrintingDeviceControls(dataSource, tenantId).page(deviceId, limit, before);
   }


   /** Read the exact durable receipt for a native or HTTP management action. */
   public JSONObject getDeviceCommand(String deviceId, String commandId) throws SQLException
   {
      return new PrintingDeviceControls(dataSource, tenantId).get(deviceId, commandId);
   }


   /** The immutable tenant scope used by host authorization and native actions. */
   public String getTenantId()
   {
      return tenantId;
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
      return createEnrollment(facilityId, deviceName, secret(), Instant.now().plusSeconds(900).toString());
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
      return new JSONObject().put("enrollmentCode", code).put("expiresAt", expires);
   }



   /***************************************************************************
    ** Consume the enrollment code atomically and issue a device credential.
    ***************************************************************************/
   public JSONObject enroll(JSONObject request) throws SQLException
   {
      return enrollForUser(request, null);
   }


   /** Native enrollment checks facility visibility before consuming the immutable enrollment code. */
   String enrollmentFacility(String code) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> rows = query(connection, "SELECT facility_id FROM printing_enrollment WHERE tenant_id=? AND code_hash=? AND consumed=0 AND expires_at>?", tenantId, hash(required(code, "enrollmentCode")), Instant.now().toString());
         if(rows.isEmpty())
         {
            throw new PrintingException(401, "Enrollment code is invalid or expired");
         }
         return rows.getFirst().getString("facility_id");
      }
   }


   /** Native enrollment binds the core-authenticated principal in the credential transaction. */
   public JSONObject enrollForUser(JSONObject request, @Nullable String principalId) throws SQLException
   {
      if(principalId != null && (principalId.isBlank() || principalId.length() > 200))
      {
         throw new PrintingException(403, "Invalid authenticated principal");
      }
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
            update(connection, "INSERT INTO printing_device (id, tenant_id, facility_id, name, agent_version, token_hash, last_seen_at, enabled, computer_name, bound_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)", id, tenantId, code.getString("facility_id"), computerName, required(request.optString("agentVersion"), "agentVersion"), hash(token), Instant.now().toString(), computerName, principalId);
            update(connection, "UPDATE printing_enrollment SET consumed=1 WHERE code_hash=?", codeHash);
            connection.commit();
            return new JSONObject().put("protocolVersion", 1).put("deviceId", id).put("deviceToken", token).put("pollIntervalSeconds", 5);
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Legacy primary credentials are valid only until native user binding.
    ***************************************************************************/
   public String authenticate(String token) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> devices = query(connection, "SELECT id FROM printing_device WHERE tenant_id=? AND token_hash=? AND enabled=1 AND revoked_at IS NULL AND bound_user_id IS NULL", tenantId, hash(token == null ? "" : token));
         if(devices.isEmpty())
         {
            throw new PrintingException(401, "Invalid device credential");
         }
         return devices.getFirst().getString("id");
      }
   }


   /** Secondary device proof never replaces the caller's native QQQ authentication and row permission. */
   public void authorizeAgent(String deviceId, String token, String principalId, boolean bind) throws SQLException
   {
      if(deviceId == null || token == null || token.length() > 128 || principalId == null || principalId.isBlank() || principalId.length() > 200)
      {
         throw new PrintingException(403, "User or device is not authorized");
      }
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            List<JSONObject> rows = query(connection, "SELECT token_hash,bound_user_id FROM printing_device WHERE tenant_id=? AND id=? AND enabled=1 AND revoked_at IS NULL FOR UPDATE", tenantId, deviceId);
            if(rows.isEmpty() || !MessageDigest.isEqual(rows.getFirst().getString("token_hash").getBytes(StandardCharsets.UTF_8), hash(token).getBytes(StandardCharsets.UTF_8)))
            {
               throw new PrintingException(403, "User or device is not authorized");
            }
            String owner = rows.getFirst().optString("bound_user_id", null);
            if(owner == null && bind)
            {
               update(connection, "UPDATE printing_device SET bound_user_id=? WHERE tenant_id=? AND id=? AND bound_user_id IS NULL", principalId, tenantId, deviceId);
            }
            else if(!principalId.equals(owner))
            {
               throw new PrintingException(403, "User or device is not authorized");
            }
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
            query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
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
      return new JSONObject().put("serverTime", now);
   }



   /***************************************************************************
    ** Replace observed inventory while retaining administrator approval.
    ***************************************************************************/
   public void inventory(String deviceId, JSONArray printers) throws SQLException
   {
      inventory(deviceId, printers, null);
   }


   /** Native inventory changes exactly the existing row set authorized before opening this transaction. */
   void inventory(String deviceId, JSONArray printers, @Nullable Set<String> authorizedRows) throws SQLException
   {
      if(printers.length() > 200)
      {
         throw new PrintingException(400, "Too many printers");
      }
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         PrintingAdmission.initializeTenant(connection, tenantId);
         connection.setAutoCommit(false);
         try
         {
            PrintingAdmission.lock(connection, tenantId);
            requireDevice(connection, deviceId);
            query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
            requireDevice(connection, deviceId);
            if(authorizedRows != null)
            {
               Set<String> currentRows = new java.util.HashSet<>();
               for(JSONObject row : query(connection, "SELECT id FROM printing_printer WHERE tenant_id=? AND device_id=? LIMIT 10001", tenantId, deviceId))
               {
                  currentRows.add(row.getString("id"));
               }
               if(!currentRows.equals(authorizedRows))
               {
                  throw new PrintingException(409, "Inventory changed; authorize its current rows before retrying");
               }
            }
            Set<String> incoming = new java.util.HashSet<>();
            for(int index = 0; index < printers.length(); index++)
            {
               incoming.add(required(printers.getJSONObject(index).optString("printerId"), "printerId"));
            }
            PrintingAdmission.inventory(connection, tenantId, deviceId, incoming, new PrintingAdmissionSettings(dataSource, tenantId, admissionLimits).effective(connection));
            update(connection, "UPDATE printing_printer SET status='UNKNOWN', available=0 WHERE device_id=? AND available=1", deviceId);
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
               update(connection, "UPDATE printing_printer SET media_profiles_json=? WHERE id=?", PrintingMediaProfile.inventory(printer).toString(), rowId);
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
      realtimeBus.publish(tenantId, deviceId);
   }



   /***************************************************************************
    ** Durably enqueue an immutable document. Repeated identical keys return it.
    ***************************************************************************/
   public JSONObject submit(JSONObject request) throws SQLException
   {
      return submitAuthorized(request, null, null);
   }


   /***************************************************************************
    ** Native operators retain current authority throughout shared queue admission.
    ***************************************************************************/
   JSONObject submitAuthorized(JSONObject request, @Nullable String printerRowId, @Nullable SubmissionAuthorization authorize) throws SQLException
   {
      SubmissionGuard guard = authorize == null ? null : new SubmissionGuard(Objects.requireNonNull(printerRowId, "An authorized submission requires its printer row"), authorize);
      checkSubmission(guard);
      String format = required(request.optString("format"), "format");
      long maximum = realtimePolicy().documentLimit(format);
      if(request.getString("contentBase64").length() > ((maximum + 2) / 3) * 4)
      {
         throw new PrintingException(413, "Encoded document exceeds its configured format limit");
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
      if(content.length > maximum)
      {
         throw new PrintingException(413, "Document exceeds configured " + format + " limit of " + maximum + " bytes");
      }
      if(content.length == 0)
      {
         throw new PrintingException(400, "Document must contain at least one byte");
      }
      if("PDF".equals(format) && !new String(content, 0, Math.min(5, content.length), StandardCharsets.US_ASCII).equals("%PDF-"))
      {
         throw new PrintingException(400, "PDF header is missing");
      }
      return submitPrepared(request, hash(content), content.length, connection -> content, null, guard);
   }



   /***************************************************************************
    ** A caller-owned current authorization check, never externally asserted proof.
    ***************************************************************************/
   @FunctionalInterface
   interface SubmissionAuthorization
   {
      /***********************************************************************
       ** Recheck caller identity, current ceilings and exact destination.
       ***********************************************************************/
      void check() throws com.kingsrook.qqq.backend.core.exceptions.QException;
   }



   /***************************************************************************
    ** Native row identity and live authority are independent of retained-source proof.
    ***************************************************************************/
   private record SubmissionGuard(String printerRowId, SubmissionAuthorization authorize)
   {
      /***********************************************************************
       ** Host callbacks cannot retarget an authorized native row to another queue.
       ***********************************************************************/
      void requirePrinter(JSONObject row, String field)
      {
         if(!printerRowId.equals(row.optString(field)))
         {
            throw new PrintingException(409, "The authorized print destination changed");
         }
      }
   }



   /***************************************************************************
    ** Preserve checked native authorization through the SQL transaction boundary.
    ***************************************************************************/
   private static void checkSubmission(@Nullable SubmissionGuard guard) throws SQLException
   {
      if(guard != null)
      {
         try
         {
            guard.authorize().check();
         }
         catch(com.kingsrook.qqq.backend.core.exceptions.QException exception)
         {
            throw new SQLException("Print submission authorization failed", exception);
         }
      }
   }



   /***************************************************************************
    ** Delay stored-document reads until atomic admission has succeeded.
    ***************************************************************************/
   @FunctionalInterface
   private interface DocumentLoader
   {
      /***********************************************************************
       ** Read the immutable document after capacity is reserved.
       ***********************************************************************/
      byte[] load(Connection connection) throws SQLException;
   }



   /***************************************************************************
    ** All producers share durable admission before object storage allocation.
    ***************************************************************************/
   private JSONObject submitPrepared(JSONObject request, String digest, int contentLength, DocumentLoader loader) throws SQLException
   {
      return submitPrepared(request, digest, contentLength, loader, null, null);
   }


   /** Only the native facade supplies retained bindings and current-authority checks. */
   JSONObject submitRetained(JSONObject request, JSONObject binding, String actor, RetainedPrintFacade.Checked authorize, RetainedContent loader) throws SQLException
   {
      binding.put("options", printOptions(request));
      if(binding.toString().length() > 16000)
      {
         throw new PrintingException(400, "Retained print binding is too large");
      }
      JSONObject source = binding.getJSONObject("source");
      BoundSubmission bound = new BoundSubmission(binding.toString(), actor);
      return submitPrepared(request, source.getString("sha256"), source.getInt("sizeBytes"), connection ->
      {
         try
         {
            byte[] bytes = loader.read();
            if("PDF".equals(request.getString("format")) && !new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.US_ASCII).equals("%PDF-"))
            {
               throw new PrintingException(400, "PDF header is missing");
            }
            return bytes;
         }
         catch(com.kingsrook.qqq.backend.core.exceptions.QException exception)
         {
            throw new SQLException("Retained content authorization failed", exception);
         }
      }, bound, new SubmissionGuard(binding.getString("printerRecordId"), authorize::run));
   }


   /** Deferred current-owner content read after shared capacity admission. */
   @FunctionalInterface
   interface RetainedContent
   {
      /** Return only authorized exact bytes. */
      byte[] read() throws com.kingsrook.qqq.backend.core.exceptions.QException;
   }


   /** Request-local immutable binding; never an externally accepted JSON proof. */
   private record BoundSubmission(String json, String actor)
   {
   }


   /***************************************************************************
    ** Reuse the existing storage reservation, queue admission, and commit path.
    ***************************************************************************/
   private JSONObject submitPrepared(JSONObject request, String digest, int contentLength, DocumentLoader loader, @Nullable BoundSubmission bound, @Nullable SubmissionGuard guard) throws SQLException
   {
      checkSubmission(guard);
      String deviceId = required(request.optString("deviceId"), "deviceId");
      String printerId = required(request.optString("printerId"), "printerId");
      String key = required(request.optString("idempotencyKey"), "idempotencyKey");
      String title = required(request.optString("title"), "title");
      String format = required(request.optString("format"), "format");
      if(!Set.of("PDF", "ZPL").contains(format))
      {
         throw new PrintingException(400, "Only PDF and ZPL are supported");
      }
      JSONObject options = printOptions(request);
      long contentLimit = realtimePolicy().documentLimit(format);
      String id = UUID.randomUUID().toString();
      String reference = storageTableName == null ? "" : configuredStorage().prefix() + id + "." + format.toLowerCase(java.util.Locale.ROOT);
      String binding = storageTableName == null ? "" : configuredStorage().binding(storageTableName);
      String uploadedId = null;
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         PrintingAdmission.initializeTenant(connection, tenantId);
         connection.setAutoCommit(false);
         try
         {
            long pendingDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while(true)
            {
               PrintingAdmission.lock(connection, tenantId);
               requireDevice(connection, deviceId);
               query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
               requireDevice(connection, deviceId);
               checkSubmission(guard);
               List<JSONObject> previous = query(connection, "SELECT " + JOB_COLUMNS + ", retained_binding_json, retained_actor_id, accepted_event_id FROM printing_job WHERE tenant_id=? AND idempotency_key=?", tenantId, key);
               if(!previous.isEmpty())
               {
                  JSONObject old = previous.getFirst();
                  if(guard != null)
                  {
                     guard.requirePrinter(old, "printer_row_id");
                  }
                  if(key.startsWith("operator:") && !old.getString("title").equals(title))
                  {
                     throw new PrintingException(409, "Idempotency key was already used for a different request");
                  }
                  if((bound == null) != old.isNull("retained_binding_json")
                     || (bound != null && (!new JSONObject(bound.json()).similar(new JSONObject(old.getString("retained_binding_json"))) || !bound.actor().equals(old.getString("retained_actor_id")))))
                  {
                     throw new PrintingException(409, "Idempotency key belongs to a different accepted source");
                  }
                  if(!old.getString("device_id").equals(deviceId) || !old.getString("printer_id").equals(printerId) || !old.getString("content_hash").equals(digest) || !old.getString("format").equals(format) || !new JSONObject(old.getString("options_json")).similar(options))
                  {
                     throw new PrintingException(409, "Idempotency key was already used for a different request");
                  }
                  JSONObject replay = bound == null ? job(old) : accepted(connection, old);
                  checkSubmission(guard);
                  connection.commit();
                  return replay;
               }
               if(query(connection, "SELECT id FROM printing_document_orphan WHERE tenant_id=? AND idempotency_key=? LIMIT 1", tenantId, key).isEmpty())
               {
                  break;
               }
               connection.commit();
               if(System.nanoTime() >= pendingDeadline)
               {
                  throw new PrintingException(429, "This request already has an upload in progress or awaiting recovery; retry later using the same request identity");
               }
               try
               {
                  Thread.sleep(25);
               }
               catch(InterruptedException exception)
               {
                  Thread.currentThread().interrupt();
                  throw new SQLException("Interrupted while waiting for the existing submission", exception);
               }
            }
            List<JSONObject> printers = query(connection, "SELECT * FROM printing_printer WHERE device_id=? AND printer_id=? AND enabled=1 AND available=1", deviceId, printerId);
            if(printers.isEmpty() || !new JSONArray(printers.getFirst().getString("formats")).toList().contains(format))
            {
               throw new PrintingException(400, "Printer is not enabled for this format");
            }
            if(guard != null)
            {
               guard.requirePrinter(printers.getFirst(), "id");
            }
            if(contentLength > contentLimit)
            {
               throw new PrintingException(413, "Document exceeds the current configured format limit");
            }
            PrintingMediaProfile.requireCurrent(printers.getFirst(), options);
            checkSubmission(guard);
            PrintingAdmission.reserve(connection, tenantId, deviceId, id, contentLength, new PrintingAdmissionSettings(dataSource, tenantId, admissionLimits).effective(connection));
            if(storageTableName != null)
            {
               reserveUpload(connection, id, storageTableName, reference, binding, deviceId, contentLength, key);
               connection.commit();
               uploadedId = id;
               if(query(connection, "SELECT id FROM printing_document_orphan WHERE id=? FOR UPDATE", id).isEmpty())
               {
                  throw new PrintingException(409, "Upload reservation expired; retry submission");
               }
               requireDevice(connection, deviceId);
               query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
               requireDevice(connection, deviceId);
               printers = query(connection, "SELECT * FROM printing_printer WHERE device_id=? AND printer_id=? AND enabled=1 AND available=1", deviceId, printerId);
               if(printers.isEmpty() || !new JSONArray(printers.getFirst().getString("formats")).toList().contains(format))
               {
                  throw new PrintingException(400, "Printer is not enabled for this format");
               }
               if(guard != null)
               {
                  guard.requirePrinter(printers.getFirst(), "id");
               }
            }
            PrintingMediaProfile.requireCurrent(printers.getFirst(), options);
            checkSubmission(guard);
            byte[] content = loader.load(connection);
            if(content.length != contentLength || !hash(content).equals(digest))
            {
               throw new PrintingException(409, "Stored print document integrity validation failed");
            }
            checkSubmission(guard);
            if(storageTableName != null)
            {
               configuredStorage().put(storageTableName, reference, format, content);
            }
            checkSubmission(guard);
            update(connection, "INSERT INTO printing_job (id, tenant_id, device_id, printer_id, title, format, content_hash, content_base64, content_length, options_json, idempotency_key, created_at, status, printer_row_id, content_storage_table, content_reference, content_binding) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?)", id, tenantId, deviceId, printerId, title, format, digest, storageTableName == null ? Base64.getEncoder().encodeToString(content) : null, content.length, options.toString(), key, Instant.now().toString(), printers.getFirst().getString("id"), storageTableName, storageTableName == null ? null : reference, storageTableName == null ? null : binding);
            if(bound != null)
            {
               String eventId = UUID.randomUUID().toString();
               String acceptedAt = Instant.now().toString();
               update(connection, "UPDATE printing_job SET retained_actor_id=?, retained_binding_json=?, accepted_event_id=? WHERE id=?", bound.actor(), bound.json(), eventId, id);
               JSONObject event = new JSONObject().put("type", "ACCEPTED").put("origin", "SERVER_ACCEPTANCE").put("status", "QUEUED");
               update(connection, "INSERT INTO printing_event (id, job_id, status, event_json, created_at, event_origin) VALUES (?, ?, 'QUEUED', ?, ?, 'SERVER_ACCEPTANCE')", eventId, id, event.toString(), acceptedAt);
            }
            update(connection, "DELETE FROM printing_document_orphan WHERE id=? AND tenant_id=?", id, tenantId);
            JSONObject stored = query(connection, "SELECT " + JOB_COLUMNS + ", retained_binding_json, retained_actor_id, accepted_event_id FROM printing_job WHERE id=?", id).getFirst();
            JSONObject result = bound == null ? job(stored) : accepted(connection, stored);
            checkSubmission(guard);
            connection.commit();
            realtimeBus.publish(tenantId, deviceId);
            return result;
         }
         catch(Exception exception)
         {
            rollbackAfterFailure(connection, exception);
            if(uploadedId != null)
            {
               try
               {
                  cleanupOrphan(connection, uploadedId);
               }
               catch(Exception cleanupFailure)
               {
                  suppressIfDistinct(exception, cleanupFailure);
               }
            }
            throw exception;
         }
      }
   }



   /***************************************************************************
    ** Preserve request intent: reject malformed types instead of coercing them.
    ***************************************************************************/
   private JSONObject printOptions(JSONObject request) throws SQLException
   {
      return printOptions(request, Math.toIntExact(realtimePolicy().get("maxCopies")));
   }


   /** Normalize an immutable intent without reading or creating tenant settings during reconciliation. */
   private static JSONObject printOptions(JSONObject request, int maximumCopies)
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
            throw new PrintingException(400, "Copies must be an integer within the configured limit");
         }
         try
         {
            count = new java.math.BigDecimal(number.toString()).intValueExact();
         }
         catch(ArithmeticException | NumberFormatException exception)
         {
            throw new PrintingException(400, "Copies must be an integer within the configured limit");
         }
      }
      Object scale = options.has("scale") ? options.opt("scale") : "ACTUAL_SIZE";
      if(count < 1 || count > maximumCopies || !(scale instanceof String) || !Set.of("ACTUAL_SIZE", "FIT").contains(scale))
      {
         throw new PrintingException(400, "Invalid print options");
      }
      if(options.has("paperWidthMm") || options.has("paperHeightMm"))
      {
         throw new PrintingException(400, "Custom paper dimensions are not supported yet; configure the Windows queue");
      }
      PrintingMediaProfile.selection(options);
      options.put("copies", count).put("scale", scale);
      if(options.toString().length() > 2000)
      {
         throw new PrintingException(400, "Print options are too large");
      }
      return options;
   }



   /***************************************************************************
    ** Claim one job; replay the same assignment until the device advances it.
    ***************************************************************************/
   public JSONObject testPrint(String deviceId, JSONObject request) throws Exception
   {
      String format = required(request.optString("format"), "format");
      TestPrintProfile profile = TestPrintProfile.resolve(format, request.has("testProfile") ? request.optString("testProfile") : null);
      byte[] bytes = "PDF".equals(format) ? PrintingTestDocuments.pdf(profile) : PrintingTestDocuments.zpl(profile);
      return submit(new JSONObject().put("deviceId", deviceId).put("printerId", required(request.optString("printerId"), "printerId"))
         .put("title", "QQQ print test").put("format", format).put("contentBase64", Base64.getEncoder().encodeToString(bytes))
         .put("idempotencyKey", "device-test:" + deviceId + ":" + required(request.optString("requestId"), "requestId")));
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
         return result;
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
         row = deviceJob(connection, deviceId, jobId);
         JSONObject document = query(connection, "SELECT content_available, content_storage_table, content_reference, content_binding FROM printing_job WHERE id=? AND tenant_id=? AND device_id=?", jobId, tenantId, deviceId).getFirst();
         row.put("content_available", document.getInt("content_available")).put("content_storage_table", document.opt("content_storage_table"))
            .put("content_reference", document.opt("content_reference")).put("content_binding", document.opt("content_binding"));
         List<JSONObject> previous = query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=? AND device_id=? AND idempotency_key=?", tenantId, deviceId, retryKey);
         if(!previous.isEmpty())
         {
            return job(previous.getFirst());
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
      if(!row.isNull("content_reference"))
      {
         verifyStoredReference(row);
      }
      return submitPrepared(new JSONObject().put("deviceId", deviceId).put("printerId", row.getString("printer_id"))
         .put("title", row.getString("title")).put("format", row.getString("format"))
         .put("options", new JSONObject(row.getString("options_json")))
         .put("idempotencyKey", retryKey), row.getString("content_hash"), row.getInt("content_length"), connection -> documentBytes(deviceJob(connection, deviceId, jobId, true)));
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
            requireDevice(connection, deviceId);
            JSONObject row = deviceJob(connection, deviceId, jobId);
            if("FAILED".equals(row.getString("status")) && CANCELED_BEFORE_DISPATCH.equals(row.optString("message")))
            {
               connection.commit();
               return new JSONObject().put("accepted", true);
            }
            if(!"QUEUED".equals(row.getString("status")))
            {
               throw new PrintingException(409, "Job already reached the device; inspect or cancel its Windows spooler job");
            }
            update(connection, "UPDATE printing_job SET status='FAILED', message=? WHERE id=?", CANCELED_BEFORE_DISPATCH, jobId);
            connection.commit();
            realtimeBus.publish(tenantId, deviceId);
            return new JSONObject().put("accepted", true);
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
      return claimForJob(deviceId, null);
   }


   /** Native adapters authorize one exact job and printer before this locked dispatch transition. */
   public JSONObject claimForJob(String deviceId, @Nullable String authorizedJobId) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            requireDevice(connection, deviceId);
            query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
            requireDevice(connection, deviceId);
            JSONObject control = query(connection, "SELECT dispatch_paused,connection_state,control_capabilities FROM printing_device WHERE tenant_id=? AND id=?", tenantId, deviceId).getFirst();
            if(control.getInt("dispatch_paused") == 1 || !"CONNECTED".equals(control.getString("connection_state")))
            {
               connection.commit();
               return new JSONObject().put("job", JSONObject.NULL);
            }
            List<JSONObject> candidates = query(connection, "SELECT j." + JOB_COLUMNS.replace(", ", ", j.") + " FROM printing_job j JOIN printing_printer p ON p.device_id=j.device_id AND p.printer_id=j.printer_id WHERE j.tenant_id=? AND j.device_id=? AND p.enabled=1 AND p.available=1 AND j.status IN ('QUEUED','CLAIMED') AND ((j.format='PDF' AND p.formats LIKE '%\"PDF\"%') OR (j.format='ZPL' AND p.formats LIKE '%\"ZPL\"%'))" + (authorizedJobId == null ? "" : " AND j.id=?") + " ORDER BY j.created_at, j.id LIMIT 1", authorizedJobId == null ? new Object[] { tenantId, deviceId } : new Object[] { tenantId, deviceId, authorizedJobId });
            if(candidates.isEmpty())
            {
               connection.commit();
               return new JSONObject().put("job", JSONObject.NULL);
            }
            JSONObject selected = candidates.getFirst();
            if(new JSONObject(selected.getString("options_json")).has("mediaProfile")
               && !new JSONArray(control.optString("control_capabilities", "[]")).toList().contains("printer-media-v1"))
            {
               throw new PrintingException(409, "This job requires an agent that validates printer media profiles");
            }
            if("QUEUED".equals(selected.getString("status")))
            {
               update(connection, "UPDATE printing_job SET status='CLAIMED' WHERE id=?", selected.getString("id"));
               selected.put("status", "CLAIMED");
            }
            connection.commit();
            return new JSONObject().put("job", job(selected));
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
         return documentBytes(row);
      }
   }


   /** Stream external documents without holding a database connection or buffering the entire body. */
   public java.io.InputStream openContent(String deviceId, String jobId) throws SQLException
   {
      JSONObject row;
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         row = deviceJob(connection, deviceId, jobId, true);
         if("QUEUED".equals(row.getString("status")))
         {
            throw new PrintingException(409, "Claim the job before downloading");
         }
         if(row.getInt("content_available") == 0)
         {
            throw new PrintingException(410, "Print document expired under the host retention policy");
         }
      }
      if(row.getLong("content_length") > realtimePolicy().documentLimit(row.getString("format")))
      {
         throw new PrintingException(413, "Document exceeds the current configured format limit");
      }
      java.io.InputStream input;
      if(row.isNull("content_reference"))
      {
         input = new java.io.ByteArrayInputStream(Base64.getDecoder().decode(row.getString("content_base64")));
      }
      else
      {
         verifyStoredReference(row);
         input = documentStorage.open(row.getString("content_storage_table"), row.getString("content_reference"));
      }
      return new PrintingDocumentStream(input, row.getLong("content_length"), row.getString("content_hash"));
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
            query(connection, "SELECT id FROM printing_device WHERE id=? FOR UPDATE", deviceId);
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
            update(connection, "INSERT INTO printing_event (id, job_id, status, event_json, created_at, event_origin) VALUES (?, ?, ?, ?, ?, 'DEVICE_OBSERVATION')", eventId, jobId, status, event.toString(), eventTime);
            if(Set.of("SPOOLED", "SPOOLER_COMPLETED").contains(status))
            {
               update(connection, "UPDATE printing_job SET completed_at=? WHERE id=?", eventTime, jobId);
            }
            update(connection, "UPDATE printing_job SET status=?, message=?, spooler_job_id=? WHERE id=?", status, message, spooler == null ? row.optString("spooler_job_id") : spooler, jobId);
            connection.commit();
            realtimeBus.publish(tenantId, deviceId);
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
               .put("agentVersion", row.getString("agent_version")).put("lastSeenAt", row.getString("last_seen_at")).put("enabled", row.getInt("enabled") == 1).put("control", PrintingDeviceControls.state(row)));
         }
         return result;
      }
   }



   /***************************************************************************
    ** Bounded inventory history for management clients.
    ***************************************************************************/
   public JSONObject printersPage(String deviceId, int limit, @Nullable String before) throws SQLException
   {
      if(limit < 1 || limit > 200)
      {
         throw new PrintingException(400, "Printer page limit must be between 1 and 200");
      }
      String name = null;
      String id = null;
      if(before != null && !before.isEmpty())
      {
         try
         {
            if(before.length() > 12000)
            {
               throw new IllegalArgumentException("Cursor too long");
            }
            JSONObject cursor = new JSONObject(new String(Base64.getUrlDecoder().decode(before), StandardCharsets.UTF_8));
            name = optionalText(cursor.getString("name"), "cursor name", 1024);
            id = UUID.fromString(cursor.getString("id")).toString();
         }
         catch(RuntimeException exception)
         {
            throw new PrintingException(400, "Invalid printer cursor");
         }
      }
      try(Connection connection = dataSource.getConnection())
      {
         requireDevice(connection, deviceId);
         String sql = "SELECT * FROM printing_printer WHERE tenant_id=? AND device_id=?";
         List<JSONObject> rows = name == null ? query(connection, sql + " ORDER BY name,id LIMIT ?", tenantId, deviceId, limit + 1)
            : query(connection, sql + " AND (name>? OR (name=? AND id>?)) ORDER BY name,id LIMIT ?", tenantId, deviceId, name, name, id, limit + 1);
         JSONArray result = new JSONArray();
         for(JSONObject row : rows.subList(0, Math.min(limit, rows.size())))
         {
            result.put(new JSONObject().put("printerId", row.getString("printer_id")).put("name", row.getString("queue_name")).put("displayName", row.optString("display_name")).put("description", row.optString("description")).put("location", row.optString("location"))
               .put("mediaProfiles", new JSONArray(row.getString("media_profiles_json"))).put("driverName", row.optString("driver_name")).put("portName", row.optString("port_name")).put("status", row.getString("status")).put("formats", new JSONArray(row.getString("formats"))).put("enabled", row.getInt("enabled") == 1).put("available", row.getInt("available") == 1).put("lastSeenAt", row.getString("last_seen_at")));
         }
         JSONObject response = new JSONObject().put("printers", result).put("nextCursor", JSONObject.NULL);
         if(rows.size() > limit)
         {
            JSONObject last = rows.get(limit - 1);
            String cursor = new JSONObject().put("name", last.getString("name")).put("id", last.getString("id")).toString();
            response.put("nextCursor", Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8)));
         }
         return response;
      }
   }



   /***************************************************************************
    ** Existing callers retain their array contract with a bounded first page.
    ***************************************************************************/
   public JSONArray printers(String deviceId) throws SQLException
   {
      return printersPage(deviceId, 200, null).getJSONArray("printers");
   }



   /***************************************************************************
    ** Return recent jobs; content is excluded from management lists.
    ***************************************************************************/
   public JSONArray jobs() throws SQLException
   {
      return jobsPage(200, null).getJSONArray("jobs");
   }



   /***************************************************************************
    ** Keyset pagination stays bounded as history grows, without document reads.
    ***************************************************************************/
   public JSONObject jobsPage(int limit, @Nullable String before) throws SQLException
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
         return response;
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
         return job(rows.getFirst());
      }
   }



   /***************************************************************************
    ** Record a secondary cleanup failure on the primary one. Throwable.addSuppressed
    ** rejects the very same instance, so this is an identity check by design; value
    ** equality would wrongly drop distinct failures that merely compare equal.
    ***************************************************************************/
   // Identity is the Throwable self-suppression contract; equals() cannot express it.
   @SuppressWarnings("ReferenceEquality")
   private static void suppressIfDistinct(Throwable primary, Throwable secondary)
   {
      if(secondary != primary)
      {
         primary.addSuppressed(secondary);
      }
   }



   /***************************************************************************
    ** Cleanup never replaces the acceptance/authorization failure that caused rollback.
    ***************************************************************************/
   static void rollbackAfterFailure(Connection connection, Exception original)
   {
      try
      {
         connection.rollback();
      }
      catch(Exception cleanupFailure)
      {
         suppressIfDistinct(original, cleanupFailure);
      }
   }


   /** Exact accepted-source lookup; unresolved object reservations never mean absence. */
   @Nullable JSONObject findRetained(String key, @Nullable JSONObject expected) throws SQLException
   {
      if(expected != null)
      {
         expected.put("options", printOptions(new JSONObject().put("options", expected.getJSONObject("options")), 32767));
      }
      try(Connection connection = dataSource.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            PrintingAdmission.lock(connection, tenantId);
            JSONObject result = findRetained(connection, key, expected);
            connection.commit();
            return result;
         }
         catch(Exception exception)
         {
            rollbackAfterFailure(connection, exception);
            throw exception;
         }
      }
   }


   /** The existing admission lock makes absence consistent with in-flight submissions. */
   private @Nullable JSONObject findRetained(Connection connection, String key, @Nullable JSONObject expected) throws SQLException
   {
      List<JSONObject> rows = query(connection, "SELECT " + JOB_COLUMNS + ", retained_binding_json, retained_actor_id, accepted_event_id FROM printing_job WHERE tenant_id=? AND idempotency_key=?", tenantId, key);
      if(rows.isEmpty())
      {
         if(!query(connection, "SELECT id FROM printing_document_orphan WHERE tenant_id=? AND idempotency_key=?", tenantId, key).isEmpty())
         {
            throw new PrintingException(409, "Retained print acceptance is pending recovery");
         }
         return null;
      }
      JSONObject row = rows.getFirst();
      if(row.isNull("retained_binding_json") || (expected != null && !expected.similar(new JSONObject(row.getString("retained_binding_json")))))
      {
         throw new PrintingException(409, "Accepted source binding differs");
      }
      return accepted(connection, row);
   }


   /** Verify the server acceptance event and immutable job content/destination before returning proof. */
   private JSONObject accepted(Connection connection, JSONObject row) throws SQLException
   {
      if(row.isNull("retained_binding_json") || row.isNull("accepted_event_id") || row.isNull("retained_actor_id"))
      {
         throw new PrintingException(409, "Job has no retained acceptance binding");
      }
      JSONObject binding = new JSONObject(row.getString("retained_binding_json"));
      JSONObject source = binding.getJSONObject("source");
      if(!tenantId.equals(binding.getString("tenantId")) || !row.getString("retained_actor_id").equals(binding.getString("actorId"))
         || !row.getString("idempotency_key").equals(RetainedPrintFacade.key(binding.getString("requestId")))
         || !row.getString("device_id").equals(binding.getString("deviceId")) || !row.getString("printer_id").equals(binding.getString("printerId"))
         || !row.getString("printer_row_id").equals(binding.getString("printerRecordId")) || !row.getString("title").equals(binding.getString("title"))
         || !row.getString("content_hash").equals(source.getString("sha256")) || row.getLong("content_length") != source.getLong("sizeBytes")
         || !row.getString("format").equals("application/pdf".equals(source.getString("mediaType")) ? "PDF" : "ZPL")
         || !new JSONObject(row.getString("options_json")).similar(binding.getJSONObject("options")))
      {
         throw new PrintingException(409, "Accepted job binding is inconsistent");
      }
      List<JSONObject> events = query(connection, "SELECT * FROM printing_event WHERE id=? AND job_id=? AND event_origin='SERVER_ACCEPTANCE' AND status='QUEUED'", row.getString("accepted_event_id"), row.getString("id"));
      if(events.size() != 1 || !new JSONObject(events.getFirst().getString("event_json")).similar(new JSONObject().put("type", "ACCEPTED").put("origin", "SERVER_ACCEPTANCE").put("status", "QUEUED")))
      {
         throw new PrintingException(409, "Committed server acceptance event is absent or inconsistent");
      }
      return new JSONObject().put("jobId", row.getString("id")).put("acceptedEventId", row.getString("accepted_event_id"))
         .put("acceptedAt", events.getFirst().getString("created_at")).put("binding", binding);
   }


   /** Old unclassified events and server acceptance are not device observation evidence. */
   JSONObject retainedObservation(String key, String eventId) throws SQLException
   {
      JSONObject acceptance = findRetained(key, null);
      if(acceptance == null)
      {
         throw new PrintingException(404, "Accepted print intent not found");
      }
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> events = query(connection, "SELECT * FROM printing_event WHERE id=? AND job_id=? AND event_origin='DEVICE_OBSERVATION'", eventId, acceptance.getString("jobId"));
         if(events.size() != 1)
         {
            throw new PrintingException(404, "Committed device observation not found");
         }
         JSONObject row = events.getFirst();
         JSONObject event = new JSONObject(row.getString("event_json"));
         if(!eventId.equals(event.getString("eventId")) || !row.getString("status").equals(event.getString("status")))
         {
            throw new PrintingException(409, "Device observation is inconsistent");
         }
         return new JSONObject().put("acceptance", acceptance).put("recordedAt", row.getString("created_at"))
            .put("state", row.getString("status")).put("spoolerJobId", event.opt("spoolerJobId") == null ? JSONObject.NULL : event.opt("spoolerJobId"));
      }
   }


   /***************************************************************************
    ** Find an accepted request during durable-effect reconciliation.
    ***************************************************************************/
   public @Nullable JSONObject findByIdempotencyKey(String key) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> rows = query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=? AND idempotency_key=?", tenantId, key);
         return rows.isEmpty() ? null : job(rows.getFirst());
      }
   }


   /** The native row accepted with an operator request is private authorization data. */
   record OperatorAcceptance(JSONObject job, String printerRowId)
   {
   }


   /** Read the exact committed row binding; a replacement queue cannot inherit history. */
   @Nullable OperatorAcceptance findOperatorAcceptance(String key) throws SQLException
   {
      try(Connection connection = dataSource.getConnection())
      {
         List<JSONObject> rows = query(connection, "SELECT " + JOB_COLUMNS + " FROM printing_job WHERE tenant_id=? AND idempotency_key=?", tenantId, key);
         if(rows.isEmpty())
         {
            return null;
         }
         JSONObject row = rows.getFirst();
         if(row.isNull("printer_row_id") || row.getString("printer_row_id").isBlank())
         {
            throw new PrintingException(409, "Accepted print destination binding is unavailable");
         }
         return new OperatorAcceptance(job(row), row.getString("printer_row_id"));
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
         return purgeCompletedContent(connection, policy, new int[1], null);
      }
   }



   /***************************************************************************
    ** Commit each confirmed removal so a later failure cannot undo its receipt.
    ***************************************************************************/
   private int purgeCompletedContent(Connection connection, PrintingRetentionPolicy policy, int[] progress, @Nullable String leaseToken) throws SQLException
   {
      java.util.Objects.requireNonNull(policy);
      if(!policy.enabled())
      {
         return 0;
      }
      String cutoff = Instant.now().minusSeconds(policy.days() * 86400L).toString();
      {
         PrintingAdmission.initializeTenant(connection, tenantId);
         connection.setAutoCommit(false);
         try
         {
            List<JSONObject> rows = query(connection, "SELECT id FROM printing_job WHERE tenant_id=? AND content_available=1 AND status IN ('SPOOLED','SPOOLER_COMPLETED') AND completed_at<? ORDER BY completed_at, id LIMIT ?", tenantId, cutoff, policy.batchSize());
            int purged = 0;
            for(JSONObject row : rows)
            {
               renewRetentionLease(connection, leaseToken);
               PrintingAdmission.lock(connection, tenantId);
               List<JSONObject> locked = query(connection, "SELECT id, device_id, content_length, format, content_storage_table, content_reference, content_binding FROM printing_job WHERE tenant_id=? AND id=? AND content_available=1 AND status IN ('SPOOLED','SPOOLER_COMPLETED') AND completed_at<? FOR UPDATE", tenantId, row.getString("id"), cutoff);
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
               if(changed == 1)
               {
                  PrintingAdmission.release(connection, tenantId, document.getString("device_id"), document.getLong("content_length"));
               }
               connection.commit();
               purged += changed;
               progress[0] += changed;
            }
            connection.commit();
            return purged;
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
      if(query(connection, "SELECT id FROM printing_device WHERE id=? AND tenant_id=? AND enabled=1 AND revoked_at IS NULL", deviceId, tenantId).isEmpty())
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
            .put("batchSize", row.optInt("retention_batch_size", defaultRetentionPolicy.batchSize())).put("lastRunAt", row.optString("last_run_at")).put("lastRunCount", row.optInt("last_run_count", 0))
            .put("lastRunError", row.optString("last_run_error")).put("eligibleBacklog", backlog);
         if(storageTableName != null)
         {
            result.put("storageTableName", storageTableName).put("storageBackendName", configuredStorage().backendName(storageTableName))
               .put("bucketName", configuredStorage().bucketName(storageTableName)).put("storagePrefix", configuredStorage().prefix());
         }
         else
         {
            result.put("storageMode", "LEGACY_DATABASE");
         }
         return result;
      }
   }



   /***************************************************************************
    ** Caller must enforce printing administrator and tenant permissions.
    ***************************************************************************/
   public void saveRetentionSettings(int days, boolean enabled) throws SQLException
   {
      saveRetentionSettings(days, enabled, getRetentionSettings().getInt("batchSize"));
   }


   /** Administrators explicitly select bounded work per scheduled cleanup invocation. */
   public void saveRetentionSettings(int days, boolean enabled, int batchSize) throws SQLException
   {
      new PrintingRetentionPolicy(enabled, days, batchSize);
      ensureRetentionSettings();
      try(Connection connection = dataSource.getConnection())
      {
         update(connection, "UPDATE printing_settings SET retention_days=?, retention_enabled=?, retention_batch_size=? WHERE tenant_id=?", days, enabled ? 1 : 0, batchSize, tenantId);
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
            return 0;
         }
         connection.setAutoCommit(false);
         try
         {
            JSONObject row = query(connection, "SELECT * FROM printing_settings WHERE tenant_id=?", tenantId).getFirst();
            PrintingRetentionPolicy policy = new PrintingRetentionPolicy(row.getInt("retention_enabled") == 1, row.getInt("retention_days"), row.optInt("retention_batch_size", defaultRetentionPolicy.batchSize()));
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
            return progress[0];
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
   private void renewRetentionLease(Connection connection, @Nullable String token) throws SQLException
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
      if(row.getInt("content_available") == 0)
      {
         throw new PrintingException(410, "Print document expired under the host retention policy; submit a new document explicitly");
      }
      byte[] bytes;
      if(row.isNull("content_reference"))
      {
         bytes = Base64.getDecoder().decode(row.getString("content_base64"));
      }
      else
      {
         verifyStoredReference(row);
         bytes = documentStorage.get(row.getString("content_storage_table"), row.getString("content_reference"), row.getInt("content_length"));
      }
      if(bytes.length != row.getInt("content_length") || !hash(bytes).equals(row.getString("content_hash")))
      {
         throw new PrintingException(502, "Stored print document integrity check failed");
      }
      return bytes;
   }



   /***************************************************************************
    ** configureStorage installs the adapter before the optional table name,
    ** before this service is exposed. Call only inside a non-null table branch;
    ** inline storage does not require an adapter. No new runtime guard is added.
    ***************************************************************************/
   @SuppressWarnings("NullAway")
   private PrintingDocumentStorage configuredStorage()
   {
      return documentStorage;
   }



   /***************************************************************************
    ** Historical table/bucket/namespace binding cannot be retargeted silently.
    ***************************************************************************/
   @EnsuresNonNull("documentStorage")
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
   private void reserveUpload(Connection connection, String id, String table, String reference, String binding, String deviceId, int contentLength, String key) throws SQLException
   {
      update(connection, "INSERT INTO printing_document_orphan (id, tenant_id, storage_table, reference, binding, created_at, device_id, content_length, idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, tenantId, table, reference, binding, Instant.now().toString(), deviceId, contentLength, key);
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
            PrintingAdmission.lock(connection, tenantId);
            List<JSONObject> rows = query(connection, "SELECT * FROM printing_document_orphan WHERE tenant_id=? AND id=? FOR UPDATE", tenantId, id);
            if(rows.isEmpty())
            {
               connection.commit();
               return 0;
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
               PrintingAdmission.release(connection, tenantId, orphan.isNull("device_id") ? null : orphan.getString("device_id"), orphan.isNull("content_length") ? PrintingAdmission.UNKNOWN_UPLOAD_BYTES : orphan.getLong("content_length"));
            }
            update(connection, "DELETE FROM printing_document_orphan WHERE id=? AND tenant_id=?", id, tenantId);
            connection.commit();
            return 1;
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
      return deviceJob(connection, deviceId, jobId, false);
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
      return rows.getFirst();
   }



   /***************************************************************************
    ** Translate durable columns to the versioned protocol representation.
    ***************************************************************************/
   private static JSONObject job(JSONObject row)
   {
      return new JSONObject().put("jobId", row.getString("id")).put("deviceId", row.getString("device_id")).put("printerId", row.getString("printer_id")).put("title", row.getString("title")).put("format", row.getString("format")).put("contentSha256", row.getString("content_hash")).put("contentLength", row.getInt("content_length")).put("options", new JSONObject(row.getString("options_json"))).put("createdAt", row.getString("created_at")).put("status", row.getString("status")).put("message", row.optString("message")).put("spoolerJobId", row.optString("spooler_job_id"));
   }



   /***************************************************************************
    ** Run a bounded, parameterized query with lower-case column names.
    ***************************************************************************/
   static List<JSONObject> query(Connection connection, String sql, @Nullable Object... values) throws SQLException
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
            return result;
         }
      }
   }



   /***************************************************************************
    ** Execute a parameterized mutation.
    ***************************************************************************/
   static int update(Connection connection, String sql, @Nullable Object... values) throws SQLException
   {
      try(PreparedStatement statement = connection.prepareStatement(sql))
      {
         bind(statement, values);
         return statement.executeUpdate();
      }
   }



   /***************************************************************************
    ** Bind values without SQL interpolation.
    ***************************************************************************/
   private static void bind(PreparedStatement statement, @Nullable Object[] values) throws SQLException
   {
      for(int index = 0; index < values.length; index++)
      {
         statement.setObject(index + 1, values[index]);
      }
   }



   /***************************************************************************
    ** Reject missing caller data before persistence.
    ***************************************************************************/
   private static String required(@Nullable String value, String name)
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
      return value;
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
      return optionalText(value, name, maximum);
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
      return value;
   }



   /***************************************************************************
    ** Generate independent, unguessable enrollment and device credentials.
    ***************************************************************************/
   private static String secret()
   {
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
   }



   /***************************************************************************
    ** Hash credential text; plaintext credentials never enter the database.
    ***************************************************************************/
   private static String hash(String value)
   {
      return hash(value.getBytes(StandardCharsets.UTF_8));
   }



   /***************************************************************************
    ** Produce the content integrity digest required by protocol v1.
    ***************************************************************************/
   private static String hash(byte[] value)
   {
      try
      {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
      }
      catch(java.security.NoSuchAlgorithmException exception)
      {
         throw new IllegalStateException(exception);
      }
   }
}



