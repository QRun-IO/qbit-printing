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


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.middleware.javalin.schemabuilder.ToSchema;
import com.kingsrook.qqq.openapi.model.Schema;
import com.kingsrook.qqq.openapi.model.Type;


/** Explicit typed native request/response contracts, namespaced independently from core schemas. */
final class PrintingApiSchemas
{
   /** Static schema definitions. */
   private PrintingApiSchemas()
   {
   }


   /** Shared wire components describe every field; no opaque JSON blob substitutes for a contract. */
   static Map<String, Schema> components()
   {
      Map<String, Schema> schemas = new LinkedHashMap<>();
      add(schemas, "Empty", fields());
      add(schemas, "Acknowledgment", fields("accepted", bool()), "accepted");
      add(schemas, "Session", fields("principalId", text(200), "tenantId", text(200), "deviceId", id()), "principalId", "tenantId");
      add(schemas, "EnrollmentRequest", fields("enrollmentCode", text(128), "deviceName", text(1024), "agentVersion", text(100)), "enrollmentCode", "deviceName", "agentVersion");
      add(schemas, "Enrollment", fields("protocolVersion", number(), "deviceId", id(), "deviceToken", text(128).withDescription("Private device proof, returned once; never log."), "pollIntervalSeconds", number()), "protocolVersion", "deviceId", "deviceToken", "pollIntervalSeconds");
      add(schemas, "Profile", fields("name", text(512).withNullable(true), "description", text(4096).withNullable(true), "location", text(512).withNullable(true)));
      add(schemas, "MediaSelection", fields("id", id(), "revision", text(64)), "id", "revision");
      add(schemas, "MediaProfile", fields("id", id(), "revision", text(64), "name", text(512), "widthMicrons", number(), "heightMicrons", number(), "dpiX", number(), "dpiY", number(),
         "source", enumeration("WINDOWS_QUEUE_DEFAULT"), "applyMode", enumeration("DRIVER_DEFAULT")), "id", "revision", "name", "widthMicrons", "heightMicrons", "dpiX", "dpiY", "source", "applyMode");
      add(schemas, "OperatorPrinter", fields("printerRowId", id(), "deviceId", id(), "printerId", id(), "name", text(1024), "deviceName", text(1024), "description", text(4096), "location", text(512), "status", text(40), "formats", array(enumeration("PDF", "ZPL")), "mediaProfiles", array(ref("MediaProfile"))),
         "printerRowId", "deviceId", "printerId", "name", "deviceName", "status", "formats", "mediaProfiles");
      add(schemas, "OperatorCatalog", fields("printers", array(ref("OperatorPrinter")), "nextCursor", text(200).withNullable(true)), "printers", "nextCursor");
      add(schemas, "OperatorSubmit", fields("requestId", text(128), "printerRowId", id(), "title", text(1024), "format", enumeration("PDF", "ZPL"), "contentBase64", text(Integer.MAX_VALUE), "options", ref("Options")),
         "requestId", "printerRowId", "title", "format", "contentBase64");
      add(schemas, "HeartbeatRequest", fields("agentVersion", text(100), "profile", ref("Profile")), "agentVersion");
      add(schemas, "Heartbeat", fields("serverTime", time()), "serverTime");
      add(schemas, "Printer", fields("printerId", id(), "name", text(1024), "driverName", text(1024), "portName", text(1024), "status", enumeration("READY", "OFFLINE", "PAUSED", "ERROR", "UNKNOWN"), "formats", array(enumeration("PDF", "ZPL")), "mediaProfiles", array(ref("MediaProfile")), "displayName", text(512), "description", text(4096), "location", text(512)), "printerId", "name", "formats");
      add(schemas, "InventoryRequest", fields("printers", array(ref("Printer"))), "printers");
      add(schemas, "Options", fields("copies", number(), "scale", enumeration("ACTUAL_SIZE", "FIT"), "mediaProfile", ref("MediaSelection"), "paperWidthMm", new Schema().withType(Type.NUMBER), "paperHeightMm", new Schema().withType(Type.NUMBER)));
      add(schemas, "Job", fields("jobId", id(), "deviceId", id(), "printerId", id(), "title", text(1024), "format", enumeration("PDF", "ZPL"), "contentSha256", text(64), "contentLength", number(), "options", ref("Options"), "createdAt", time(), "status", enumeration("QUEUED", "CLAIMED", "RECEIVED", "SUBMITTING", "SPOOLED", "SPOOLER_COMPLETED", "FAILED", "OUTCOME_UNKNOWN"), "message", text(2048), "spoolerJobId", id()), "jobId", "deviceId", "printerId", "title", "format", "contentSha256", "contentLength", "options", "createdAt", "status");
      add(schemas, "Claim", fields("job", ref("Job").withNullable(true)), "job");
      add(schemas, "Jobs", fields("jobs", array(ref("Job"))), "jobs");
      add(schemas, "EventRequest", fields("eventId", id(), "status", enumeration("RECEIVED", "SUBMITTING", "SPOOLED", "SPOOLER_COMPLETED", "FAILED", "OUTCOME_UNKNOWN"), "message", text(2048), "spoolerJobId", id()), "eventId", "status");
      add(schemas, "TestRequest", fields("requestId", id(), "printerId", id(), "format", enumeration("PDF", "ZPL"), "testProfile", text(40)), "requestId", "printerId", "format");
      add(schemas, "RetryRequest", fields("requestId", id(), "confirmDuplicateRisk", bool()), "requestId");
      add(schemas, "DesiredState", fields("revision", number(), "dispatchPaused", bool(), "connectionState", enumeration("CONNECTED", "DISCONNECTED")), "revision", "dispatchPaused", "connectionState");
      add(schemas, "ControlPollRequest", fields("controlVersion", number(), "agentVersion", text(100), "capabilities", array(enumeration("REFRESH_INVENTORY", "CANCEL_SPOOL", "printer-media-v1")), "lastAppliedRevision", number()), "controlVersion", "agentVersion", "capabilities", "lastAppliedRevision");
      add(schemas, "Command", fields("commandId", id(), "requestId", id(), "action", enumeration("REFRESH_INVENTORY", "CANCEL_SPOOL"), "createdAt", time(), "expiresAt", time(), "jobId", id()), "commandId", "requestId", "action", "createdAt", "expiresAt");
      add(schemas, "ControlPoll", fields("controlVersion", number(), "serverTime", time(), "pollIntervalSeconds", number(), "desiredState", ref("DesiredState"), "commands", array(ref("Command"))), "controlVersion", "serverTime", "pollIntervalSeconds", "desiredState", "commands");
      add(schemas, "ReceiptRequest", fields("receiptId", id(), "outcome", enumeration("INVENTORY_REFRESHED", "CANCEL_REQUESTED", "ALREADY_ABSENT", "OWNERSHIP_MISMATCH", "UNSUPPORTED", "FAILED", "EXPIRED", "OUTCOME_UNKNOWN"), "completedAt", time(), "message", text(1024)), "receiptId", "outcome", "completedAt");
      Schema actions = enumeration("PAUSE_DISPATCH", "RESUME_DISPATCH", "DISCONNECT", "RECONNECT", "REVOKE", "REFRESH_INVENTORY", "CLEAR_QUEUE", "CANCEL_SPOOL");
      add(schemas, "ActionRequest", fields("requestId", id(), "issuedAt", time(), "action", actions, "jobId", id()), "requestId", "issuedAt", "action");
      add(schemas, "Action", fields("commandId", id(), "requestId", id(), "issuedAt", time(), "deviceId", id(), "action", actions, "status", enumeration("PENDING", "SUCCEEDED", "FAILED", "EXPIRED", "OUTCOME_UNKNOWN"), "createdAt", time(), "expiresAt", time(), "jobId", id(), "resultCode", text(80), "cancelledJobCount", number(), "receipt", ref("ReceiptRequest")), "commandId", "requestId", "issuedAt", "deviceId", "action", "status", "createdAt", "expiresAt");
      add(schemas, "Actions", fields("commands", array(ref("Action")), "nextCursor", text(128).withNullable(true)), "commands", "nextCursor");
      return (schemas);
   }


   /** Every schema has the extension prefix required by the native registry. */
   static String name(String shortName)
   {
      return ("Printing" + shortName + "V1");
   }


   /** Refer to one typed reusable component. */
   static Schema ref(String name)
   {
      return (new Schema().withRefToSchema(name(name)));
   }


   /** Bounded Unicode text. */
   static Schema text(int limit)
   {
      return (new Schema().withType(Type.STRING).withMaxLength(limit));
   }


   /** SchemaBuilder customization retains explicit required fields unsupported by the base RC20 model. */
   private record Shape(Map<String, Schema> properties, List<String> required) implements ToSchema
   {
      @Override
      public Schema toSchema()
      {
         return (new RequiredSchema(required).withType(Type.OBJECT).withProperties(properties));
      }
   }


   /** Required is a standard OpenAPI keyword missing from RC20's base Schema bean. */
   public static class RequiredSchema extends Schema
   {
      private final List<String> required;

      /** Retain the explicit protocol field requirements. */
      public RequiredSchema(List<String> required)
      {
         this.required = required;
      }

      /** Published by the core Jackson schema serializer. */
      public List<String> getRequired()
      {
         return (required);
      }
   }


   /** Build typed object fields without map arity limits. */
   private static Map<String, Schema> fields(Object... pairs)
   {
      Map<String, Schema> fields = new LinkedHashMap<>();
      for(int index = 0; index < pairs.length; index += 2)
      {
         fields.put((String) pairs[index], (Schema) pairs[index + 1]);
      }
      return (fields);
   }


   /** Publish a customized typed schema. */
   private static void add(Map<String, Schema> target, String name, Map<String, Schema> properties, String... required)
   {
      target.put(name(name), new Shape(properties, List.of(required)).toSchema());
   }


   /** Protocol identifier. */
   private static Schema id()
   {
      return (text(128));
   }


   /** UTC wire timestamp. */
   private static Schema time()
   {
      return (text(40).withFormat("date-time").withDescription("RFC3339 UTC ending in uppercase Z."));
   }


   /** Exact JSON integer, never a stringified numeric value. */
   private static Schema number()
   {
      return (new Schema().withType(Type.INTEGER));
   }


   /** Required booleans must never be inferred from missing fields. */
   private static Schema bool()
   {
      return (new Schema().withType(Type.BOOLEAN));
   }


   /** Closed action or status vocabulary. */
   private static Schema enumeration(String... values)
   {
      return (new Schema().withType(Type.STRING).withEnumValues(List.of(values)));
   }


   /** Homogeneous typed arrays. */
   private static Schema array(Schema items)
   {
      return (new Schema().withType(Type.ARRAY).withItems(items));
   }
}
