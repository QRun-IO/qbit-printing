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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.StatisticsData;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.TableData;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Real H2-backed dashboard reads, authorization, tenant locks and native links.
 *******************************************************************************/
class PrintingHomeTest
{
   /***************************************************************************
    ** Counts and attention contain only the viewer's tenant, with native links.
    ***************************************************************************/
   @Test
   void scopedCountsAttentionAndDrillThrough() throws Exception
   {
      DriverDataSource source = source();
      QInstance instance = instance(source);
      seed(source, "tenant-a");
      seed(source, "tenant-b");
      QSession viewer = viewer("tenant-a", true);
      QContext.init(instance, viewer);
      try
      {
         assertEquals("FAILED", new PrintingService(source, "tenant-a").jobs().getJSONObject(0).getString("status"));
         var rawRows = new com.kingsrook.qqq.backend.core.actions.tables.QueryAction().execute(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput().withTableName(PrintingTables.JOB)).getRecords();
         assertEquals(1, rawRows.size(), JsonUtils.toJson(rawRows));
         StatisticsData failure = (StatisticsData) render(instance, "printingHomeFailures");
         assertEquals(1, failure.getCount(), JsonUtils.toJson(rawRows));
         assertTrue(failure.getCountContext().contains("last 24 hours"));
         assertTrue(failure.getCountURL().contains("filter="));
         assertTrue(failure.getCountURL().contains(PrintingTables.JOB));
         assertEquals(1, ((StatisticsData) render(instance, "printingHomeOffline")).getCount());
         assertEquals(1, ((StatisticsData) render(instance, "printingHomePrinters")).getCount());
         assertEquals(0, ((StatisticsData) render(instance, "printingHomePending")).getCount());
         TableData attention = (TableData) render(instance, "printingHomeAttention");
         assertEquals(3, attention.getRows().size());
         String attentionJson = JsonUtils.toJson(attention);
         assertTrue(attentionJson.contains("tenant-a printer"));
         assertFalse(attentionJson.contains("tenant-b"));
         assertFalse(attentionJson.contains("<a"));
         assertEquals(1, ((TableData) render(instance, "printingHomeWork")).getRows().size());
         assertEquals(viewer, QContext.getQSession());
         assertEquals(List.of("tenant-a"), viewer.getSecurityKeyValues("printing_tenant"));
         assertTrue(JsonUtils.toJson(render(instance, "printingHomeHeader")).contains("/app/printingEnrollDevice/?returnTo=%2Fapp%2Fprinting%2F"));
      }
      finally
      {
         QContext.clear();
      }
   }



   /***************************************************************************
    ** Control polls prove liveness; explicit administrative intent stays visible.
    ***************************************************************************/
   @Test
   void controlConnectionAndPendingCommandsRemainVisible() throws Exception
   {
      DriverDataSource source = source();
      QInstance instance = instance(source);
      seed(source, "tenant-a");
      QContext.init(instance, viewer("tenant-a", true));
      try
      {
         PrintingService service = new PrintingService(source, "tenant-a");
         String device = service.devices().getJSONObject(0).getString("deviceId");
         service.deviceControlPoll(device, new JSONObject().put("controlVersion", 1).put("agentVersion", "test")
            .put("capabilities", new JSONArray(List.of("REFRESH_INVENTORY"))).put("lastAppliedRevision", 0));
         assertEquals(0, ((StatisticsData) render(instance, "printingHomeOffline")).getCount());
         service.deviceAction(device, new JSONObject().put("requestId", UUID.randomUUID().toString()).put("issuedAt", java.time.Instant.now().toString())
            .put("action", "REFRESH_INVENTORY"), "viewer");
         assertEquals(1, ((StatisticsData) render(instance, "printingHomeControls")).getCount());
         assertTrue(JsonUtils.toJson(render(instance, "printingHomeAttention")).contains("REFRESH_INVENTORY"));
         try(Connection connection = source.getConnection(); PreparedStatement expired = connection.prepareStatement("UPDATE printing_device_command SET expires_at=? WHERE device_id=?"))
         {
            expired.setString(1, java.time.Instant.now().minusSeconds(1).toString());
            expired.setString(2, device);
            expired.executeUpdate();
         }
         assertEquals(0, ((StatisticsData) render(instance, "printingHomeControls")).getCount());
         assertTrue(JsonUtils.toJson(render(instance, "printingHomeAttention")).contains("EXPIRED"));
         service.deviceAction(device, new JSONObject().put("requestId", UUID.randomUUID().toString()).put("issuedAt", java.time.Instant.now().toString())
            .put("action", "DISCONNECT"), "viewer");
         assertEquals(1, ((StatisticsData) render(instance, "printingHomeOffline")).getCount());
         assertTrue(JsonUtils.toJson(render(instance, "printingHomeAttention")).contains("Administratively disconnected"));
      }
      finally
      {
         QContext.clear();
      }
   }



   /***************************************************************************
    ** Empty state guides setup; an unprivileged or unscoped caller gets no data.
    ***************************************************************************/
   @Test
   void guidedEmptyStateAndAuthorization() throws Exception
   {
      QInstance instance = instance(source());
      QContext.init(instance, viewer("tenant-empty", true));
      try
      {
         String header = JsonUtils.toJson(render(instance, "printingHomeHeader"));
         assertTrue(header.contains("No print devices are connected"));
         assertTrue(header.contains("/app/printingEnrollDevice/?returnTo=%2Fapp%2Fprinting%2F"));
         assertEquals(PrintingTables.DEVICE, instance.getProcess("printingEnrollDevice").getTableName());
         assertEquals(0, instance.getProcess("printingEnrollDevice").getMinInputRecords());
         TableData attention = (TableData) render(instance, "printingHomeAttention");
         assertTrue(attention.getRows().isEmpty());
         assertTrue(attention.getNoRowsFoundHTML().contains("No issues"));
         QContext.init(instance, viewer("tenant-empty", false));
         assertThrows(QPermissionDeniedException.class, () -> render(instance, "printingHomeFailures"));
         QContext.init(instance, new QSession().withUser(new QUser().withIdReference("unscoped")).withPermission("printing_admin"));
         assertThrows(QPermissionDeniedException.class, () -> render(instance, "printingHomeFailures"));
         assertEquals(12, instance.getWidget("printingHomeAttention").getGridColumns());
         assertEquals(false, instance.getWidget("printingHomeWork").getDefaultValues().get("storeDropdownSelections"));
      }
      finally
      {
         QContext.clear();
      }
   }



   /***************************************************************************
    ** Render the production renderer with the registered widget metadata.
    ***************************************************************************/
   private static Object render(QInstance instance, String name) throws Exception
   {
      return (new PrintingHome().render(new RenderWidgetInput().withWidgetMetaData(instance.getWidget(name))).getWidgetData());
   }



   /***************************************************************************
    ** A real RDBMS instance binds the QBit and service to one isolated database.
    ***************************************************************************/
   private static QInstance instance(DriverDataSource source) throws Exception
   {
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      QInstance instance = new QInstance();
      String backend = "printing-test-" + UUID.randomUUID();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK));
      instance.addBackend(new RDBMSBackendMetaData().withName(backend).withVendor("h2").withHostName("mem").withDatabaseName("test").withUsername("sa").withPassword("").withJdbcUrl(source.url()));
      new PrintingQBitProducer().withConfig(new PrintingQBitConfig().withBackendName(backend).withService(service)).produce(instance, "printing");
      new QInstanceValidator().validate(instance);
      return (instance);
   }



   /***************************************************************************
    ** An ordinary scoped viewer, never the all-powerful system session.
    ***************************************************************************/
   private static QSession viewer(String tenant, boolean admin)
   {
      QSession session = new QSession().withUser(new QUser().withIdReference("viewer")).withSecurityKeyValue("printing_tenant", tenant);
      if(admin)
      {
         session.withPermission("printing_admin");
      }
      return (session);
   }



   /***************************************************************************
    ** Independent shared-connection H2 database for each test.
    ***************************************************************************/
   private static DriverDataSource source()
   {
      return (new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE", "sa", ""));
   }



   /***************************************************************************
    ** Synthetic operational rows never leave the test database or print.
    ***************************************************************************/
   private static void seed(DriverDataSource source, String tenant) throws Exception
   {
      PrintingService service = new PrintingService(source, tenant);
      JSONObject code = service.createEnrollment("warehouse", tenant + " device");
      String device = service.enroll(code.put("deviceName", tenant + " device").put("agentVersion", "test")).getString("deviceId");
      service.inventory(device, new JSONArray().put(new JSONObject().put("printerId", "test").put("name", tenant + " printer").put("status", "OFFLINE").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "test", true);
      String job = service.submit(new JSONObject().put("deviceId", device).put("printerId", "test").put("title", tenant + " job").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(StandardCharsets.UTF_8))).put("idempotencyKey", "test")).getString("jobId");
      try(Connection connection = source.getConnection();
         PreparedStatement failure = connection.prepareStatement("UPDATE printing_job SET status='FAILED' WHERE id=?");
         PreparedStatement offline = connection.prepareStatement("UPDATE printing_device SET last_seen_at='2020-01-01T00:00:00Z' WHERE id=?"))
      {
         failure.setString(1, job);
         failure.executeUpdate();
         offline.setString(1, device);
         offline.executeUpdate();
      }
   }
}
