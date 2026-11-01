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


import java.math.BigDecimal;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Native settings forms preserve exact values and enforce current authority.
 *******************************************************************************/
class PrintingRealtimeProcessesTest
{
   /***************************************************************************
    ** Opening settings cannot save them until the operator passes native review.
    ***************************************************************************/
   @Test
   void nativeReviewIsReadOnly() throws Exception
   {
      PrintingService service = fixture();
      var preview = new RunProcessAction().execute(new RunProcessInput().withProcessName(PrintingRealtimeProcesses.SETTINGS));
      assertFalse(preview.getException().isPresent(), () -> preview.getException().toString());
      assertEquals("review", preview.getProcessState().getNextStepName().orElseThrow());
      assertEquals(0, service.getRealtimeSettings().getLong("revision"));
   }



   /***************************************************************************
    ** Large PDFs and fractional human units round-trip without a fixed 20 MiB cap.
    ***************************************************************************/
   @Test
   void exactPolicyPersistsAndRejectsPartialOrInconsistentEdits() throws Exception
   {
      PrintingService service = fixture();
      RunBackendStepInput save = prepared(PrintingRealtimeProcesses.SETTINGS);
      save.addValue("maxPdfMiB", new BigDecimal("250.5"));
      save.addValue("maxConcurrentDownloads", 3);
      new PrintingRealtimeProcesses().run(save, new RunBackendStepOutput());
      assertEquals(262668288L, service.getRealtimeSettings().getLong("maxPdfBytes"));
      assertEquals(3, service.getRealtimeSettings().getInt("maxConcurrentDownloads"));
      assertEquals(1, service.getRealtimeSettings().getLong("revision"));
      RunBackendStepInput fractionalByte = prepared(PrintingRealtimeProcesses.SETTINGS).addValue("maxPdfMiB", new BigDecimal("0.1"));
      assertThrows(QException.class, () -> new PrintingRealtimeProcesses().run(fractionalByte, new RunBackendStepOutput()));
      RunBackendStepInput tooLittleDisk = prepared(PrintingRealtimeProcesses.SETTINGS).addValue("maxStagedMiB", 1);
      assertThrows(QException.class, () -> new PrintingRealtimeProcesses().run(tooLittleDisk, new RunBackendStepOutput()));
      assertThrows(QException.class, () -> new PrintingRealtimeProcesses().run(input(PrintingRealtimeProcesses.SETTINGS, "execute"), new RunBackendStepOutput()));
      assertEquals(1, service.getRealtimeSettings().getLong("revision"));
   }



   /***************************************************************************
    ** Backend invocation cannot bypass either tenant scope or administrator role.
    ***************************************************************************/
   @Test
   void currentUserAndTenantAreRequired() throws Exception
   {
      fixture();
      QInstance instance = QContext.getQInstance();
      RunBackendStepInput save = prepared(PrintingRealtimeProcesses.SETTINGS);
      QContext.init(instance, session("foreign", true));
      assertThrows(QPermissionDeniedException.class, () -> new PrintingRealtimeProcesses().run(save, new RunBackendStepOutput()));
      QContext.init(instance, session("tenant-a", false));
      assertThrows(QPermissionDeniedException.class, () -> new PrintingRealtimeProcesses().run(save, new RunBackendStepOutput()));
   }



   /***************************************************************************
    ** Queue limits use the same typed native forms and survive new service objects.
    ***************************************************************************/
   @Test
   void admissionSettingsPersistAndRejectInvalidCapacity() throws Exception
   {
      PrintingService service = fixture();
      RunBackendStepInput save = prepared(PrintingRealtimeProcesses.ADMISSION).addValue("deviceJobsPerHour", 15000).addValue("deviceOutstandingMiB", new BigDecimal("10240.5"));
      new PrintingRealtimeProcesses().run(save, new RunBackendStepOutput());
      assertEquals(15000, service.getAdmissionSettings().getInt("deviceJobsPerHour"));
      assertEquals(10737942528L, service.getAdmissionSettings().getLong("deviceOutstandingBytes"));
      RunBackendStepInput zero = prepared(PrintingRealtimeProcesses.ADMISSION).addValue("devicePrinters", 0);
      assertThrows(QException.class, () -> new PrintingRealtimeProcesses().run(zero, new RunBackendStepOutput()));
      assertTrue(service.getAdmissionSettings().getInt("devicePrinters") > 0);
   }



   /***************************************************************************
    ** Prepared form values exercise the same complete fields used by the UI.
    ***************************************************************************/
   private static RunBackendStepInput prepared(String process) throws Exception
   {
      RunBackendStepOutput output = new RunBackendStepOutput();
      new PrintingRealtimeProcesses().run(input(process, "prepare"), output);
      RunBackendStepInput save = input(process, "execute");
      output.getValues().forEach(save::addValue);
      return save;
   }



   /***************************************************************************
    ** Each test owns a database and normal authenticated tenant session.
    ***************************************************************************/
   private static PrintingService fixture() throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:realtime-ui-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule.class));
      instance.addTable(new QTableMetaData().withName("fixture").withBackendName("memory").withPrimaryKeyField("id").withField(new QFieldMetaData("id", QFieldType.STRING)));
      instance.addQBit(new QBitMetaData().withGroupId("com.kingsrook.qbits").withArtifactId("qbit-printing").withVersion("test").withNamespace("printing").withConfig(new PrintingQBitConfig().withService(service)));
      QAppMetaData app = new QAppMetaData().withName("printing").withLabel("Printing");
      PrintingRealtimeProcesses.register(instance, app);
      instance.addApp(app);
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.MOCK));
      QContext.init(instance, session("tenant-a", true));
      return service;
   }



   /***************************************************************************
    ** Negative authorization cases retain a real user to test role and tenant.
    ***************************************************************************/
   private static QSession session(String tenant, boolean administrator)
   {
      QSession session = new QSession().withUser(new QUser().withIdReference("operator")).withSecurityKeyValue("printing_tenant", tenant);
      if(administrator)
      {
         session.withPermissions("printing_admin");
      }
      return session;
   }



   /***************************************************************************
    ** Backend entrypoints use explicit process and step names.
    ***************************************************************************/
   private static RunBackendStepInput input(String process, String step)
   {
      RunBackendStepInput input = new RunBackendStepInput().withProcessName(process);
      input.setStepName(step);
      return input;
   }



   /***************************************************************************
    ** Never leak current tenant authority into another test.
    ***************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }
}
