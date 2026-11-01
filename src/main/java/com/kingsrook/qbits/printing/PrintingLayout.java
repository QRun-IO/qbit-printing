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


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;


/*******************************************************************************
 ** A small monospace page writer shared by the record, table-list and text
 ** documents: a heading on the first page, a running title on later pages,
 ** lines that break onto new pages (optionally repeating a header), and
 ** "Page n of m" footers. Characters the bundled font cannot encode print as ?.
 *******************************************************************************/
final class PrintingLayout implements AutoCloseable
{
   static final float MARGIN = 40;
   static final int MAX_PAGES = 500;

   private final PDDocument document = new PDDocument();
   private final PDType0Font font;
   private final PDRectangle size;
   private final String title;
   private PDPageContentStream content;
   private float y;
   private List<String> repeatHeader = List.of();
   private int repeatSize = 8;
   private String footer = "";
   private float leading = 1.35f;



   /***************************************************************************
    ** Page size LETTER or A4, portrait or landscape; the first page carries
    ** the title and subtitle.
    ***************************************************************************/
   PrintingLayout(String pageSize, boolean landscape, String title, String subtitle) throws QException
   {
      try(InputStream stream = PrintingLayout.class.getResourceAsStream(PrintingTestDocuments.FONT_RESOURCE))
      {
         if(stream == null)
         {
            throw new IOException("Bundled document font is missing");
         }
         font = PDType0Font.load(document, stream, true);
         PDRectangle base = "A4".equals(pageSize == null ? null : pageSize.toUpperCase(Locale.ROOT)) ? PDRectangle.A4 : PDRectangle.LETTER;
         size = landscape ? new PDRectangle(base.getHeight(), base.getWidth()) : base;
         this.title = title == null ? "" : title;
         newPage();
         line(this.title, 15);
         y -= 4;
         if(subtitle != null && !subtitle.isBlank())
         {
            line(subtitle, 9);
         }
         y -= 4;
         rule();
         y -= 6;
      }
      catch(IOException exception)
      {
         close();
         throw new QException("Could not start the print document", exception);
      }
   }



   /***************************************************************************
    ** How many monospace characters fit across the page at a font size.
    ***************************************************************************/
   int columns(float fontSize)
   {
      return (Math.max(20, (int) ((size.getWidth() - 2 * MARGIN) / (0.6f * fontSize))));
   }



   /***************************************************************************
    ** Line spacing as a multiple of the font size (1.35 by default; text
    ** captures use less so box-drawing characters join up).
    ***************************************************************************/
   void leading(float factor)
   {
      leading = factor;
   }



   /***************************************************************************
    ** Text at the bottom left of every page (who printed it and when).
    ***************************************************************************/
   void footer(String text)
   {
      footer = text == null ? "" : text;
   }



   /***************************************************************************
    ** Lines repeated at the top of every following page (a table header).
    ***************************************************************************/
   void repeatOnNewPages(List<String> lines, int fontSize)
   {
      repeatHeader = List.copyOf(lines);
      repeatSize = fontSize;
   }



   /***************************************************************************
    ** One line at a font size; breaks the page first when it does not fit.
    ***************************************************************************/
   void line(String text, float fontSize) throws IOException
   {
      float step = fontSize * leading;
      if(y - step < MARGIN + 14)
      {
         newPage();
         for(String header : repeatHeader)
         {
            draw(header, repeatSize, repeatSize * leading);
         }
      }
      draw(text, fontSize, step);
   }



   /***************************************************************************
    ** Keep at least this many lines together (a heading with its first rows).
    ***************************************************************************/
   void keep(int lines, int fontSize) throws IOException
   {
      if(y - lines * fontSize * 1.35f < MARGIN + 14)
      {
         newPage();
      }
   }



   /***************************************************************************
    ** Vertical space.
    ***************************************************************************/
   void gap(float points)
   {
      y -= points;
   }



   /***************************************************************************
    ** A thin rule across the page.
    ***************************************************************************/
   void rule() throws IOException
   {
      content.moveTo(MARGIN, y);
      content.lineTo(size.getWidth() - MARGIN, y);
      content.stroke();
      y -= 4;
   }



   /***************************************************************************
    ** Footers on every page, then the PDF bytes.
    ***************************************************************************/
   byte[] finish() throws QException
   {
      try
      {
         content.close();
         content = null;
         int total = document.getNumberOfPages();
         for(int i = 0; i < total; i++)
         {
            PDPage page = document.getPage(i);
            try(PDPageContentStream footer = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true))
            {
               String text = "Page " + (i + 1) + " of " + total;
               footer.beginText();
               footer.setFont(font, 8);
               footer.newLineAtOffset(size.getWidth() - MARGIN - text.length() * 4.8f, MARGIN - 14);
               footer.showText(text);
               footer.endText();
               if(!this.footer.isEmpty())
               {
                  footer.beginText();
                  footer.setFont(font, 8);
                  footer.newLineAtOffset(MARGIN, MARGIN - 14);
                  footer.showText(safe(PrintingRecordDocuments.truncate(this.footer, Math.max(10, columns(8) - text.length() - 2))));
                  footer.endText();
               }
            }
         }
         ByteArrayOutputStream output = new ByteArrayOutputStream();
         document.save(output);
         return (output.toByteArray());
      }
      catch(IOException exception)
      {
         throw new QException("Could not render the print document", exception);
      }
   }



   /***************************************************************************
    ** Release the document.
    ***************************************************************************/
   @Override
   public void close()
   {
      try
      {
         if(content != null)
         {
            content.close();
         }
         document.close();
      }
      catch(IOException exception)
      {
         // nothing more to release
      }
   }



   /***************************************************************************
    ** Start a page; later pages show the running title.
    ***************************************************************************/
   private void newPage() throws IOException
   {
      if(document.getNumberOfPages() >= MAX_PAGES)
      {
         throw new IOException("The document would exceed " + MAX_PAGES + " pages; print fewer rows");
      }
      if(content != null)
      {
         content.close();
      }
      PDPage page = new PDPage(size);
      document.addPage(page);
      content = new PDPageContentStream(document, page);
      y = size.getHeight() - MARGIN;
      if(document.getNumberOfPages() > 1)
      {
         draw(PrintingRecordDocuments.truncate(title, columns(8)) + " (continued)", 8, 12);
         y -= 4;
      }
   }



   /***************************************************************************
    ** Draw one line at the current position.
    ***************************************************************************/
   private void draw(String text, float fontSize, float step) throws IOException
   {
      y -= step;
      content.beginText();
      content.setFont(font, fontSize);
      content.newLineAtOffset(MARGIN, y);
      content.showText(safe(PrintingRecordDocuments.truncate(text, columns(fontSize))));
      content.endText();
   }



   /***************************************************************************
    ** Replace characters the font cannot encode, and control characters.
    ***************************************************************************/
   private String safe(String text)
   {
      StringBuilder out = new StringBuilder();
      text.codePoints().forEach(codePoint ->
      {
         String character = new String(Character.toChars(codePoint));
         if(Character.isISOControl(codePoint))
         {
            out.append(' ');
            return;
         }
         try
         {
            font.encode(character);
            out.append(character);
         }
         catch(IllegalArgumentException | IOException exception)
         {
            out.append('?');
         }
      });
      return (out.toString());
   }



   /***************************************************************************
    ** Width-limited columns for a table: natural widths shrunk to the line.
    ***************************************************************************/
   static int[] columnWidths(List<String> headers, List<List<String>> rows, int available)
   {
      return (fit(naturalWidths(headers, rows), available));
   }



   /***************************************************************************
    ** Each column's natural width: the header or the 90th percentile of its
    ** cells, between 4 and 60 characters.
    ***************************************************************************/
   static int[] naturalWidths(List<String> headers, List<List<String>> rows)
   {
      int count = headers.size();
      int[] widths = new int[count];
      for(int c = 0; c < count; c++)
      {
         List<Integer> lengths = new ArrayList<>();
         for(List<String> row : rows)
         {
            lengths.add(c < row.size() && row.get(c) != null ? row.get(c).length() : 0);
         }
         lengths.sort(null);
         int typical = lengths.isEmpty() ? 0 : lengths.get(Math.max(0, (int) Math.ceil(lengths.size() * 0.9) - 1));
         widths[c] = Math.max(4, Math.min(60, Math.max(headers.get(c).length(), typical)));
      }
      return (widths);
   }



   /***************************************************************************
    ** The width a row of these columns takes, with two-space gaps.
    ***************************************************************************/
   static int lineWidth(int[] widths)
   {
      return (sum(widths) + Math.max(0, widths.length - 1) * 2);
   }



   /***************************************************************************
    ** Shrink widths (widest first, never below 4) until a row fits.
    ***************************************************************************/
   static int[] fit(int[] start, int available)
   {
      int[] widths = start.clone();
      while(lineWidth(widths) > available)
      {
         int widest = 0;
         for(int c = 1; c < widths.length; c++)
         {
            if(widths[c] > widths[widest])
            {
               widest = c;
            }
         }
         if(widths[widest] <= 4)
         {
            break;
         }
         widths[widest]--;
      }
      return (widths);
   }



   /***************************************************************************
    ** Sum of widths.
    ***************************************************************************/
   private static int sum(int[] values)
   {
      int total = 0;
      for(int value : values)
      {
         total += value;
      }
      return (total);
   }



   /***************************************************************************
    ** One table row laid out in fixed-width columns.
    ***************************************************************************/
   static String row(List<String> cells, int[] widths)
   {
      StringBuilder out = new StringBuilder();
      for(int c = 0; c < widths.length; c++)
      {
         String cell = c < cells.size() && cells.get(c) != null ? cells.get(c).replace('\n', ' ').replace('\r', ' ') : "";
         cell = PrintingRecordDocuments.truncate(cell, widths[c]);
         out.append(c == 0 ? "" : "  ").append(String.format("%-" + widths[c] + "s", cell));
      }
      return (out.toString().stripTrailing());
   }
}
