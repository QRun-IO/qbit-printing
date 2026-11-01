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


import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;


/** Printing selections enforce current native table permissions before preview or extraction. */
public class PrintingExtract extends ExtractViaQueryStep
{
   /** Preserve the standard callback, then prevent it from weakening current-user enforcement. */
   @Override
   protected void customizeInputPreQuery(RunBackendStepInput input, QueryInput query)
   {
      super.customizeInputPreQuery(input, query);
      query.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
   }


   /** RC20 exposes no count-input hook; authorize before its unchanged tenant-filtered count. */
   @Override
   public Integer doCount(RunBackendStepInput input) throws QException
   {
      CountInput count = new CountInput();
      count.setTableName(input.getValueString(FIELD_SOURCE_TABLE));
      count.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      PermissionsHelper.checkTablePermissionThrowing(count, TablePermissionSubType.READ);
      return super.doCount(input);
   }
}
