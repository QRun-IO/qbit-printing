/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2026.  Kingsrook, LLC
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


import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.json.JSONObject;


/*******************************************************************************
 ** Native authorized acceptance of exact retained originals. No device wire DTO
 ** changes, owner-specific dependencies, alternate queue, or impersonation.
 *******************************************************************************/
public final class RetainedPrintFacade
{
   /** Immutable owner metadata; the canonical reference binds source/version/generation/actor/tenant, never credentials. */
   public record SourceReference(String owner, String opaqueReference, String sha256, long sizeBytes, String mediaType)
   {
      /** Validate before allocation, persistence or native dispatch. */
      public SourceReference
      {
         text(owner, 100);
         text(opaqueReference, 4000);
         if(sizeBytes < 1 || sizeBytes > Integer.MAX_VALUE || sha256 == null || !sha256.matches("[a-f0-9]{64}")
            || !("application/pdf".equals(mediaType) || "application/vnd.zebra-zpl".equals(mediaType)))
         {
            throw new PrintingException(400, "Invalid retained source metadata");
         }
      }

      /** Fixed fields, not an arbitrary caller JSON document. */
      JSONObject json()
      {
         return (new JSONObject().put("owner", owner).put("opaqueReference", opaqueReference).put("sha256", sha256)
            .put("sizeBytes", sizeBytes).put("mediaType", mediaType));
      }
   }

   /** Caller chooses only an intent, owner locator and native destination, not an actor. */
   public record Request(String requestId, String sourceLocator, String printerRecordId, String title, String optionsJson)
   {
      /** Freeze and bound the complete input before invoking trusted adapters. */
      public Request
      {
         requestIdentity(requestId);
         text(sourceLocator, 200);
         text(printerRecordId, 36);
         text(title, 1024);
         if(optionsJson == null || optionsJson.length() > 2000)
         {
            throw new PrintingException(400, "Invalid print options");
         }
         new JSONObject(optionsJson);
      }
   }

   /** Server acceptance is queue durability, not device or physical-output evidence. */
   public record Acceptance(String requestId, String jobId, String acceptedEventId, Instant acceptedAt, String tenantId,
      String actorId, SourceReference source, String deviceId, String printerId, String printerRecordId, String title, String optionsJson)
   {
   }

   /** Exact immutable device observation, separate from the server acceptance event. */
   public record Observation(Acceptance acceptance, String eventId, Instant recordedAt, String state, String spoolerJobId)
   {
   }

   /** Resolve current source and destination authority, then use the existing queue transaction. */
   public Acceptance submit(Request request) throws QException
   {
      Prepared prepared = prepare(request, true);
      try
      {
         JSONObject result = prepared.service().submitRetained(prepared.wire(), prepared.binding(), prepared.actor(),
            () -> prepared.check(), () ->
            {
               prepared.check();
               byte[] bytes = prepared.authority().read(prepared.source(), request.requestId());
               prepared.check();
               if(bytes == null || bytes.length != prepared.source().sizeBytes())
               {
                  throw new QException("Retained source changed its content length");
               }
               return bytes;
            });
         Acceptance accepted = acceptance(result);
         checkDestination(accepted);
         prepared.check();
         return (accepted);
      }
      catch(SQLException exception)
      {
         throw new QException("Retained print acceptance unavailable; reconcile the same request identity", exception);
      }
   }

   /** Absence is returned only when neither an accepted job nor an unresolved upload exists. */
   public Acceptance find(Request request) throws QException
   {
      Prepared prepared = prepare(request, false);
      try
      {
         JSONObject row = prepared.service().findRetained(key(request.requestId()), prepared.binding());
         prepared.check();
         if(row == null)
         {
            return null;
         }
         Acceptance accepted = acceptance(row);
         checkDestination(accepted);
         prepared.check();
         return (accepted);
      }
      catch(SQLException exception)
      {
         throw new QException("Retained print lookup unavailable", exception);
      }
   }

   /** Current operator needs owner observation authority and the exact destination, never generic job access. */
   public Observation observation(String requestId, String eventId) throws QException
   {
      requestIdentity(requestId);
      text(eventId, 128);
      String reporter = PrintingOperatorAuthorization.principal();
      requireStorage(false);
      PrintingService service = PrintingProcesses.currentService();
      RetainedPrintSourceAuthority authority = authority(service);
      var instance = QContext.getQInstance();
      var session = QContext.getQSession();
      try
      {
         JSONObject stored = service.findRetained(key(requestId), null);
         if(stored == null)
         {
            throw new PrintingException(404, "Accepted print intent not found");
         }
         Acceptance accepted = acceptance(stored);
         checkDestination(accepted);
         JSONObject proof = service.retainedObservation(key(requestId), eventId);
         if(!accepted.equals(acceptance(proof.getJSONObject("acceptance"))))
         {
            throw new PrintingException(409, "Accepted print binding changed");
         }
         authority.authorizeObservation(accepted.source(), requestId);
         if(instance != QContext.getQInstance() || session != QContext.getQSession()
            || !reporter.equals(PrintingOperatorAuthorization.principal()) || service != PrintingProcesses.currentService()
            || authority != authority(service))
         {
            throw new QException("Printing reporter authority changed");
         }
         requireStorage(false);
         checkDestination(accepted);
         return (new Observation(accepted, eventId, Instant.parse(proof.getString("recordedAt")), proof.getString("state"),
            proof.isNull("spoolerJobId") ? null : proof.getString("spoolerJobId")));
      }
      catch(SQLException exception)
      {
         throw new QException("Committed print observation unavailable", exception);
      }
   }

   /** Capture one native request context and trusted configuration; callbacks cannot retarget it. */
   private Prepared prepare(Request request, boolean mutation) throws QException
   {
      Objects.requireNonNull(request);
      if(QContext.getQBackendTransaction() != null)
      {
         throw new QException("Retained print acceptance requires its own committed transaction");
      }
      String actor = PrintingOperatorAuthorization.principal();
      text(actor, 200);
      requireStorage(mutation);
      if(mutation)
      {
         PrintingOperatorAuthorization.requireSubmission();
      }
      PrintingService service = PrintingProcesses.currentService();
      var instance = QContext.getQInstance();
      var session = QContext.getQSession();
      RetainedPrintSourceAuthority authority = authority(service);
      QRecord printer = mutation ? PrintingOperatorAuthorization.destination(request.printerRecordId())
         : PrintingOperatorAuthorization.authorizeDestinationHistory(request.printerRecordId());
      String device = printer.getValueString("device_id");
      String printerId = printer.getValueString("printer_id");
      SourceReference source = Objects.requireNonNull(authority.resolve(request.sourceLocator(), request.requestId()));
      JSONObject wire = new JSONObject().put("deviceId", device).put("printerId", printerId).put("idempotencyKey", key(request.requestId()))
         .put("title", request.title()).put("format", "application/pdf".equals(source.mediaType()) ? "PDF" : "ZPL")
         .put("options", new JSONObject(request.optionsJson()));
      JSONObject binding = new JSONObject().put("requestId", request.requestId()).put("actorId", actor).put("source", source.json())
         .put("printerRecordId", request.printerRecordId()).put("deviceId", device).put("printerId", printerId).put("title", request.title())
         .put("options", new JSONObject(request.optionsJson())).put("tenantId", service.getTenantId());
      Checked check = () ->
      {
         requireStorage(mutation);
         if(mutation)
         {
            PrintingOperatorAuthorization.requireSubmission();
         }
         if(instance != QContext.getQInstance() || session != QContext.getQSession() || QContext.getQBackendTransaction() != null
            || service != PrintingProcesses.currentService() || authority != authority(service) || !actor.equals(PrintingOperatorAuthorization.principal()))
         {
            throw new QException("Retained print authority changed");
         }
         QRecord current = mutation ? PrintingOperatorAuthorization.printer(request.printerRecordId(), wire.getString("format"))
            : PrintingOperatorAuthorization.authorizeDestinationHistory(request.printerRecordId());
         if(!device.equals(current.getValueString("device_id")) || !printerId.equals(current.getValueString("printer_id")))
         {
            throw new QException("Retained print destination changed");
         }
      };
      check.run();
      Prepared prepared = new Prepared(service, authority, source, actor, wire, binding, () ->
      {
         check.run();
         SourceReference current = authority.resolve(request.sourceLocator(), request.requestId());
         check.run();
         if(!source.equals(current))
         {
            throw new QException("Retained print source changed");
         }
      });
      prepared.check();
      return (prepared);
   }


   /** Historical evidence requires current record authority without granting generic queue reads. */
   private static void checkDestination(Acceptance accepted) throws QException
   {
      QRecord printer = PrintingOperatorAuthorization.authorizeDestinationHistory(accepted.printerRecordId());
      if(!accepted.deviceId().equals(printer.getValueString("device_id")) || !accepted.printerId().equals(printer.getValueString("printer_id")))
      {
         throw new QException("Accepted print destination changed");
      }
   }

   /** Keep owner service writes within native credential/channel ceilings, without generic edit roles. */
   private static void requireStorage(boolean mutation) throws QException
   {
      var instance = QContext.getQInstance();
      var table = instance == null ? null : instance.getTable(PrintingTables.JOB);
      if(table == null)
      {
         throw new QException("Printing job metadata is unavailable");
      }
      var rules = PermissionsHelper.getEffectivePermissionRules(table, instance);
      if(PermissionsHelper.isDeniedForClientChannel(table, rules)
         || (mutation && PermissionsHelper.isWriteDeniedForClientChannel(table, rules))
         || PermissionsHelper.isOutsideCredentialScope(table, mutation ? TablePermissionSubType.INSERT : TablePermissionSubType.READ))
      {
         throw new com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException("Current credential or client channel cannot access print acceptance");
      }
   }

   /** Only the host's matching instance configuration can supply owner authority. */
   private static RetainedPrintSourceAuthority authority(PrintingService service) throws QException
   {
      for(var metadata : QContext.getQInstance().getQBits().values())
      {
         if(metadata.getConfig() instanceof PrintingQBitConfig config && config.getService() == service && config.getRetainedSourceAuthority() != null)
         {
            return (config.getRetainedSourceAuthority());
         }
      }
      throw new QException("Retained printing is not configured");
   }

   /** Package-private checked transaction callbacks never originate in request JSON. */
   @FunctionalInterface
   interface Checked
   {
      /** Reauthorize without committing any printing state. */
      void run() throws QException;
   }

   /** One request-local immutable capture; JSON never escapes to callers. */
   private record Prepared(PrintingService service, RetainedPrintSourceAuthority authority, SourceReference source,
      String actor, JSONObject wire, JSONObject binding, Checked verifier)
   {
      /** Run the exact capture's current-authority check. */
      void check() throws QException
      {
         verifier.run();
      }
   }

   /** Stable namespace prevents accidental collision with ordinary producer keys. */
   static String key(String requestId)
   {
      return ("retained:" + requestId);
   }


   /** Shared stable intent syntax supports owner namespaces such as echo:scan-id. */
   private static void requestIdentity(String value)
   {
      if(value == null || !value.matches("[A-Za-z0-9._:-]{1,128}"))
      {
         throw new PrintingException(400, "Invalid retained print request identity");
      }
   }

   /** Decode only a server-built accepted proof, never a wire job or caller payload. */
   private static Acceptance acceptance(JSONObject row)
   {
      JSONObject binding = row.getJSONObject("binding");
      JSONObject source = binding.getJSONObject("source");
      return (new Acceptance(binding.getString("requestId"), row.getString("jobId"), row.getString("acceptedEventId"),
         Instant.parse(row.getString("acceptedAt")), binding.getString("tenantId"), binding.getString("actorId"),
         new SourceReference(source.getString("owner"), source.getString("opaqueReference"), source.getString("sha256"),
            source.getLong("sizeBytes"), source.getString("mediaType")), binding.getString("deviceId"), binding.getString("printerId"), binding.getString("printerRecordId"),
         binding.getString("title"), binding.getJSONObject("options").toString()));
   }

   /** Reject malformed Unicode, controls and oversized identifiers without logging values. */
   private static void text(String value, int maximum)
   {
      if(value == null || value.isBlank() || value.length() > maximum)
      {
         throw new PrintingException(400, "Invalid retained print identifier");
      }
      for(int i = 0; i < value.length(); i++)
      {
         char c = value.charAt(i);
         if(Character.isISOControl(c) || Character.isLowSurrogate(c)
            || (Character.isHighSurrogate(c) && (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))))
         {
            throw new PrintingException(400, "Invalid retained print identifier");
         }
      }
   }
}
