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


import org.json.JSONObject;


/*******************************************************************************
 ** Explicit stock and resolution choices for built-in diagnostic documents.
 *******************************************************************************/
public enum TestPrintProfile
{
   LETTER("Letter - 8.5 x 11 in", 0),
   A4("A4 - 210 x 297 mm", 0),
   LABEL_4X6_203("4 x 6 in label - 203 dpi", 203),
   LABEL_4X6_300("4 x 6 in label - 300 dpi", 300),
   LABEL_4X6_600("4 x 6 in label - 600 dpi", 600),
   LABEL_4X2_203("4 x 2 in label - 203 dpi", 203, 2),
   LABEL_4X2_300("4 x 2 in label - 300 dpi", 300, 2),
   LABEL_4X2_600("4 x 2 in label - 600 dpi", 600, 2),
   LABEL_4X1_203("4 x 1 in label - 203 dpi", 203, 1),
   LABEL_4X1_300("4 x 1 in label - 300 dpi", 300, 1),
   LABEL_4X1_600("4 x 1 in label - 600 dpi", 600, 1);

   private static final int LEGACY_LABEL_HEIGHT_INCHES = 6;
   private static final double MM_PER_INCH = 25.4;

   private final String label;
   private final int dpi;
   private final int heightInches;



   /***************************************************************************
    ** Store a human label and printer density without guessing from a name.
    ***************************************************************************/
   TestPrintProfile(String label, int dpi)
   {
      this(label, dpi, dpi > 0 ? LEGACY_LABEL_HEIGHT_INCHES : 0);
   }



   /***************************************************************************
    ** Labels are always four inches wide; the height is explicit stock data.
    ***************************************************************************/
   TestPrintProfile(String label, int dpi, int heightInches)
   {
      this.label = label;
      this.dpi = dpi;
      this.heightInches = heightInches;
   }



   /***************************************************************************
    ** Validate before enqueueing. Omitted profiles retain legacy defaults.
    ***************************************************************************/
   public static TestPrintProfile resolve(String format, String value) throws PrintingException
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
         throw new PrintingException(400, "ZPL test prints require a label profile with the matching printer dpi");
      }
      return (profile);
   }



   /***************************************************************************
    ** Friendly choice label used by native QQQ forms.
    ***************************************************************************/
   public String getLabel()
   {
      return (label);
   }



   /***************************************************************************
    ** Zero marks page profiles; label values are supported device resolutions.
    ***************************************************************************/
   public int getDpi()
   {
      return (dpi);
   }



   /***************************************************************************
    ** Label height in inches; zero for page profiles.
    ***************************************************************************/
   public int getHeightInches()
   {
      return (heightInches);
   }



   /***************************************************************************
    ** Exact PDF media width in points (72 per inch); zero for page profiles.
    ***************************************************************************/
   public int getWidthPoints()
   {
      return (dpi > 0 ? 4 * 72 : 0);
   }



   /***************************************************************************
    ** Exact PDF media height in points (72 per inch); zero for page profiles.
    ***************************************************************************/
   public int getHeightPoints()
   {
      return (heightInches * 72);
   }



   /***************************************************************************
    ** Only labels shorter than the legacy 4 x 6 stock need job-specific paper;
    ** 4 x 6 and page profiles keep using the Windows queue default untouched.
    ***************************************************************************/
   public boolean needsJobPaper(String format)
   {
      return ("PDF".equals(format) && dpi > 0 && heightInches != LEGACY_LABEL_HEIGHT_INCHES);
   }



   /***************************************************************************
    ** Actual-size paper for this job (never a media profile), or null when the
    ** configured queue default applies. Millimetres are exact: 101.6 x 25.4/50.8.
    ***************************************************************************/
   public JSONObject jobOptions(String format)
   {
      if(!needsJobPaper(format))
      {
         return (null);
      }
      return (new JSONObject().put("paperWidthMm", 4 * MM_PER_INCH).put("paperHeightMm", heightInches * MM_PER_INCH));
   }
}

