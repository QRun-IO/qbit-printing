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
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Verify portable font/media content and bounded monochrome printer payloads.
 *******************************************************************************/
class PrintingTestDocumentsTest
{
   /***************************************************************************
    ** The packaged mascot is the exact official asset, with an undistorted
    ** visible outline in every PDF and each supported thermal raster density.
    ***************************************************************************/
   @Test
   void officialQueryMascotPreservesAspectRatioAcrossFormats() throws Exception
   {
      byte[] asset;
      try(var stream = getClass().getResourceAsStream("/com/kingsrook/qbits/printing/images/query-mascot.png"))
      {
         asset = stream.readAllBytes();
      }
      assertEquals("385a053e15e0a9fc6f8a965257e3917db827594ab46ecf8cdddb847770a25dbb", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(asset)));
      BufferedImage official = ImageIO.read(new java.io.ByteArrayInputStream(asset));
      assertEquals(1326, official.getWidth());
      assertEquals(1394, official.getHeight());
      double expectedRatio = visibleAspectRatio(official);
      for(TestPrintProfile profile : TestPrintProfile.values())
      {
         try(PDDocument document = PDDocument.load(PrintingTestDocuments.pdf(profile)))
         {
            int images = 0;
            var resources = document.getPage(0).getResources();
            for(var name : resources.getXObjectNames())
            {
               if(resources.getXObject(name) instanceof PDImageXObject image)
               {
                  images++;
                  assertEquals(image.getWidth(), image.getHeight());
                  assertEquals(expectedRatio, visibleAspectRatio(image.getImage()), 0.01);
               }
            }
            assertEquals(1, images);
         }
         if(profile.getDpi() > 0)
         {
            double scale = profile.getDpi() / 72.0;
            BufferedImage raster = PrintingTestDocuments.labelImage(profile.getDpi());
            BufferedImage mascot = raster.getSubimage((int) Math.floor(210 * scale), (int) Math.floor(30 * scale), (int) Math.ceil(54 * scale), (int) Math.ceil(54 * scale));
            assertEquals(expectedRatio, visibleAspectRatio(mascot), 0.03);
         }
      }
   }



   /***************************************************************************
    ** Compare the actual visible artwork, ignoring transparent/white padding.
    ***************************************************************************/
   private static double visibleAspectRatio(BufferedImage image)
   {
      int left = image.getWidth();
      int right = -1;
      int top = image.getHeight();
      int bottom = -1;
      for(int y = 0; y < image.getHeight(); y++)
      {
         for(int x = 0; x < image.getWidth(); x++)
         {
            int pixel = image.getRGB(x, y);
            if((pixel >>> 24) >= 128 && (pixel & 0xffffff) < 0x808080)
            {
               left = Math.min(left, x);
               right = Math.max(right, x);
               top = Math.min(top, y);
               bottom = Math.max(bottom, y);
            }
         }
      }
      assertTrue(right > left && bottom > top);
      return (right - left + 1.0) / (bottom - top + 1.0);
   }



   /***************************************************************************
    ** Every PDF profile has searchable diagnostic text and an embedded font.
    ***************************************************************************/
   @Test
   void pdfProfilesEmbedFontAndUsePhysicalMedia() throws Exception
   {
      for(TestPrintProfile profile : TestPrintProfile.values())
      {
         byte[] bytes = PrintingTestDocuments.pdf(profile);
         assertArrayEquals(bytes, PrintingTestDocuments.pdf(profile), "Identical requests must preserve the content hash");
         try(PDDocument document = PDDocument.load(bytes))
         {
            assertEquals(1, document.getNumberOfPages());
            PDRectangle size = document.getPage(0).getMediaBox();
            PDRectangle expected = profile.getDpi() > 0 ? new PDRectangle(288, 432) : (profile == TestPrintProfile.A4 ? PDRectangle.A4 : PDRectangle.LETTER);
            assertEquals(expected.getWidth(), size.getWidth(), 0.01);
            assertEquals(expected.getHeight(), size.getHeight(), 0.01);
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("QQQ"));
            assertTrue(text.contains("TEST PRINT - NOT POSTAGE"));
            assertTrue(text.contains("0O  1Il  5S"));
            assertTrue(text.contains("25.4 mm"));
            var resources = document.getPage(0).getResources();
            int fonts = 0;
            for(var name : resources.getFontNames())
            {
               fonts++;
               assertTrue(resources.getFont(name).isEmbedded());
               assertTrue(resources.getFont(name).getName().contains("JetBrainsMono"));
            }
            assertEquals(1, fonts);
         }
      }
   }



   /***************************************************************************
    ** All supported densities preserve inches and carry valid bounded GFA data.
    ***************************************************************************/
   @Test
   void labelDensitiesCarryExactBitmapAndNativeBarcodeWithoutPersistentDownloads() throws Exception
   {
      for(int dpi : new int[] {203, 300, 600})
      {
         byte[] bytes = PrintingTestDocuments.zpl(dpi);
         assertTrue(bytes.length < 3 * 1024 * 1024);
         String zpl = new String(bytes, StandardCharsets.US_ASCII);
         int width = dpi * 4;
         int height = dpi * 6;
         int rowBytes = (width + 7) / 8;
         assertTrue(zpl.startsWith("^XA^PW" + width + "^LL" + height + "^LH0,0"));
         byte[] bitmap = reconstructGraphicStrips(zpl, rowBytes, height);
         BufferedImage expected = PrintingTestDocuments.labelImage(dpi);
         for(int y = 0; y < height; y++)
         {
            for(int x = 0; x < rowBytes * 8; x++)
            {
               boolean black = (bitmap[y * rowBytes + x / 8] & (0x80 >>> (x % 8))) != 0;
               boolean expectedBlack = x < width && (expected.getRGB(x, y) & 0xffffff) == 0;
               assertEquals(expectedBlack, black, "Reconstructed raster and row padding must match");
            }
         }
         assertEquals(0, bitmap[0]);
         assertEquals(0, bitmap[bitmap.length - 1]);
         assertTrue(zpl.contains("^FD" + PrintingTestDocuments.BARCODE_VALUE + "^FS"));
         Matcher barcode = Pattern.compile("\\^FO(\\d+),(\\d+)\\^BY(\\d+)\\^BCN,(\\d+),N,N,N").matcher(zpl);
         assertTrue(barcode.find());
         int barcodeX = Integer.parseInt(barcode.group(1));
         int barcodeY = Integer.parseInt(barcode.group(2));
         int module = Integer.parseInt(barcode.group(3));
         int barcodeHeight = Integer.parseInt(barcode.group(4));
         int maximumModules = 11 * (PrintingTestDocuments.BARCODE_VALUE.length() + 2) + 13;
         assertTrue(barcodeX >= 10 * module);
         assertTrue(barcodeX + (maximumModules + 10) * module < width - dpi / 8);
         assertTrue(barcodeY > 258 * dpi / 72);
         assertTrue(barcodeY + barcodeHeight < 330 * dpi / 72);
         assertTrue(zpl.endsWith("^PQ1^XZ"));
         assertFalse(zpl.contains("~D"));
         assertFalse(zpl.contains("^A0"));
      }
      assertArrayEquals(PrintingTestDocuments.zpl(203), PrintingTestDocuments.zpl());
   }



   /***************************************************************************
    ** Parse actual wire fields and reject any missing, overlapping or oversized
    ** strip before comparing the complete bitmap with the rendered source.
    ***************************************************************************/
   private static byte[] reconstructGraphicStrips(String zpl, int rowBytes, int height)
   {
      Matcher matcher = Pattern.compile("\\^FO0,(\\d+)\\^GFA,(\\d+),(\\d+),(\\d+),([0-9A-F]+)\\^FS").matcher(zpl);
      byte[] bitmap = new byte[rowBytes * height];
      int nextRow = 0;
      int strips = 0;
      while(matcher.find())
      {
         strips++;
         int firstRow = Integer.parseInt(matcher.group(1));
         int length = Integer.parseInt(matcher.group(2));
         assertEquals(nextRow, firstRow);
         assertTrue(length > 0 && length <= 99999);
         assertEquals(length, Integer.parseInt(matcher.group(3)));
         assertEquals(rowBytes, Integer.parseInt(matcher.group(4)));
         assertEquals(0, length % rowBytes);
         assertEquals(length * 2, matcher.group(5).length());
         byte[] strip = HexFormat.of().parseHex(matcher.group(5));
         assertTrue(firstRow * rowBytes + strip.length <= bitmap.length);
         System.arraycopy(strip, 0, bitmap, firstRow * rowBytes, strip.length);
         nextRow += length / rowBytes;
      }
      assertEquals(height, nextRow);
      assertTrue(strips > 1);
      assertEquals(strips, zpl.split("\\^GFA,", -1).length - 1);
      return bitmap;
   }



   /***************************************************************************
    ** Visible text and border fit inside quiet margins at each device density.
    ***************************************************************************/
   @Test
   void rasterArtworkLeavesPhysicalMargins() throws Exception
   {
      for(int dpi : new int[] {203, 300, 600})
      {
         BufferedImage image = PrintingTestDocuments.labelImage(dpi);
         int black = 0;
         int margin = dpi / 8;
         for(int y = 0; y < image.getHeight(); y++)
         {
            for(int x = 0; x < image.getWidth(); x++)
            {
               if((image.getRGB(x, y) & 0xffffff) == 0)
               {
                  black++;
                  assertTrue(x >= margin && x < image.getWidth() - margin && y >= margin && y < image.getHeight() - margin);
               }
            }
         }
         assertTrue(black > dpi * dpi / 2);
      }
   }



   /***************************************************************************
    ** Invalid combinations fail before a job can be created.
    ***************************************************************************/
   @Test
   void profilesRejectWrongFormatStockAndDensity() throws Exception
   {
      assertEquals(TestPrintProfile.LETTER, TestPrintProfile.resolve("PDF", null));
      assertEquals(TestPrintProfile.LABEL_4X6_203, TestPrintProfile.resolve("ZPL", null));
      assertEquals(TestPrintProfile.A4, TestPrintProfile.resolve("PDF", "A4"));
      assertEquals(400, assertThrows(PrintingException.class, () -> TestPrintProfile.resolve("ZPL", "LETTER")).getStatus());
      assertThrows(PrintingException.class, () -> TestPrintProfile.resolve("ZPL", "A4"));
      assertThrows(PrintingException.class, () -> TestPrintProfile.resolve("PDF", "UNKNOWN"));
      assertThrows(PrintingException.class, () -> TestPrintProfile.resolve("RAW", null));
      assertThrows(IllegalArgumentException.class, () -> PrintingTestDocuments.zpl(200));
   }
}

