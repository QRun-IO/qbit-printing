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
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectRequest;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectResult;
import com.kingsrook.qqq.backend.core.jobs.effects.EffectTarget;
import com.kingsrook.qqq.backend.core.jobs.effects.ReconcileResult;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import org.json.JSONObject;


/*******************************************************************************
 ** Outbox target for durable enqueue, not physical printing. Stateless by design.
 ** Host code must authorize the destination before emitting this trusted effect.
 *******************************************************************************/
public class PrintEnqueueEffectTarget implements EffectTarget
{
   /***************************************************************************
    ** Enqueue using the outbox's stable key; SENT means durably queued only.
    ***************************************************************************/
   @Override
   public EffectResult deliver(EffectRequest request) throws Exception
   {
      JSONObject payload = new JSONObject(request.payload()).put("idempotencyKey", request.idempotencyKey());
      try
      {
         JSONObject job = serviceForEffect().submit(payload);
         return (EffectResult.sent(job.getString("jobId")));
      }
      catch(PrintingException exception)
      {
         return (EffectResult.failed(exception.getMessage()));
      }
   }



   /***************************************************************************
    ** Resolve a lost enqueue response by the same durable identity.
    ***************************************************************************/
   @Override
   public ReconcileResult reconcile(EffectRequest request) throws Exception
   {
      JSONObject job = serviceForEffect().findByIdempotencyKey(request.idempotencyKey());
      return (job == null ? ReconcileResult.notFound() : ReconcileResult.found(job.getString("jobId")));
   }


   /***************************************************************************
    ** The durable dispatcher runs as the system, without an emitting user's
    ** security keys. Its trusted outbox is bound to the current QQQ instance;
    ** that instance's configured service fixes the tenant. Destination validation
    ** remains inside PrintingService. Inline user calls retain user scope checks.
    ***************************************************************************/
   private static PrintingService serviceForEffect() throws QException
   {
      if(!(QContext.getQSession() instanceof QSystemUserSession))
      {
         return (PrintingProcesses.currentService());
      }
      for(QBitMetaData metadata : QContext.getQInstance().getQBits().values())
      {
         if(metadata.getConfig() instanceof PrintingQBitConfig config && config.getService() != null)
         {
            return (config.getService());
         }
      }
      throw new QException("Printing QBit is not configured for this effect dispatcher");
   }

}

