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
   private PrintingAdmissionLimits admissionLimits = PrintingAdmissionLimits.defaults();
   private PrintingRetentionPolicy retentionPolicy = new PrintingRetentionPolicy(true, 365, 200);
   private String storageTableName;
   private String schedulerName;
   private String facilityPossibleValueSourceName;
   private transient RetainedPrintSourceAuthority retainedSourceAuthority;


   /** Explicit trusted host integration; absent means retained submissions are disabled. */
   public PrintingQBitConfig withRetainedSourceAuthority(RetainedPrintSourceAuthority value)
   {
      retainedSourceAuthority = java.util.Objects.requireNonNull(value);
      return (this);
   }


   /** Never serialized into client metadata or supplied by a request. */
   public RetainedPrintSourceAuthority getRetainedSourceAuthority()
   {
      return (retainedSourceAuthority);
   }



   /***************************************************************************
    ** Resource capacity is deployment-owned and independent of retention age.
    ***************************************************************************/
   public PrintingAdmissionLimits getAdmissionLimits()
   {
      return (admissionLimits);
   }



   /***************************************************************************
    ** Positive tenant/device limits apply at the shared admission boundary.
    ***************************************************************************/
   public PrintingQBitConfig withAdmissionLimits(PrintingAdmissionLimits value)
   {
      admissionLimits = java.util.Objects.requireNonNull(value);
      return (this);
   }



   /***************************************************************************
    ** Reuse the host's facility selector and its existing record security.
    ***************************************************************************/
   public PrintingQBitConfig withFacilityPossibleValueSourceName(String value)
   {
      facilityPossibleValueSourceName = value;
      return (this);
   }



   /***************************************************************************
    ** Missing configuration uses the named standalone default facility.
    ***************************************************************************/
   public String getFacilityPossibleValueSourceName()
   {
      return (facilityPossibleValueSourceName);
   }



   /***************************************************************************
    ** Deployment-owned dedicated S3 table; operators cannot retarget old files.
    ***************************************************************************/
   public PrintingQBitConfig withStorageTableName(String value)
   {
      storageTableName = value;
      return (this);
   }



   /***************************************************************************
    ** Existing storage metadata owns bucket, endpoint and credentials.
    ***************************************************************************/
   public String getStorageTableName()
   {
      return (storageTableName);
   }



   /***************************************************************************
    ** Bind bounded five-minute maintenance to a scheduler already configured by the host.
    ***************************************************************************/
   public PrintingQBitConfig withSchedulerName(String value)
   {
      schedulerName = value;
      return (this);
   }



   /***************************************************************************
    ** Missing host scheduler means no automatic background maintenance.
    ***************************************************************************/
   public String getSchedulerName()
   {
      return (schedulerName);
   }



   /***************************************************************************
    ** Host defaults are one year; setting policy does not itself run a purge.
    ***************************************************************************/
   public PrintingQBitConfig withRetentionPolicy(PrintingRetentionPolicy value)
   {
      retentionPolicy = java.util.Objects.requireNonNull(value);
      return (this);
   }



   /***************************************************************************
    ** The host authorizes and schedules bounded service maintenance explicitly.
    ***************************************************************************/
   public PrintingRetentionPolicy getRetentionPolicy()
   {
      return (retentionPolicy);
   }



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


