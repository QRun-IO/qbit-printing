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


import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Verify that adopting the namespace preserves prototype enrollment and data.
 *******************************************************************************/
class PrintingMigrationTest
{
   /***************************************************************************
    ** A prototype database can restart with its original device credential.
    ***************************************************************************/
   @Test
   void preservesPrototypeData() throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      JSONObject enrollment = service.createEnrollment("warehouse", "station");
      JSONObject device = service.enroll(enrollment.put("deviceName", "station").put("agentVersion", "test"));
      String deviceId = device.getString("deviceId");
      service.inventory(deviceId, new JSONArray().put(new JSONObject().put("printerId", "zebra").put("name", "Labels").put("status", "READY").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(deviceId, "zebra", true);
      String jobId = service.submit(new JSONObject().put("deviceId", deviceId).put("printerId", "zebra").put("title", "Migration test").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString("^XA^XZ".getBytes(java.nio.charset.StandardCharsets.UTF_8))).put("idempotencyKey", "migration-test")).getString("jobId");
      try(Connection connection = source.getConnection())
      {
         try(PreparedStatement statement = connection.prepareStatement("ALTER TABLE printing_job DROP COLUMN printer_row_id"))
         {
            statement.execute();
         }
         for(String table : List.of("printing_device", "printing_printer"))
         {
            for(String column : List.of("display_name", "description", "location", table.equals("printing_device") ? "computer_name" : "queue_name"))
            {
               try(PreparedStatement statement = connection.prepareStatement("ALTER TABLE " + table + " DROP COLUMN " + column))
               {
                  statement.execute();
               }
            }
         }
         for(String suffix : List.of("enrollment", "device", "printer", "job", "event"))
         {
            try(PreparedStatement statement = connection.prepareStatement("ALTER TABLE printing_" + suffix + " RENAME TO print_" + suffix))
            {
               statement.execute();
            }
         }
      }
      PrintingService restarted = new PrintingService(source, "tenant-a");
      restarted.initialize();
      restarted.initialize();
      assertEquals(device.getString("deviceId"), restarted.authenticate(device.getString("deviceToken")));
      assertEquals(1, restarted.devices().length());
      assertEquals("station", restarted.devices().getJSONObject(0).getString("computerName"));
      assertEquals("Labels", restarted.printers(deviceId).getJSONObject(0).getString("name"));
      assertEquals(jobId, restarted.claim(deviceId).getJSONObject("job").getString("jobId"));
      try(Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM printing_job j JOIN printing_printer p ON j.printer_row_id=p.id WHERE j.id=?"))
      {
         statement.setString(1, jobId);
         try(java.sql.ResultSet rows = statement.executeQuery())
         {
            rows.next();
            assertEquals(1, rows.getInt(1));
         }
      }
   }



   /***************************************************************************
    ** File-backed state survives last-connection closure without reinitialization.
    ** This checks cold reopen durability, not reproduction of every old-driver file.
    ***************************************************************************/
   @Test
   void preservesAuditSchemaAndSettingsAcrossColdReopens(@TempDir Path directory) throws Exception
   {
      String url = "jdbc:h2:file:" + directory.resolve("cold-reopen").toAbsolutePath();
      DriverDataSource source = new DriverDataSource(url, "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      assertEquals(365, service.getRetentionSettings().getInt("days"));
      service.saveRetentionSettings(400, false);
      JSONObject device = service.enroll(service.createEnrollment("default", "Synthetic station")
         .put("deviceName", "Synthetic station").put("agentVersion", "test"));
      for(int restart = 0; restart < 3; restart++)
      {
         DriverDataSource reopened = new DriverDataSource(url + ";IFEXISTS=TRUE", "sa", "");
         PrintingService restarted = new PrintingService(reopened, "tenant-a");
         assertEquals(400, restarted.getRetentionSettings().getInt("days"));
         assertEquals(device.getString("deviceId"), restarted.authenticate(device.getString("deviceToken")));
         try(Connection connection = reopened.getConnection())
         {
            for(String table : List.of("printing_settings", "printing_document_orphan", "printing_resource_usage", "printing_admission_event"))
            {
               try(PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table); java.sql.ResultSet rows = statement.executeQuery())
               {
                  assertTrue(rows.next());
               }
            }
         }
      }
   }



   /***************************************************************************
    ** Mixed namespaces are not silently merged or discarded.
    ***************************************************************************/
   @Test
   void refusesConflictingTables() throws Exception
   {
      DriverDataSource source = new DriverDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
      PrintingService service = new PrintingService(source, "tenant-a");
      service.initialize();
      try(Connection connection = source.getConnection(); PreparedStatement statement = connection.prepareStatement("CREATE TABLE print_device (id VARCHAR(36))"))
      {
         statement.execute();
      }
      assertThrows(SQLException.class, service::initialize);
   }
}
