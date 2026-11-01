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


import java.util.Map;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Explicit local-host configuration. Never render credentials in diagnostics.
 *******************************************************************************/
record PrintingLocalSettings(String jdbcUrl, String databaseUser, String databasePassword, String tenant,
   int port, String administratorToken, String username, @Nullable String initialPassword)
{
   /***************************************************************************
    ** Environment launch supports a durable local H2 file, never a remote bind.
    ***************************************************************************/
   static PrintingLocalSettings fromEnvironment(Map<String, String> environment)
   {
      String jdbc = required(environment, "QQQ_PRINT_JDBC_URL");
      if(!jdbc.startsWith("jdbc:h2:file:") || jdbc.contains("\n") || jdbc.contains("\r"))
      {
         throw new IllegalArgumentException("QQQ_PRINT_JDBC_URL must identify a durable local H2 file");
      }
      int port = Integer.parseInt(environment.getOrDefault("QQQ_PRINT_PORT", "18081"));
      if(port < 1 || port > 65535)
      {
         throw new IllegalArgumentException("QQQ_PRINT_PORT must be between 1 and 65535");
      }
      return new PrintingLocalSettings(jdbc, environment.getOrDefault("QQQ_PRINT_DB_USER", "sa"),
         environment.getOrDefault("QQQ_PRINT_DB_PASSWORD", ""), environment.getOrDefault("QQQ_PRINT_TENANT", "demo"), port,
         required(environment, "QQQ_PRINT_ADMIN_TOKEN"), required(environment, "QQQ_PRINT_UI_USERNAME"), environment.get("QQQ_PRINT_UI_INITIAL_PASSWORD"));
   }



   /***************************************************************************
    ** Tests may use isolated memory databases through this package-private API.
    ***************************************************************************/
   PrintingLocalSettings
   {
      if(administratorToken == null || administratorToken.length() < 24 || username == null || username.isBlank()
         || username.length() > 200 || username.contains(":") || username.chars().anyMatch(Character::isISOControl)
         || tenant == null || tenant.isBlank() || tenant.length() > 200)
      {
         throw new IllegalArgumentException("Explicit administrator token, username and tenant are required");
      }
   }



   /***************************************************************************
    ** Fail without including the supplied value.
    ***************************************************************************/
   private static String required(Map<String, String> values, String name)
   {
      String value = values.get(name);
      if(value == null || value.isBlank())
      {
         throw new IllegalArgumentException("Set " + name);
      }
      return value;
   }



   /***************************************************************************
    ** Records must not expose their secret components through default toString.
    ***************************************************************************/
   @Override
   public String toString()
   {
      return "PrintingLocalSettings[loopbackPort=" + port + ", credentials=redacted]";
   }
}
