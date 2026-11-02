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
 ** Host-configurable logical resource ceilings, independent of retention age.
 *******************************************************************************/
public record PrintingAdmissionLimits(int deviceOutstandingJobs, int tenantOutstandingJobs,
   long deviceOutstandingBytes, long tenantOutstandingBytes, long deviceRetainedBytes, long tenantRetainedBytes,
   int deviceJobsPerHour, int tenantJobsPerHour, int devicePrinters, int tenantPrinters)
{
   /***************************************************************************
    ** Never silently disable a security boundary through zero/negative limits.
    ***************************************************************************/
   public PrintingAdmissionLimits
   {
      if(deviceOutstandingJobs < 1 || tenantOutstandingJobs < 1 || deviceOutstandingBytes < 1 || tenantOutstandingBytes < 1
         || deviceRetainedBytes < 1 || tenantRetainedBytes < 1 || deviceJobsPerHour < 1 || tenantJobsPerHour < 1 || devicePrinters < 1 || tenantPrinters < 1)
      {
         throw new IllegalArgumentException("Printing admission limits must all be positive");
      }
   }



   /***************************************************************************
    ** Capacity must be sized for document sizes and the deployment's retention.
    ***************************************************************************/
   public static PrintingAdmissionLimits defaults()
   {
      long gibibyte = 1024L * 1024 * 1024;
      return (new PrintingAdmissionLimits(10_000, 100_000, 10 * gibibyte, 100 * gibibyte,
         2048 * gibibyte, 20_480 * gibibyte, 2_000, 20_000, 1_000, 20_000));
   }
}
