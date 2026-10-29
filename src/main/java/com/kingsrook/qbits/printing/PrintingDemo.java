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
import io.javalin.Javalin;


/*******************************************************************************
 ** Loopback development server with durable H2 storage and explicit admin token.
 *******************************************************************************/
public final class PrintingDemo
{
   /***************************************************************************
    ** Start without printing credentials. Real deployments terminate TLS at ingress.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      String adminToken = System.getenv("QQQ_PRINT_ADMIN_TOKEN");
      if(adminToken == null || adminToken.length() < 24)
      {
         throw new IllegalArgumentException("Set QQQ_PRINT_ADMIN_TOKEN to at least 24 characters");
      }
      String url = System.getenv().getOrDefault("QQQ_PRINT_JDBC_URL", "jdbc:h2:file:./.local/printing");
      java.nio.file.Files.createDirectories(java.nio.file.Path.of(".local"));
      int port = Integer.parseInt(System.getenv().getOrDefault("QQQ_PRINT_PORT", "18081"));
      PrintingService service = new PrintingService(new DriverDataSource(url, "sa", ""), "demo");
      service.initialize();
      PrintingRoutes routes = new PrintingRoutes(service, context -> MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), PrintingRoutes.bearer(context).getBytes(StandardCharsets.UTF_8)));
      Javalin app = Javalin.create(config ->
      {
         config.http.maxRequestSize = 29 * 1024 * 1024;
         routes.acceptJavalinConfig(config);
      }).start("127.0.0.1", port);
      Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
   }
}


