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


import java.util.List;
import java.util.Map;
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
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Native retention forms enforce administrator and tenant scope independently.
 *******************************************************************************/
class PrintingRetentionProcessesTest
{
   /***************************************************************************
    ** Opening native cleanup is read-only until the operator continues past review.
    ***************************************************************************/
   @Test
   void nativeCleanupPausesForReviewBeforeRunningMaintenance() throws Exception
   {
      PrintingService service = fixture();
      var preview = new RunProcessAction().execute(new RunProcessInput().withProcessName(PrintingRetentionProcesses.RUN_NOW));
      assertFalse(preview.getException().isPresent(), () -> preview.getException().toString());
      assertEquals("review", preview.getProcessState().getNextStepName().orElseThrow());
      assertTrue(service.getRetentionSettings().optString("lastRunAt").isBlank());
      var completed = new RunProcessAction().execute(new RunProcessInput().withProcessName(PrintingRetentionProcesses.RUN_NOW)
         .withProcessUUID(preview.getProcessUUID()).withStartAfterStep("review").withFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP));
      assertFalse(completed.getException().isPresent(), () -> completed.getException().toString());
      assertFalse(service.getRetentionSettings().optString("lastRunAt").isBlank());
   }



   /***************************************************************************
    ** Registering a QBit never starts maintenance without a host scheduler binding.
    ***************************************************************************/
   @Test
   void scheduleRequiresExplicitHostBindingAndUsesBoundedFiveMinuteCadence()
   {
      QInstance manual = new QInstance();
      PrintingRetentionProcesses.register(manual, new PrintingQBitConfig());
      assertNotNull(manual.getProcess(PrintingRetentionProcesses.SETTINGS));
      assertNotNull(manual.getProcess(PrintingRetentionProcesses.RUN_NOW));
      assertNull(manual.getProcess(PrintingRetentionProcesses.SCHEDULED));
      QInstance scheduled = new QInstance();
      PrintingRetentionProcesses.register(scheduled, new PrintingQBitConfig().withSchedulerName("host-scheduler"));
      var schedule = scheduled.getProcess(PrintingRetentionProcesses.SCHEDULED).getSchedule();
      assertEquals("host-scheduler", schedule.getSchedulerName());
      assertEquals("0 */5 * * * ?", schedule.getCronExpression());
      assertEquals("UTC", schedule.getCronTimeZoneId());
      assertTrue(scheduled.getProcess(PrintingRetentionProcesses.SCHEDULED).getIsHidden());
   }



   /***************************************************************************
    ** Backend direct invocation cannot bypass either permission or tenant checks.
    ***************************************************************************/
   @Test
   void settingsEnforceAdminTenantAndValidationAndReadPersistedPolicy() throws Exception
   {
      PrintingService service = fixture();
      PrintingRetentionProcesses step = new PrintingRetentionProcesses();
      RunBackendStepInput save = input(PrintingRetentionProcesses.SETTINGS, "execute").addValue("days", 730).addValue("enabled", false).addValue("batchSize", 100);
      QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("foreign")));
      assertThrows(QPermissionDeniedException.class, () -> step.run(save, new RunBackendStepOutput()));
      QContext.init(QContext.getQInstance(), new QSession().withSecurityKeyValue("printing_tenant", "tenant-a"));
      assertThrows(QPermissionDeniedException.class, () -> step.run(save, new RunBackendStepOutput()));
      QContext.init(QContext.getQInstance(), new QSession().withPermissions("printing_admin").withSecurityKeyValue("printing_tenant", "tenant-a"));
      step.run(save, new RunBackendStepOutput());
      assertEquals(730, service.getRetentionSettings().getInt("days"));
      assertFalse(service.getRetentionSettings().getBoolean("enabled"));
      RunBackendStepOutput prepared = new RunBackendStepOutput();
      step.run(input(PrintingRetentionProcesses.SETTINGS, "prepare"), prepared);
      assertEquals(730, prepared.getValueInteger("days"));
      assertEquals(false, prepared.getValueBoolean("enabled"));
      assertTrue(prepared.getValueString("storage").contains("not configured"));
      assertTrue(prepared.getValueString("schedule").contains("Not scheduled"));
      assertThrows(QException.class, () -> step.run(input(PrintingRetentionProcesses.SETTINGS, "execute").addValue("days", 0).addValue("enabled", true), new RunBackendStepOutput()));
      assertEquals(730, service.getRetentionSettings().getInt("days"));
   }



   /***************************************************************************
    ** Only a true system session can call the hidden scheduled entrypoint.
    ***************************************************************************/
   @Test
   void scheduledEntryRejectsHumanAdminAndUsesInstanceBoundSystemService() throws Exception
   {
      fixture();
      PrintingRetentionProcesses step = new PrintingRetentionProcesses();
      RunBackendStepInput scheduled = input(PrintingRetentionProcesses.SCHEDULED, "execute");
      assertThrows(QPermissionDeniedException.class, () -> step.run(scheduled, new RunBackendStepOutput()));
      QContext.init(QContext.getQInstance(), new QSystemUserSession(QContext.getQInstance()));
      RunBackendStepOutput result = new RunBackendStepOutput();
      step.run(scheduled, result);
      assertTrue(result.getValueString("result").contains("0 eligible"));
   }



   /***************************************************************************
    ** Each test owns an isolated database and tenant-specific instance binding.
    ***************************************************************************/
   private static PrintingService fixture() throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:retention-ui-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule.class));
      instance.addTable(new QTableMetaData().withName("fixture").withBackendName("memory").withPrimaryKeyField("id").withField(new QFieldMetaData("id", QFieldType.STRING)));
      PrintingQBitConfig config = new PrintingQBitConfig().withService(service);
      instance.addQBit(new QBitMetaData().withGroupId("com.kingsrook.qbits").withArtifactId("qbit-printing").withVersion("test").withNamespace("printing").withConfig(config));
      PrintingRetentionProcesses.register(instance, config);
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.MOCK));
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference("operator")).withPermissions("printing_admin").withSecurityKeyValue("printing_tenant", "tenant-a"));
      return (service);
   }



   /***************************************************************************
    ** Explicit step names prevent a forged form from selecting another operation.
    ***************************************************************************/
   private static RunBackendStepInput input(String process, String step)
   {
      RunBackendStepInput input = new RunBackendStepInput().withProcessName(process);
      input.setStepName(step);
      return (input);
   }



   /***************************************************************************
    ** Thread-local context must never carry tenant authorization between tests.
    ***************************************************************************/
   @AfterEach
   void cleanup()
   {
      QContext.clear();
   }
}
