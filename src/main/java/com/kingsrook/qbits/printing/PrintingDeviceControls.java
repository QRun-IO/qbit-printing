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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.kingsrook.qbits.printing.PrintingService.query;
import static com.kingsrook.qbits.printing.PrintingService.update;


/** Durable device controls share the device lock with dispatch and submission. */
final class PrintingDeviceControls
{
   private static final Set<String> ACTIONS = Set.of("PAUSE_DISPATCH", "RESUME_DISPATCH", "DISCONNECT", "RECONNECT", "REVOKE", "REFRESH_INVENTORY", "CLEAR_QUEUE", "CANCEL_SPOOL");
   private static final Set<String> REMOTE = Set.of("REFRESH_INVENTORY", "CANCEL_SPOOL");
   private final DataSource source;
   private final String tenant;


   /** Bind to the same immutable tenant and pool as the queue. */
   PrintingDeviceControls(DataSource source, String tenant)
   {
      this.source = source;
      this.tenant = tenant;
   }


   /** Add compatible default state and a bounded durable command ledger. */
   static void initialize(Connection connection) throws SQLException
   {
      for(String sql : new String[] {
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS dispatch_paused INTEGER DEFAULT 0 NOT NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS connection_state VARCHAR(20) DEFAULT 'CONNECTED' NOT NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS control_revision BIGINT DEFAULT 0 NOT NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS control_version INTEGER DEFAULT 0 NOT NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS control_capabilities VARCHAR(200) DEFAULT '[]' NOT NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS control_last_poll_at VARCHAR(40)",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS control_applied_revision BIGINT DEFAULT 0 NOT NULL",
         "ALTER TABLE printing_device ADD COLUMN IF NOT EXISTS revoked_at VARCHAR(40)",
         "CREATE TABLE IF NOT EXISTS printing_device_command (id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(200) NOT NULL, device_id VARCHAR(36) NOT NULL REFERENCES printing_device(id), request_id VARCHAR(128) NOT NULL, action VARCHAR(40) NOT NULL, job_id VARCHAR(36), actor VARCHAR(200) NOT NULL, request_json VARCHAR(2000) NOT NULL, status VARCHAR(40) NOT NULL, created_at VARCHAR(40) NOT NULL, expires_at VARCHAR(40) NOT NULL, result_code VARCHAR(80), cancelled_job_count INTEGER, receipt_json VARCHAR(8000), UNIQUE(tenant_id,device_id,request_id))",
         "CREATE INDEX IF NOT EXISTS printing_command_owner_idx ON printing_device_command(tenant_id,device_id,created_at,id)",
         "CREATE INDEX IF NOT EXISTS printing_command_pending_idx ON printing_device_command(tenant_id,device_id,status,expires_at)"
      })
      {
         try(var statement = connection.prepareStatement(sql))
         {
            statement.execute();
         }
      }
   }


   /** Pure status projection never returns credential verifiers. */
   static JSONObject state(JSONObject row)
   {
      JSONObject result = new JSONObject().put("supported", row.getInt("control_version") == 1)
         .put("capabilities", new JSONArray(row.getString("control_capabilities")))
         .put("lastAppliedRevision", row.getLong("control_applied_revision"))
         .put("desiredState", new JSONObject().put("revision", row.getLong("control_revision"))
            .put("dispatchPaused", row.getInt("dispatch_paused") == 1).put("connectionState", row.getString("connection_state")));
      if(row.has("control_last_poll_at"))
      {
         result.put("lastPollAt", row.getString("control_last_poll_at"));
      }
      return (result);
   }


   /** Strict action input and original receipt enforce replay before any side effect. */
   JSONObject action(String deviceId, JSONObject request, String actor) throws SQLException
   {
      return (action(deviceId, request, actor, null));
   }


   /** A native authorized queue snapshot must still match after taking the device lock. */
   JSONObject action(String deviceId, JSONObject request, String actor, Set<String> authorizedQueue) throws SQLException
   {
      fields(request, Set.of("requestId", "issuedAt", "action", "jobId"));
      String requestId = text(request, "requestId", 128);
      if(!requestId.matches("[A-Za-z0-9._-]+"))
      {
         throw new PrintingException(400, "Invalid requestId");
      }
      String action = text(request, "action", 40);
      if(!ACTIONS.contains(action) || request.has("jobId") != "CANCEL_SPOOL".equals(action))
      {
         throw new PrintingException(400, "Invalid device action");
      }
      String jobId = "CANCEL_SPOOL".equals(action) ? text(request, "jobId", 36) : null;
      if(actor == null || actor.isBlank() || actor.length() > 200)
      {
         throw new PrintingException(400, "A bounded authenticated actor is required");
      }
      try(Connection connection = source.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            JSONObject device = lock(connection, deviceId, false);
            List<JSONObject> old = query(connection, "SELECT * FROM printing_device_command WHERE tenant_id=? AND device_id=? AND request_id=?", tenant, deviceId, requestId);
            if(!old.isEmpty())
            {
               if(!new JSONObject(old.getFirst().getString("request_json")).similar(request))
               {
                  throw new PrintingException(409, "Request identity was already used for a different action");
               }
               connection.commit();
               return (record(old.getFirst()));
            }
            Instant issued;
            try
            {
               issued = Instant.parse(utc(request, "issuedAt"));
            }
            catch(java.time.format.DateTimeParseException exception)
            {
               throw new PrintingException(400, "Invalid action time");
            }
            Instant current = Instant.now();
            if(issued.isBefore(current.minusSeconds(900)))
            {
               throw new PrintingException(410, "Action request expired; review a new action explicitly");
            }
            if(issued.isAfter(current.plusSeconds(60)))
            {
               throw new PrintingException(400, "Action time is too far in the future");
            }
            if(device.getInt("enabled") != 1 || device.has("revoked_at"))
            {
               throw new PrintingException(409, "Revoked devices require a new local enrollment");
            }
            if(!REMOTE.contains(action) && !Set.of("CLEAR_QUEUE", "REVOKE").contains(action) && device.getLong("control_revision") >= 9007199254740991L)
            {
               throw new PrintingException(409, "Control revision capacity reached");
            }
            expire(connection, deviceId);
            update(connection, "DELETE FROM printing_device_command WHERE id IN (SELECT id FROM printing_device_command WHERE tenant_id=? AND device_id=? AND status<>'PENDING' AND created_at<? AND expires_at<? ORDER BY created_at,id LIMIT 200)", tenant, deviceId, Instant.now().minusSeconds(7 * 86400).toString(), Instant.now().toString());
            long history = count(connection, "SELECT COUNT(*) AS n FROM printing_device_command WHERE tenant_id=? AND device_id=?", tenant, deviceId);
            if(history >= 1000 && !"REVOKE".equals(action))
            {
               throw new PrintingException(429, "Device control receipt capacity reached; receipts are retained and revocation remains available");
            }
            String id = UUID.randomUUID().toString();
            String now = current.toString();
            String expires = current.plusSeconds(900).toString();
            String result = null;
            Integer cancelled = null;
            if(REMOTE.contains(action))
            {
               if(device.getInt("control_version") != 1 || !new JSONArray(device.getString("control_capabilities")).toList().contains(action))
               {
                  throw new PrintingException(409, "CONTROL_UNSUPPORTED");
               }
               if(count(connection, "SELECT COUNT(*) AS n FROM printing_device_command WHERE tenant_id=? AND device_id=? AND status='PENDING'", tenant, deviceId) >= 100)
               {
                  throw new PrintingException(429, "Too many pending device commands");
               }
               if(jobId != null)
               {
                  List<JSONObject> jobs = query(connection, "SELECT id,status,spooler_job_id FROM printing_job WHERE tenant_id=? AND device_id=? AND id=?", tenant, deviceId, jobId);
                  if(jobs.isEmpty())
                  {
                     throw new PrintingException(404, "Job not found");
                  }
                  if(!Set.of("SPOOLED", "SPOOLER_COMPLETED").contains(jobs.getFirst().getString("status")) || jobs.getFirst().optString("spooler_job_id").isBlank())
                  {
                     throw new PrintingException(409, "Only an owned job with a recorded spool identity can be canceled");
                  }
               }
            }
            else if("CLEAR_QUEUE".equals(action))
            {
               if(authorizedQueue != null)
               {
                  Set<String> currentQueue = new java.util.HashSet<>();
                  for(JSONObject row : query(connection, "SELECT id FROM printing_job WHERE tenant_id=? AND device_id=? AND status='QUEUED' LIMIT 10001", tenant, deviceId))
                  {
                     currentQueue.add(row.getString("id"));
                  }
                  if(!currentQueue.equals(authorizedQueue))
                  {
                     throw new PrintingException(409, "The queue changed; review the current authorized jobs before retrying");
                  }
               }
               cancelled = update(connection, "UPDATE printing_job SET status='FAILED',message=? WHERE tenant_id=? AND device_id=? AND status='QUEUED'", PrintingService.CANCELED_BEFORE_DISPATCH, tenant, deviceId);
               result = "QUEUED_JOBS_CANCELLED";
            }
            else if("REVOKE".equals(action))
            {
               update(connection, "UPDATE printing_device SET enabled=0,token_hash=?,revoked_at=?,dispatch_paused=1,connection_state='DISCONNECTED',control_revision=? WHERE tenant_id=? AND id=?", UUID.randomUUID().toString(), now, Math.min(9007199254740991L, device.getLong("control_revision") + 1), tenant, deviceId);
               update(connection, "UPDATE printing_device_command SET status='OUTCOME_UNKNOWN' WHERE tenant_id=? AND device_id=? AND status='PENDING'", tenant, deviceId);
               result = "CREDENTIAL_REVOKED";
            }
            else
            {
               int paused = device.getInt("dispatch_paused");
               String connected = device.getString("connection_state");
               if("PAUSE_DISPATCH".equals(action))
               {
                  paused = 1;
               }
               if("RESUME_DISPATCH".equals(action))
               {
                  paused = 0;
               }
               if("DISCONNECT".equals(action))
               {
                  connected = "DISCONNECTED";
               }
               if("RECONNECT".equals(action))
               {
                  connected = "CONNECTED";
               }
               update(connection, "UPDATE printing_device SET dispatch_paused=?,connection_state=?,control_revision=control_revision+1 WHERE tenant_id=? AND id=?", paused, connected, tenant, deviceId);
               result = "DESIRED_STATE_UPDATED";
            }
            update(connection, "INSERT INTO printing_device_command (id,tenant_id,device_id,request_id,action,job_id,actor,request_json,status,created_at,expires_at,result_code,cancelled_job_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
               id, tenant, deviceId, requestId, action, jobId, actor, request.toString(), REMOTE.contains(action) ? "PENDING" : "SUCCEEDED", now, expires, result, cancelled);
            JSONObject response = record(command(connection, deviceId, id));
            connection.commit();
            return (response);
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }


   /** Independent outbound control traffic remains available while disconnected. */
   JSONObject poll(String deviceId, JSONObject request) throws SQLException
   {
      fields(request, Set.of("controlVersion", "agentVersion", "capabilities", "lastAppliedRevision"));
      if(integer(request, "controlVersion") != 1)
      {
         throw new PrintingException(400, "Unsupported control version");
      }
      String version = text(request, "agentVersion", 100);
      long applied = integer(request, "lastAppliedRevision");
      if(!(request.opt("capabilities") instanceof JSONArray capabilities) || capabilities.length() > 2
         || capabilities.toList().stream().anyMatch(value -> !REMOTE.contains(value)) || Set.copyOf(capabilities.toList()).size() != capabilities.length())
      {
         throw new PrintingException(400, "Invalid control capabilities");
      }
      try(Connection connection = source.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            JSONObject device = lock(connection, deviceId, true);
            if(applied > device.getLong("control_revision"))
            {
               throw new PrintingException(409, "Unknown control revision");
            }
            applied = Math.max(applied, device.getLong("control_applied_revision"));
            String now = Instant.now().toString();
            update(connection, "UPDATE printing_device SET control_version=1,control_capabilities=?,control_last_poll_at=?,control_applied_revision=?,agent_version=?,last_seen_at=? WHERE tenant_id=? AND id=?",
               capabilities.toString(), now, applied, version, now, tenant, deviceId);
            expire(connection, deviceId);
            JSONArray commands = new JSONArray();
            for(JSONObject row : query(connection, "SELECT * FROM printing_device_command WHERE tenant_id=? AND device_id=? AND status='PENDING' AND (action<>'REFRESH_INVENTORY' OR ?='CONNECTED') ORDER BY created_at,id LIMIT 20", tenant, deviceId, device.getString("connection_state")))
            {
               JSONObject value = record(row);
               JSONObject delivery = new JSONObject();
               for(String key : List.of("commandId", "requestId", "action", "createdAt", "expiresAt", "jobId"))
               {
                  if(value.has(key))
                  {
                     delivery.put(key, value.get(key));
                  }
               }
               commands.put(delivery);
            }
            connection.commit();
            return (new JSONObject().put("controlVersion", 1).put("serverTime", now).put("pollIntervalSeconds", 5)
               .put("desiredState", state(device).getJSONObject("desiredState")).put("commands", commands));
         }
         catch(Exception exception)
         {
            connection.rollback();
            throw exception;
         }
      }
   }


   /** An immutable receipt records observation, never rewrites printing outcomes. */
   JSONObject receipt(String deviceId, String commandId, JSONObject receipt) throws SQLException
   {
      fields(receipt, Set.of("receiptId", "outcome", "completedAt", "message"));
      if(!text(receipt, "receiptId", 128).matches("[A-Za-z0-9._-]+"))
      {
         throw new PrintingException(400, "Invalid receiptId");
      }
      String outcome = text(receipt, "outcome", 40);
      try
      {
         Instant.parse(utc(receipt, "completedAt"));
      }
      catch(java.time.format.DateTimeParseException exception)
      {
         throw new PrintingException(400, "Invalid receipt time");
      }
      if(receipt.has("message") && (!(receipt.opt("message") instanceof String message) || message.length() > 1024 || message.indexOf('\0') >= 0))
      {
         throw new PrintingException(400, "Invalid receipt message");
      }
      if(receipt.toString().length() > 8000)
      {
         throw new PrintingException(400, "Receipt is too large");
      }
      try(Connection connection = source.getConnection())
      {
         connection.setAutoCommit(false);
         try
         {
            lock(connection, deviceId, true);
            JSONObject row = command(connection, deviceId, commandId);
            String action = row.getString("action");
            if(!REMOTE.contains(action))
            {
               throw new PrintingException(409, "This action has no device receipt");
            }
            Set<String> outcomes = "REFRESH_INVENTORY".equals(action)
               ? Set.of("INVENTORY_REFRESHED", "UNSUPPORTED", "FAILED", "EXPIRED", "OUTCOME_UNKNOWN")
               : Set.of("CANCEL_REQUESTED", "ALREADY_ABSENT", "OWNERSHIP_MISMATCH", "UNSUPPORTED", "FAILED", "EXPIRED", "OUTCOME_UNKNOWN");
            if(!outcomes.contains(outcome))
            {
               throw new PrintingException(400, "Invalid command outcome");
            }
            if(row.has("receipt_json"))
            {
               if(!new JSONObject(row.getString("receipt_json")).similar(receipt))
               {
                  throw new PrintingException(409, "Command receipt is immutable");
               }
            }
            else
            {
               String status = switch(outcome)
               {
                  case "INVENTORY_REFRESHED", "CANCEL_REQUESTED", "ALREADY_ABSENT" -> "SUCCEEDED";
                  case "EXPIRED", "OUTCOME_UNKNOWN" -> outcome;
                  default -> "FAILED";
               };
               update(connection, "UPDATE printing_device_command SET status=?,receipt_json=? WHERE id=? AND tenant_id=? AND device_id=?", status, receipt.toString(), commandId, tenant, deviceId);
            }
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


   /** Management history is bounded and remains readable after revocation. */
   JSONObject page(String deviceId, int limit, String before) throws SQLException
   {
      if(limit < 1 || limit > 200)
      {
         throw new PrintingException(400, "Invalid command page limit");
      }
      try(Connection connection = source.getConnection())
      {
         List<JSONObject> rows;
         if(query(connection, "SELECT id FROM printing_device WHERE tenant_id=? AND id=?", tenant, deviceId).isEmpty())
         {
            throw new PrintingException(404, "Device not found");
         }
         if(before == null)
         {
            rows = query(connection, "SELECT * FROM printing_device_command WHERE tenant_id=? AND device_id=? ORDER BY created_at DESC,id DESC LIMIT ?", tenant, deviceId, limit + 1);
         }
         else
         {
            JSONObject cursor = command(connection, deviceId, before);
            rows = query(connection, "SELECT * FROM printing_device_command WHERE tenant_id=? AND device_id=? AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT ?", tenant, deviceId, cursor.getString("created_at"), cursor.getString("created_at"), before, limit + 1);
         }
         JSONArray commands = new JSONArray();
         for(JSONObject row : rows.subList(0, Math.min(limit, rows.size())))
         {
            commands.put(record(row));
         }
         return (new JSONObject().put("commands", commands).put("nextCursor", rows.size() > limit ? rows.get(limit - 1).getString("id") : JSONObject.NULL));
      }
   }


   /** Look up one exact tenant/device command without exposing another owner. */
   JSONObject get(String deviceId, String commandId) throws SQLException
   {
      try(Connection connection = source.getConnection())
      {
         return (record(command(connection, deviceId, commandId)));
      }
   }


   /** Serialize a device before any state, claim, or command mutation. */
   private JSONObject lock(Connection connection, String deviceId, boolean active) throws SQLException
   {
      List<JSONObject> rows = query(connection, "SELECT * FROM printing_device WHERE tenant_id=? AND id=? FOR UPDATE", tenant, deviceId);
      if(rows.isEmpty() || (active && (rows.getFirst().getInt("enabled") != 1 || rows.getFirst().has("revoked_at"))))
      {
         throw new PrintingException(404, "Device not found");
      }
      return (rows.getFirst());
   }


   /** Expiry stops delivery, but a later first receipt can still report actual execution. */
   private void expire(Connection connection, String deviceId) throws SQLException
   {
      update(connection, "UPDATE printing_device_command SET status='EXPIRED' WHERE tenant_id=? AND device_id=? AND status='PENDING' AND expires_at<?", tenant, deviceId, Instant.now().toString());
   }


   /** Single-record lookup is always scoped, including cursors and receipts. */
   private JSONObject command(Connection connection, String deviceId, String id) throws SQLException
   {
      List<JSONObject> rows = query(connection, "SELECT * FROM printing_device_command WHERE tenant_id=? AND device_id=? AND id=?", tenant, deviceId, id);
      if(rows.isEmpty())
      {
         throw new PrintingException(404, "Command not found");
      }
      return (rows.getFirst());
   }


   /** Stable wire projection excludes raw request and authentication state. */
   private static JSONObject record(JSONObject row)
   {
      JSONObject value = new JSONObject().put("commandId", row.getString("id")).put("requestId", row.getString("request_id")).put("issuedAt", new JSONObject(row.getString("request_json")).getString("issuedAt"))
         .put("deviceId", row.getString("device_id")).put("action", row.getString("action")).put("status", row.getString("status"))
         .put("createdAt", row.getString("created_at")).put("expiresAt", row.getString("expires_at"));
      if("PENDING".equals(row.getString("status")) && Instant.parse(row.getString("expires_at")).isBefore(Instant.now()))
      {
         value.put("status", "EXPIRED");
      }
      if(row.has("job_id"))
      {
         value.put("jobId", row.getString("job_id"));
      }
      if(row.has("result_code"))
      {
         value.put("resultCode", row.getString("result_code"));
      }
      if(row.has("cancelled_job_count"))
      {
         value.put("cancelledJobCount", row.getInt("cancelled_job_count"));
      }
      if(row.has("receipt_json"))
      {
         value.put("receipt", new JSONObject(row.getString("receipt_json")));
      }
      return (value);
   }


   /** Numeric aggregates do not load a receipt history into application memory. */
   private static long count(Connection connection, String sql, Object... values) throws SQLException
   {
      return (query(connection, sql, values).getFirst().getLong("n"));
   }


   /** Reject ambiguous or oversized text before persistence. */
   private static String text(JSONObject object, String field, int maximum)
   {
      if(!(object.opt(field) instanceof String value) || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
      {
         throw new PrintingException(400, "Invalid " + field);
      }
      return (value);
   }


   /** Protocol times have explicit UTC spelling rather than an implicit local zone. */
   private static String utc(JSONObject object, String field)
   {
      String value = text(object, field, 40);
      if(!value.endsWith("Z"))
      {
         throw new PrintingException(400, "UTC timestamp required");
      }
      return (value);
   }

   /** Require an exact nonnegative integral JSON value without coercion. */
   private static long integer(JSONObject object, String field)
   {
      Object value = object.opt(field);
      if(!(value instanceof Integer || value instanceof Long) || (((Number) value).longValue() < 0 || ((Number) value).longValue() > 9007199254740991L))
      {
         throw new PrintingException(400, "Invalid " + field);
      }
      return (((Number) value).longValue());
   }


   /** A closed vocabulary prevents arbitrary command payloads. */
   private static void fields(JSONObject object, Set<String> allowed)
   {
      if(!allowed.containsAll(object.keySet()))
      {
         throw new PrintingException(400, "Unknown control field");
      }
   }
}
