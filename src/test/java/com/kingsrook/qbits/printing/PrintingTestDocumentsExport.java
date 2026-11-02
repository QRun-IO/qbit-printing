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


import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;


/*******************************************************************************
 ** Manual local preview export only; this utility never connects to a printer.
 *******************************************************************************/
public final class PrintingTestDocumentsExport
{
   /***************************************************************************
    ** Write diagnostic artifacts to the caller's explicit preview directory.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      if(args.length != 1)
      {
         throw new IllegalArgumentException("Pass one local preview output directory");
      }
      Path directory = Path.of(args[0]).toAbsolutePath();
      Files.createDirectories(directory);
      for(TestPrintProfile profile : TestPrintProfile.values())
      {
         byte[] pdf = PrintingTestDocuments.pdf(profile);
         Files.write(directory.resolve(profile.name() + ".pdf"), pdf);
         try(PDDocument document = PDDocument.load(pdf))
         {
            ImageIO.write(new PDFRenderer(document).renderImageWithDPI(0, 144), "png", directory.resolve(profile.name() + "-pdf.png").toFile());
         }
         if(profile.getDpi() > 0)
         {
            Files.write(directory.resolve(profile.name() + ".zpl"), PrintingTestDocuments.zpl(profile));
            ImageIO.write(PrintingTestDocuments.labelImage(profile.getDpi()), "png", directory.resolve(profile.name() + "-raster.png").toFile());
         }
      }
   }
}

