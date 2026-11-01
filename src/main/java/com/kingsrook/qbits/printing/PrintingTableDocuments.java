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


import java.io.IOException;
import java.util.List;
import com.kingsrook.qqq.backend.core.exceptions.QException;


/*******************************************************************************
 ** A table list: a grid of rows under a repeated column header, in fixed-width
 ** monospace columns sized from the content and shrunk to fit the page.
 *******************************************************************************/
public final class PrintingTableDocuments
{
   static final int FONT_SIZE = 8;



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingTableDocuments()
   {
   }



   /***************************************************************************
    ** Render columns and rows (display text) on LETTER or A4, portrait or
    ** landscape. The footer line says how many rows were printed.
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, List<String> columns, List<List<String>> rows, String pageSize, boolean landscape) throws QException
   {
      if(columns.isEmpty())
      {
         throw new QException("A table list needs at least one column");
      }
      try(PrintingLayout layout = new PrintingLayout(pageSize, landscape, title, subtitle))
      {
         int[] widths = PrintingLayout.columnWidths(columns, rows, layout.columns(FONT_SIZE));
         String header = PrintingLayout.row(columns, widths);
         String rule = "-".repeat(Math.min(layout.columns(FONT_SIZE), header.length()));
         layout.line(header, FONT_SIZE);
         layout.line(rule, FONT_SIZE);
         layout.repeatOnNewPages(List.of(header, rule), FONT_SIZE);
         for(List<String> row : rows)
         {
            layout.line(PrintingLayout.row(row, widths), FONT_SIZE);
         }
         layout.repeatOnNewPages(List.of(), FONT_SIZE);
         layout.gap(4);
         layout.line(rows.size() + (rows.size() == 1 ? " row" : " rows"), FONT_SIZE);
         return (layout.finish());
      }
      catch(IOException exception)
      {
         throw new QException("Could not render the table list: " + exception.getMessage(), exception);
      }
   }
}
