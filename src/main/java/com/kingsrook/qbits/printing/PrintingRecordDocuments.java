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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** A Letter "record sheet": a heading and subtitle, then each section of the
 ** record (label/value lines, wrapped and continued across pages), then a
 ** summary of each child list (its label, how many rows, and the first rows as
 ** a small table). It is the generic document a record print renders when the
 ** host supplies no template of its own.
 *******************************************************************************/
public final class PrintingRecordDocuments
{
   static final int MAX_LINES = 4000;
   private static final int BODY_SIZE = 10;
   private static final int CHILD_SIZE = 8;



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingRecordDocuments()
   {
   }



   /***************************************************************************
    ** One section: a label (blank for none) and label/value lines in order.
    ***************************************************************************/
   public record Section(String label, Map<String, String> fields)
   {
   }



   /***************************************************************************
    ** One child list: its label, total row count, column headings and the
    ** first rows (display text).
    ***************************************************************************/
   public record ChildList(String label, int total, List<String> columns, List<List<String>> rows)
   {
   }



   /***************************************************************************
    ** One section without children (the original record sheet).
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, Map<String, String> fields) throws QException
   {
      return pdf(title, subtitle, List.of(new Section("", fields)), List.of());
   }



   /***************************************************************************
    ** Render sections and child list summaries as a PDF.
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, List<Section> sections, List<ChildList> children) throws QException
   {
      return pdf(title, subtitle, sections, children, null);
   }



   /***************************************************************************
    ** Render sections and child list summaries under the app's header/footer.
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, List<Section> sections, List<ChildList> children, @Nullable PrintingChrome chrome) throws QException
   {
      try(PrintingLayout layout = new PrintingLayout("LETTER", false, title, subtitle, chrome))
      {
         int columns = layout.columns(BODY_SIZE);
         int labelWidth = Math.min(28, Math.max(8, sections.stream().flatMap(s -> s.fields().keySet().stream()).mapToInt(String::length).max().orElse(8) + 1));
         int lines = 0;
         for(Section section : sections)
         {
            if(section.fields().isEmpty())
            {
               continue;
            }
            if(section.label() != null && !section.label().isBlank())
            {
               layout.gap(4);
               layout.keep(3, BODY_SIZE);
               layout.line(section.label(), BODY_SIZE + 1);
            }
            for(Map.Entry<String, String> field : section.fields().entrySet())
            {
               List<String> wrapped = wrap(field.getValue() == null ? "" : field.getValue(), columns - labelWidth - 1);
               for(int i = 0; i < wrapped.size() && lines++ < MAX_LINES; i++)
               {
                  layout.line(String.format("%-" + labelWidth + "s %s", i == 0 ? truncate(field.getKey(), labelWidth - 1) : "", wrapped.get(i)), BODY_SIZE);
               }
            }
         }
         for(ChildList child : children)
         {
            layout.gap(8);
            layout.keep(4, CHILD_SIZE);
            layout.line(child.label() + " (" + child.total() + ")", BODY_SIZE + 1);
            if(child.rows().isEmpty())
            {
               layout.line("(none)", CHILD_SIZE);
               continue;
            }
            int[] widths = PrintingLayout.columnWidths(child.columns(), child.rows(), layout.columns(CHILD_SIZE));
            layout.line(PrintingLayout.row(child.columns(), widths), CHILD_SIZE);
            for(List<String> row : child.rows())
            {
               layout.line(PrintingLayout.row(row, widths), CHILD_SIZE);
            }
            if(child.total() > child.rows().size())
            {
               layout.line("... and " + (child.total() - child.rows().size()) + " more", CHILD_SIZE);
            }
         }
         return layout.finish();
      }
      catch(IOException exception)
      {
         throw new QException("Could not render the record document: " + exception.getMessage(), exception);
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
      return lines;
   }



   /***************************************************************************
    ** Shorten to at most length characters, marking the cut.
    ***************************************************************************/
   static String truncate(String text, int length)
   {
      String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
      return value.length() <= length ? value : value.substring(0, Math.max(0, length - 1)) + "~";
   }
}
