/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2026.  Kingsrook, LLC
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
import java.sql.DriverManager;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QCredentialScope;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3BackendMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3TableBackendDetails;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Real native post-Get hooks must not carry stale authority into trusted writes.
 *******************************************************************************/
class PrintingOperatorAuthorityTest
{
   private static final ThreadLocal<Runnable> AFTER_GET = new ThreadLocal<>();
   private static final ThreadLocal<AtomicInteger> DEVICE_QUERIES = new ThreadLocal<>();
   private static final ThreadLocal<String> RETARGET_DEVICE = new ThreadLocal<>();


   /***************************************************************************
    ** Each case owns its native session and synthetic database.
    ***************************************************************************/
   @AfterEach
   void clear()
   {
      AFTER_GET.remove();
      DEVICE_QUERIES.remove();
      RETARGET_DEVICE.remove();
      QContext.clear();
   }


   /***************************************************************************
    ** Independent mutable authority components must all retain current meaning.
    ***************************************************************************/
   enum Change
   {
      ACTOR, SESSION, CREDENTIAL, PROCESS, ROW_SCOPE, CHANNEL
   }


   /***************************************************************************
    ** Authorization changes in the last destination hook precede any admission.
    ***************************************************************************/
   @ParameterizedTest
   @EnumSource(Change.class)
   void submissionRejectsPostGetAuthorityChanges(Change change) throws Exception
   {
      var fixture = fixture();
      mutateAfterGet(fixture, change, true);
      Exception denied = null;
      try
      {
         PrintingOperatorAuthorization.submit(request(fixture));
      }
      catch(Exception exception)
      {
         denied = exception;
      }
      assertEquals(null, AFTER_GET.get(), "The real native Get callback must run before assessing denial");
      assertTrue(denied instanceof QException, "Submission must reject changed authority");
      assertCounts(fixture, 0);
   }


   /***************************************************************************
    ** Receipt lookup cannot return the old actor's result after a native hook.
    ***************************************************************************/
   @ParameterizedTest
   @EnumSource(value = Change.class, names = { "ACTOR", "SESSION", "CREDENTIAL", "ROW_SCOPE", "CHANNEL" })
   void observationRejectsPostGetAuthorityChanges(Change change) throws Exception
   {
      var fixture = fixture();
      PrintingOperatorAuthorization.submit(request(fixture));
      mutateAfterGet(fixture, change, false);
      Exception denied = null;
      try
      {
         PrintingOperatorAuthorization.observe("echo:hook");
      }
      catch(Exception exception)
      {
         denied = exception;
      }
      assertEquals(null, AFTER_GET.get(), "The real native Get callback must run before assessing denial");
      assertTrue(denied instanceof QException, "Observation must reject changed authority");
      assertCounts(fixture, 1);
   }


   /***************************************************************************
    ** A read-only credential may still observe its own authorized accepted job.
    ***************************************************************************/
   @Test
   void readOnlyObservationAndExactReplayRemainAvailable() throws Exception
   {
      var fixture = fixture();
      JSONObject first = PrintingOperatorAuthorization.submit(request(fixture));
      assertEquals(first.getString("jobId"), PrintingOperatorAuthorization.submit(request(fixture)).getString("jobId"));
      QContext.getQSession().setCredentialScope(new QCredentialScope().withReadOnly(true));
      assertEquals(first.getString("jobId"), PrintingOperatorAuthorization.observe("echo:hook").getString("jobId"));
      assertCounts(fixture, 1);
   }


   /***************************************************************************
    ** A late storage callback cannot commit with authority revoked during upload.
    ***************************************************************************/
   @ParameterizedTest
   @EnumSource(value = Change.class, names = { "ACTOR", "CREDENTIAL", "PROCESS", "CHANNEL" })
   void storageCallbackDenialRollsBackJobAndCleansOwnedUpload(Change change) throws Exception
   {
      var fixture = fixture();
      RevokingStorage storage = new RevokingStorage(() -> change(fixture, change, true));
      fixture.service().configureStorage(storage, "printing_documents");
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.submit(request(fixture)));
      assertEquals(1, storage.writes);
      assertTrue(storage.objects.isEmpty(), "The unaccepted owned upload is cleaned up");
      assertCounts(fixture, 0);
   }


   /***************************************************************************
    ** Even a consistently retargeting host hook cannot change the native row intent.
    ***************************************************************************/
   @Test
   void postQueryDestinationRewriteCannotSelectAnotherQueue() throws Exception
   {
      var fixture = fixture();
      RETARGET_DEVICE.set(fixture.otherDevice());
      fixture.instance().getTable(PrintingTables.PRINTER).withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(RetargetingPrinter.class));
      assertEquals(409, assertThrows(PrintingException.class, () -> PrintingOperatorAuthorization.submit(request(fixture))).getStatus());
      assertCounts(fixture, 0);
   }


   /** History belongs to the accepted native row, even if its wire pair is reused. */
   @Test
   void observationKeepsOriginalPrinterRowIdentity() throws Exception
   {
      var fixture = fixture();
      JSONObject accepted = PrintingOperatorAuthorization.submit(request(fixture));
      fixture.service().enablePrinter(fixture.device(), "laser", false);
      assertEquals(accepted.getString("jobId"), PrintingOperatorAuthorization.observe("echo:hook").getString("jobId"));
      try(var connection = connection(fixture); var statement = connection.prepareStatement("DELETE FROM printing_printer WHERE id=?"))
      {
         statement.setString(1, fixture.printerId());
         assertEquals(1, statement.executeUpdate());
      }
      fixture.service().inventory(fixture.device(), new JSONArray().put(new JSONObject().put("printerId", "laser").put("name", "Replacement queue")
         .put("formats", new JSONArray().put("PDF"))));
      fixture.service().enablePrinter(fixture.device(), "laser", true);
      try(var connection = connection(fixture); var statement = connection.prepareStatement("SELECT id FROM printing_printer WHERE device_id=? AND printer_id='laser'"))
      {
         statement.setString(1, fixture.device());
         try(var rows = statement.executeQuery())
         {
            assertTrue(rows.next());
            assertNotEquals(fixture.printerId(), rows.getString(1));
         }
      }
      assertThrows(QException.class, () -> PrintingOperatorAuthorization.observe("echo:hook"));
      assertCounts(fixture, 1);
   }


   /***************************************************************************
    ** Install only the last native destination callback, after its permission check.
    ***************************************************************************/
   private void mutateAfterGet(PrintingProcessesTest.Fixture fixture, Change change, boolean mutation)
   {
      if(change == Change.ROW_SCOPE)
      {
         fixture.instance().addSecurityKeyType(new com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType().withName("revoked_destination"));
         fixture.instance().getTable(PrintingTables.DEVICE).withRecordSecurityLock(new com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock()
            .withFieldName("id").withSecurityKeyType("revoked_destination"));
         QContext.getQSession().withSecurityKeyValue("revoked_destination", fixture.device());
      }
      fixture.instance().getTable(PrintingTables.DEVICE).getCustomizers().put(TableCustomizers.POST_QUERY_RECORD.getRole(), new QCodeReference(ChangingGet.class));
      DEVICE_QUERIES.set(new AtomicInteger());
      AFTER_GET.set(() -> change(fixture, change, mutation));
   }


   /** Apply the same change either in a native Get hook or in the storage callback. */
   private static void change(PrintingProcessesTest.Fixture fixture, Change change, boolean mutation)
   {
      switch(change)
      {
         case ACTOR -> QContext.getQSession().setUser(new QUser().withIdReference("another-actor"));
         case SESSION -> QContext.init(fixture.instance(), new QSession().withUser(new QUser().withIdReference("operator"))
            .withPermissions("printing_operator").withSecurityKeyValue("printing_tenant", "tenant-a"));
         case CREDENTIAL -> QContext.getQSession().setCredentialScope(mutation ? new QCredentialScope().withReadOnly(true)
            : new QCredentialScope().withTables(Set.of(PrintingTables.DEVICE, PrintingTables.PRINTER)));
         case PROCESS -> QContext.getQSession().setCredentialScope(new QCredentialScope().withProcesses(Set.of()));
         case ROW_SCOPE -> QContext.getQSession().setSecurityKeyValues(Map.of("printing_tenant", List.of("tenant-a"), "revoked_destination", List.of("none")));
         case CHANNEL ->
         {
            QContext.getQSession().setClientChannel("api/test");
            fixture.instance().getTable(PrintingTables.JOB).getPermissionRules().withDeniedClients(Set.of("api"));
         }
         default -> throw new IllegalArgumentException("Unknown authority mutation");
      }
   }


   /***************************************************************************
    ** QQQ invokes this same hook for real Get actions, not a mocked facade call.
    ***************************************************************************/
   public static class ChangingGet implements TableCustomizerInterface
   {
      @Override
      public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records)
      {
         // RC20's RDBMS Get delegates to Query; its hook is the second device query.
         if(AFTER_GET.get() != null && (input instanceof GetInput || DEVICE_QUERIES.get().incrementAndGet() == 2))
         {
            Runnable action = AFTER_GET.get();
            AFTER_GET.remove();
            action.run();
         }
         return records;
      }
   }


   /** Return altered display data while preserving the requested row ID. */
   public static class RetargetingPrinter implements TableCustomizerInterface
   {
      @Override
      public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records)
      {
         records.forEach(record -> record.setValue("device_id", RETARGET_DEVICE.get()));
         return records;
      }
   }


   /** External IO is replaced, while production binding and cleanup logic remain. */
   private static class RevokingStorage extends PrintingDocumentStorage
   {
      private final Map<String, byte[]> objects = new HashMap<>();
      private final Runnable revoke;
      private int writes;

      /** This fixture has no network target or real bucket. */
      RevokingStorage(Runnable revoke)
      {
         super(storageInstance(), "tenant-a");
         this.revoke = revoke;
      }

      /** Publish the synthetic object, then change the caller before acceptance. */
      @Override
      public void put(String table, String reference, String format, byte[] content)
      {
         objects.put(reference, content.clone());
         writes++;
         revoke.run();
      }

      /** Observe actual service compensation after the denied database transaction. */
      @Override
      public void delete(String table, String reference)
      {
         objects.remove(reference);
      }

      /** Real S3 metadata exercises the production immutable storage binding. */
      private static QInstance storageInstance()
      {
         QInstance instance = new QInstance();
         instance.addBackend(new S3BackendMetaData().withName("objects").withBucketName("synthetic-operator").withRegion("us-east-1"));
         instance.addTable(new QTableMetaData().withName("printing_documents").withBackendName("objects").withBackendDetails(new S3TableBackendDetails()));
         return instance;
      }
   }


   /***************************************************************************
    ** Shared native fixture starts with an operator, without administration rights.
    ***************************************************************************/
   private PrintingProcessesTest.Fixture fixture() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      return fixture;
   }


   /***************************************************************************
    ** Tiny synthetic bytes never leave the in-memory queue.
    ***************************************************************************/
   private JSONObject request(PrintingProcessesTest.Fixture fixture)
   {
      return new JSONObject().put("printerRowId", fixture.printerId()).put("requestId", "echo:hook").put("title", "Synthetic")
         .put("format", "PDF").put("contentBase64", Base64.getEncoder().encodeToString("%PDF-synthetic".getBytes(StandardCharsets.US_ASCII)));
   }


   /***************************************************************************
    ** Independent SQL proves denial created no queue, event or upload reservation.
    ***************************************************************************/
   private void assertCounts(PrintingProcessesTest.Fixture fixture, int expectedJobs) throws Exception
   {
      try(var connection = connection(fixture))
      {
         for(String table : List.of("printing_job", "printing_event", "printing_document_orphan"))
         {
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table))
            {
               rows.next();
               assertEquals("printing_job".equals(table) ? expectedJobs : 0, rows.getInt(1), table);
            }
         }
      }
   }


   /** Inspect only the current test's synthetic database, without a native permission bypass. */
   private java.sql.Connection connection(PrintingProcessesTest.Fixture fixture) throws Exception
   {
      PrintingQBitConfig config = fixture.instance().getQBits().values().stream().map(value -> value.getConfig()).filter(PrintingQBitConfig.class::isInstance)
         .map(PrintingQBitConfig.class::cast).findFirst().orElseThrow();
      RDBMSBackendMetaData backend = (RDBMSBackendMetaData) fixture.instance().getBackend(config.getBackendName());
      return DriverManager.getConnection(backend.getJdbcUrl(), "sa", "");
   }
}
