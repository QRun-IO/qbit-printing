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


import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Explicit stock and resolution choices for built-in diagnostic documents.
 *******************************************************************************/
public enum TestPrintProfile
{
   LETTER("Letter - 8.5 x 11 in", 0),
   A4("A4 - 210 x 297 mm", 0),
   LABEL_4X6_203("4 x 6 in label - 203 dpi", 203),
   LABEL_4X6_300("4 x 6 in label - 300 dpi", 300),
   LABEL_4X6_600("4 x 6 in label - 600 dpi", 600);

   private final String label;
   private final int dpi;



   /***************************************************************************
    ** Store a human label and printer density without guessing from a name.
    ***************************************************************************/
   TestPrintProfile(String label, int dpi)
   {
      this.label = label;
      this.dpi = dpi;
   }



   /***************************************************************************
    ** Validate before enqueueing. Omitted profiles retain legacy defaults.
    ***************************************************************************/
   public static TestPrintProfile resolve(String format, @Nullable String value) throws PrintingException
   {
      if(!"PDF".equals(format) && !"ZPL".equals(format))
      {
         throw new PrintingException(400, "Only PDF and ZPL are supported");
      }
      TestPrintProfile profile;
      try
      {
         profile = value == null ? ("PDF".equals(format) ? LETTER : LABEL_4X6_203) : valueOf(value);
      }
      catch(IllegalArgumentException exception)
      {
         throw new PrintingException(400, "Unknown test profile");
      }
      if("ZPL".equals(format) && profile.dpi == 0)
      {
         throw new PrintingException(400, "ZPL test prints require a 4 x 6 label profile with the matching printer dpi");
      }
      return profile;
   }



   /***************************************************************************
    ** Friendly choice label used by native QQQ forms.
    ***************************************************************************/
   public String getLabel()
   {
      return label;
   }



   /***************************************************************************
    ** Zero marks page profiles; label values are supported device resolutions.
    ***************************************************************************/
   public int getDpi()
   {
      return dpi;
   }
}

