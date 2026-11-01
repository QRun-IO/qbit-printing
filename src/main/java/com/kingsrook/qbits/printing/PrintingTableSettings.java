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


import com.kingsrook.qqq.backend.core.model.metadata.tables.QSupplementalTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Per-table printing metadata. A table carrying it with printable false gets
 ** no Print process from PrintingRecordProcesses.registerAll:
 ** {@code table.withSupplementalMetaData(new PrintingTableSettings().withPrintable(false))}.
 *******************************************************************************/
public class PrintingTableSettings extends QSupplementalTableMetaData
{
   public static final String TYPE = "printing";

   private boolean printable = true;



   /***************************************************************************
    ** The settings of a table, or null when it has none.
    ***************************************************************************/
   public static @Nullable PrintingTableSettings of(QTableMetaData table)
   {
      return table.getSupplementalMetaData() == null ? null : (PrintingTableSettings) table.getSupplementalMetaData(TYPE);
   }



   /***************************************************************************
    ** Supplemental metadata type.
    ***************************************************************************/
   @Override
   public String getType()
   {
      return TYPE;
   }



   /***************************************************************************
    ** Whether the table's records and lists may be printed (default true).
    ***************************************************************************/
   public boolean getPrintable()
   {
      return printable;
   }



   /***************************************************************************
    ** Fluent setter.
    ***************************************************************************/
   public PrintingTableSettings withPrintable(boolean value)
   {
      printable = value;
      return this;
   }



   /***************************************************************************
    ** No mutable references beyond the flag, which the base clone copies.
    ***************************************************************************/
   @Override
   protected QSupplementalTableMetaData finishClone(QSupplementalTableMetaData clone)
   {
      return clone;
   }
}
