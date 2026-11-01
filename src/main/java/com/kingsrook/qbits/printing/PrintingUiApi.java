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
import java.util.Map;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.MiddlewareSpecExtension;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.MiddlewareSpecExtensionsMetaData;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.Response;
import com.kingsrook.qqq.openapi.model.Schema;
import com.kingsrook.qqq.openapi.model.Type;
import io.javalin.http.Context;


/** Native QQQ printing extension; core session authentication stays authoritative. */
public final class PrintingUiApi
{
   public static final String PREFIX = "/qbit/printing";

   /** Static registry. */
   private PrintingUiApi()
   {
   }

   /** Producer registration gives discovery, OpenAPI, session setup and credential scopes. */
   public static void register(QInstance instance)
   {
      MiddlewareSpecExtensionsMetaData.ofOrWithNew(instance).withExtension(extension());
   }

   /** Semantic extension 2.0.0 requires authenticated realtime delivery for native claims; 2.1.0 adds the record, table-list and text print processes; 2.2.0 adds printingRenderToPdf, the header/footer values and content sections; 2.3.0 adds authorized operator submission, reported queue-default media and the retained-source facade. */
   public static MiddlewareSpecExtension extension()
   {
      return new MiddlewareSpecExtension().withName("printing").withSemanticVersion("2.3.0")
         .withDescription("Native current-user printing with secondary device proof, device-control-v1 and realtime-v1; record, table-list and text printing processes; render to PDF with the app header and footer; least-privilege operator catalog and printer-media-v1.")
         .withSpec(Session.class)
         .withSpec(Bind.class)
         .withSpec(Enroll.class)
         .withSpec(Heartbeat.class)
         .withSpec(Inventory.class)
         .withSpec(Claim.class)
         .withSpec(Queued.class)
         .withSpec(Content.class)
         .withSpec(Event.class)
         .withSpec(Test.class)
         .withSpec(Retry.class)
         .withSpec(Cancel.class)
         .withSpec(Poll.class)
         .withSpec(Receipt.class)
         .withSpec(Action.class)
         .withSpec(Commands.class)
         .withSpec(Command.class)
         .withSpec(OperatorCatalog.class)
         .withSpec(OperatorSubmit.class)
         .withSpec(OperatorRequest.class);
   }



   /***************************************************************************
    ** Current-actor, approved/exposed printer catalog; no administrative permission.
    ***************************************************************************/
   public static class OperatorCatalog extends PrintingUiApiSpec
   {
      /***********************************************************************
       ** Public registry constructor.
       ***********************************************************************/
      public OperatorCatalog()
      {
         super("OperatorCatalog", HttpMethod.GET, "/operator/printers", null, "OperatorCatalog");
      }
   }



   /***************************************************************************
    ** Operator document submission uses the existing durable queue.
    ***************************************************************************/
   public static class OperatorSubmit extends PrintingUiApiSpec
   {
      /***********************************************************************
       ** Public registry constructor.
       ***********************************************************************/
      public OperatorSubmit()
      {
         super("OperatorSubmit", HttpMethod.POST, "/operator/jobs", "OperatorSubmit", "Job");
      }
   }



   /***************************************************************************
    ** Exact actor/request lookup replaces general job history access.
    ***************************************************************************/
   public static class OperatorRequest extends PrintingUiApiSpec
   {
      /***********************************************************************
       ** Public registry constructor.
       ***********************************************************************/
      public OperatorRequest()
      {
         super("OperatorRequest", HttpMethod.GET, "/operator/requests/{requestId}", null, "Job");
      }
   }


   /** Session native endpoint. */
   public static class Session extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Session()
      {
         super("Session", HttpMethod.GET, "/session", null, "Session");
      }

   }


   /** Bind native endpoint. */
   public static class Bind extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Bind()
      {
         super("Bind", HttpMethod.POST, "/device/bind", "Empty", "Acknowledgment");
      }

   }


   /** Enroll native endpoint. */
   public static class Enroll extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Enroll()
      {
         super("Enroll", HttpMethod.POST, "/devices/enroll", "EnrollmentRequest", "Enrollment");
      }

   }


   /** Heartbeat native endpoint. */
   public static class Heartbeat extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Heartbeat()
      {
         super("Heartbeat", HttpMethod.POST, "/device/heartbeat", "HeartbeatRequest", "Heartbeat");
      }

   }


   /** Inventory native endpoint. */
   public static class Inventory extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Inventory()
      {
         super("Inventory", HttpMethod.PUT, "/device/printers", "InventoryRequest", "Acknowledgment");
      }

   }


   /** Claim native endpoint. */
   public static class Claim extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Claim()
      {
         super("Claim", HttpMethod.POST, "/device/jobs/claim", "Empty", "Claim");
      }

   }


   /** Queued native endpoint. */
   public static class Queued extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Queued()
      {
         super("Queued", HttpMethod.GET, "/device/jobs/queued", null, "Jobs");
      }

   }


   /** Content native endpoint. */
   public static class Content extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Content()
      {
         super("Content", HttpMethod.GET, "/device/jobs/{jobId}/content", null, "Empty");
      }

      /** Binary document bytes use an explicit octet-stream OpenAPI response. */
      @Override
      public Map<Integer, Response> defineResponses()
      {
         Map<Integer, Response> responses = new LinkedHashMap<>(super.defineResponses());
         responses.put(200, new Response().withDescription("Verified retained document bytes")
            .withContent(Map.of("application/octet-stream", new com.kingsrook.qqq.openapi.model.Content().withSchema(new Schema().withType(Type.STRING).withFormat("binary")))));
         return responses;
      }

      /** Do not JSON-serialize document contents or include them in logs. */
      @Override
      public void handleOutput(Context context, JsonOutput output)
      {
         context.header("Cache-Control", "no-store").contentType("application/octet-stream").result((java.io.InputStream) output.getResult());
      }
   }


   /** Event native endpoint. */
   public static class Event extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Event()
      {
         super("Event", HttpMethod.POST, "/device/jobs/{jobId}/events", "EventRequest", "Acknowledgment");
      }

   }


   /** Test native endpoint. */
   public static class Test extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Test()
      {
         super("Test", HttpMethod.POST, "/device/test-print", "TestRequest", "Job");
      }

   }


   /** Retry native endpoint. */
   public static class Retry extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Retry()
      {
         super("Retry", HttpMethod.POST, "/device/jobs/{jobId}/retry", "RetryRequest", "Job");
      }

   }


   /** Cancel native endpoint. */
   public static class Cancel extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Cancel()
      {
         super("Cancel", HttpMethod.POST, "/device/jobs/{jobId}/cancel", "Empty", "Acknowledgment");
      }

   }


   /** Poll native endpoint. */
   public static class Poll extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Poll()
      {
         super("Poll", HttpMethod.POST, "/device/control/poll", "ControlPollRequest", "ControlPoll");
      }

   }


   /** Receipt native endpoint. */
   public static class Receipt extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Receipt()
      {
         super("Receipt", HttpMethod.POST, "/device/control/commands/{commandId}/receipt", "ReceiptRequest", "Acknowledgment");
      }

   }


   /** Action native endpoint. */
   public static class Action extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Action()
      {
         super("Action", HttpMethod.POST, "/admin/devices/{deviceId}/actions", "ActionRequest", "Action");
      }

   }


   /** Commands native endpoint. */
   public static class Commands extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Commands()
      {
         super("Commands", HttpMethod.GET, "/admin/devices/{deviceId}/commands", null, "Actions");
      }

   }


   /** Command native endpoint. */
   public static class Command extends PrintingUiApiSpec
   {
      /** Public no-argument constructor required by the core extension registry. */
      public Command()
      {
         super("Command", HttpMethod.GET, "/admin/devices/{deviceId}/commands/{commandId}", null, "Action");
      }

   }
}
