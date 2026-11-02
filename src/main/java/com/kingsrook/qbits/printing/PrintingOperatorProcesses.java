/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2026.  Kingsrook, LLC
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
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import org.json.JSONObject;


/*******************************************************************************
 ** Explicit operator backend process for host-generated document submissions.
 *******************************************************************************/
public class PrintingOperatorProcesses implements BackendStep
{
   public static final String SUBMIT = "printingSubmit";


   /***************************************************************************
    ** Host workflows call this typed input process; it grants no management role.
    ***************************************************************************/
   public static void register(QInstance instance)
   {
      instance.addProcess(new QProcessMetaData().withName(SUBMIT).withLabel("Submit a print document")
         .withIsHidden(true)
         .withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
            .withCustomPermissionChecker(new QCodeReference(PrintingOperatorPermission.class)))
         .withStep(new QBackendStepMetaData().withName("execute").withCode(new QCodeReference(PrintingOperatorProcesses.class))
         .withInputData(new QFunctionInputMetaData().withFields(List.of(new QFieldMetaData("requestId", QFieldType.STRING),
            new QFieldMetaData("printerRowId", QFieldType.STRING).withPossibleValueSourceName(PrintingTables.PRINTER),
            new QFieldMetaData("title", QFieldType.STRING), new QFieldMetaData("format", QFieldType.STRING).withPossibleValueSourceName("printing_format"),
            new QFieldMetaData("contentBase64", QFieldType.STRING), new QFieldMetaData("options", QFieldType.STRING))))));
   }


   /***************************************************************************
    ** Direct step invocation receives the same current-actor checks as native HTTP.
    ***************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      try
      {
         JSONObject request = new JSONObject();
         for(String field : List.of("requestId", "printerRowId", "title", "format", "contentBase64"))
         {
            request.put(field, input.getValueString(field));
         }
         String options = input.getValueString("options");
         if(options != null)
         {
            request.put("options", new JSONObject(options));
         }
         output.addValue("job", PrintingOperatorAuthorization.submit(request).toString());
      }
      catch(QException exception)
      {
         throw exception;
      }
      catch(Exception exception)
      {
         throw new QException("Print submission failed", exception);
      }
   }
}
