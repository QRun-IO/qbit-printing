/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2026.  Kingsrook, LLC
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
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


/*******************************************************************************
 ** Actual owner transaction, native authorization and migration; no device output.
 *******************************************************************************/
class RetainedPrintFacadeTest
{
   private final RetainedPrintFacade facade = new RetainedPrintFacade();
   private final byte[] bytes = "%PDF-1.4\nretained fixture".getBytes(StandardCharsets.US_ASCII);
   private final AtomicInteger reads = new AtomicInteger();
   private byte @Nullable [] readOverride;
   private long declaredSize;
   private String opaque = "immutable-version-1";
   private boolean observationAllowed = true;
   private Runnable onObservation = () ->
   {
      // No default callback side effects.
   };
   private Runnable onRead = () ->
   {
      // No default callback side effects.
   };

   /** Native row metadata and real H2 share the printing service database. */
   private PrintingProcessesTest.Fixture fixture() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      config(fixture).withRetainedSourceAuthority(new RetainedPrintSourceAuthority()
      {
         @Override
         public RetainedPrintFacade.SourceReference resolve(String locator, String requestId) throws QException
         {
            if(!"source-1".equals(locator))
            {
               throw new QException("Source denied");
            }
            try
            {
               return new RetainedPrintFacade.SourceReference("test-owner", opaque, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                  declaredSize == 0 ? bytes.length : declaredSize, "application/pdf");
            }
            catch(java.security.NoSuchAlgorithmException exception)
            {
               throw new QException("Fixture hash failed", exception);
            }
         }

         @Override
         public byte[] read(RetainedPrintFacade.SourceReference source, String requestId)
         {
            reads.incrementAndGet();
            onRead.run();
            return readOverride == null ? bytes.clone() : readOverride.clone();
         }

         @Override
         public void authorizeObservation(RetainedPrintFacade.SourceReference source, String requestId) throws QException
         {
            if(!observationAllowed || !opaque.equals(source.opaqueReference()))
            {
               throw new QException("Current owner observation denied");
            }
            onObservation.run();
         }
      });
      return fixture;
   }

   /** Resolve the production configured integration, not an invocation-supplied authority. */
   private PrintingQBitConfig config(PrintingProcessesTest.Fixture fixture)
   {
      return fixture.instance().getQBits().values().stream().map(bit -> bit.getConfig()).filter(PrintingQBitConfig.class::isInstance)
         .map(PrintingQBitConfig.class::cast).findFirst().orElseThrow();
   }

   /** Current synthetic native administrator; no arbitrary caller actor field exists. */
   private QSession session(String actor)
   {
      return new QSession().withUser(new QUser().withIdReference(actor)).withPermissions("printing_admin").withSecurityKeyValue("printing_tenant", "tenant-a");
   }

   /** Same stable request in every replay. */
   private RetainedPrintFacade.Request request(PrintingProcessesTest.Fixture fixture)
   {
      return new RetainedPrintFacade.Request("intent-1", "source-1", fixture.printerId(), "Retained document", "{}");
   }

   /** Independent SQL connection observes actual committed storage. */
   private Connection connection(PrintingProcessesTest.Fixture fixture) throws Exception
   {
      RDBMSBackendMetaData backend = (RDBMSBackendMetaData) fixture.instance().getBackend(config(fixture).getBackendName());
      return DriverManager.getConnection(backend.getJdbcUrl(), "sa", "");
   }

   /** Read only a fixed test-owned table count. */
   private int count(PrintingProcessesTest.Fixture fixture, String table) throws Exception
   {
      try(Connection connection = connection(fixture); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table))
      {
         rows.next();
         return rows.getInt(1);
      }
   }

   /** Replay and lost-response lookup preserve complete binding without another byte read. */
   @Test
   void acceptedReplayAndLookupReturnSameCommittedProof() throws Exception
   {
      var fixture = fixture();
      assertNull(facade.find(request(fixture)));
      var accepted = facade.submit(request(fixture));
      assertEquals("operator", accepted.actorId());
      assertEquals("Retained document", accepted.title());
      assertEquals(1, new JSONObject(accepted.optionsJson()).getInt("copies"));
      assertEquals(opaque, accepted.source().opaqueReference());
      assertEquals(accepted, facade.submit(request(fixture)));
      assertEquals(accepted, facade.find(request(fixture)));
      assertEquals(1, reads.get());
      assertEquals(1, count(fixture, "printing_job"));
      assertEquals(1, count(fixture, "printing_event"));
      assertEquals("QUEUED", fixture.service().getJob(accepted.jobId()).getString("status"));
   }

   /** A committed transaction with a lost acknowledgment is recovered without a second job. */
   @Test
   void commitThenThrowRecoversExactAcceptance() throws Exception
   {
      var fixture = fixture();
      RDBMSBackendMetaData backend = (RDBMSBackendMetaData) fixture.instance().getBackend(config(fixture).getBackendName());
      DriverDataSource actual = new DriverDataSource(backend.getJdbcUrl(), "sa", "");
      var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
      javax.sql.DataSource source = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { javax.sql.DataSource.class }, (proxy, method, args) ->
      {
         try
         {
            Object result = method.invoke(actual, args);
            if(result instanceof Connection connection)
            {
               return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class }, (connectionProxy, operation, values) ->
               {
                  try
                  {
                     Object returned = operation.invoke(connection, values);
                     if("commit".equals(operation.getName()) && fail.get())
                     {
                        try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM printing_job WHERE accepted_event_id IS NOT NULL"))
                        {
                           rows.next();
                           if(rows.getInt(1) == 1 && fail.compareAndSet(true, false))
                           {
                              throw new java.sql.SQLException("Synthetic lost commit acknowledgment", "08007");
                           }
                        }
                     }
                     return returned;
                  }
                  catch(java.lang.reflect.InvocationTargetException exception)
                  {
                     throw exception.getCause();
                  }
               });
            }
            return result;
         }
         catch(java.lang.reflect.InvocationTargetException exception)
         {
            throw exception.getCause();
         }
      });
      config(fixture).withService(new PrintingService(source, "tenant-a"));
      assertThrows(QException.class, () -> facade.submit(request(fixture)));
      var recovered = facade.find(request(fixture));
      assertNotNull(recovered);
      assertEquals(recovered, facade.submit(request(fixture)));
      assertEquals(1, reads.get());
      assertEquals(1, count(fixture, "printing_job"));
      assertEquals(1, count(fixture, "printing_event"));
   }

   /** Unknown owner configuration, parent denial, tenant denial and callback revocation do not enqueue. */
   @Test
   void currentAuthorityIsRequiredBeforeAndAfterOwnerCallbacks() throws Exception
   {
      var unconfigured = PrintingProcessesTest.fixture();
      assertThrows(QException.class, () -> facade.submit(request(unconfigured)));
      var fixture = fixture();
      assertThrows(QException.class, () -> facade.submit(new RetainedPrintFacade.Request("bad", "denied", fixture.printerId(), "Test", "{}")));
      assertThrows(QException.class, () -> facade.submit(new RetainedPrintFacade.Request("foreign", "source-1", fixture.foreignPrinterId(), "Test", "{}")));
      onRead = () -> QContext.getQSession().setPermissions(Set.of());
      assertThrows(QException.class, () -> facade.submit(request(fixture)));
      assertEquals(0, count(fixture, "printing_job"));
      assertEquals(0, count(fixture, "printing_event"));
   }

   /** Read-only credentials and write-denied channels permit lookup, never queue mutation. */
   @Test
   void nativeMutationCeilingsCannotBeBypassedByAdministratorRole() throws Exception
   {
      var fixture = fixture();
      var accepted = facade.submit(request(fixture));
      QContext.getQSession().withCredentialScope(new com.kingsrook.qqq.backend.core.model.session.QCredentialScope().withReadOnly(true));
      assertEquals(accepted, facade.find(request(fixture)));
      assertThrows(QException.class, () -> facade.submit(request(fixture)));
      QContext.getQSession().withCredentialScope(null).withClientChannel("api");
      com.kingsrook.qqq.backend.core.model.metadata.permissions.ClientChannelsMetaData.ofOrWithNew(fixture.instance()).withDeniedWritePattern("api", PrintingTables.JOB);
      assertEquals(accepted, facade.find(request(fixture)));
      assertThrows(QException.class, () -> facade.submit(request(fixture)));
      assertEquals(1, reads.get());
      assertEquals(1, count(fixture, "printing_job"));
   }

   /** Initialization upgrades the original 300-character column; spaces and Unicode remain exact. */
   @Test
   void titleAt1024CharactersIsStoredAndReplayedWithoutNormalization() throws Exception
   {
      var fixture = fixture();
      try(Connection connection = connection(fixture); var statement = connection.createStatement())
      {
         statement.execute("ALTER TABLE printing_job ALTER COLUMN title VARCHAR(300)");
      }
      fixture.service().initialize();
      String title = "  " + "é".repeat(1020) + "  ";
      var request = new RetainedPrintFacade.Request("long-title", "source-1", fixture.printerId(), title, "{}");
      var accepted = facade.submit(request);
      assertEquals(title, fixture.service().getJob(accepted.jobId()).getString("title"));
      assertEquals(accepted, facade.find(request));
      assertEquals(accepted, facade.submit(request));
      assertThrows(PrintingException.class, () -> new RetainedPrintFacade.Request("long-title", "source-1", fixture.printerId(), title + "x", "{}"));
   }

   /** Current destination approval is checked again after the retained owner read. */
   @Test
   void printerDisabledDuringOwnerReadCannotBeAccepted() throws Exception
   {
      var fixture = fixture();
      onRead = () ->
      {
         try
         {
            fixture.service().enablePrinter(fixture.device(), "laser", false);
         }
         catch(java.sql.SQLException exception)
         {
            throw new IllegalStateException(exception);
         }
      };
      assertThrows(QException.class, () -> facade.submit(request(fixture)));
      assertEquals(1, reads.get());
      assertEquals(0, count(fixture, "printing_job"));
      assertEquals(0, count(fixture, "printing_event"));
   }

   /** Stable key cannot be rebound by another actor, version, title, options or destination. */
   @Test
   void changedIntentAndUnboundCollisionRefuse() throws Exception
   {
      var fixture = fixture();
      var accepted = facade.submit(request(fixture));
      opaque = "immutable-version-2";
      assertThrows(PrintingException.class, () -> facade.submit(request(fixture)));
      opaque = "immutable-version-1";
      QContext.init(fixture.instance(), session("another-operator"));
      assertThrows(PrintingException.class, () -> facade.submit(request(fixture)));
      QContext.init(fixture.instance(), session("operator"));
      assertThrows(PrintingException.class, () -> facade.submit(new RetainedPrintFacade.Request("intent-1", "source-1", fixture.printerId(), "Changed", "{}")));
      assertThrows(PrintingException.class, () -> facade.submit(new RetainedPrintFacade.Request("intent-1", "source-1", fixture.otherPrinterId(), "Retained document", "{}")));
      assertThrows(PrintingException.class, () -> facade.submit(new RetainedPrintFacade.Request("intent-1", "source-1", fixture.printerId(), "Retained document", "{\"copies\":2}")));
      assertEquals(accepted, facade.find(request(fixture)));
      JSONObject raw = new JSONObject().put("deviceId", fixture.device()).put("printerId", "laser").put("title", "Raw")
         .put("format", "PDF").put("contentBase64", java.util.Base64.getEncoder().encodeToString(bytes)).put("idempotencyKey", "retained:raw-key");
      fixture.service().submit(raw);
      assertThrows(PrintingException.class, () -> facade.submit(new RetainedPrintFacade.Request("raw-key", "source-1", fixture.printerId(), "Raw", "{}")));
      raw.put("idempotencyKey", "retained:intent-1");
      assertThrows(PrintingException.class, () -> fixture.service().submit(raw));
   }

   /** A real database refusal of acceptance evidence rolls the actual job insert back. */
   @Test
   void failedAcceptanceEventRollsBackJob() throws Exception
   {
      var fixture = fixture();
      try(Connection connection = connection(fixture); var statement = connection.createStatement())
      {
         statement.execute("ALTER TABLE printing_event ADD CONSTRAINT refuse_acceptance CHECK (event_origin IS NULL OR event_origin <> 'SERVER_ACCEPTANCE')");
      }
      assertThrows(QException.class, () -> facade.submit(request(fixture)));
      assertEquals(0, count(fixture, "printing_job"));
      assertEquals(0, count(fixture, "printing_event"));
      assertNull(facade.find(request(fixture)));
   }

   /** Actual transaction cleanup preserves the owner failure, including reused exception identity. */
   @Test
   void rollbackFailureNeverMasksOwnerFailure() throws Exception
   {
      for(boolean sameFailure : List.of(false, true))
      {
         var fixture = fixture();
         PrintingException original = new PrintingException(403, "Synthetic owner denial");
         Exception cleanup = sameFailure ? original : new java.sql.SQLException("Synthetic rollback failure");
         RDBMSBackendMetaData backend = (RDBMSBackendMetaData) fixture.instance().getBackend(config(fixture).getBackendName());
         DriverDataSource actual = new DriverDataSource(backend.getJdbcUrl(), "sa", "");
         javax.sql.DataSource source = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { javax.sql.DataSource.class }, (proxy, method, args) ->
         {
            try
            {
               Object result = method.invoke(actual, args);
               if(result instanceof Connection connection)
               {
                  return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class }, (connectionProxy, operation, values) ->
                  {
                     try
                     {
                        Object returned = operation.invoke(connection, values);
                        if("rollback".equals(operation.getName()))
                        {
                           throw cleanup;
                        }
                        return returned;
                     }
                     catch(java.lang.reflect.InvocationTargetException exception)
                     {
                        throw exception.getCause();
                     }
                  });
               }
               return result;
            }
            catch(java.lang.reflect.InvocationTargetException exception)
            {
               throw exception.getCause();
            }
         });
         config(fixture).withService(new PrintingService(source, "tenant-a"));
         onRead = () ->
         {
            throw original;
         };
         org.junit.jupiter.api.Assertions.assertSame(original, assertThrows(PrintingException.class, () -> facade.submit(request(fixture))));
         assertEquals(sameFailure ? 0 : 1, original.getSuppressed().length);
         if(!sameFailure)
         {
            org.junit.jupiter.api.Assertions.assertSame(cleanup, original.getSuppressed()[0]);
         }
         assertEquals(0, count(fixture, "printing_job"));
      }
   }

   /** Competing callers share the existing tenant admission lock and unique request identity. */
   @Test
   void concurrentIdenticalRequestsShareOneJobAndAcceptance() throws Exception
   {
      var fixture = fixture();
      try(var executor = Executors.newFixedThreadPool(2))
      {
         java.util.concurrent.Callable<RetainedPrintFacade.Acceptance> task = () ->
         {
            QContext.init(fixture.instance(), session("operator"));
            try
            {
               return facade.submit(request(fixture));
            }
            finally
            {
               QContext.clear();
            }
         };
         var futures = executor.invokeAll(List.of(task, task), 20, java.util.concurrent.TimeUnit.SECONDS);
         assertEquals(futures.get(0).get(), futures.get(1).get());
      }
      assertEquals(1, reads.get());
      assertEquals(1, count(fixture, "printing_job"));
      assertEquals(1, count(fixture, "printing_event"));
   }

   /** Device receipts are exact committed events; server acceptance cannot masquerade as one. */
   @Test
   void deviceObservationRequiresExactEventAndCurrentDestinationAccess() throws Exception
   {
      var fixture = fixture();
      var accepted = facade.submit(request(fixture));
      assertThrows(PrintingException.class, () -> facade.observation("intent-1", accepted.acceptedEventId()));
      fixture.service().claim(fixture.device());
      String eventId = UUID.randomUUID().toString();
      fixture.service().event(fixture.device(), accepted.jobId(), new JSONObject().put("eventId", eventId).put("status", "RECEIVED"));
      var observed = facade.observation("intent-1", eventId);
      assertEquals(accepted, observed.acceptance());
      assertEquals("RECEIVED", observed.state());
      assertNotNull(observed.recordedAt());
      observationAllowed = false;
      assertThrows(QException.class, () -> facade.observation("intent-1", eventId));
      observationAllowed = true;
      var configured = Objects.requireNonNull(config(fixture).getRetainedSourceAuthority(), "The fixture configures a retained source authority");
      config(fixture).withRetainedSourceAuthority(new RetainedPrintSourceAuthority()
      {
         @Override
         public RetainedPrintFacade.SourceReference resolve(String locator, String requestId) throws QException
         {
            return configured.resolve(locator, requestId);
         }

         @Override
         public byte[] read(RetainedPrintFacade.SourceReference source, String requestId) throws QException
         {
            return configured.read(source, requestId);
         }
      });
      assertThrows(QException.class, () -> facade.observation("intent-1", eventId));
      config(fixture).withRetainedSourceAuthority(configured);
      QContext.getQSession().setPermissions(Set.of());
      assertThrows(QException.class, () -> facade.observation("intent-1", eventId));
   }

   /** A least-privilege operator can accept its exact source without generic job or management access. */
   @Test
   void operatorAcceptanceDoesNotGrantQueueEnumeration() throws Exception
   {
      var fixture = fixture();
      QContext.init(fixture.instance(), session("operator"));
      QContext.getQSession().setPermissions(Set.of("printing_operator"));
      var request = new RetainedPrintFacade.Request("echo:scan-1", "source-1", fixture.printerId(), "Retained document", "{}");
      var accepted = facade.submit(request);
      assertEquals(accepted, facade.find(request));
      assertThrows(QException.class, () -> new PrintingAdminPermission().checkPermissionsThrowing(
         new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput(), null));
      var query = new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput().withTableName(PrintingTables.JOB);
      query.setPermissionEnforcement(com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement.ENFORCE);
      assertThrows(QException.class, () -> new com.kingsrook.qqq.backend.core.actions.tables.QueryAction().execute(query));
      assertEquals(1, count(fixture, "printing_job"));
   }

   /** Owner callbacks cannot return evidence after narrowing the reporter's current credential or row authority. */
   @Test
   void observationRechecksCurrentCeilingAndDestinationAfterOwnerCallback() throws Exception
   {
      for(String change : List.of("credential", "destination"))
      {
         var fixture = fixture();
         var accepted = facade.submit(request(fixture));
         fixture.service().claim(fixture.device());
         String eventId = UUID.randomUUID().toString();
         fixture.service().event(fixture.device(), accepted.jobId(), new JSONObject().put("eventId", eventId).put("status", "RECEIVED"));
         onObservation = () ->
         {
            if("credential".equals(change))
            {
               QContext.getQSession().withCredentialScope(new com.kingsrook.qqq.backend.core.model.session.QCredentialScope()
                  .withTables(Set.of(PrintingTables.PRINTER, PrintingTables.DEVICE)));
            }
            else
            {
               try(Connection connection = connection(fixture); var statement = connection.prepareStatement("UPDATE printing_printer SET tenant_id='tenant-b' WHERE id=?"))
               {
                  statement.setString(1, fixture.printerId());
                  statement.executeUpdate();
               }
               catch(Exception exception)
               {
                  throw new IllegalStateException(exception);
               }
            }
         };
         assertThrows(QException.class, () -> facade.observation("intent-1", eventId));
         assertEquals(1, count(fixture, "printing_job"));
         assertEquals(2, count(fixture, "printing_event"));
      }
   }

   /** Content returned by an owner must match both the accepted hash and declared length. */
   @Test
   void changedReadBytesCannotCommitAcceptance() throws Exception
   {
      for(boolean lengthMismatch : List.of(false, true))
      {
         var fixture = fixture();
         readOverride = java.util.Arrays.copyOf(bytes, bytes.length + (lengthMismatch ? 1 : 0));
         readOverride[readOverride.length - 1] ^= 1;
         if(lengthMismatch)
         {
            assertThrows(QException.class, () -> facade.submit(request(fixture)));
         }
         else
         {
            assertEquals(409, assertThrows(PrintingException.class, () -> facade.submit(request(fixture))).getStatus());
         }
         assertEquals(0, count(fixture, "printing_job"));
         assertEquals(0, count(fixture, "printing_event"));
      }
   }

   /** The current format policy rejects before reading; the obsolete fixed 20 MiB limit does not. */
   @Test
   void configuredDocumentLimitGatesOwnerRead() throws Exception
   {
      var fixture = fixture();
      JSONObject settings = fixture.service().getRealtimeSettings();
      settings.remove("revision");
      settings.put("maxPdfBytes", bytes.length - 1);
      fixture.service().saveRealtimeSettings(settings);
      assertEquals(413, assertThrows(PrintingException.class, () -> facade.submit(request(fixture))).getStatus());
      assertEquals(0, reads.get());
      settings.put("maxPdfBytes", 32 * 1024 * 1024);
      fixture.service().saveRealtimeSettings(settings);
      declaredSize = 21 * 1024 * 1024;
      // The tiny synthetic reader intentionally cannot satisfy this larger declaration.
      // Reaching it proves admission uses current policy without allocating a large fixture.
      assertThrows(QException.class, () -> facade.submit(request(fixture)));
      assertEquals(1, reads.get());
      assertEquals(0, count(fixture, "printing_job"));
   }

   /** Reconciliation is read-only and preserves the accepted intent after dispatch settings change. */
   @Test
   void lookupDoesNotReadBytesOrInitializeSettingsAndAllowsHistoricalDestination() throws Exception
   {
      var fixture = fixture();
      try(Connection connection = connection(fixture); var statement = connection.createStatement())
      {
         statement.executeUpdate("DELETE FROM printing_settings");
      }
      assertNull(facade.find(request(fixture)));
      assertEquals(0, count(fixture, "printing_settings"));
      assertEquals(0, count(fixture, "printing_job"));
      assertEquals(0, count(fixture, "printing_event"));
      assertEquals(0, reads.get());
      var request = new RetainedPrintFacade.Request("lookup-1", "source-1", fixture.printerId(), "Retained document", "{\"copies\":2}");
      var accepted = facade.submit(request);
      JSONObject settings = fixture.service().getRealtimeSettings();
      settings.remove("revision");
      settings.put("maxCopies", 1);
      fixture.service().saveRealtimeSettings(settings);
      fixture.service().enablePrinter(fixture.device(), "laser", false);
      assertEquals(accepted, facade.find(request));
      assertEquals(1, reads.get());
      assertEquals(1, count(fixture, "printing_job"));
      assertEquals(1, count(fixture, "printing_event"));
      assertThrows(QException.class, () -> facade.submit(request));
   }

   /** An exact accepted media revision remains immutable when the reported queue default changes. */
   @Test
   void acceptedMediaRevisionIsReconciledWithoutReinterpretingCurrentInventory() throws Exception
   {
      var fixture = fixture();
      var profile = new PrintingMediaProfile("default", "a".repeat(64), "4 by 6 inches", 101600, 152400, 300, 300);
      JSONObject printer = new JSONObject().put("printerId", "laser").put("name", "Packing laser").put("status", "READY")
         .put("formats", new org.json.JSONArray().put("PDF")).put("mediaProfiles", new org.json.JSONArray().put(profile.json()));
      fixture.service().inventory(fixture.device(), new org.json.JSONArray().put(printer));
      JSONObject options = new JSONObject().put("mediaProfile", new JSONObject().put("id", profile.id()).put("revision", profile.revision()));
      var request = new RetainedPrintFacade.Request("echo:media-1", "source-1", fixture.printerId(), "Retained document", options.toString());
      var accepted = facade.submit(request);
      assertEquals(profile.revision(), new JSONObject(accepted.optionsJson()).getJSONObject("mediaProfile").getString("revision"));
      printer.put("mediaProfiles", new org.json.JSONArray().put(new PrintingMediaProfile("default", "b".repeat(64), "Letter", 215900, 279400, 600, 600).json()));
      fixture.service().inventory(fixture.device(), new org.json.JSONArray().put(printer));
      assertEquals(accepted, facade.find(request));
      assertEquals(1, reads.get());
      var newIntent = new RetainedPrintFacade.Request("echo:media-2", "source-1", fixture.printerId(), "Retained document", options.toString());
      assertEquals(409, assertThrows(PrintingException.class, () -> facade.submit(newIntent)).getStatus());
      options.getJSONObject("mediaProfile").put("revision", "b".repeat(64));
      var changedReplay = new RetainedPrintFacade.Request("echo:media-1", "source-1", fixture.printerId(), "Retained document", options.toString());
      assertEquals(409, assertThrows(PrintingException.class, () -> facade.find(changedReplay)).getStatus());
      assertEquals(1, count(fixture, "printing_job"));
      assertEquals(1, count(fixture, "printing_event"));
   }

   /** A caller transaction cannot roll back an independently acknowledged queue acceptance. */
   @Test
   void ambientTransactionRefusesBeforeOwnerReadOrQueueMutation() throws Exception
   {
      var fixture = fixture();
      try(var transaction = new com.kingsrook.qqq.backend.core.actions.QBackendTransaction())
      {
         QContext.setTransaction(transaction);
         try
         {
            assertThrows(QException.class, () -> facade.submit(request(fixture)));
            assertEquals(0, reads.get());
            assertEquals(0, count(fixture, "printing_job"));
            assertEquals(0, count(fixture, "printing_event"));
         }
         finally
         {
            QContext.setTransaction(null);
         }
      }
      assertNotNull(facade.submit(request(fixture)));
   }

   /** Additive upgrade preserves existing ordinary jobs without inventing accepted bindings. */
   @Test
   void upgradePreservesOldUnboundJobs() throws Exception
   {
      var fixture = fixture();
      String old = PrintingProcesses.submitTest(PrintingProcesses.resolvePrinter(fixture.printerId(), fixture.device()), true);
      try(Connection connection = connection(fixture); var statement = connection.createStatement())
      {
         statement.execute("ALTER TABLE printing_job DROP COLUMN retained_actor_id");
         statement.execute("ALTER TABLE printing_job DROP COLUMN retained_binding_json");
         statement.execute("ALTER TABLE printing_job DROP COLUMN accepted_event_id");
         statement.execute("ALTER TABLE printing_event DROP COLUMN event_origin");
      }
      fixture.service().initialize();
      fixture.service().initialize();
      assertEquals(old, fixture.service().getJob(old).getString("jobId"));
      assertEquals(1, count(fixture, "printing_job"));
      assertEquals(0, count(fixture, "printing_event"));
      assertNotNull(facade.submit(request(fixture)));
   }

   /** Unresolved storage reservations cannot be reported as an absent accepted request. */
   @Test
   void pendingUploadLookupRefusesFalseAbsence() throws Exception
   {
      var fixture = fixture();
      try(Connection connection = connection(fixture); var statement = connection.prepareStatement("INSERT INTO printing_document_orphan (id,tenant_id,storage_table,reference,binding,created_at,device_id,content_length,idempotency_key) VALUES (?,'tenant-a','fixture','ref','hash',?, ?,1,'retained:intent-1')"))
      {
         statement.setString(1, UUID.randomUUID().toString());
         statement.setString(2, java.time.Instant.now().toString());
         statement.setString(3, fixture.device());
         statement.executeUpdate();
      }
      assertThrows(PrintingException.class, () -> facade.find(request(fixture)));
      assertEquals(0, reads.get());
   }

   /** Lookup cannot report absence while another owner transaction holds admission. */
   @Test
   void lookupWaitsForExistingAdmissionTransaction() throws Exception
   {
      var fixture = fixture();
      try(Connection connection = connection(fixture); var executor = Executors.newSingleThreadExecutor())
      {
         connection.setAutoCommit(false);
         PrintingAdmission.lock(connection, "tenant-a");
         var entered = new java.util.concurrent.CountDownLatch(1);
         var result = executor.submit(() ->
         {
            QContext.init(fixture.instance(), session("operator"));
            try
            {
               entered.countDown();
               return facade.find(request(fixture));
            }
            finally
            {
               QContext.clear();
            }
         });
         try
         {
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> result.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
         }
         finally
         {
            connection.rollback();
         }
         assertNull(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
      }
   }

   /** Release only this test's thread-local identity. */
   @AfterEach
   void clear()
   {
      QContext.clear();
   }
}
