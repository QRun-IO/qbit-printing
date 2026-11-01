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
import java.util.Map;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;


/*******************************************************************************
 ** A plain Letter "record sheet": a heading, a subtitle and one label/value line
 ** per field, wrapped and continued across pages. It is the generic document a
 ** record print renders when the host supplies no template of its own.
 *******************************************************************************/
public final class PrintingRecordDocuments
{
   static final int MAX_LINES = 2000;
   private static final float MARGIN = 54;
   private static final int TITLE_SIZE = 16;
   private static final int BODY_SIZE = 10;
   private static final float LEADING = 14;



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingRecordDocuments()
   {
   }



   /***************************************************************************
    ** Render one record as a PDF. Field order is the map's order; values are the
    ** display text the caller already formatted (null values print blank).
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, Map<String, String> fields) throws QException
   {
      try(PDDocument document = new PDDocument(); InputStream fontStream = fontStream())
      {
         PDType0Font font = PDType0Font.load(document, fontStream, true);
         float width = PDRectangle.LETTER.getWidth() - 2 * MARGIN;
         int columns = Math.max(20, (int) (width / (font.getStringWidth("0") / 1000 * BODY_SIZE)));
         int labelWidth = Math.min(28, Math.max(8, fields.keySet().stream().mapToInt(String::length).max().orElse(8) + 1));
         List<String> lines = new ArrayList<>();
         for(Map.Entry<String, String> field : fields.entrySet())
         {
            List<String> wrapped = wrap(field.getValue() == null ? "" : field.getValue(), columns - labelWidth - 1);
            for(int i = 0; i < wrapped.size() && lines.size() < MAX_LINES; i++)
            {
               String label = i == 0 ? truncate(field.getKey(), labelWidth - 1) : "";
               lines.add(String.format("%-" + labelWidth + "s %s", label, wrapped.get(i)));
            }
         }
         int index = 0;
         int pageNumber = 0;
         do
         {
            pageNumber++;
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try(PDPageContentStream content = new PDPageContentStream(document, page))
            {
               float y = PDRectangle.LETTER.getHeight() - MARGIN;
               text(content, font, TITLE_SIZE, MARGIN, y, truncate(title, (int) (width / (font.getStringWidth("0") / 1000 * TITLE_SIZE))));
               y -= LEADING + 6;
               text(content, font, BODY_SIZE, MARGIN, y, truncate(subtitle + (pageNumber > 1 ? " (continued, page " + pageNumber + ")" : ""), columns));
               y -= 6;
               content.moveTo(MARGIN, y);
               content.lineTo(MARGIN + width, y);
               content.stroke();
               y -= LEADING + 4;
               while(index < lines.size() && y > MARGIN)
               {
                  text(content, font, BODY_SIZE, MARGIN, y, lines.get(index++));
                  y -= LEADING;
               }
            }
         }
         while(index < lines.size());
         ByteArrayOutputStream output = new ByteArrayOutputStream();
         document.save(output);
         return (output.toByteArray());
      }
      catch(IOException exception)
      {
         throw new QException("Could not render the record document", exception);
      }
   }



   /***************************************************************************
    ** Break text at spaces (or hard, for long words) into lines of at most
    ** width characters; embedded line breaks start new lines.
    ***************************************************************************/
   static List<String> wrap(String text, int width)
   {
      List<String> lines = new ArrayList<>();
      for(String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').split("\n", -1))
      {
         String rest = paragraph.strip();
         if(rest.isEmpty())
         {
            lines.add("");
            continue;
         }
         while(rest.length() > width)
         {
            int cut = rest.lastIndexOf(' ', width);
            if(cut <= 0)
            {
               cut = width;
            }
            lines.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
         }
         lines.add(rest);
      }
      return (lines);
   }



   /***************************************************************************
    ** Shorten to at most length characters, marking the cut.
    ***************************************************************************/
   static String truncate(String text, int length)
   {
      String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
      return (value.length() <= length ? value : value.substring(0, Math.max(0, length - 1)) + "~");
   }



   /***************************************************************************
    ** Draw one line, replacing characters the bundled font cannot encode.
    ***************************************************************************/
   private static void text(PDPageContentStream content, PDType0Font font, int size, float x, float y, String text) throws IOException
   {
      StringBuilder safe = new StringBuilder();
      text.codePoints().forEach(codePoint ->
      {
         String character = new String(Character.toChars(codePoint));
         try
         {
            font.encode(character);
            safe.append(character);
         }
         catch(IllegalArgumentException | IOException exception)
         {
            safe.append('?');
         }
      });
      content.beginText();
      content.setFont(font, size);
      content.newLineAtOffset(x, y);
      content.showText(safe.toString());
      content.endText();
   }



   /***************************************************************************
    ** The licensed font shipped for the diagnostic documents.
    ***************************************************************************/
   private static InputStream fontStream() throws IOException
   {
      InputStream stream = PrintingRecordDocuments.class.getResourceAsStream(PrintingTestDocuments.FONT_RESOURCE);
      if(stream == null)
      {
         throw new IOException("Bundled document font is missing");
      }
      return (stream);
   }
}
