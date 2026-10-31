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


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3BackendMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3TableBackendDetails;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Persisted S3 binding contracts use real metadata and the production guard.
 ** Only object IO is recorded in memory; no endpoint or credentials are used.
 *******************************************************************************/
class PrintingStorageBindingTest
{
   private static final byte[] DOCUMENT = "^XA^FDLayout contract^FS^XZ".getBytes(StandardCharsets.US_ASCII);
   private static final String LEGACY_BINDING = "120fbf86e305b3457c0ef9fd62829d974730506ceae267b2b96503475724935d";
   private static final String POSIX_BINDING = "dd336d2ddced413ee7a9a35ffa92e40fc20eb0ab2bbced01cba76081482738a1";
   private static final String WINDOWS_BINDING = "2981f67f05b02d4e32822a910e948d28ac565410780defe47f5ba1fd9bfd3667";



   /***************************************************************************
    ** Golden hashes represent AWS/print-documents, us-east-1, false path-style,
    ** empty base paths, and reviewed qqq-s3-filesystem-v1 separators 47 or 92.
    ** Identity components use ordered JSON; the untagged hash predates this
    ** layout contract and is never adopted.
    ***************************************************************************/
   @Test
   void rejectsUntaggedAndOtherPlatformBindings()
   {
      RecordingStorage storage = new RecordingStorage();
      for(String incompatible : incompatibleBindings())
      {
         assertEquals(409, assertThrows(PrintingException.class, () -> storage.verifyBinding("printing_documents", incompatible)).getStatus());
      }
      String current = File.separatorChar == '\\' ? WINDOWS_BINDING : POSIX_BINDING;
      assertEquals(current, storage.binding("printing_documents"));
      storage.verifyBinding("printing_documents", current);
      assertTrue(storage.calls.isEmpty());
   }



   /***************************************************************************
    ** Valid multiline prefixes must not move delimiters between identity parts.
    ** RC.20 resolves these pairs to different physical S3 keys.
    ***************************************************************************/
   @Test
   void rejectsMultilinePrefixRetargeting()
   {
      QInstance instance = s3Instance();
      S3BackendMetaData backend = (S3BackendMetaData) instance.getBackend("s3");
      S3TableBackendDetails details = (S3TableBackendDetails) instance.getTable("printing_documents").getBackendDetails();
      backend.setEndpoint("https://storage.example.invalid");
      backend.setPathStyleAccess(true);
      backend.setBasePath("alpha\nbeta");
      details.setBasePath("gamma");
      PrintingDocumentStorage storage = new PrintingDocumentStorage(instance, "tenant-a");
      String persisted = storage.binding("printing_documents");
      storage.verifyBinding("printing_documents", persisted);

      backend.setBasePath("alpha");
      details.setBasePath("beta\ngamma");
      assertEquals(409, assertThrows(PrintingException.class, () -> storage.verifyBinding("printing_documents", persisted)).getStatus());
   }



   /***************************************************************************
    ** A wrong-layout source cannot be read, copied or acknowledged as deleted.
    ** A rejected retry must not allocate or compensate another upload intent.
    ***************************************************************************/
   @Test
   void preservesHistoricalContentAndAccountingOnBindingMismatch() throws Exception
   {
      for(String incompatible : incompatibleBindings())
      {
         Fixture fixture = fixture();
         String id = fixture.service.submit(request(fixture.device, "original")).getString("jobId");
         String reference = fixture.storage.prefix() + id + ".zpl";
         ageSuccess(fixture, id);
         assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, id));
         execute(fixture.source, "UPDATE printing_job SET content_binding=? WHERE id=?", incompatible, id);
         fixture.storage.calls.clear();

         assertEquals(409, assertThrows(PrintingException.class, () -> fixture.service.content(fixture.device, id)).getStatus());
         assertEquals(409, assertThrows(PrintingException.class, () -> fixture.service.retry(fixture.device, id, retryRequest())).getStatus());
         assertEquals(409, assertThrows(PrintingException.class, fixture.service::runRetention).getStatus());
         assertTrue(fixture.storage.calls.isEmpty());
         assertArrayEquals(DOCUMENT, fixture.storage.objects.get(reference));
         assertEquals(1, fixture.storage.objects.size());
         JSONObject stored = row(fixture.source, "SELECT content_available,content_binding,content_reference FROM printing_job WHERE id=?", id);
         assertEquals(1, stored.getInt("CONTENT_AVAILABLE"));
         assertEquals(incompatible, stored.getString("CONTENT_BINDING"));
         assertEquals(reference, stored.getString("CONTENT_REFERENCE"));
         assertEquals(1, row(fixture.source, "SELECT COUNT(*) AS total FROM printing_job").getInt("TOTAL"));
         assertEquals(0, row(fixture.source, "SELECT COUNT(*) AS total FROM printing_document_orphan").getInt("TOTAL"));
         assertEquals(1, row(fixture.source, "SELECT COUNT(*) AS total FROM printing_admission_event").getInt("TOTAL"));
         assertRetainedBytes(fixture, DOCUMENT.length);
         assertEquals(0, fixture.service.getRetentionSettings().getInt("lastRunCount"));
         assertFalse(fixture.service.getRetentionSettings().getString("lastRunError").isBlank());

         execute(fixture.source, "UPDATE printing_job SET content_binding=? WHERE id=?", fixture.storage.binding("printing_documents"), id);
         String retry = fixture.service.retry(fixture.device, id, retryRequest()).getString("jobId");
         execute(fixture.source, "UPDATE printing_job SET content_binding=? WHERE id=?", incompatible, id);
         fixture.storage.calls.clear();
         assertEquals(retry, fixture.service.retry(fixture.device, id, retryRequest()).getString("jobId"));
         assertTrue(fixture.storage.calls.isEmpty());
         execute(fixture.source, "UPDATE printing_job SET content_binding=? WHERE id=?", fixture.storage.binding("printing_documents"), id);
         fixture.service.claim(fixture.device);
         assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, retry));
         assertEquals(1, fixture.service.runRetention());
         assertFalse(fixture.storage.objects.containsKey(reference));
         assertRetainedBytes(fixture, DOCUMENT.length);
      }
   }



   /***************************************************************************
    ** Abandoned uploads retain both their exact object and quota on mismatch.
    ***************************************************************************/
   @Test
   void preservesOrphanReservationBeforeStorageIo() throws Exception
   {
      for(String incompatible : incompatibleBindings())
      {
         Fixture fixture = fixture();
         fixture.storage.failUpload = true;
         fixture.storage.failDelete = true;
         assertThrows(SQLException.class, () -> fixture.service.submit(request(fixture.device, "orphan")));
         JSONObject orphan = row(fixture.source, "SELECT * FROM printing_document_orphan");
         String reference = orphan.getString("REFERENCE");
         execute(fixture.source, "UPDATE printing_document_orphan SET binding=?,created_at=?", incompatible, Instant.now().minusSeconds(7200).toString());
         fixture.storage.failUpload = false;
         fixture.storage.failDelete = false;
         fixture.storage.calls.clear();

         assertEquals(409, assertThrows(PrintingException.class, fixture.service::runRetention).getStatus());
         assertTrue(fixture.storage.calls.isEmpty());
         JSONObject retained = row(fixture.source, "SELECT * FROM printing_document_orphan");
         assertEquals(orphan.getString("ID"), retained.getString("ID"));
         assertEquals(reference, retained.getString("REFERENCE"));
         assertEquals(incompatible, retained.getString("BINDING"));
         assertEquals(orphan.getString("IDEMPOTENCY_KEY"), retained.getString("IDEMPOTENCY_KEY"));
         assertArrayEquals(DOCUMENT, fixture.storage.objects.get(reference));
         assertRetainedBytes(fixture, DOCUMENT.length);
         assertEquals(0, fixture.service.getRetentionSettings().getInt("lastRunCount"));

         execute(fixture.source, "UPDATE printing_document_orphan SET binding=?", fixture.storage.binding("printing_documents"));
         assertEquals(1, fixture.service.runRetention());
         assertTrue(fixture.storage.objects.isEmpty());
         assertEquals(0, row(fixture.source, "SELECT COUNT(*) AS total FROM printing_document_orphan").getInt("TOTAL"));
         assertRetainedBytes(fixture, 0);
      }
   }



   /***************************************************************************
    ** Old inline documents do not require an external binding after enabling S3.
    ***************************************************************************/
   @Test
   void keepsInlineDatabaseDocumentsReadableAndRetryable() throws Exception
   {
      Fixture fixture = fixture();
      PrintingService inline = new PrintingService(fixture.source, "tenant-a");
      String id = inline.submit(request(fixture.device, "inline")).getString("jobId");
      ageSuccess(fixture, id);
      assertArrayEquals(DOCUMENT, inline.content(fixture.device, id));
      assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, id));
      assertTrue(fixture.storage.calls.isEmpty());
      JSONObject original = row(fixture.source, "SELECT content_reference,content_binding FROM printing_job WHERE id=?", id);
      assertTrue(original.isNull("CONTENT_REFERENCE"));
      assertTrue(original.isNull("CONTENT_BINDING"));
      String retry = fixture.service.retry(fixture.device, id, retryRequest()).getString("jobId");
      fixture.service.claim(fixture.device);
      assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, retry));
      fixture.storage.calls.clear();
      assertEquals(1, fixture.service.runRetention());
      assertTrue(fixture.storage.calls.isEmpty());
      assertEquals(1, fixture.storage.objects.size());
      assertRetainedBytes(fixture, DOCUMENT.length);
   }



   /***************************************************************************
    ** Persisted foreign/experimental fixtures are never generated by the guard.
    ***************************************************************************/
   private static List<String> incompatibleBindings()
   {
      return (List.of(LEGACY_BINDING, File.separatorChar == '\\' ? POSIX_BINDING : WINDOWS_BINDING));
   }



   /***************************************************************************
    ** Exercise real service transactions with an actual S3 metadata fingerprint.
    ***************************************************************************/
   private static Fixture fixture() throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:binding-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      RecordingStorage storage = new RecordingStorage();
      service.configureStorage(storage, "printing_documents");
      String device = service.enroll(service.createEnrollment("warehouse", "station").put("deviceName", "station").put("agentVersion", "test")).getString("deviceId");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "queue", true);
      return (new Fixture(source, service, storage, device));
   }



   /***************************************************************************
    ** Native metadata identity shared by the persisted golden fixtures.
    ***************************************************************************/
   private static QInstance s3Instance()
   {
      QInstance instance = new QInstance();
      S3BackendMetaData backend = new S3BackendMetaData().withName("s3").withBucketName("print-documents").withRegion("us-east-1");
      backend.setPathStyleAccess(false);
      instance.addBackend(backend);
      instance.addTable(new QTableMetaData().withName("printing_documents").withBackendName("s3").withBackendDetails(new S3TableBackendDetails()));
      return (instance);
   }



   /***************************************************************************
    ** Synthetic requests never contact a printer.
    ***************************************************************************/
   private static JSONObject request(String device, String key)
   {
      return (new JSONObject().put("deviceId", device).put("printerId", "queue").put("title", "Synthetic").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString(DOCUMENT)).put("idempotencyKey", key));
   }



   /***************************************************************************
    ** A fresh action identity must attempt the original document read.
    ***************************************************************************/
   private static JSONObject retryRequest()
   {
      return (new JSONObject().put("requestId", "retry").put("confirmDuplicateRisk", true));
   }



   /***************************************************************************
    ** Synthetic completion makes one job eligible without a physical print.
    ***************************************************************************/
   private static void ageSuccess(Fixture fixture, String id) throws Exception
   {
      execute(fixture.source, "UPDATE printing_job SET status='SPOOLED',completed_at=? WHERE id=?", Instant.now().minusSeconds(400 * 86400L).toString(), id);
   }



   /***************************************************************************
    ** Both tenant and device accounting must preserve the held bytes.
    ***************************************************************************/
   private static void assertRetainedBytes(Fixture fixture, long expected) throws Exception
   {
      for(String device : List.of("", fixture.device))
      {
         assertEquals(expected, row(fixture.source, "SELECT retained_bytes FROM printing_resource_usage WHERE tenant_id='tenant-a' AND device_id=?", device).getLong("RETAINED_BYTES"));
      }
   }



   /***************************************************************************
    ** Mutate only isolated persisted fixtures to represent migration boundaries.
    ***************************************************************************/
   private static void execute(DriverDataSource source, String sql, Object... values) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.prepareStatement(sql))
      {
         for(int index = 0; index < values.length; index++)
         {
            statement.setObject(index + 1, values[index]);
         }
         statement.executeUpdate();
      }
   }



   /***************************************************************************
    ** Inspect durable state independently of service response summaries.
    ***************************************************************************/
   private static JSONObject row(DriverDataSource source, String sql, Object... values) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.prepareStatement(sql))
      {
         for(int index = 0; index < values.length; index++)
         {
            statement.setObject(index + 1, values[index]);
         }
         try(var rows = statement.executeQuery())
         {
            assertTrue(rows.next());
            JSONObject result = new JSONObject();
            for(int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
            {
               result.put(rows.getMetaData().getColumnLabel(index), rows.getObject(index) == null ? JSONObject.NULL : rows.getObject(index));
            }
            return (result);
         }
      }
   }



   /***************************************************************************
    ** Keep each tenant, database and object collection isolated.
    ***************************************************************************/
   private record Fixture(DriverDataSource source, PrintingService service, RecordingStorage storage, String device)
   {
   }



   /***************************************************************************
    ** Preserve production binding checks; replace only external object IO.
    ***************************************************************************/
   private static class RecordingStorage extends PrintingDocumentStorage
   {
      private final Map<String, byte[]> objects = new HashMap<>();
      private final List<String> calls = new ArrayList<>();
      private boolean failUpload;
      private boolean failDelete;



      /************************************************************************
       ** No memory-backend bypass or layout override is involved.
       ************************************************************************/
      private RecordingStorage()
      {
         super(s3Instance(), "tenant-a");
      }



      /************************************************************************
       ** Record an upload, optionally losing its acknowledgement afterwards.
       ************************************************************************/
      @Override
      public void put(String table, String reference, String format, byte[] content) throws SQLException
      {
         calls.add("PUT " + reference);
         objects.put(reference, content.clone());
         if(failUpload)
         {
            throw new SQLException("Synthetic upload acknowledgement failure");
         }
      }



      /************************************************************************
       ** Unexpected reads are observable independently of returned bytes.
       ************************************************************************/
      @Override
      public byte[] get(String table, String reference, int maximum)
      {
         calls.add("GET " + reference);
         return (objects.get(reference).clone());
      }



      /************************************************************************
       ** A missing-object delete succeeds, matching the dangerous S3 behavior.
       ************************************************************************/
      @Override
      public void delete(String table, String reference) throws SQLException
      {
         calls.add("DELETE " + reference);
         if(failDelete)
         {
            throw new SQLException("Synthetic deletion failure");
         }
         objects.remove(reference);
      }
   }
}
