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


import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


/** Length and digest verification remains bounded while transferring a retained object. */
final class PrintingDocumentStream extends FilterInputStream
{
   private final long length;
   private final String expectedHash;
   private final MessageDigest digest;
   private long received;
   private boolean verified;


   /** Wrap a source with immutable document integrity metadata. */
   PrintingDocumentStream(InputStream input, long length, String expectedHash)
   {
      super(input);
      this.length = length;
      this.expectedHash = expectedHash;
      try
      {
         digest = MessageDigest.getInstance("SHA-256");
      }
      catch(NoSuchAlgorithmException exception)
      {
         throw new IllegalStateException(exception);
      }
   }


   @Override
   public int read() throws IOException
   {
      byte[] one = new byte[1];
      return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
   }


   @Override
   public int read(byte[] bytes, int offset, int count) throws IOException
   {
      int read = in.read(bytes, offset, (int) Math.min(count, Math.max(1, length - received + 1)));
      if(read > 0)
      {
         received += read;
         if(received > length)
         {
            throw new IOException("Stored print document exceeds its declared length");
         }
         digest.update(bytes, offset, read);
      }
      else if(read == -1 && !verified)
      {
         verified = true;
         if(received != length || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash))
         {
            throw new IOException("Stored print document integrity check failed");
         }
      }
      return read;
   }
}