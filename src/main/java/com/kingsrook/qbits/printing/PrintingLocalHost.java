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
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.AuthScope;
import com.kingsrook.qqq.backend.core.model.metadata.branding.QBrandingMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.quartz.QuartzSchedulerMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.authentication.implementations.TableBasedAuthenticationModule;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncher;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncherConfig;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;


/*******************************************************************************
 ** Authenticated loopback host: native QQQ screens and paired device routes share
 ** one durable database. This is not a remote production deployment template.
 *******************************************************************************/
public class PrintingLocalHost extends AbstractQQQApplication
{
   static final String USER_TABLE = "printing_admin_user";
   static final String SESSION_TABLE = "printing_admin_session";
   static final String BACKEND = "printing-local-db";
   static final String SCHEDULER = "printing-local-scheduler";
   private final String backendName = BACKEND + "-" + java.util.UUID.randomUUID();
   private final PrintingLocalSettings settings;
   private final DriverDataSource source;
   private final PrintingService service;



   /***************************************************************************
    ** No default password, mock identity or synthetic records are provisioned.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      PrintingLocalHost host = new PrintingLocalHost(PrintingLocalSettings.fromEnvironment(System.getenv()));
      QApplicationLauncher.run(host, new QApplicationLauncherConfig()
         .withServerCustomizer(server -> host.configure(server, true)));
   }



   /***************************************************************************
    ** Construction opens only the explicitly configured database.
    ***************************************************************************/
   PrintingLocalHost(PrintingLocalSettings settings) throws Exception
   {
      this.settings = new PrintingLocalSettings(settings.jdbcUrl(), settings.databaseUser(), settings.databasePassword(), settings.tenant(),
         settings.port(), settings.administratorToken(), settings.username(), null);
      source = new DriverDataSource(settings.jdbcUrl(), settings.databaseUser(), settings.databasePassword());
      service = new PrintingService(source, settings.tenant());
      service.initialize();
      initializeAuthentication(settings.initialPassword());
   }



   /***************************************************************************
    ** Native authentication and an instance-owned Quartz schedule are registered.
    ***************************************************************************/
   @Override
   public QInstance defineQInstance() throws QException
   {
      try
      {
         QInstance instance = new QInstance();
         instance.setFailOnMetaDataProducerError(true);
         instance.setHomeAppName("printing");
         instance.setBranding(new QBrandingMetaData().withAppName("QQQ Printing"));
         instance.addBackend(new RDBMSBackendMetaData().withName(backendName).withVendor("h2").withHostName("local")
            .withDatabaseName("printing").withUsername(settings.databaseUser()).withPassword(settings.databasePassword()).withJdbcUrl(settings.jdbcUrl()));
         PrintingLocalAuthentication.Metadata auth = new PrintingLocalAuthentication.Metadata(settings.username(), settings.tenant());
         auth.withUserTableName(USER_TABLE).withSessionTableName(SESSION_TABLE).withInactivityTimeoutSeconds(3600);
         auth.withName("local-printing").withModuleCodeReference(new QCodeReference(PrintingLocalAuthentication.class));
         instance.registerAuthenticationProvider(AuthScope.instanceDefault(), auth);
         instance.addTable(auth.defineStandardUserTable(backendName));
         instance.addTable(auth.defineStandardSessionTable(backendName));
         Properties properties = new Properties();
         properties.setProperty("org.quartz.scheduler.instanceName", SCHEDULER);
         properties.setProperty("org.quartz.threadPool.threadCount", "2");
         instance.addScheduler(new QuartzSchedulerMetaData().withProperties(properties).withName(SCHEDULER));
         new PrintingQBitProducer().withConfig(new PrintingQBitConfig().withBackendName(backendName).withService(service)
            .withSchedulerName(SCHEDULER)).produce(instance, "printing");
         mapPhysicalNames(instance);
         return (instance);
      }
      catch(Exception exception)
      {
         throw new QException("Unable to initialize the authenticated local printing host", exception);
      }
   }



   /***************************************************************************
    ** Public middleware integration; a test may omit frontend assets only.
    ***************************************************************************/
   void configure(QApplicationJavalinServer server, boolean frontend)
   {
      PrintingRoutes routes = new PrintingRoutes(service, context -> MessageDigest.isEqual(
         settings.administratorToken().getBytes(StandardCharsets.UTF_8), PrintingRoutes.bearer(context).getBytes(StandardCharsets.UTF_8)), PrintingRoutes.LegacyEnrollment.DISABLED);
      server.withPort(settings.port()).withServeFrontendNext(frontend).withServeFrontendMaterialDashboard(false)
         .withAdditionalRouteProviders(List.of(routes)).withJavalinConfigCustomizer(config ->
         {
            config.jetty.host = "127.0.0.1";
            config.http.maxRequestSize = 29 * 1024 * 1024;
            config.routes.before(PrintingLocalHost::guardOrigin);
         });
   }



   /***************************************************************************
    ** Reject DNS rebinding and cross-origin cookie mutations on real printers.
    ** Explicit API credentials without ambient cookies remain usable locally.
    ***************************************************************************/
   static void guardOrigin(Context context)
   {
      String host = context.header("Host");
      int port = context.req().getLocalPort();
      Set<String> hosts = port == 80 ? Set.of("localhost", "127.0.0.1", "localhost:80", "127.0.0.1:80")
         : Set.of("localhost:" + port, "127.0.0.1:" + port);
      if(host == null || !hosts.contains(host.toLowerCase(Locale.ROOT)))
      {
         throw new ForbiddenResponse("The local printing host accepts only loopback host names");
      }
      String origin = context.header("Origin");
      if(origin != null && !origin.equals("http://" + host))
      {
         throw new ForbiddenResponse("Cross-origin access to local printing is not allowed");
      }
      if("cross-site".equalsIgnoreCase(context.header("Sec-Fetch-Site")))
      {
         throw new ForbiddenResponse("Cross-site access to local printing is not allowed");
      }
      boolean unsafe = !Set.of("GET", "HEAD", "OPTIONS").contains(context.method().name());
      boolean cookie = context.cookie("sessionUUID") != null || context.cookie("sessionId") != null;
      if(unsafe && cookie && origin == null)
      {
         throw new ForbiddenResponse("Cookie-authenticated changes require a same-origin request");
      }
   }



   /***************************************************************************
    ** Add native auth tables; an existing account is never implicitly reset.
    ***************************************************************************/
   private void initializeAuthentication(String initialPassword) throws Exception
   {
      try(Connection connection = source.getConnection(); var statement = connection.createStatement())
      {
         statement.execute("CREATE TABLE IF NOT EXISTS printing_admin_user (id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, username VARCHAR(200) NOT NULL UNIQUE, passwordHash VARCHAR(1024) NOT NULL, fullName VARCHAR(512), createDate TIMESTAMP WITH TIME ZONE, modifyDate TIMESTAMP WITH TIME ZONE)");
         statement.execute("CREATE TABLE IF NOT EXISTS printing_admin_session (id VARCHAR(100) PRIMARY KEY, userId INTEGER NOT NULL REFERENCES printing_admin_user(id), accessTimestamp TIMESTAMP WITH TIME ZONE, createDate TIMESTAMP WITH TIME ZONE, modifyDate TIMESTAMP WITH TIME ZONE)");
         try(var users = statement.executeQuery("SELECT username FROM printing_admin_user"))
         {
            boolean found = false;
            boolean any = false;
            while(users.next())
            {
               any = true;
               found |= settings.username().equals(users.getString(1));
            }
            if(found)
            {
               return;
            }
            if(any)
            {
               throw new IllegalArgumentException("The configured local administrator does not match the initialized account; no account was changed");
            }
         }
         String password = initialPassword;
         if(password == null || password.length() < 16 || password.length() > 1024)
         {
            throw new IllegalArgumentException("First startup requires QQQ_PRINT_UI_INITIAL_PASSWORD of 16 to 1024 characters; no default password is provided");
         }
         try(var insert = connection.prepareStatement("INSERT INTO printing_admin_user(username,passwordHash,fullName,createDate,modifyDate) VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
         {
            insert.setString(1, settings.username());
            insert.setString(2, TableBasedAuthenticationModule.PasswordHasher.createHashedPassword(password));
            insert.setString(3, "Local printing administrator");
            insert.executeUpdate();
         }
      }
   }



   /***************************************************************************
    ** Preserve the existing H2 identifier convention without renaming live tables.
    ***************************************************************************/
   private void mapPhysicalNames(QInstance instance) throws Exception
   {
      try(Connection connection = source.getConnection())
      {
         boolean uppercase = connection.getMetaData().storesUpperCaseIdentifiers();
         for(QTableMetaData table : instance.getTables().values())
         {
            if(backendName.equals(table.getBackendName()))
            {
               table.withBackendDetails(new RDBMSTableBackendDetails().withTableName(uppercase ? table.getName().toUpperCase(Locale.ROOT) : table.getName()));
               for(var field : table.getFields().values())
               {
                  field.withBackendName(uppercase ? field.getName().toUpperCase(Locale.ROOT) : field.getName());
               }
            }
         }
      }
   }
}
