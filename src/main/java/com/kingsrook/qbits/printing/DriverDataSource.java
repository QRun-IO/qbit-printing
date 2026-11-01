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


import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Minimal JDBC data source for demos; production applications supply a pool.
 *******************************************************************************/
public record DriverDataSource(String url, String user, String password) implements DataSource
{
   @Override
   public Connection getConnection() throws SQLException
   {
      return DriverManager.getConnection(url, user, password);
   }



   @Override
   public Connection getConnection(String username, String secret) throws SQLException
   {
      return DriverManager.getConnection(url, username, secret);
   }



   @Override
   public @Nullable PrintWriter getLogWriter()
   {
      return null;
   }



   @Override
   public void setLogWriter(PrintWriter writer)
   {
      // This simple demo data source does not log JDBC calls or credentials.
   }



   @Override
   public void setLoginTimeout(int seconds)
   {
      DriverManager.setLoginTimeout(seconds);
   }



   @Override
   public int getLoginTimeout()
   {
      return DriverManager.getLoginTimeout();
   }



   @Override
   public Logger getParentLogger()
   {
      return Logger.getLogger("com.kingsrook.qbits.printing");
   }



   @Override
   public <T> T unwrap(Class<T> type) throws SQLException
   {
      if(type.isInstance(this))
      {
         return type.cast(this);
      }
      throw new SQLException("Not a wrapper");
   }



   @Override
   public boolean isWrapperFor(Class<?> type)
   {
      return type.isInstance(this);
   }



   @Override
   public String toString()
   {
      return "DriverDataSource[credentials redacted]";
   }
}


