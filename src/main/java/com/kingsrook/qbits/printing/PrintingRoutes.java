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


import java.util.function.Predicate;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.middleware.javalin.QJavalinRouteProviderInterface;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.json.JSONException;
import org.json.JSONObject;


/*******************************************************************************
 ** Protocol v1 routes. The host supplies its existing administrator auth guard.
 *******************************************************************************/
public class PrintingRoutes implements QJavalinRouteProviderInterface
{
   public static final String BASE = "/printing/v1";
   private final PrintingService service;
   private final Predicate<Context> administrator;



   /***************************************************************************
    ** Attach this route provider to the host's QApplicationJavalinServer.
    ***************************************************************************/
   public PrintingRoutes(PrintingService service, Predicate<Context> administrator)
   {
      this.service = service;
      this.administrator = administrator;
   }



   /***************************************************************************
    ** Runtime ownership is supplied by the service, not a global QInstance.
    ***************************************************************************/
   @Override
   public void setQInstance(QInstance instance)
   {
      // The constructor binds this provider to one application's service.
   }



   /***************************************************************************
    ** Register routes before server start, as required by Javalin 7.
    ***************************************************************************/
   @Override
   public void acceptJavalinConfig(JavalinConfig config)
   {
      config.routes.post(BASE + "/enroll", context -> handle(context, () -> service.enroll(body(context))));
      config.routes.put(BASE + "/device/printers", context -> handle(context, () ->
      {
         service.inventory(device(context), body(context).getJSONArray("printers"));
         return accepted();
      }));
      config.routes.post(BASE + "/device/heartbeat", context -> handle(context, () -> service.heartbeat(device(context), body(context))));
      config.routes.post(BASE + "/device/jobs/claim", context -> handle(context, () -> service.claim(device(context))));
      config.routes.get(BASE + "/device/jobs/queued", context -> handle(context, () -> new JSONObject().put("jobs", service.queuedJobs(device(context)))));
      config.routes.post(BASE + "/device/test-print", context -> handle(context, () -> service.testPrint(device(context), body(context))));
      config.routes.post(BASE + "/device/jobs/{jobId}/retry", context -> handle(context, () -> service.retry(device(context), context.pathParam("jobId"), body(context))));
      config.routes.post(BASE + "/device/jobs/{jobId}/cancel", context -> handle(context, () -> service.cancel(device(context), context.pathParam("jobId"))));
      config.routes.get(BASE + "/device/jobs/{jobId}/content", context -> handle(context, () ->
      {
         byte[] bytes = service.content(device(context), context.pathParam("jobId"));
         context.contentType("application/octet-stream").result(bytes);
         return null;
      }));
      config.routes.post(BASE + "/device/jobs/{jobId}/events", context -> handle(context, () ->
      {
         service.event(device(context), context.pathParam("jobId"), body(context));
         return accepted();
      }));
      config.routes.post(BASE + "/admin/enrollments", context -> handle(context, () ->
      {
         admin(context);
         JSONObject body = body(context);
         context.status(201);
         return service.createEnrollment(body.getString("facilityId"), body.getString("deviceName"));
      }));
      config.routes.get(BASE + "/admin/devices", context -> handle(context, () ->
      {
         admin(context);
         return new JSONObject().put("devices", service.devices());
      }));
      config.routes.get(BASE + "/admin/devices/{deviceId}/printers", context -> handle(context, () ->
      {
         admin(context);
         return new JSONObject().put("printers", service.printers(context.pathParam("deviceId")));
      }));
      config.routes.put(BASE + "/admin/devices/{deviceId}/printers/{printerId}", context -> handle(context, () ->
      {
         admin(context);
         service.enablePrinter(context.pathParam("deviceId"), context.pathParam("printerId"), body(context).getBoolean("enabled"));
         return accepted();
      }));
      config.routes.post(BASE + "/admin/jobs", context -> handle(context, () ->
      {
         admin(context);
         return service.submit(body(context));
      }));
      config.routes.get(BASE + "/admin/jobs", context -> handle(context, () ->
      {
         admin(context);
         String limit = context.queryParam("limit");
         return service.jobsPage(limit == null ? 200 : Integer.parseInt(limit), context.queryParam("before"));
      }));
      config.routes.get(BASE + "/admin/jobs/{jobId}", context -> handle(context, () ->
      {
         admin(context);
         return service.getJob(context.pathParam("jobId"));
      }));
   }



   /***************************************************************************
    ** Verify a device bearer credential for every operation.
    ***************************************************************************/
   private String device(Context context) throws java.sql.SQLException
   {
      return (service.authenticate(bearer(context)));
   }



   /***************************************************************************
    ** Require the host's explicit administrator policy.
    ***************************************************************************/
   private void admin(Context context)
   {
      if(!administrator.test(context))
      {
         throw new PrintingException(403, "Printing administrator permission required");
      }
   }



   /***************************************************************************
    ** Extract credentials only from the Authorization header.
    ***************************************************************************/
   public static String bearer(Context context)
   {
      String header = context.header("Authorization");
      return (header != null && header.startsWith("Bearer ") ? header.substring(7) : "");
   }



   /***************************************************************************
    ** Parse bounded protocol JSON; the server also configures request limits.
    ***************************************************************************/
   private static JSONObject body(Context context)
   {
      if(context.bodyAsBytes().length > 29 * 1024 * 1024)
      {
         throw new PrintingException(413, "Request is too large");
      }
      return (new JSONObject(context.body()));
   }



   /***************************************************************************
    ** Standard mutation acknowledgment.
    ***************************************************************************/
   private static JSONObject accepted()
   {
      return (new JSONObject().put("accepted", true));
   }



   /***************************************************************************
    ** Scope error handling to this plugin's routes; never expose SQL/secrets.
    ***************************************************************************/
   private static void handle(Context context, Operation operation)
   {
      context.header("Cache-Control", "no-store");
      try
      {
         JSONObject result = operation.run();
         if(result != null)
         {
            context.contentType("application/json").result(result.toString());
         }
      }
      catch(PrintingException exception)
      {
         error(context, exception.getStatus(), exception.getMessage());
      }
      catch(JSONException | IllegalArgumentException exception)
      {
         error(context, 400, "Invalid request");
      }
      catch(Exception exception)
      {
         error(context, 500, "Printing operation failed");
      }
   }



   /***************************************************************************
    ** Emit the shared protocol error envelope.
    ***************************************************************************/
   private static void error(Context context, int status, String message)
   {
      context.status(status).contentType("application/json").result(new JSONObject().put("code", "PRINTING_" + status).put("message", message).toString());
   }



   /***************************************************************************
    ** Checked route operation.
    ***************************************************************************/
   @FunctionalInterface
   private interface Operation
   {
      /************************************************************************
       ** Execute the route's service operation.
       ************************************************************************/
      JSONObject run() throws Exception;
   }
}


