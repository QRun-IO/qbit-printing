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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.processes.QProcessCallback;
import com.kingsrook.qqq.backend.core.actions.reporting.RecordPipe;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Exercise the actual frozen extract and record pipe, without relying on later Transform checks. */
class PrintingExtractTest
{
   /** Each test owns its native context. */
   @AfterEach
   void clearContext()
   {
      QContext.clear();
   }


   /** A table-specific denial precedes both preview count and any streamed row. */
   @Test
   void tablePolicyBlocksCountAndExtractEvenWithPermissionWeakeningCallback() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      fixture.instance().getTable(PrintingTables.DEVICE).getPermissionRules().withCustomPermissionChecker(new QCodeReference(DenyRead.class));
      RunBackendStepInput input = input(fixture.device());
      AtomicBoolean callback = new AtomicBoolean();
      input.setCallback(new QProcessCallback()
      {
         @Override
         public void customizeInputPreQuery(RunBackendStepInput step, QueryInput query)
         {
            callback.set(true);
            query.setPermissionEnforcement(PermissionEnforcement.DEFAULT);
         }
      });
      PrintingExtract extract = prepare(input);
      assertThrows(QPermissionDeniedException.class, () -> extract.doCount(input));
      assertThrows(QPermissionDeniedException.class, () -> extract.run(input, new RunBackendStepOutput()));
      assertTrue(callback.get());
      assertEquals(0, extract.getRecordPipe().countAvailableRecords());
   }


   /** Tenant row locks remain authoritative for both selected IDs and query selection. */
   @Test
   void nativeSelectionsCountAndStreamOnlyCurrentTenantRows() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      RunBackendStepInput selected = input(fixture.device() + "," + fixture.foreignDevice());
      PrintingExtract extract = prepare(selected);
      assertEquals(1, extract.doCount(selected));
      extract.run(selected, new RunBackendStepOutput());
      assertEquals(List.of(fixture.device()), extract.getRecordPipe().consumeAvailableRecords().stream().map(row -> row.getValueString("id")).toList());
      RunBackendStepInput query = input(null);
      query.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, "{}");
      PrintingExtract all = prepare(query);
      assertEquals(2, all.doCount(query));
      all.run(query, new RunBackendStepOutput());
      assertEquals(Set.of(fixture.device(), fixture.otherDevice()), all.getRecordPipe().consumeAvailableRecords().stream().map(row -> row.getValueString("id")).collect(Collectors.toSet()));
      QContext.getQSession().setPermissions(Set.of());
      assertThrows(QPermissionDeniedException.class, () -> all.doCount(query));
      assertThrows(QPermissionDeniedException.class, () -> all.run(query, new RunBackendStepOutput()));
   }


   /** Every printing streamed-selection process uses the enforced wrapper. */
   @Test
   void allPrintingSelectionRegistrationsUseEnforcedExtraction() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      int checked = 0;
      for(var process : fixture.instance().getProcesses().values())
      {
         var field = process.getInputField(StreamedETLWithFrontendProcess.FIELD_EXTRACT_CODE);
         if(field.isPresent() && field.get().getDefaultValue() instanceof QCodeReference code)
         {
            assertEquals(PrintingExtract.class.getName(), code.getName());
            checked++;
         }
      }
      assertEquals(14, checked);
   }


   /** Real native input fields drive standard selected-ID or filter extraction. */
   private static RunBackendStepInput input(String ids)
   {
      RunBackendStepInput input = new RunBackendStepInput().withProcessName(PrintingDeviceProcesses.Action.PAUSE.process)
         .addValue(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, PrintingTables.DEVICE);
      if(ids != null)
      {
         input.addValue("recordIds", ids);
      }
      return (input);
   }


   /** Bounded in-memory pipe removes asynchronous worker timing from the authorization assertion. */
   private static PrintingExtract prepare(RunBackendStepInput input) throws QException
   {
      PrintingExtract extract = new PrintingExtract();
      extract.preRun(input, new RunBackendStepOutput());
      extract.setRecordPipe(new RecordPipe(20));
      return (extract);
   }


   /** Permission varies by table independently of the user's process-administration grant. */
   public static class DenyRead extends PrintingReadPermission
   {
      @Override
      public void checkPermissionsThrowing(AbstractActionInput input, MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
      {
         super.checkPermissionsThrowing(input, metadata);
         if(input instanceof QueryInput || input instanceof CountInput)
         {
            throw new QPermissionDeniedException("Synthetic table read denied");
         }
      }
   }
}
