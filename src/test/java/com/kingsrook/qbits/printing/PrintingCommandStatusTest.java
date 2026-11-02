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


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;


/*******************************************************************************
 ** Native reads derive expiry without an agent poll, mutation or tenant bypass.
 *******************************************************************************/
class PrintingCommandStatusTest
{
   /***************************************************************************
    ** Keep each synthetic fixture's authority local to that test.
    ***************************************************************************/
   @AfterEach
   void clearContext()
   {
      QContext.clear();
   }



   /***************************************************************************
    ** An offline stale row displays expired even though stored state stays pending.
    ***************************************************************************/
   @Test
   void fullGetAndProjectedQueryDeriveExpiryWithoutChangingRecordedState() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      DriverDataSource source = source(fixture);
      String id = seed(source, "tenant-a", fixture.device(), Instant.now().minusSeconds(60).toString());
      QRecord record = get(id);
      assertEquals("EXPIRED", record.getValueString("command_status"));
      assertEquals("PENDING", record.getValueString("status"));

      QueryInput query = new QueryInput().withTableName(PrintingTables.COMMAND)
         .withFieldNamesToInclude(Set.of("id", "action", "command_status"))
         .withFilter(new QQueryFilter().withCriteria("id", QCriteriaOperator.EQUALS, id));
      query.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      QRecord projected = new QueryAction().execute(query).getRecords().getFirst();
      assertEquals("EXPIRED", projected.getValueString("command_status"));
      assertFalse(projected.getValues().containsKey("status"));
      assertFalse(projected.getValues().containsKey("expires_at"));
      try(Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT status FROM printing_device_command WHERE id=?"))
      {
         statement.setString(1, id);
         try(var result = statement.executeQuery())
         {
            result.next();
            assertEquals("PENDING", result.getString(1));
         }
      }
   }



   /***************************************************************************
    ** Hydration uses the same current native permissions and tenant row security.
    ***************************************************************************/
   @Test
   void selectedProjectionCannotExposeAnotherTenantsCommands() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      DriverDataSource source = source(fixture);
      String own = seed(source, "tenant-a", fixture.device(), Instant.now().minusSeconds(60).toString());
      String foreign = seed(source, "tenant-b", fixture.foreignDevice(), Instant.now().minusSeconds(60).toString());
      QueryInput query = new QueryInput().withTableName(PrintingTables.COMMAND)
         .withFieldNamesToInclude(Set.of("id", "command_status"))
         .withFilter(new QQueryFilter().withCriteria("id", QCriteriaOperator.IN, List.of(own, foreign)));
      query.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      List<QRecord> rows = new QueryAction().execute(query).getRecords();
      assertEquals(List.of(own), rows.stream().map(row -> row.getValueString("id")).toList());
      assertEquals("EXPIRED", rows.getFirst().getValueString("command_status"));
      assertNull(get(foreign));
      QContext.getQSession().setPermissions(Set.of());
      assertThrows(QException.class, () -> new QueryAction().execute(query));
   }



   /***************************************************************************
    ** Arbitrary projections need a primary key to hydrate an authoritative value.
    ***************************************************************************/
   @Test
   void derivedStatusProjectionRequiresId() throws Exception
   {
      var fixture = PrintingProcessesTest.fixture();
      seed(source(fixture), "tenant-a", fixture.device(), Instant.now().minusSeconds(60).toString());
      QueryInput query = new QueryInput().withTableName(PrintingTables.COMMAND).withFieldNamesToInclude(Set.of("action", "command_status"));
      query.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      assertThrows(QException.class, () -> new QueryAction().execute(query));
   }



   /***************************************************************************
    ** Expiry cannot erase a receipt; missing or malformed pending expiry is unknown.
    ***************************************************************************/
   @Test
   void effectiveStatusKeepsTerminalReceiptsAndRejectsMalformedPendingExpiry()
   {
      Instant now = Instant.parse("2026-10-31T12:00:00Z");
      QRecord row = new QRecord().withValue("status", "PENDING").withValue("expires_at", now.toString());
      assertEquals("PENDING", PrintingCommandStatus.effective(row, now));
      row.setValue("expires_at", now.minusSeconds(1).toString());
      assertEquals("EXPIRED", PrintingCommandStatus.effective(row, now));
      row.setValue("expires_at", "not-a-timestamp");
      assertEquals("UNKNOWN", PrintingCommandStatus.effective(row, now));
      row.setValue("expires_at", null);
      assertEquals("UNKNOWN", PrintingCommandStatus.effective(row, now));
      for(String status : List.of("SUCCEEDED", "FAILED", "EXPIRED", "OUTCOME_UNKNOWN"))
      {
         row.setValue("status", status);
         assertEquals(status, PrintingCommandStatus.effective(row, now));
      }
      row.setValue("status", "unrecognized");
      assertEquals("UNKNOWN", PrintingCommandStatus.effective(row, now));
   }



   /***************************************************************************
    ** Reuse the existing isolated native fixture without exposing new production APIs.
    ***************************************************************************/
   private static DriverDataSource source(PrintingProcessesTest.Fixture fixture)
   {
      RDBMSBackendMetaData backend = (RDBMSBackendMetaData) fixture.instance().getBackend(fixture.instance().getTable(PrintingTables.COMMAND).getBackendName());
      return (new DriverDataSource(backend.getJdbcUrl(), "sa", ""));
   }



   /***************************************************************************
    ** Direct fixture inserts represent a device that never polls after acceptance.
    ***************************************************************************/
   private static String seed(DriverDataSource source, String tenant, String device, String expiresAt) throws Exception
   {
      String id = UUID.randomUUID().toString();
      String issued = Instant.now().minusSeconds(960).toString();
      JSONObject request = new JSONObject().put("requestId", id).put("issuedAt", issued).put("action", "REFRESH_INVENTORY");
      try(Connection connection = source.getConnection();
         PreparedStatement insert = connection.prepareStatement("INSERT INTO printing_device_command (id,tenant_id,device_id,request_id,action,actor,request_json,status,created_at,expires_at) VALUES (?,?,?,?,?,?,?,'PENDING',?,?)");
         PreparedStatement offline = connection.prepareStatement("UPDATE printing_device SET last_seen_at=?,control_last_poll_at=NULL WHERE id=?"))
      {
         insert.setString(1, id);
         insert.setString(2, tenant);
         insert.setString(3, device);
         insert.setString(4, id);
         insert.setString(5, "REFRESH_INVENTORY");
         insert.setString(6, "operator");
         insert.setString(7, request.toString());
         insert.setString(8, issued);
         insert.setString(9, expiresAt);
         insert.executeUpdate();
         offline.setString(1, issued);
         offline.setString(2, device);
         offline.executeUpdate();
      }
      return (id);
   }



   /***************************************************************************
    ** Native get also runs the post-query customizer and ordinary record security.
    ***************************************************************************/
   private static QRecord get(String id) throws QException
   {
      GetInput input = new GetInput();
      input.setTableName(PrintingTables.COMMAND);
      input.setPrimaryKey(id);
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      return (new GetAction().execute(input).getRecord());
   }
}
