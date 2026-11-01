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
import org.jspecify.annotations.Nullable;


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
    ** landscape, without preferred widths or a footer.
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, List<String> columns, List<List<String>> rows, String pageSize, boolean landscape) throws QException
   {
      return pdf(title, subtitle, null, columns, rows, null, pageSize, landscape ? "LANDSCAPE" : "PORTRAIT", null);
   }



   /***************************************************************************
    ** Render a table list. Preferred widths (the grid's, in characters) are
    ** the starting column widths, else the content decides; either way they
    ** shrink to fit the page. Orientation AUTO turns the page landscape when
    ** the columns do not fit portrait. The header repeats on every page; the
    ** footer text goes at the bottom left of every page.
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, @Nullable String footer, List<String> columns, List<List<String>> rows, int @Nullable [] preferredWidths, String pageSize, String orientation, @Nullable PrintingChrome chrome) throws QException
   {
      if(columns.isEmpty())
      {
         throw new QException("A table list needs at least one column");
      }
      int[] natural = PrintingLayout.naturalWidths(columns, rows);
      if(preferredWidths != null && preferredWidths.length == columns.size())
      {
         for(int c = 0; c < natural.length; c++)
         {
            natural[c] = Math.max(4, Math.min(60, preferredWidths[c]));
         }
      }
      boolean landscape = isLandscape(orientation, natural, pageSize);
      try(PrintingLayout layout = new PrintingLayout(pageSize, landscape, title, subtitle, chrome))
      {
         layout.footer(footer);
         int[] widths = PrintingLayout.fit(natural, layout.columns(FONT_SIZE));
         String header = PrintingLayout.row(columns, widths);
         String rule = "-".repeat(Math.min(layout.columns(FONT_SIZE), Math.max(1, header.length())));
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
         return layout.finish();
      }
      catch(IOException exception)
      {
         throw new QException("Could not render the table list: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** PORTRAIT, LANDSCAPE, or AUTO: landscape when the natural widths do not
    ** fit across a portrait page.
    ***************************************************************************/
   static boolean isLandscape(String orientation, int[] natural, String pageSize)
   {
      if("LANDSCAPE".equalsIgnoreCase(orientation))
      {
         return true;
      }
      if(!"AUTO".equalsIgnoreCase(orientation))
      {
         return false;
      }
      float width = ("A4".equalsIgnoreCase(pageSize) ? 595.28f : 612f) - 2 * PrintingLayout.MARGIN;
      return PrintingLayout.lineWidth(natural) > (int) (width / (0.6f * FONT_SIZE));
   }
}
