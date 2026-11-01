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


/*******************************************************************************
 ** Shared diagnostic documents. Font data travels with each job, never as a
 ** persistent printer download or an assumption about installed printer fonts.
 *******************************************************************************/
public final class PrintingTestDocuments
{
   static final String FONT_RESOURCE = "/com/kingsrook/qbits/printing/fonts/JetBrainsMono-Bold.ttf";
   private static final String LOGO_RESOURCE = "/com/kingsrook/qbits/printing/images/query-mascot.png";
   static final String BARCODE_VALUE = "QQQ-TEST-123456";



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
         PDRectangle media = profile.getDpi() > 0 ? new PDRectangle(288, 432) : (profile == TestPrintProfile.A4 ? PDRectangle.A4 : PDRectangle.LETTER);
         PDPage page = new PDPage(media);
         document.addPage(page);
         try(PDPageContentStream content = new PDPageContentStream(document, page))
         {
            if(profile.getDpi() > 0)
            {
               pdfLabel(content, font);
               content.drawImage(LosslessFactory.createFromImage(document, logo()), 210, 348, 54, 54);
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
      return zpl(profile.getDpi());
   }



   /***************************************************************************
    ** A four-by-six-inch label at the explicitly chosen printer resolution.
    ** Text uses the bundled font rasterized to one bit; Code 128 stays native.
    ***************************************************************************/
   public static byte[] zpl(int dpi) throws QException
   {
      BufferedImage image = labelImage(dpi);
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
      zpl.append("^FO").append(dots(24, dpi)).append(',').append(dots(276, dpi)).append("^BY").append(module)
         .append("^BCN,").append(dots(42, dpi)).append(",N,N,N^FD").append(BARCODE_VALUE).append("^FS^PQ1^XZ");
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
      if(!Set.of(203, 300, 600).contains(dpi))
      {
         throw new IllegalArgumentException("Label resolution must be 203, 300 or 600 dpi");
      }
      try(InputStream stream = fontStream())
      {
         Font font = Font.createFont(Font.TRUETYPE_FONT, stream);
         BufferedImage image = new BufferedImage(dpi * 4, dpi * 6, BufferedImage.TYPE_BYTE_BINARY);
         Graphics2D graphics = image.createGraphics();
         try
         {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.scale(dpi / 72.0, dpi / 72.0);
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
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
}

