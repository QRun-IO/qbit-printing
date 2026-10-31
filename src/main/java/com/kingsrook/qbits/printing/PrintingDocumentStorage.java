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


import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import com.kingsrook.qqq.backend.core.actions.tables.StorageAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3BackendMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3TableBackendDetails;
import org.json.JSONArray;


/*******************************************************************************
 ** Reuse the host's QQQ storage table and backend credentials, including S3.
 ** Only service-generated object references enter this adapter after tenant auth.
 *******************************************************************************/
public class PrintingDocumentStorage
{
   private static final String S3_KEY_LAYOUT = "qqq-s3-filesystem-v1";
   private final QInstance instance;
   private final String prefix;
   private final boolean allowMemoryTest;



   /***************************************************************************
    ** Bind storage to one host instance, including historical table bindings.
    ***************************************************************************/
   public PrintingDocumentStorage(QInstance instance, String tenantId)
   {
      this(instance, tenantId, false);
   }



   /***************************************************************************
    ** Package-scoped memory fixture seam; no runtime environment bypass exists.
    ***************************************************************************/
   PrintingDocumentStorage(QInstance instance, String tenantId, boolean allowMemoryTest)
   {
      this.instance = java.util.Objects.requireNonNull(instance);
      this.prefix = "printing/" + digest(tenantId) + "/";
      this.allowMemoryTest = allowMemoryTest;
   }



   /***************************************************************************
    ** Store immutable document bytes through the existing configured backend.
    ***************************************************************************/
   public void put(String table, String reference, String format, byte[] content) throws SQLException
   {
      execute(() ->
      {
         try(OutputStream stream = new StorageAction().createOutputStream(input(table, reference).withContentType("PDF".equals(format) ? "application/pdf" : "application/vnd.zebra-zpl")))
         {
            stream.write(content);
         }
         return null;
      });
   }



   /***************************************************************************
    ** Bound reads even if stored content was replaced outside this service.
    ***************************************************************************/
   public byte[] get(String table, String reference) throws SQLException
   {
      return (get(table, reference, Math.toIntExact(PrintingRealtimePolicy.defaults().get("maxPdfBytes"))));
   }


   /** Internal retry callers supply the exact persisted length, independent of the current format policy. */
   public byte[] get(String table, String reference, int maximum) throws SQLException
   {
      return (execute(() ->
      {
         try(InputStream stream = new StorageAction().getInputStream(input(table, reference)))
         {
            byte[] bytes = stream.readNBytes(maximum);
            if(stream.read() != -1)
            {
               throw new PrintingException(502, "Stored print document exceeds its declared length");
            }
            return bytes;
         }
      }));
   }


   /** Return the native backend stream; the request owns closing it and enforcing its declared length. */
   public InputStream open(String table, String reference) throws SQLException
   {
      return (execute(() -> new StorageAction().getInputStream(input(table, reference))));
   }



   /***************************************************************************
    ** Backend deletion is idempotent; callers mark receipts only after success.
    ***************************************************************************/
   public void delete(String table, String reference) throws SQLException
   {
      execute(() ->
      {
         new StorageAction().deleteObject(input(table, reference));
         return null;
      });
   }



   /***************************************************************************
    ** Read-only host metadata lets administrators see the resolved destination.
    ***************************************************************************/
   public String backendName(String table)
   {
      if(instance.getTable(table) == null)
      {
         throw new IllegalArgumentException("Configured printing storage table does not exist");
      }
      return (instance.getTable(table).getBackendName());
   }



   /***************************************************************************
    ** Validate dedicated bucket use visible in this host's metadata. External
    ** bucket policies and unrelated applications still require deployment review.
    ** RC.20 keys use File.separator; changing that layout requires explicit
    ** binding review and migration, not silently adopting historical objects.
    ** Ordered JSON preserves component boundaries, including multiline paths.
    ***************************************************************************/
   public String binding(String table)
   {
      var metadata = instance.getTable(table);
      var backend = instance.getBackend(backendName(table));
      if(allowMemoryTest && "memory".equals(backend.getBackendType()))
      {
         return (digest(table + ":" + backend.getName()));
      }
      if(!table.startsWith("printing_") || !(backend instanceof S3BackendMetaData s3) || !(metadata.getBackendDetails() instanceof S3TableBackendDetails details)
         || s3.getBucketName() == null || s3.getBucketName().isBlank())
      {
         throw new IllegalArgumentException("Printing requires a dedicated printing_* S3 storage table and configured bucket");
      }
      String identity = bucketIdentity(s3);
      for(var other : instance.getTables().values())
      {
         if(!other.getName().startsWith("printing_") && instance.getBackend(other.getBackendName()) instanceof S3BackendMetaData otherBackend
            && identity.equals(bucketIdentity(otherBackend)))
         {
            throw new IllegalArgumentException("Printing bucket is shared with a non-printing table; configure a dedicated bucket");
         }
      }
      return (digest(new JSONArray().put(identity).put(java.util.Objects.toString(s3.getRegion(), "")).put(java.util.Objects.toString(s3.getPathStyleAccess(), "false"))
         .put(java.util.Objects.toString(s3.getBasePath(), "")).put(java.util.Objects.toString(details.getBasePath(), ""))
         .put(S3_KEY_LAYOUT).put((int) java.io.File.separatorChar).toString()));
   }



   /***************************************************************************
    ** Display only the bucket name, never access keys or endpoint query data.
    ***************************************************************************/
   public String bucketName(String table)
   {
      var backend = instance.getBackend(backendName(table));
      return (backend instanceof S3BackendMetaData s3 ? s3.getBucketName() : "In-memory test storage");
   }



   /***************************************************************************
    ** A tenant-specific owned namespace is shared by validation and generation.
    ***************************************************************************/
   public String prefix()
   {
      return (prefix);
   }



   /***************************************************************************
    ** Keep historical bindings immutable even when a host retargets metadata.
    ***************************************************************************/
   public void verifyBinding(String table, String expected)
   {
      if(!binding(table).equals(expected))
      {
         throw new PrintingException(409, "Stored document destination no longer matches the host configuration");
      }
   }



   /***************************************************************************
    ** Include provider identity; identical bucket names at distinct endpoints
    ** are not assumed to share storage. Fingerprints never contain credentials.
    ***************************************************************************/
   private static String bucketIdentity(S3BackendMetaData backend)
   {
      String endpoint = java.util.Objects.toString(backend.getEndpoint(), "AWS");
      if(!"AWS".equals(endpoint))
      {
         java.net.URI uri = java.net.URI.create(endpoint).normalize();
         if(uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
            || ("http".equalsIgnoreCase(uri.getScheme()) && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT)))))
         {
            throw new IllegalArgumentException("Printing storage endpoint requires HTTPS (except loopback development) and no credentials, query or fragment");
         }
         endpoint = uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
            + (uri.getPort() < 0 || ("https".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == 443) || ("http".equalsIgnoreCase(uri.getScheme()) && uri.getPort() == 80) ? "" : ":" + uri.getPort()) + java.util.Objects.toString(uri.getPath(), "").replaceAll("/+$", "");
      }
      return (new JSONArray().put(endpoint).put(java.util.Objects.toString(backend.getBucketName())).toString());
   }



   /***************************************************************************
    ** Hash namespace/binding values instead of embedding host identifiers.
    ***************************************************************************/
   private static String digest(String value)
   {
      try
      {
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))));
      }
      catch(java.security.NoSuchAlgorithmException exception)
      {
         throw new IllegalStateException(exception);
      }
   }



   /***************************************************************************
    ** No caller-selected paths, bucket URLs or filesystem traversal are accepted.
    ***************************************************************************/
   private StorageInput input(String table, String reference)
   {
      binding(table);
      if(reference == null || !reference.startsWith(prefix) || !reference.matches("printing/[a-f0-9]{64}/[a-f0-9-]{36}\\.(pdf|zpl)"))
      {
         throw new IllegalArgumentException("Invalid owned printing storage reference");
      }
      return (new StorageInput(table).withReference(reference));
   }



   /***************************************************************************
    ** Device HTTP threads have no QQQ session. Use a narrow instance-bound
    ** system context only for storage, and always restore the caller context.
    ***************************************************************************/
   private <T> T execute(Callable<T> operation) throws SQLException
   {
      var original = QContext.capture();
      try
      {
         QContext.init(instance, new QSystemUserSession(instance));
         return (operation.call());
      }
      catch(PrintingException exception)
      {
         throw exception;
      }
      catch(Exception exception)
      {
         throw new SQLException("Printing document storage operation failed", exception);
      }
      finally
      {
         QContext.init(original);
      }
   }
}

