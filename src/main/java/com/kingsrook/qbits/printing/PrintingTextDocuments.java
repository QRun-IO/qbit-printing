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
      if(text == null || text.isEmpty())
      {
         throw new QException("There is no text to print");
      }
      if(text.length() > MAX_CHARACTERS)
      {
         throw new QException("The text is too long to print (at most " + MAX_CHARACTERS + " characters)");
      }
      try(PrintingLayout layout = new PrintingLayout(pageSize, landscape, title, subtitle))
      {
         int width = layout.columns(FONT_SIZE);
         for(String line : text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1))
         {
            String rest = line.stripTrailing();
            do
            {
               layout.line(rest.length() > width ? rest.substring(0, width) : rest, FONT_SIZE);
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
}
