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


import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;


/*******************************************************************************
 ** Host configuration. The backend and service must use the same database.
 *******************************************************************************/
public class PrintingQBitConfig implements QBitConfig
{
   private String backendName;
   private transient PrintingService service;



   /***************************************************************************
    ** Bind metadata reads to the host's existing RDBMS backend.
    ***************************************************************************/
   public PrintingQBitConfig withBackendName(String value)
   {
      backendName = value;
      return (this);
   }



   /***************************************************************************
    ** Bind the current application's printing service.
    ***************************************************************************/
   public PrintingQBitConfig withService(PrintingService value)
   {
      service = value;
      return (this);
   }



   /***************************************************************************
    ** Return the table backend name.
    ***************************************************************************/
   public String getBackendName()
   {
      return (backendName);
   }



   /***************************************************************************
    ** Return the printing runtime without serializing credentials or connections.
    ***************************************************************************/
   public PrintingService getService()
   {
      return (service);
   }
}


