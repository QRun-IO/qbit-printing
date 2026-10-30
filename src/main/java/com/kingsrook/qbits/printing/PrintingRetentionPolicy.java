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


/*******************************************************************************
 ** Bounded maintenance policy; the host separately binds a native scheduler.
 *******************************************************************************/
public record PrintingRetentionPolicy(boolean enabled, int days, int batchSize)
{
   /***************************************************************************
    ** Bound age and work per maintenance invocation before touching storage.
    ***************************************************************************/
   public PrintingRetentionPolicy
   {
      if(days < 1 || days > 36500 || batchSize < 1 || batchSize > 200)
      {
         throw new IllegalArgumentException("Retention requires 1..36500 days and a batch of 1..200 jobs");
      }
   }



   /***************************************************************************
    ** Explicitly disable removal while retaining the standard age and batch bounds.
    ***************************************************************************/
   public static PrintingRetentionPolicy disabled()
   {
      return (new PrintingRetentionPolicy(false, 365, 200));
   }
}

