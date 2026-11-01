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
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            BufferedImage raster = PrintingTestDocuments.labelImage(profile.getDpi(), profile.getHeightInches());
            int[] box = switch(profile.getHeightInches())
            {
               case 1 -> new int[] {248, 9, 28};
               case 2 -> new int[] {240, 11, 36};
               default -> new int[] {210, 30, 54};
            };
            BufferedImage mascot = raster.getSubimage((int) Math.floor(box[0] * scale), (int) Math.floor(box[1] * scale), (int) Math.ceil(box[2] * scale), (int) Math.ceil(box[2] * scale));
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
            PDRectangle expected = profile.getDpi() > 0 ? new PDRectangle(288, profile.getHeightInches() * 72) : (profile == TestPrintProfile.A4 ? PDRectangle.A4 : PDRectangle.LETTER);
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
      return (reconstructGraphicStrips(zpl, rowBytes, height, 2));
   }



   /***************************************************************************
    ** Short labels fit one strip, so callers state how many strips they require.
    ***************************************************************************/
   private static byte[] reconstructGraphicStrips(String zpl, int rowBytes, int height, int minimumStrips)
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
      assertTrue(strips >= minimumStrips);
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
    ** Short stock has exact media boxes and job paper; 4 x 6 is unchanged and
    ** never gets job paper. Drawn pixels stay inside the physical label.
    ***************************************************************************/
   @Test
   void shortLabelPdfsUseExactMediaAndJobPaperWhileFourBySixIsPreserved() throws Exception
   {
      Object[][] stocks = {
         {TestPrintProfile.LABEL_4X1_300, 72f, 25.4},
         {TestPrintProfile.LABEL_4X2_300, 144f, 50.8},
         {TestPrintProfile.LABEL_4X6_300, 432f, null}};
      for(Object[] stock : stocks)
      {
         TestPrintProfile profile = (TestPrintProfile) stock[0];
         float height = (Float) stock[1];
         try(PDDocument document = PDDocument.load(PrintingTestDocuments.pdf(profile)))
         {
            PDRectangle media = document.getPage(0).getMediaBox();
            assertEquals(0, media.getLowerLeftX(), 0.001);
            assertEquals(0, media.getLowerLeftY(), 0.001);
            assertEquals(288, media.getWidth(), 0.001);
            assertEquals(height, media.getHeight(), 0.001);
            BufferedImage page = new PDFRenderer(document).renderImageWithDPI(0, 300);
            // PDFBox truncates float scale * points (e.g. 432 * 4.1666665 = 1799.9999), so the raster may be one pixel short.
            // The exact physical size is asserted on the media box above.
            assertEquals(1200, page.getWidth(), 1);
            assertEquals(Math.round(height / 72 * 300), page.getHeight(), 1);
            int margin = profile.getHeightInches() == 6 ? 11 * 300 / 72 : 4 * 300 / 72;
            int dark = 0;
            for(int y = 0; y < page.getHeight(); y++)
            {
               for(int x = 0; x < page.getWidth(); x++)
               {
                  if((page.getRGB(x, y) & 0xff) < 128)
                  {
                     dark++;
                     assertTrue(x >= margin - 1 && x < page.getWidth() - margin + 1 && y >= margin - 1 && y < page.getHeight() - margin + 1, "Ink outside quiet margin at " + x + "," + y);
                  }
               }
            }
            assertTrue(dark > 1000);
         }
         JSONObject options = profile.jobOptions("PDF");
         if(stock[2] == null)
         {
            assertNull(options);
         }
         else
         {
            JSONObject paper = java.util.Objects.requireNonNull(options, "Short label stock carries actual-size job paper");
            assertEquals(101.6, paper.getDouble("paperWidthMm"), 0.0001);
            assertEquals((Double) stock[2], paper.getDouble("paperHeightMm"), 0.0001);
            assertEquals(2, paper.length());
         }
         assertNull(profile.jobOptions("ZPL"));
      }
   }



   /***************************************************************************
    ** Every short-label density emits proper PW/LL, one copy, no printer-wide
    ** settings, an exact raster, and native barcode only where it fits.
    ***************************************************************************/
   @Test
   void shortLabelZplCarriesExactDimensionsAndBoundedBarcode() throws Exception
   {
      for(TestPrintProfile profile : TestPrintProfile.values())
      {
         if(profile.getDpi() == 0 || profile.getHeightInches() == 6)
         {
            continue;
         }
         int dpi = profile.getDpi();
         int width = dpi * 4;
         int height = dpi * profile.getHeightInches();
         String zpl = new String(PrintingTestDocuments.zpl(profile), StandardCharsets.US_ASCII);
         assertTrue(zpl.startsWith("^XA^PW" + width + "^LL" + height + "^LH0,0"), profile.name());
         assertTrue(zpl.endsWith("^PQ1^XZ"));
         assertEquals(1, zpl.split("\\^PQ", -1).length - 1);
         assertEquals(1, zpl.split("\\^XA", -1).length - 1);
         for(String forbidden : new String[] {"~D", "^A0", "^MT", "^MD", "^PR", "^JU", "^CF", "^PO"})
         {
            assertFalse(zpl.contains(forbidden), forbidden);
         }
         int rowBytes = (width + 7) / 8;
         byte[] bitmap = reconstructGraphicStrips(zpl, rowBytes, height, 1);
         BufferedImage expected = PrintingTestDocuments.labelImage(dpi, profile.getHeightInches());
         assertEquals(width, expected.getWidth());
         assertEquals(height, expected.getHeight());
         int margin = dpi / 18;
         int black = 0;
         for(int y = 0; y < height; y++)
         {
            for(int x = 0; x < rowBytes * 8; x++)
            {
               boolean set = (bitmap[y * rowBytes + x / 8] & (0x80 >>> (x % 8))) != 0;
               boolean expectedBlack = x < width && (expected.getRGB(x, y) & 0xffffff) == 0;
               assertEquals(expectedBlack, set, "Reconstructed raster and row padding must match");
               if(expectedBlack)
               {
                  black++;
                  assertTrue(x >= margin && x < width - margin && y >= margin && y < height - margin, "Ink outside margin at " + x + "," + y);
               }
            }
         }
         assertTrue(black > dpi * dpi / 8);
         Matcher barcode = Pattern.compile("\\^FO(\\d+),(\\d+)\\^BY(\\d+)\\^BCN,(\\d+),N,N,N\\^FD" + PrintingTestDocuments.BARCODE_VALUE + "\\^FS").matcher(zpl);
         if(profile.getHeightInches() == 1)
         {
            assertFalse(zpl.contains("^BC"), "A 4 x 1 label has no room for a scannable barcode");
            continue;
         }
         assertTrue(barcode.find());
         int x = Integer.parseInt(barcode.group(1));
         int y = Integer.parseInt(barcode.group(2));
         int module = Integer.parseInt(barcode.group(3));
         int barHeight = Integer.parseInt(barcode.group(4));
         assertFalse(barcode.find(), "Exactly one native barcode");
         int modules = 11 * (PrintingTestDocuments.BARCODE_VALUE.length() + 2) + 13;
         assertTrue(x >= 10 * module + margin, "Quiet zone");
         assertTrue(x + modules * module <= 170 * dpi / 72, "Barcode must end before its caption");
         assertTrue(y > 111 * dpi / 72, "Barcode must sit below the ruler");
         assertTrue(y + barHeight < height - margin);
         assertTrue(barHeight >= dpi / 8);
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

