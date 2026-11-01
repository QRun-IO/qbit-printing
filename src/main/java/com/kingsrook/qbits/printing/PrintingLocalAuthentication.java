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


import java.util.Map;
import java.util.Set;
import com.kingsrook.qqq.backend.core.exceptions.QAuthenticationException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.TableBasedAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.authentication.implementations.TableBasedAuthenticationModule;


/*******************************************************************************
 ** Authorization mapping only; password/session verification remains native QQQ.
 *******************************************************************************/
public class PrintingLocalAuthentication extends TableBasedAuthenticationModule
{
   /***************************************************************************
    ** Only the explicitly configured, successfully verified account gets access.
    ***************************************************************************/
   @Override
   public QSession createSession(QInstance instance, Map<String, String> context) throws QAuthenticationException
   {
      QSession session = super.createSession(instance, context);
      session.setPermissions(Set.of());
      if(!(instance.getAuthentication() instanceof Metadata metadata) || session.getUser() == null
         || !metadata.username.equals(session.getUser().getIdReference()))
      {
         super.logout(instance, session.getUuid());
         throw new QAuthenticationException("This account is not authorized for local printing administration");
      }
      session.setPermissions(Set.of("printing_admin"));
      session.withSecurityKeyValue("printing_tenant", metadata.tenant);
      return session;
   }



   /***************************************************************************
    ** Host-only identity mapping; no password or device credential in metadata.
    ***************************************************************************/
   static class Metadata extends TableBasedAuthenticationMetaData
   {
      private final String username;
      private final String tenant;



      /***********************************************************************
       ** Keep identity fields out of ordinary JavaBean frontend serialization.
       ***********************************************************************/
      Metadata(String username, String tenant)
      {
         this.username = username;
         this.tenant = tenant;
      }
   }
}
