/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2026.  Kingsrook, LLC
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


import com.kingsrook.qqq.backend.core.exceptions.QException;


/*******************************************************************************
 ** Trusted host adapter to an independent retained-document owner. Both methods
 ** must authorize the CURRENT native actor and exact eligible source. A locator
 ** is correlation, never permission. Do not reconstruct or replace sessions.
 *******************************************************************************/
public interface RetainedPrintSourceAuthority
{
   /**
    ** Resolve a canonical immutable version under the owner's current admission policy.
    ** The opaque reference binds source association, version, generation request,
    ** initiating actor and tenant; printing adds no release or quarantine override.
    ** Resolution is read-only: it must not generate, dispatch, release, or reconcile work.
    */
   RetainedPrintFacade.SourceReference resolve(String locator, String requestId) throws QException;

   /** Read only that version, with current owner authorization and bounded bytes. */
   byte[] read(RetainedPrintFacade.SourceReference source, String requestId) throws QException;

   /**
    ** Authorize exposing an accepted source's metadata to the current reporter.
    ** This grants neither byte access nor impersonation of the initiating actor.
    ** Owners may apply their observation policy independently of release state.
    */
   default void authorizeObservation(RetainedPrintFacade.SourceReference source, String requestId) throws QException
   {
      throw new QException("Retained source observation is not configured");
   }
}
