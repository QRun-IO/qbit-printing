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


import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3BackendMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3TableBackendDetails;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Exercise the native storage abstraction with isolated in-memory objects.
 ** S3 metadata validation never contacts an endpoint or uses credentials.
 *******************************************************************************/
class PrintingDocumentStorageTest
{
   private static final byte[] DOCUMENT = "^XA^FO20,20^FDTest^FS^XZ".getBytes(StandardCharsets.US_ASCII);



   /***************************************************************************
    ** New payloads are external; historical bindings survive target changes.
    ***************************************************************************/
   @Test
   void storesNativeObjectsAndReadsImmutableHistoricalBindings() throws Exception
   {
      Fixture fixture = fixture();
      String id = fixture.service.submit(request(fixture.device, "first")).getString("jobId");
      JSONObject stored = row(fixture.source, "SELECT * FROM printing_job WHERE id='" + id + "'");
      fixture.service.claim(fixture.device);
      assertTrue(stored.isNull("CONTENT_BASE64"));
      assertEquals("printing_documents", stored.getString("CONTENT_STORAGE_TABLE"));
      assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, id));
      assertTrue(QContext.getQSession() == null);
      fixture.service.configureStorage(fixture.storage, "printing_archive");
      assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, id));
      ageSuccess(fixture, id);
      JSONObject retryRequest = new JSONObject().put("requestId", "retry").put("confirmDuplicateRisk", true);
      String retry = fixture.service.retry(fixture.device, id, retryRequest).getString("jobId");
      fixture.service.claim(fixture.device);
      assertNotEquals(id, retry);
      assertEquals("printing_archive", row(fixture.source, "SELECT * FROM printing_job WHERE id='" + retry + "'").getString("CONTENT_STORAGE_TABLE"));
      assertEquals(1, fixture.service.runRetention());
      assertEquals(retry, fixture.service.retry(fixture.device, id, retryRequest).getString("jobId"));
      assertEquals(410, assertThrows(PrintingException.class, () -> fixture.service.retry(fixture.device, id, new JSONObject(retryRequest.toString()).put("requestId", "new"))).getStatus());
      assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, retry));
      assertEquals(1, fixture.service.getRetentionSettings().getInt("lastRunCount"));
      assertEquals(0, fixture.service.getRetentionSettings().getLong("eligibleBacklog"));
   }



   /***************************************************************************
    ** Foreign tenant, sibling job and retargeted metadata fail before deletion.
    ***************************************************************************/
   @Test
   void rejectsForeignAndSiblingReferencesAndChangedBindings() throws Exception
   {
      Fixture fixture = fixture();
      String first = fixture.service.submit(request(fixture.device, "first")).getString("jobId");
      String second = fixture.service.submit(request(fixture.device, "second")).getString("jobId");
      execute(fixture.source, "UPDATE printing_job SET status='CLAIMED'");
      String own = fixture.storage.prefix() + first + ".zpl";
      String sibling = fixture.storage.prefix() + second + ".zpl";
      for(String invalid : List.of(sibling, new PrintingDocumentStorage(fixture.instance, "foreign", true).prefix() + first + ".zpl", "../foreign.zpl"))
      {
         execute(fixture.source, "UPDATE printing_job SET content_reference=? WHERE id=?", invalid, first);
         assertEquals(409, assertThrows(PrintingException.class, () -> fixture.service.content(fixture.device, first)).getStatus());
         ageSuccess(fixture, first);
         assertThrows(PrintingException.class, fixture.service::runRetention);
         assertArrayEquals(DOCUMENT, fixture.storage.get("printing_documents", own));
         assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, second));
      }
      execute(fixture.source, "UPDATE printing_job SET content_reference=?,content_binding=? WHERE id=?", own, "incorrect", first);
      assertEquals(409, assertThrows(PrintingException.class, () -> fixture.service.content(fixture.device, first)).getStatus());
   }



   /***************************************************************************
    ** Upload/delete failures leave durable exact references for later recovery.
    ***************************************************************************/
   @Test
   void uploadFailureAndOrphanRetryNeverDeleteAnotherJob() throws Exception
   {
      Fixture fixture = fixture();
      fixture.storage.failUpload = true;
      assertThrows(SQLException.class, () -> fixture.service.submit(request(fixture.device, "compensated")));
      assertEquals(0, row(fixture.source, "SELECT COUNT(*) AS total FROM printing_document_orphan").getInt("TOTAL"));
      assertThrows(SQLException.class, () -> fixture.storage.get("printing_documents", java.util.Objects.requireNonNull(fixture.storage.lastReference)));
      fixture.storage.failDelete = true;
      assertThrows(SQLException.class, () -> fixture.service.submit(request(fixture.device, "broken")));
      assertEquals(0, fixture.service.jobs().length());
      JSONObject orphan = row(fixture.source, "SELECT * FROM printing_document_orphan");
      String reference = orphan.getString("REFERENCE");
      assertArrayEquals(DOCUMENT, fixture.storage.get("printing_documents", reference));
      fixture.storage.failUpload = false;
      fixture.storage.failDelete = false;
      String other = fixture.service.submit(request(fixture.device, "other")).getString("jobId");
      fixture.service.claim(fixture.device);
      execute(fixture.source, "UPDATE printing_document_orphan SET created_at=?,reference=?", Instant.now().minusSeconds(7200).toString(), fixture.storage.prefix() + other + ".zpl");
      assertThrows(PrintingException.class, fixture.service::runRetention);
      assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, other));
      execute(fixture.source, "UPDATE printing_document_orphan SET reference=?", reference);
      assertEquals(1, fixture.service.runRetention());
      assertThrows(SQLException.class, () -> fixture.storage.get("printing_documents", reference));
      assertArrayEquals(DOCUMENT, fixture.service.content(fixture.device, other));
   }



   /***************************************************************************
    ** A later failed removal cannot roll back a prior confirmed receipt.
    ***************************************************************************/
   @Test
   void commitsSuccessfulDeletionBeforeLaterFailure() throws Exception
   {
      Fixture fixture = fixture();
      String first = fixture.service.submit(request(fixture.device, "first")).getString("jobId");
      String second = fixture.service.submit(request(fixture.device, "second")).getString("jobId");
      ageSuccess(fixture, first);
      ageSuccess(fixture, second);
      fixture.storage.failDeleteNumber = 2;
      assertThrows(SQLException.class, fixture.service::runRetention);
      assertEquals(1, row(fixture.source, "SELECT COUNT(*) AS total FROM printing_job WHERE content_available=0").getInt("TOTAL"));
      assertEquals(1, fixture.service.getRetentionSettings().getInt("lastRunCount"));
      assertFalse(fixture.service.getRetentionSettings().getString("lastRunError").isBlank());
      fixture.storage.failDeleteNumber = -1;
      assertEquals(1, fixture.service.runRetention());
      assertEquals(0, fixture.service.runRetention());
   }



   /***************************************************************************
    ** Canonical bucket checks ignore region/path-style/default-port aliases.
    ***************************************************************************/
   @Test
   void validatesDedicatedS3BindingWithoutNetwork() throws Exception
   {
      QInstance instance = new QInstance();
      S3BackendMetaData printing = new S3BackendMetaData().withName("printing").withBucketName("print-documents").withEndpoint("https://storage.example");
      instance.addBackend(printing);
      instance.addTable(new QTableMetaData().withName("printing_documents").withBackendName("printing").withBackendDetails(new S3TableBackendDetails()));
      PrintingDocumentStorage storage = new PrintingDocumentStorage(instance, "tenant-a");
      String original = storage.binding("printing_documents");
      printing.setBucketName("another-bucket");
      assertThrows(PrintingException.class, () -> storage.verifyBinding("printing_documents", original));
      printing.setBucketName("print-documents");
      S3BackendMetaData other = new S3BackendMetaData().withName("uploads").withBucketName("print-documents").withEndpoint("https://storage.example:443/");
      other.setPathStyleAccess(true);
      other.setRegion("different-region");
      instance.addBackend(other);
      instance.addTable(new QTableMetaData().withName("general_uploads").withBackendName("uploads").withBackendDetails(new S3TableBackendDetails()));
      assertThrows(IllegalArgumentException.class, () -> storage.binding("printing_documents"));
      other.setEndpoint("https://separate.example");
      assertEquals(original, storage.binding("printing_documents"));
      for(String endpoint : List.of("http://remote.example", "ftp://remote.example", "https://user:secret@storage.example", "https://storage.example?secret=value", "//storage.example"))
      {
         printing.setEndpoint(endpoint);
         assertThrows(IllegalArgumentException.class, () -> storage.binding("printing_documents"));
      }
      printing.setEndpoint("http://127.0.0.1:9000");
      assertFalse(storage.binding("printing_documents").isBlank());
   }



   /***************************************************************************
    ** Existing active documents get a full retention interval after completion.
    ***************************************************************************/
   @Test
   void completionAgeAndLegacyMigrationAreConservative() throws Exception
   {
      Fixture fixture = fixture();
      String job = fixture.service.submit(request(fixture.device, "old-active")).getString("jobId");
      execute(fixture.source, "UPDATE printing_job SET created_at=? WHERE id=?", Instant.now().minusSeconds(800 * 86400L).toString(), job);
      fixture.service.claim(fixture.device);
      fixture.service.event(fixture.device, job, new JSONObject().put("eventId", UUID.randomUUID().toString()).put("status", "SPOOLED"));
      assertEquals(0, fixture.service.runRetention());
      execute(fixture.source, "UPDATE printing_job SET completed_at=NULL WHERE id=?", job);
      fixture.service.initialize();
      assertEquals(0, fixture.service.runRetention());
      assertFalse(row(fixture.source, "SELECT * FROM printing_job WHERE id='" + job + "'").isNull("COMPLETED_AT"));
      execute(fixture.source, "DELETE FROM printing_event WHERE job_id=?", job);
      execute(fixture.source, "UPDATE printing_job SET completed_at=NULL WHERE id=?", job);
      fixture.service.initialize();
      assertEquals(0, fixture.service.runRetention());
   }



   /***************************************************************************
    ** Shared synthetic fixture; no S3 bucket or printer is used.
    ***************************************************************************/
   private static Fixture fixture() throws Exception
   {
      QContext.clear();
      QInstance instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData().withName("mock").withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.MOCK));
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule.class));
      for(String table : List.of("printing_documents", "printing_archive"))
      {
         instance.addTable(new QTableMetaData().withName(table).withBackendName("memory").withPrimaryKeyField("id")
            .withField(new QFieldMetaData("id", QFieldType.STRING)).withField(new QFieldMetaData("content", QFieldType.BLOB)));
      }
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:storage-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      FaultStorage storage = new FaultStorage(instance);
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      service.configureStorage(storage, "printing_documents");
      String device = service.enroll(service.createEnrollment("warehouse", "station").put("deviceName", "station").put("agentVersion", "test")).getString("deviceId");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "queue", true);
      return new Fixture(source, service, storage, instance, device);
   }


   /** Native StorageAction content larger than the old cap is consumed incrementally and verified. */
   @Test
   void streamsLargePdfAndRejectsChangedLengthWithoutBufferingInTheAdapter() throws Exception
   {
      Fixture fixture = fixture();
      fixture.service.inventory(fixture.device, new JSONArray().put(new JSONObject().put("printerId", "queue").put("name", "Synthetic")
         .put("formats", new JSONArray(List.of("PDF")))));
      byte[] document = new byte[21 * 1024 * 1024];
      System.arraycopy("%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, document, 0, 9);
      JSONObject submitted = fixture.service.submit(new JSONObject().put("deviceId", fixture.device).put("printerId", "queue").put("title", "Large synthetic PDF")
         .put("format", "PDF").put("contentBase64", Base64.getEncoder().encodeToString(document)).put("idempotencyKey", "large-pdf"));
      fixture.service.claim(fixture.device);
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      long count = 0;
      try(var stream = fixture.service.openContent(fixture.device, submitted.getString("jobId")))
      {
         byte[] buffer = new byte[65536];
         int read;
         while((read = stream.read(buffer)) != -1)
         {
            count += read;
            digest.update(buffer, 0, read);
         }
      }
      assertEquals(document.length, count);
      assertEquals(submitted.getString("contentSha256"), java.util.HexFormat.of().formatHex(digest.digest()));
      fixture.storage.put("printing_documents", java.util.Objects.requireNonNull(fixture.storage.lastReference), "PDF", new byte[] {1, 2, 3});
      try(var stream = fixture.service.openContent(fixture.device, submitted.getString("jobId")))
      {
         assertThrows(java.io.IOException.class, stream::readAllBytes);
      }
   }



   /***************************************************************************
    ** Synthetic bytes never leave the test process.
    ***************************************************************************/
   private static JSONObject request(String device, String key)
   {
      return new JSONObject().put("deviceId", device).put("printerId", "queue").put("title", "Synthetic").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString(DOCUMENT)).put("idempotencyKey", key);
   }



   /***************************************************************************
    ** Explicitly age a synthetic successful receipt.
    ***************************************************************************/
   private static void ageSuccess(Fixture fixture, String id) throws Exception
   {
      execute(fixture.source, "UPDATE printing_job SET status='SPOOLED',completed_at=? WHERE id=?", Instant.now().minusSeconds(400 * 86400L).toString(), id);
   }



   /***************************************************************************
    ** Isolated database mutations for fault and migration fixtures.
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
    ** Read synthetic metadata without loading production state.
    ***************************************************************************/
   private static JSONObject row(DriverDataSource source, String sql) throws Exception
   {
      try(var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
      {
         assertTrue(rows.next());
         JSONObject result = new JSONObject();
         for(int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
         {
            result.put(rows.getMetaData().getColumnLabel(index), rows.getObject(index) == null ? JSONObject.NULL : rows.getObject(index));
         }
         return result;
      }
   }



   /***************************************************************************
    ** Do not leak the memory backend context between tests.
    ***************************************************************************/
   @AfterEach
   void clear()
   {
      QContext.clear();
   }



   /***************************************************************************
    ** Compact isolated state owned by one test.
    ***************************************************************************/
   private record Fixture(DriverDataSource source, PrintingService service, FaultStorage storage, QInstance instance, String device)
   {
   }



   /***************************************************************************
    ** Fail after upload or at a selected deletion to exercise crash receipts.
    ***************************************************************************/
   private static class FaultStorage extends PrintingDocumentStorage
   {
      private boolean failUpload;
      private boolean failDelete;
      private int failDeleteNumber = -1;
      private int deletes;
      private @Nullable String lastReference;



      /************************************************************************
       ** Use real QQQ memory storage underneath each injected failure.
       ************************************************************************/
      private FaultStorage(QInstance instance)
      {
         super(instance, "tenant-a", true);
      }



      /************************************************************************
       ** Ambiguous upload failure may already have stored all bytes.
       ************************************************************************/
      @Override
      public void put(String table, String reference, String format, byte[] content) throws SQLException
      {
         lastReference = reference;
         super.put(table, reference, format, content);
         if(failUpload)
         {
            throw new SQLException("Synthetic upload acknowledgement failure");
         }
      }



      /************************************************************************
       ** Failed deletes must retain a retryable database reference.
       ************************************************************************/
      @Override
      public void delete(String table, String reference) throws SQLException
      {
         deletes++;
         if(failDelete || deletes == failDeleteNumber)
         {
            throw new SQLException("Synthetic deletion failure");
         }
         super.delete(table, reference);
      }
   }
}


