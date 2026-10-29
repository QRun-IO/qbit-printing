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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.branding.QBrandingMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.authentication.QAuthenticationModuleCustomizerInterface;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Loopback-only native QQQ UI demonstration with isolated synthetic records.
 ** No device transport routes are registered, so no physical agent can claim jobs.
 *******************************************************************************/
public class PrintingUiDemo extends AbstractQQQApplication
{
   private static final String TENANT = "printing-ui-demo";
   private static final String JDBC = "jdbc:h2:mem:printing_ui_demo;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE";



   /***************************************************************************
    ** Start the real QQQ middleware and frontend-next on loopback only.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      QApplicationJavalinServer server = new QApplicationJavalinServer(new PrintingUiDemo())
         .withPort(Integer.getInteger("printing.ui.port", 18082))
         .withServeFrontendNext(true)
         .withServeFrontendMaterialDashboard(false)
         .withJavalinConfigCustomizer(config -> config.jetty.host = "127.0.0.1");
      server.start();
   }



   /***************************************************************************
    ** Build actual QBit metadata against the same JDBC database as its service.
    ***************************************************************************/
   @Override
   public QInstance defineQInstance() throws QException
   {
      try
      {
         PrintingService service = new PrintingService(new DriverDataSource(JDBC, "sa", ""), TENANT);
         service.initialize();
         seed(service);
         QInstance instance = new QInstance();
         instance.setHomeAppName("printing");
         instance.setBranding(new QBrandingMetaData().withAppName("QQQ Printing — Local Demo"));
         instance.registerAuthenticationProvider(com.kingsrook.qqq.backend.core.model.metadata.authentication.AuthScope.instanceDefault(), new QAuthenticationMetaData().withName("local-demo")
            .withType(QAuthenticationType.MOCK).withCustomizer(new QCodeReference(DemoIdentity.class)));
         instance.addBackend(new RDBMSBackendMetaData().withName("printing-db").withVendor("h2")
            .withHostName("mem").withDatabaseName("printing_ui_demo").withUsername("sa").withPassword("").withJdbcUrl(JDBC));
         new PrintingQBitProducer().withConfig(new PrintingQBitConfig().withBackendName("printing-db").withService(service)).produce(instance, "printing");
         return (instance);
      }
      catch(Exception e)
      {
         throw new QException("Unable to initialize local printing UI demo", e);
      }
   }



   /***************************************************************************
    ** Clearly labeled sample queues; credentials are discarded and never exposed.
    ***************************************************************************/
   private static void seed(PrintingService service) throws Exception
   {
      if(!service.devices().isEmpty())
      {
         return;
      }
      JSONObject code = service.createEnrollment("DEMO-WAREHOUSE", "Demo packing station");
      String device = service.enroll(code.put("deviceName", "Demo packing station").put("agentVersion", "0.1.0-demo")).getString("deviceId");
      service.inventory(device, new JSONArray()
         .put(new JSONObject().put("printerId", "demo-laser").put("name", "Demo laser — packing slips").put("driverName", "Synthetic demo queue").put("status", "READY").put("formats", new JSONArray(List.of("PDF"))))
         .put(new JSONObject().put("printerId", "demo-zebra").put("name", "Demo Zebra — shipping labels").put("driverName", "Synthetic demo queue").put("status", "OFFLINE").put("formats", new JSONArray(List.of("ZPL")))));
      service.enablePrinter(device, "demo-laser", true);
      service.enablePrinter(device, "demo-zebra", true);
      service.submit(new JSONObject().put("deviceId", device).put("printerId", "demo-zebra")
         .put("title", "DEMO ONLY — sample shipping label").put("format", "ZPL")
         .put("contentBase64", Base64.getEncoder().encodeToString("^XA^FO30,30^ADN,30,20^FDDEMO ONLY^FS^XZ".getBytes(StandardCharsets.UTF_8)))
         .put("idempotencyKey", "demo-label-1"));
   }



   /***************************************************************************
    ** Fixed local demonstration identity, never suitable for a deployed host.
    ***************************************************************************/
   public static class DemoIdentity implements QAuthenticationModuleCustomizerInterface
   {
      /***********************************************************************
       ** Give the local demonstration its explicit printing permissions.
       ***********************************************************************/
      @Override
      public void customizeSession(QInstance instance, QSession session, Map<String, Object> context)
      {
         session.setPermissions(Set.of("printing_admin"));
         session.withSecurityKeyValue("printing_tenant", TENANT);
         session.getUser().setFullName("Local demo administrator");
      }
   }
}
