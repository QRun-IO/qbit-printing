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
import com.kingsrook.qqq.backend.core.exceptions.QException;


/*******************************************************************************
 ** Plain text as a PDF: monospace, long lines hard-wrapped, broken across
 ** pages. Used to print a capture of a terminal screen (or any text).
 *******************************************************************************/
public final class PrintingTextDocuments
{
   static final int FONT_SIZE = 8;
   static final float MIN_FONT_SIZE = 5.5f;
   static final int MAX_CHARACTERS = 2_000_000;



   /***************************************************************************
    ** Utility class.
    ***************************************************************************/
   private PrintingTextDocuments()
   {
   }



   /***************************************************************************
    ** Render text on LETTER or A4, portrait or landscape.
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, String text, String pageSize, boolean landscape) throws QException
   {
      return (pdf(title, subtitle, text, pageSize, landscape, null));
   }



   /***************************************************************************
    ** Render text under the app's header/footer.
    ***************************************************************************/
   public static byte[] pdf(String title, String subtitle, String text, String pageSize, boolean landscape, PrintingChrome chrome) throws QException
   {
      if(text == null || text.isEmpty())
      {
         throw new QException("There is no text to print");
      }
      if(text.length() > MAX_CHARACTERS)
      {
         throw new QException("The text is too long to print (at most " + MAX_CHARACTERS + " characters)");
      }
      try(PrintingLayout layout = new PrintingLayout(pageSize, landscape, title, subtitle, chrome))
      {
         String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1);
         float size = fontSize(lines, layout.columns(1) * 1f);
         int width = layout.columns(size);
         layout.leading(1.2f);
         for(String line : lines)
         {
            String rest = line.stripTrailing();
            do
            {
               layout.line(rest.length() > width ? rest.substring(0, width) : rest, size);
               rest = rest.length() > width ? rest.substring(width) : "";
            }
            while(!rest.isEmpty());
         }
         return (layout.finish());
      }
      catch(IOException exception)
      {
         throw new QException("Could not render the text: " + exception.getMessage(), exception);
      }
   }



   /***************************************************************************
    ** The font size (8 down to 5.5 points) at which the widest line fits, so a
    ** screen capture keeps its layout; wider text still wraps at 5.5.
    ***************************************************************************/
   static float fontSize(String[] lines, float charactersAtOnePoint)
   {
      int widest = 1;
      for(String line : lines)
      {
         widest = Math.max(widest, line.stripTrailing().length());
      }
      float fits = charactersAtOnePoint / widest;
      return (Math.max(MIN_FONT_SIZE, Math.min(FONT_SIZE, (float) Math.floor(fits * 2) / 2)));
   }



   /***************************************************************************
    ** One section of a content document: a title (blank for none) and its
    ** lines, as the client read them from a screen panel.
    ***************************************************************************/
   public record Section(String title, java.util.List<String> lines)
   {
   }



   /***************************************************************************
    ** A screen's content as a document: each section's title as a heading,
    ** its lines in the monospace face, long lines wrapped at spaces to the page
    ** width (no borders, no navigation).
    ***************************************************************************/
   public static byte[] sectionsPdf(String title, String subtitle, java.util.List<Section> sections, String pageSize, boolean landscape, PrintingChrome chrome) throws QException
   {
      if(sections.isEmpty() || sections.stream().allMatch(section -> section.lines().stream().allMatch(String::isBlank) && (section.title() == null || section.title().isBlank())))
      {
         throw new QException("There is no content to print");
      }
      int total = sections.stream().mapToInt(section -> section.lines().stream().mapToInt(String::length).sum()).sum();
      if(total > MAX_CHARACTERS)
      {
         throw new QException("The content is too long to print (at most " + MAX_CHARACTERS + " characters)");
      }
      try(PrintingLayout layout = new PrintingLayout(pageSize, landscape, title, subtitle, chrome))
      {
         int width = layout.columns(FONT_SIZE);
         boolean first = true;
         for(Section section : sections)
         {
            if(section.title() != null && !section.title().isBlank())
            {
               layout.gap(first ? 0 : 8);
               layout.keep(3, FONT_SIZE);
               layout.line(section.title(), 11);
               layout.gap(2);
            }
            first = false;
            for(String line : section.lines())
            {
               for(String wrapped : PrintingRecordDocuments.wrap(line.stripTrailing(), width))
               {
                  layout.line(wrapped, FONT_SIZE);
               }
            }
         }
         return (layout.finish());
      }
      catch(IOException exception)
      {
         throw new QException("Could not render the content: " + exception.getMessage(), exception);
      }
   }
}
