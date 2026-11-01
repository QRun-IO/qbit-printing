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


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.exceptions.QBadRequestException;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.middleware.javalin.QJavalinUtils;
import com.kingsrook.qqq.middleware.javalin.specs.BasicOperation;
import com.kingsrook.qqq.middleware.javalin.specs.BasicResponse;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.AbstractJsonEndpointSpec;
import com.kingsrook.qqq.middleware.javalin.specs.v1.responses.BasicErrorResponseV1;
import com.kingsrook.qqq.openapi.model.HttpMethod;
import com.kingsrook.qqq.openapi.model.In;
import com.kingsrook.qqq.openapi.model.Parameter;
import com.kingsrook.qqq.openapi.model.Schema;
import com.kingsrook.qqq.openapi.model.Type;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;


/** Native secured QQQ specs: session setup and credential scope execute before request construction. */
public abstract class PrintingUiApiSpec extends AbstractJsonEndpointSpec
{
   private final String operation;
   private final String path;
   private final HttpMethod method;
   private final @Nullable String requestSchema;
   private final String responseSchema;


   /** Immutable operation metadata; request credentials never live on shared spec instances. */
   protected PrintingUiApiSpec(String operation, HttpMethod method, String path, @Nullable String requestSchema, String responseSchema)
   {
      this.operation = operation;
      this.method = method;
      this.path = path;
      this.requestSchema = requestSchema;
      this.responseSchema = responseSchema;
   }


   /** All operations are discoverable under the registered extension namespace. */
   @Override
   public BasicOperation defineBasicOperation()
   {
      return new BasicOperation().withHttpMethod(method).withPath(PrintingUiApi.PREFIX + path)
         .withOperationId("printing" + operation).withShortSummary("Printing " + operation)
         .withLongDescription(operation.startsWith("Operator") ? "Requires the current QQQ user session, printing_operator or printing_admin, current tenant and native destination record access. Submissions additionally require printingSubmit process and credential/channel write authority."
            : "Requires the current QQQ user session, printing_admin permission and native record access. Device operations additionally require secondary device headers bound to this principal. No primary device-bearer authentication.");
   }


   /** Typed request bodies use the same wire vocabulary as the native client. */
   @Override
   protected @Nullable Schema defineRequestBodySchema()
   {
      return requestSchema == null ? null : PrintingApiSchemas.ref(requestSchema);
   }


   /** Typed response body. */
   @Override
   protected String getResponseSchemaName()
   {
      return PrintingApiSchemas.name(responseSchema);
   }


   /** Complete namespaced component definitions are merged into the core OpenAPI document. */
   @Override
   public Map<String, Schema> defineComponentSchemas()
   {
      return PrintingApiSchemas.components();
   }


   /** Document secondary proof independently from core session authentication. */
   @Override
   public List<Parameter> defineRequestParameters()
   {
      List<Parameter> parameters = new ArrayList<>();
      if(path.startsWith("/device/") || "Session".equals(operation))
      {
         boolean required = !"Session".equals(operation);
         parameters.add(new Parameter().withName(PrintingAgentAuthorization.DEVICE_HEADER).withIn(In.HEADER).withRequired(required).withSchema(PrintingApiSchemas.text(36)));
         parameters.add(new Parameter().withName(PrintingAgentAuthorization.TOKEN_HEADER).withIn(In.HEADER).withRequired(required).withSchema(PrintingApiSchemas.text(128)).withDescription("Private secondary device proof; never log."));
      }
      for(String name : List.of("deviceId", "jobId", "commandId", "requestId"))
      {
         if(path.contains("{" + name + "}"))
         {
            parameters.add(new Parameter().withName(name).withIn(In.PATH).withRequired(true).withSchema(PrintingApiSchemas.text(128)));
         }
      }
      if("Commands".equals(operation))
      {
         parameters.add(new Parameter().withName("limit").withIn(In.QUERY).withSchema(new Schema().withType(Type.INTEGER)).withDescription("1 through 200; default 50."));
         parameters.add(new Parameter().withName("before").withIn(In.QUERY).withSchema(PrintingApiSchemas.text(128)));
      }
      if("OperatorCatalog".equals(operation))
      {
         parameters.add(new Parameter().withName("limit").withIn(In.QUERY).withSchema(new Schema().withType(Type.INTEGER)).withDescription("1 through 200; default 50."));
         parameters.add(new Parameter().withName("after").withIn(In.QUERY).withSchema(PrintingApiSchemas.text(200)));
      }
      return parameters;
   }


   /** Authenticate before processing, retain only the verified public device ID in the request input. */
   @Override
   public JsonInput buildInput(Context context) throws Exception
   {
      context.header("Cache-Control", "no-store");
      if(operation.startsWith("Operator"))
      {
         PrintingOperatorAuthorization.principal();
      }
      else
      {
         PrintingControlAuthorization.principal();
      }
      long maximum = "Inventory".equals(operation) ? 2097152 : 32768;
      if("OperatorSubmit".equals(operation))
      {
         PrintingOperatorAuthorization.requireSubmission();
         PrintingRealtimePolicy policy = PrintingProcesses.currentService().realtimePolicy();
         maximum = ((Math.max(policy.get("maxPdfBytes"), policy.get("maxZplBytes")) + 2) / 3) * 4 + 65536;
      }
      if(context.bodyAsBytes().length > maximum)
      {
         throw new PrintingException(413, "Printing request exceeds its size limit");
      }
      JsonInput input = super.buildInput(context);
      if("Empty".equals(requestSchema) && !input.getBody().isEmpty())
      {
         throw new QBadRequestException("This operation requires an empty JSON object");
      }
      String id = context.header(PrintingAgentAuthorization.DEVICE_HEADER);
      String token = context.header(PrintingAgentAuthorization.TOKEN_HEADER);
      if(path.startsWith("/device/") || ("Session".equals(operation) && (id != null || token != null)))
      {
         input.getPathParams().put("_verifiedDevice", PrintingAgentAuthorization.device(id, token, "Bind".equals(operation)));
      }
      return input;
   }


   /** Domain mutations follow native user/table/row/process checks, never generic table-write bypass flags. */
   @Override
   protected Object handle(JsonInput input) throws Exception
   {
      PrintingService service = PrintingProcesses.currentService();
      if(operation.startsWith("Operator"))
      {
         JSONObject result = switch(operation)
         {
            case "OperatorCatalog" ->
            {
               Integer requestedLimit = input.getQueryParamInteger("limit");
               yield PrintingOperatorAuthorization.catalog(requestedLimit == null ? 50 : requestedLimit, input.getQueryParam("after"));
            }
            case "OperatorSubmit" -> PrintingOperatorAuthorization.submit(new JSONObject(input.getBody()));
            case "OperatorRequest" -> PrintingOperatorAuthorization.observe(input.getPathParam("requestId"));
            default -> throw new PrintingException(400, "Unknown operator operation");
         };
         return result.toMap();
      }
      String principal = PrintingControlAuthorization.principal();
      String device = input.getPathParam("_verifiedDevice");
      JSONObject body = new JSONObject(input.getBody());
      String job = input.getPathParam("jobId");
      Object result;
      switch(operation)
      {
         case "Session" ->
         {
            result = new JSONObject().put("principalId", principal).put("tenantId", service.getTenantId()).put("deviceId", device);
         }
         case "Bind" -> result = accepted();
         case "Enroll" ->
         {
            PrintingControlAuthorization.process("printingEnrollDevice");
            PrintingControlAuthorization.table(PrintingTables.DEVICE);
            String facility = service.enrollmentFacility(body.optString("enrollmentCode"));
            try
            {
               PrintingFacilities.validate(facility);
            }
            catch(QException denied)
            {
               throw new QPermissionDeniedException("Enrollment facility is not accessible");
            }
            result = service.enrollForUser(body, principal);
         }
         case "Heartbeat" -> result = service.heartbeat(device, body);
         case "Inventory" ->
         {
            service.inventory(device, body.getJSONArray("printers"), PrintingAgentAuthorization.inventory(device));
            result = accepted();
         }
         case "Claim" ->
         {
            service.requireRealtimeReady(device);
            result = PrintingAgentAuthorization.claim(device);
         }
         case "Queued" ->
         {
            PrintingControlAuthorization.table(PrintingTables.JOB);
            JSONArray jobs = service.queuedJobs(device);
            for(int index = 0; index < jobs.length(); index++)
            {
               PrintingControlAuthorization.job(device, jobs.getJSONObject(index).getString("jobId"));
            }
            result = new JSONObject().put("jobs", jobs);
         }
         case "Content" ->
         {
            PrintingControlAuthorization.job(device, job);
            return service.openContent(device, job);
         }
         case "Event" ->
         {
            PrintingControlAuthorization.job(device, job);
            service.event(device, job, body);
            result = accepted();
         }
         case "Test" ->
         {
            PrintingAgentAuthorization.printer(device, body.optString("printerId"));
            PrintingControlAuthorization.process("PDF".equals(body.optString("format")) ? "printingTestPdf" : "printingTestZpl");
            result = service.testPrint(device, body);
         }
         case "Retry" ->
         {
            PrintingControlAuthorization.job(device, job);
            PrintingControlAuthorization.process(PrintingJobProcesses.RETRY);
            result = service.retry(device, job, body);
         }
         case "Cancel" ->
         {
            PrintingControlAuthorization.job(device, job);
            PrintingControlAuthorization.process(PrintingJobProcesses.CANCEL);
            result = service.cancel(device, job);
         }
         case "Poll" ->
         {
            PrintingControlAuthorization.table(PrintingTables.COMMAND);
            JSONObject poll = service.deviceControlPoll(device, body);
            JSONArray commands = poll.getJSONArray("commands");
            for(int index = 0; index < commands.length(); index++)
            {
               command(device, commands.getJSONObject(index).getString("commandId"));
            }
            result = poll;
         }
         case "Receipt" ->
         {
            command(device, input.getPathParam("commandId"));
            result = service.deviceControlReceipt(device, input.getPathParam("commandId"), body);
         }
         case "Action" -> result = PrintingControlAuthorization.deviceAction(input.getPathParam("deviceId"), body);
         case "Commands" ->
         {
            device = input.getPathParam("deviceId");
            PrintingControlAuthorization.record(PrintingTables.DEVICE, device);
            PrintingControlAuthorization.table(PrintingTables.COMMAND);
            Integer limit = input.getQueryParamInteger("limit");
            JSONObject page = service.deviceCommandsPage(device, limit == null ? 50 : limit, input.getQueryParam("before"));
            JSONArray commands = page.getJSONArray("commands");
            for(int index = 0; index < commands.length(); index++)
            {
               command(device, commands.getJSONObject(index).getString("commandId"));
            }
            result = page;
         }
         case "Command" ->
         {
            device = input.getPathParam("deviceId");
            PrintingControlAuthorization.record(PrintingTables.DEVICE, device);
            command(device, input.getPathParam("commandId"));
            result = service.getDeviceCommand(device, input.getPathParam("commandId"));
         }
         default -> throw new PrintingException(400, "Unknown printing operation");
      }
      return result instanceof JSONObject json ? json.toMap() : result;
   }


   /** Receipt and history access apply child-record security independently of device access. */
   static void command(String device, String id) throws Exception
   {
      QRecord command = PrintingControlAuthorization.record(PrintingTables.COMMAND, id);
      if(!device.equals(command.getValueString("device_id")))
      {
         throw new QPermissionDeniedException("Command is not assigned to this device");
      }
      if(command.getValueString("job_id") != null)
      {
         PrintingControlAuthorization.job(device, command.getValueString("job_id"));
      }
   }


   /** Mutation success does not assert a physical print outcome. */
   private static JSONObject accepted()
   {
      return new JSONObject().put("accepted", true);
   }


   /** Use the core error envelope and suppress backend/credential diagnostics. */
   @Override
   protected void handleException(Context context, Exception exception)
   {
      Throwable cause = exception;
      while(cause.getCause() != null && !(cause instanceof PrintingException) && !(cause instanceof QAuthenticationException)
         && !(cause instanceof QPermissionDeniedException) && !(cause instanceof QBadRequestException))
      {
         cause = cause.getCause();
      }
      int status = cause instanceof PrintingException printing ? printing.getStatus()
         : cause instanceof QAuthenticationException ? 401 : cause instanceof QPermissionDeniedException ? 403
         : cause instanceof QBadRequestException || cause instanceof org.json.JSONException ? 400 : 500;
      String message = status == 500 ? "The printing operation could not be completed"
         : status == 401 ? "Sign in to continue" : status == 403 ? "User or device is not authorized" : cause.getMessage();
      context.header("Cache-Control", "no-store").contentType("application/json");
      QJavalinUtils.respondWithError(context, org.eclipse.jetty.http.HttpStatus.getCode(status), message);
   }


   /** Standard native authentication, authorization, conflict and capacity responses. */
   @Override
   public List<BasicResponse> defineAdditionalBasicResponses()
   {
      return List.of(new BasicResponse(HttpStatus.BAD_REQUEST, "Invalid request", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.UNAUTHORIZED, "Native QQQ sign-in required", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.FORBIDDEN, "Permission, device proof or user binding denied", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.NOT_FOUND, "Record not found", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.CONFLICT, "State or request conflict", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.GONE, "Request or content expired", BasicErrorResponseV1.class.getSimpleName()),
         new BasicResponse(HttpStatus.TOO_MANY_REQUESTS, "Bounded capacity reached", BasicErrorResponseV1.class.getSimpleName()));
   }
}
