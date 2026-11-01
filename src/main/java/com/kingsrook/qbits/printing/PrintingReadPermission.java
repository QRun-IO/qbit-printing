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


import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.metadata.MetaDataInput;
import com.kingsrook.qqq.backend.core.model.actions.metadata.TableMetaDataInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Operational rows are read-only; mutations must pass PrintingService validation.
 *******************************************************************************/
public class PrintingReadPermission extends PrintingAdminPermission
{
   /***************************************************************************
    ** Require explicit printing administrator access and reject generic writes.
    ***************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput input, @Nullable MetaDataWithPermissionRules metadata) throws QPermissionDeniedException
   {
      if(metadata instanceof com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData table
         && (PrintingTables.DEVICE.equals(table.getName()) || PrintingTables.PRINTER.equals(table.getName())))
      {
         new PrintingOperatorPermission().checkPermissionsThrowing(input, metadata);
      }
      else
      {
         super.checkPermissionsThrowing(input, metadata);
      }
      if(input != null && !(input instanceof QueryInput) && !(input instanceof GetInput) && !(input instanceof CountInput)
         && !(input instanceof MetaDataInput) && !(input instanceof TableMetaDataInput))
      {
         throw new QPermissionDeniedException("Use the printing management service to change operational state");
      }
   }
}



