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


import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.jobs.DurableJobsMetaData;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectRequest;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectResult;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectTargets;
import com.kingsrook.qqq.backend.core.jobs.effects.JobEffectType;
import com.kingsrook.qqq.backend.core.jobs.effects.ReconcileResult;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


/*******************************************************************************
 ** Registered durable target behavior under the core dispatcher's system session.
 *******************************************************************************/
class PrintEnqueueEffectTargetTest
{
   private static final String TARGET = "customerPackingSlipQueue";



   /***************************************************************************
    ** Release caller context; the cached target must remain stateless.
    ***************************************************************************/
   @AfterEach
   void clearContext()
   {
      QContext.clear();
      EffectTargets.clear();
   }



   /***************************************************************************
    ** System dispatch, replay and reconciliation need no user tenant key.
    ***************************************************************************/
   @Test
   void systemDeliveryAndReconciliationRemainBoundToCurrentInstanceTenant() throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      String device = configure(service);
      PrintingService otherTenant = new PrintingService(source, "tenant-b");
      String otherDevice = configure(otherTenant);
      QInstance instance = instance(service);
      QContext.init(instance, new QSystemUserSession(instance));
      assertNull(QContext.getQSession().getSecurityKeyValues().get("printing_tenant"));
      var target = EffectTargets.get(TARGET);
      EffectRequest request = request("42:0:shipment-1:print", device);
      assertEquals(ReconcileResult.Outcome.NOT_FOUND, target.reconcile(request).outcome());
      EffectResult sent = target.deliver(request);
      assertEquals(EffectResult.Outcome.SENT, sent.outcome());
      assertNotNull(sent.providerRef());
      assertEquals(sent.providerRef(), target.deliver(request).providerRef());
      assertEquals(sent.providerRef(), service.findByIdempotencyKey(request.idempotencyKey()).getString("jobId"));
      ReconcileResult reconciled = target.reconcile(request);
      assertEquals(ReconcileResult.Outcome.FOUND, reconciled.outcome());
      assertEquals(sent.providerRef(), reconciled.providerRef());
      assertEquals(EffectResult.Outcome.FAILED, target.deliver(request("42:0:foreign:print", otherDevice)).outcome());
      assertNull(otherTenant.findByIdempotencyKey("42:0:foreign:print"));
      QInstance otherInstance = instance(otherTenant);
      QContext.init(otherInstance, new QSystemUserSession(otherInstance));
      assertEquals(ReconcileResult.Outcome.NOT_FOUND, EffectTargets.get(TARGET).reconcile(request).outcome());
      assertEquals(EffectResult.Outcome.SENT, EffectTargets.get(TARGET).deliver(request("43:0:shipment-2:print", otherDevice)).outcome());
      assertNull(service.findByIdempotencyKey("43:0:shipment-2:print"));
   }



   /***************************************************************************
    ** Ordinary inline callers cannot use the trusted dispatcher scope.
    ***************************************************************************/
   @Test
   void inlineDeliveryStillRequiresMatchingTenantScope() throws Exception
   {
      PrintingService service = new PrintingService(new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""), "tenant-a");
      service.initialize();
      String device = configure(service);
      QInstance instance = instance(service);
      QContext.init(instance, new QSession().withPermissions("printing_admin").withSecurityKeyValue("printing_tenant", "tenant-b"));
      var target = EffectTargets.get(TARGET);
      EffectRequest request = request("inline:shipment-1:print", device);
      assertThrows(QPermissionDeniedException.class, () -> target.deliver(request));
      assertThrows(QPermissionDeniedException.class, () -> target.reconcile(request));
      QContext.getQSession().setSecurityKeyValues(Map.of());
      assertThrows(QPermissionDeniedException.class, () -> target.deliver(request));
      assertNull(service.findByIdempotencyKey(request.idempotencyKey()));
      QContext.getQSession().withSecurityKeyValue("printing_tenant", "tenant-a");
      assertEquals(EffectResult.Outcome.SENT, target.deliver(request).outcome());
   }



   /***************************************************************************
    ** Register the same public target contract the real dispatcher resolves.
    ***************************************************************************/
   private static QInstance instance(PrintingService service) throws Exception
   {
      QInstance instance = new QInstance();
      instance.addBackend(new com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData().withName("jobs-memory").withBackendType(com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule.class));
      instance.withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData().withName("mock").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.MOCK));
      new com.kingsrook.qqq.backend.core.jobs.DurableJobsMetaDataProvider().defineAll(instance, "jobs-memory", null);
      instance.addQBit(new QBitMetaData().withGroupId("com.kingsrook.qbits").withArtifactId("qbit-printing").withNamespace("printing").withVersion("0.1.0-rc.1")
         .withConfig(new PrintingQBitConfig().withService(service)));
      instance.withSupplementalMetaData(new DurableJobsMetaData().withEffectTarget(TARGET, new QCodeReference(PrintEnqueueEffectTarget.class)));
      return (instance);
   }



   /***************************************************************************
    ** Synthetic approved printer, without any external transport.
    ***************************************************************************/
   private static String configure(PrintingService service) throws Exception
   {
      JSONObject code = service.createEnrollment("warehouse", "Packing station");
      String device = service.enroll(code.put("deviceName", "Packing station").put("agentVersion", "test")).getString("deviceId");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "zebra").put("name", "Packing Zebra").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "zebra", true);
      return (device);
   }



   /***************************************************************************
    ** Payload cannot choose its tenant or override the durable effect identity.
    ***************************************************************************/
   private static EffectRequest request(String key, String device)
   {
      Map<String, Serializable> payload = Map.of("deviceId", device, "printerId", "zebra", "title", "Durable test", "format", "ZPL",
         "contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(StandardCharsets.UTF_8)), "idempotencyKey", "ignored-payload-key");
      return (new EffectRequest(key, "print", JobEffectType.IDEMPOTENT_EXTERNAL, 42, 0, "shipment-1", payload, null, 1));
   }
}
