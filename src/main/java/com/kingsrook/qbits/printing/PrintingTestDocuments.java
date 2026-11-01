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
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;


/*******************************************************************************
 ** Shared diagnostic documents. Font data travels with each job, never as a
 ** persistent printer download or an assumption about installed printer fonts.
 *******************************************************************************/
public final class PrintingTestDocuments
{
   static final String FONT_RESOURCE = "/com/kingsrook/qbits/printing/fonts/JetBrainsMono-Bold.ttf";
   private static final String LOGO_RESOURCE = "/com/kingsrook/qbits/printing/images/query-mascot.png";
   static final String BARCODE_VALUE = "QQQ-TEST-123456";
   private static final int LEGACY_LABEL_HEIGHT_INCHES = 6;
   static final int COMPACT_BARCODE_X = 16;
   static final int COMPACT_BARCODE_Y = 118;
   static final int COMPACT_BARCODE_HEIGHT = 16;



   /***************************************************************************
    ** One vector Letter page with an embedded, subsetted warehouse test font.
    ***************************************************************************/
   public static byte[] pdf() throws QException
   {
      return pdf(TestPrintProfile.LETTER);
   }



   /***************************************************************************
    ** PDF media is explicit; label profiles retain their four-by-six-inch size.
    ***************************************************************************/
   public static byte[] pdf(TestPrintProfile profile) throws QException
   {
      try(PDDocument document = new PDDocument(); InputStream fontStream = fontStream())
      {
         COSArray identifiers = new COSArray();
         identifiers.add(new COSString("QQQ-TEST-" + profile.name()));
         identifiers.add(new COSString("QQQ-TEST-" + profile.name()));
         document.getDocument().getTrailer().setItem(COSName.ID, identifiers);
         PDType0Font font = PDType0Font.load(document, fontStream, true);
         PDRectangle media = profile.getDpi() > 0 ? new PDRectangle(profile.getWidthPoints(), profile.getHeightPoints()) : (profile == TestPrintProfile.A4 ? PDRectangle.A4 : PDRectangle.LETTER);
         PDPage page = new PDPage(media);
         document.addPage(page);
         try(PDPageContentStream content = new PDPageContentStream(document, page))
         {
            if(profile.getDpi() > 0 && profile.getHeightInches() == LEGACY_LABEL_HEIGHT_INCHES)
            {
               pdfLabel(content, font);
               content.drawImage(LosslessFactory.createFromImage(document, logo()), 210, 348, 54, 54);
            }
            else if(profile.getDpi() > 0)
            {
               content.setLineWidth(1);
               compactLabel(new PdfCanvas(content, font, LosslessFactory.createFromImage(document, logo()), profile.getHeightPoints()), profile.getHeightInches(), "PDF / ACTUAL SIZE");
            }
            else
            {
               pdfText(content, font, 56, 48, 690, "QQQ");
               content.drawImage(LosslessFactory.createFromImage(document, logo()), media.getWidth() - 132, 660, 84, 84);
               pdfText(content, font, 18, 48, 650, "WAREHOUSE PRINT CHECK");
               pdfText(content, font, 14, 48, 620, "TEST PRINT - NOT POSTAGE");
               pdfText(content, font, 11, 48, 590, (profile == TestPrintProfile.A4 ? "A4 210 x 297 mm" : "LETTER 8.5 x 11 in") + " / ACTUAL SIZE");
               pdfText(content, font, 12, 48, 535, "READ EACH CHARACTER");
               pdfText(content, font, 32, 48, 490, "0O  1Il  5S  8B  2Z  6G");
               pdfText(content, font, 18, 48, 440, "BIN A01-I1 / SKU O0-5S8B");
               pdfText(content, font, 18, 48, 410, "ORDER QQQ-123456 / QTY 02");
               pdfText(content, font, 12, 48, 350, "Measure this line: 25.4 mm / 1 inch");
               content.setLineWidth(1);
               content.moveTo(48, 330);
               content.lineTo(120, 330);
               content.moveTo(48, 325);
               content.lineTo(48, 335);
               content.moveTo(120, 325);
               content.lineTo(120, 335);
               content.stroke();
               pdfGrayBars(content);
               pdfText(content, font, 10, 48, 195, "Check edges, solid fill and each gray level.");
               pdfText(content, font, 10, 48, 170, "Confirm paper output; SPOOLED means submitted only.");
               content.addRect(36, 36, media.getWidth() - 72, media.getHeight() - 72);
               content.stroke();
            }
         }
         ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         document.save(bytes);
         return bytes.toByteArray();
      }
      catch(IOException exception)
      {
         throw new QException("Could not generate branded test PDF", exception);
      }
   }



   /***************************************************************************
    ** Compatibility default for callers that have no configured label density.
    ***************************************************************************/
   public static byte[] zpl() throws QException
   {
      return zpl(203);
   }



   /***************************************************************************
    ** Reject page-only profiles even when invoked outside the HTTP entrypoint.
    ***************************************************************************/
   public static byte[] zpl(TestPrintProfile profile) throws QException
   {
      return zpl(profile.getDpi(), profile.getHeightInches());
   }



   /***************************************************************************
    ** A four-by-six-inch label at the explicitly chosen printer resolution.
    ***************************************************************************/
   public static byte[] zpl(int dpi) throws QException
   {
      return zpl(dpi, LEGACY_LABEL_HEIGHT_INCHES);
   }



   /***************************************************************************
    ** A four-inch-wide label of the explicit height at the chosen resolution.
    ** Text uses the bundled font rasterized to one bit; Code 128 stays native.
    ** No media-type, darkness or speed commands are sent, so the printer keeps
    ** its own direct-thermal configuration and this never changes its defaults.
    ***************************************************************************/
   static byte[] zpl(int dpi, int heightInches) throws QException
   {
      BufferedImage image = labelImage(dpi, heightInches);
      int rowBytes = (image.getWidth() + 7) / 8;
      byte[] bitmap = new byte[rowBytes * image.getHeight()];
      for(int y = 0; y < image.getHeight(); y++)
      {
         for(int x = 0; x < image.getWidth(); x++)
         {
            if((image.getRGB(x, y) & 0xffffff) == 0)
            {
               bitmap[y * rowBytes + x / 8] |= (byte) (0x80 >>> (x % 8));
            }
         }
      }
      int module = Math.max(2, dpi / 100);
      StringBuilder zpl = new StringBuilder("^XA^PW" + image.getWidth() + "^LL" + image.getHeight() + "^LH0,0");
      appendGraphicStrips(zpl, bitmap, rowBytes, image.getHeight());
      if(heightInches == LEGACY_LABEL_HEIGHT_INCHES)
      {
         zpl.append("^FO").append(dots(24, dpi)).append(',').append(dots(276, dpi)).append("^BY").append(module)
            .append("^BCN,").append(dots(42, dpi)).append(",N,N,N^FD").append(BARCODE_VALUE).append("^FS");
      }
      else if(heightInches == 2)
      {
         zpl.append("^FO").append(dots(COMPACT_BARCODE_X, dpi)).append(',').append(dots(COMPACT_BARCODE_Y, dpi)).append("^BY").append(module)
            .append("^BCN,").append(dots(COMPACT_BARCODE_HEIGHT, dpi)).append(",N,N,N^FD").append(BARCODE_VALUE).append("^FS");
      }
      zpl.append("^PQ1^XZ");
      return zpl.toString().getBytes(StandardCharsets.US_ASCII);
   }



   /***************************************************************************
    ** Zebra limits each graphic field to 99,999 bytes. Whole-row strips preserve
    ** padding and image coordinates without downloading persistent graphics.
    ***************************************************************************/
   private static void appendGraphicStrips(StringBuilder zpl, byte[] bitmap, int rowBytes, int height)
   {
      int rowsPerStrip = 99999 / rowBytes;
      for(int firstRow = 0; firstRow < height; firstRow += rowsPerStrip)
      {
         int rows = Math.min(rowsPerStrip, height - firstRow);
         int length = rows * rowBytes;
         int offset = firstRow * rowBytes;
         zpl.append("^FO0,").append(firstRow).append("^GFA,").append(length).append(',').append(length).append(',').append(rowBytes).append(',')
            .append(HexFormat.of().withUpperCase().formatHex(bitmap, offset, offset + length)).append("^FS");
      }
   }



   /***************************************************************************
    ** Deterministic image also supports inspection without contacting a printer.
    ***************************************************************************/
   static BufferedImage labelImage(int dpi) throws QException
   {
      return labelImage(dpi, LEGACY_LABEL_HEIGHT_INCHES);
   }



   /***************************************************************************
    ** Four-inch-wide label raster of an explicit supported height.
    ***************************************************************************/
   static BufferedImage labelImage(int dpi, int heightInches) throws QException
   {
      if(!Set.of(203, 300, 600).contains(dpi))
      {
         throw new IllegalArgumentException("Label resolution must be 203, 300 or 600 dpi");
      }
      if(!Set.of(1, 2, LEGACY_LABEL_HEIGHT_INCHES).contains(heightInches))
      {
         throw new IllegalArgumentException("Label height must be 1, 2 or 6 inches");
      }
      try(InputStream stream = fontStream())
      {
         Font font = Font.createFont(Font.TRUETYPE_FONT, stream);
         BufferedImage image = new BufferedImage(dpi * 4, dpi * heightInches, BufferedImage.TYPE_BYTE_BINARY);
         Graphics2D graphics = image.createGraphics();
         try
         {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.scale(dpi / 72.0, dpi / 72.0);
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            if(heightInches != LEGACY_LABEL_HEIGHT_INCHES)
            {
               compactLabel(new ImageCanvas(graphics, font, logo()), heightInches, dpi + " dpi / 1 copy");
               scanText(graphics, font, heightInches);
               return image;
            }
            graphics.drawRect(12, 12, 264, 408);
            graphics.drawImage(logo(), 210, 30, 54, 54, null);
            labelText(graphics, font, 43, 24, 58, "QQQ");
            labelText(graphics, font, 10, 24, 79, "WAREHOUSE PRINT CHECK");
            labelText(graphics, font, 12, 24, 101, "TEST PRINT - NOT POSTAGE");
            labelText(graphics, font, 9, 24, 119, "4 x 6 in / " + dpi + " dpi / 1 copy");
            graphics.drawLine(24, 130, 264, 130);
            labelText(graphics, font, 9, 24, 148, "READ EACH CHARACTER");
            labelText(graphics, font, 25, 24, 179, "0O  1Il  5S");
            labelText(graphics, font, 25, 24, 210, "8B  2Z   6G");
            labelText(graphics, font, 12, 24, 239, "BIN A01-I1 / SKU O0-5S8B");
            labelText(graphics, font, 12, 24, 258, "ORDER QQQ-123456 / QTY 02");
            labelText(graphics, font, 10, 24, 338, "SCAN: " + BARCODE_VALUE);
            graphics.drawLine(24, 365, 96, 365);
            graphics.drawLine(24, 361, 24, 369);
            graphics.drawLine(96, 361, 96, 369);
            labelText(graphics, font, 9, 106, 368, "25.4 mm / 1 inch");
            labelText(graphics, font, 8, 24, 398, "Confirm output, size and barcode scan.");
         }
         finally
         {
            graphics.dispose();
         }
         return image;
      }
      catch(IOException | FontFormatException exception)
      {
         throw new QException("Could not generate branded test label", exception);
      }
   }



   /***************************************************************************
    ** Fail explicitly if packaging omitted the licensed font resource.
    ***************************************************************************/
   private static InputStream fontStream() throws IOException
   {
      InputStream stream = PrintingTestDocuments.class.getResourceAsStream(FONT_RESOURCE);
      if(stream == null)
      {
         throw new IOException("Bundled test-document font is missing");
      }
      return stream;
   }



   /***************************************************************************
    ** Preserve Query's official artwork and aspect ratio inside square layout
    ** boxes, compositing transparency on white before monochrome printing.
    ***************************************************************************/
   private static BufferedImage logo() throws IOException
   {
      try(InputStream stream = PrintingTestDocuments.class.getResourceAsStream(LOGO_RESOURCE))
      {
         if(stream == null)
         {
            throw new IOException("Bundled Query mascot is missing");
         }
         BufferedImage source = ImageIO.read(stream);
         int size = Math.max(source.getWidth(), source.getHeight());
         BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
         Graphics2D graphics = image.createGraphics();
         try
         {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(source, (size - source.getWidth()) / 2, (size - source.getHeight()) / 2, null);
         }
         finally
         {
            graphics.dispose();
         }
         return image;
      }
   }



   /***************************************************************************
    ** Convert layout points to printer dots without changing physical size.
    ***************************************************************************/
   private static int dots(int points, int dpi)
   {
      return (int) Math.round(points * dpi / 72.0);
   }



   /***************************************************************************
    ** Draw text using the same bundled face on both document formats.
    ***************************************************************************/
   private static void labelText(Graphics2D graphics, Font font, int size, int x, int y, String text)
   {
      graphics.setFont(font.deriveFont((float) size));
      graphics.drawString(text, x, y);
   }



   /***************************************************************************
    ** Vector PDF text remains searchable and carries its own font subset.
    ***************************************************************************/
   private static void pdfText(PDPageContentStream content, PDType0Font font, int size, int x, int y, String text) throws IOException
   {
      content.beginText();
      content.setFont(font, size);
      content.newLineAtOffset(x, y);
      content.showText(text);
      content.endText();
   }



   /***************************************************************************
    ** Five vector gray levels expose missing toner and driver conversion.
    ***************************************************************************/
   private static void pdfGrayBars(PDPageContentStream content) throws IOException
   {
      for(int index = 0; index < 5; index++)
      {
         content.setNonStrokingColor(index / 4.0f);
         content.addRect(48 + index * 72, 240, 72, 48);
         content.fill();
      }
      content.setNonStrokingColor(0f);
      content.addRect(48, 240, 360, 48);
      content.stroke();
   }



   /***************************************************************************
    ** Compact label PDF keeps vector glyphs and explicit physical media bounds.
    ***************************************************************************/
   private static void pdfLabel(PDPageContentStream content, PDType0Font font) throws IOException
   {
      pdfText(content, font, 43, 24, 374, "QQQ");
      pdfText(content, font, 10, 24, 353, "WAREHOUSE PRINT CHECK");
      pdfText(content, font, 12, 24, 331, "TEST PRINT - NOT POSTAGE");
      pdfText(content, font, 9, 24, 313, "4 x 6 in / PDF / ACTUAL SIZE");
      pdfText(content, font, 9, 24, 284, "READ EACH CHARACTER");
      pdfText(content, font, 25, 24, 253, "0O  1Il  5S");
      pdfText(content, font, 25, 24, 222, "8B  2Z   6G");
      pdfText(content, font, 12, 24, 193, "BIN A01-I1 / SKU O0-5S8B");
      pdfText(content, font, 12, 24, 174, "ORDER QQQ-123456 / QTY 02");
      pdfText(content, font, 11, 24, 132, "PDF FONT / SCALE CHECK");
      pdfText(content, font, 9, 106, 64, "25.4 mm / 1 inch");
      pdfText(content, font, 8, 24, 34, "Confirm output and measure the line.");
      content.setLineWidth(1);
      content.moveTo(24, 67);
      content.lineTo(96, 67);
      content.moveTo(24, 63);
      content.lineTo(24, 71);
      content.moveTo(96, 63);
      content.lineTo(96, 71);
      content.addRect(12, 12, 264, 408);
      content.stroke();
   }



   /***************************************************************************
    ** Top-left origin drawing surface in points, shared by the PDF and raster
    ** paths so the compact 4 x 1 and 4 x 2 layouts cannot drift apart.
    ***************************************************************************/
   private interface LabelCanvas
   {
      /************************************************************************
       ** Draw a text baseline at the given point size and position.
       ************************************************************************/
      void text(int size, int x, int y, String text) throws IOException;



      /************************************************************************
       ** Stroke a one-point line between two points.
       ************************************************************************/
      void line(int x1, int y1, int x2, int y2) throws IOException;



      /************************************************************************
       ** Stroke a rectangle outline from its top-left corner.
       ************************************************************************/
      void rect(int x, int y, int width, int height) throws IOException;



      /************************************************************************
       ** Draw the square Query mascot from its top-left corner.
       ************************************************************************/
      void logo(int x, int y, int size) throws IOException;
   }



   /***************************************************************************
    ** Own layouts for the short stocks; the 4 x 6 layout is never scaled down.
    ** Text starts at 7 pt (about 20 dots at 203 dpi); only a 4 x 2 label has
    ** room for the native barcode, which the ZPL path places at COMPACT_BARCODE_*.
    ***************************************************************************/
   private static void compactLabel(LabelCanvas canvas, int heightInches, String medium) throws IOException
   {
      String stock = "4 x " + heightInches + " in / " + medium;
      if(heightInches == 2)
      {
         canvas.rect(5, 5, 278, 134);
         canvas.logo(240, 11, 36);
         canvas.text(26, 12, 38, "QQQ");
         canvas.text(9, 70, 26, "TEST PRINT - NOT POSTAGE");
         canvas.text(8, 70, 38, stock);
         canvas.line(12, 54, 232, 54);
         canvas.text(16, 12, 72, "0O  1Il  5S  8B  2Z");
         canvas.text(9, 12, 86, "BIN A01-I1 / SKU O0-5S8B");
         canvas.text(9, 12, 98, "ORDER QQQ-123456 / QTY 02");
         ruler(canvas, 108, 8, 92, 111);
      }
      else
      {
         canvas.rect(5, 5, 278, 62);
         canvas.logo(248, 9, 28);
         canvas.text(20, 12, 28, "QQQ");
         canvas.text(8, 56, 17, "TEST PRINT - NOT POSTAGE");
         canvas.text(7, 56, 27, stock);
         canvas.line(12, 33, 240, 33);
         canvas.text(12, 12, 46, "0O  1Il  5S  8B  2Z");
         canvas.text(7, 158, 44, "BIN A01-I1 / SKU O0-5S8B");
         ruler(canvas, 57, 7, 92, 60);
         canvas.text(7, 165, 60, "ORDER QQQ-123456 / QTY 02");
      }
   }



   /***************************************************************************
    ** One-inch (25.4 mm) measuring line with end ticks and its caption.
    ***************************************************************************/
   private static void ruler(LabelCanvas canvas, int y, int size, int captionX, int captionY) throws IOException
   {
      canvas.line(12, y, 84, y);
      canvas.line(12, y - 3, 12, y + 3);
      canvas.line(84, y - 3, 84, y + 3);
      canvas.text(size, captionX, captionY, "25.4 mm / 1 inch");
   }



   /***************************************************************************
    ** Caption beside the native 4 x 2 barcode; it exists only in the ZPL raster.
    ***************************************************************************/
   private static void scanText(Graphics2D graphics, Font font, int heightInches)
   {
      if(heightInches == 2)
      {
         labelText(graphics, font, 8, 170, 130, "SCAN: " + BARCODE_VALUE);
      }
   }



   /***************************************************************************
    ** Vector PDF surface; converts the top-left layout to PDF's bottom-left origin.
    ***************************************************************************/
   private static final class PdfCanvas implements LabelCanvas
   {
      private final PDPageContentStream content;
      private final PDType0Font font;
      private final PDImageXObject mascot;
      private final int height;



      /************************************************************************
       ** Draw onto one page whose height converts top-left layout coordinates.
       ************************************************************************/
      PdfCanvas(PDPageContentStream content, PDType0Font font, PDImageXObject mascot, int height)
      {
         this.content = content;
         this.font = font;
         this.mascot = mascot;
         this.height = height;
      }



      @Override
      public void text(int size, int x, int y, String text) throws IOException
      {
         pdfText(content, font, size, x, height - y, text);
      }



      @Override
      public void line(int x1, int y1, int x2, int y2) throws IOException
      {
         content.moveTo(x1, height - y1);
         content.lineTo(x2, height - y2);
         content.stroke();
      }



      @Override
      public void rect(int x, int y, int width, int rectHeight) throws IOException
      {
         content.addRect(x, height - y - rectHeight, width, rectHeight);
         content.stroke();
      }



      @Override
      public void logo(int x, int y, int size) throws IOException
      {
         content.drawImage(mascot, x, height - y - size, size, size);
      }
   }



   /***************************************************************************
    ** One-bit raster surface drawing the same layout with the same bundled font.
    ***************************************************************************/
   private record ImageCanvas(Graphics2D graphics, Font font, BufferedImage mascot) implements LabelCanvas
   {
      @Override
      public void text(int size, int x, int y, String text)
      {
         labelText(graphics, font, size, x, y, text);
      }



      @Override
      public void line(int x1, int y1, int x2, int y2)
      {
         graphics.drawLine(x1, y1, x2, y2);
      }



      @Override
      public void rect(int x, int y, int width, int height)
      {
         graphics.drawRect(x, y, width, height);
      }



      @Override
      public void logo(int x, int y, int size)
      {
         graphics.drawImage(mascot, x, y, size, size, null);
      }
   }
}

