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


import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;


/*******************************************************************************
 ** The header and footer every printed page carries, whatever the document:
 **
 **   <app>                                         <server> · <user> · <printed at>
 **   <title / breadcrumb>
 **   ...
 **   <footer note: a grid's filter and sort>
 **   <footer: "qqq ui 1.0.0 · server">                                Page n of m
 **
 ** Clients send the fields as process values (headerApp, headerTitle,
 ** headerServer, headerUser, headerPrintedAt, footerLeft, footerNote) so the
 ** text matches what the user saw (their date format, the app's name, the
 ** breadcrumb). Missing fields fall back to the instance's branding name, the
 ** session user, the current instant and "QQQ printing".
 *******************************************************************************/
public record PrintingChrome(String app, String title, String server, String user, String printedAt, String footerLeft, String footerNote)
{
   /***************************************************************************
    ** The fields from process values, with server-side defaults.
    ***************************************************************************/
   public static PrintingChrome from(RunBackendStepInput input, String fallbackTitle)
   {
      return (new PrintingChrome(
         value(input, "headerApp", defaultApp()),
         value(input, "headerTitle", fallbackTitle),
         value(input, "headerServer", ""),
         value(input, "headerUser", defaultUser()),
         value(input, "headerPrintedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()),
         value(input, "footerLeft", "QQQ printing"),
         value(input, "footerNote", "")));
   }



   /***************************************************************************
    ** Defaults only (documents rendered outside a process).
    ***************************************************************************/
   public static PrintingChrome defaults(String title)
   {
      return (new PrintingChrome(defaultApp(), title == null ? "" : title, "", defaultUser(), Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), "QQQ printing", ""));
   }



   /***************************************************************************
    ** The same chrome with a footer note (a grid's filter and sort).
    ***************************************************************************/
   public PrintingChrome withFooterNote(String note)
   {
      return (new PrintingChrome(app, title, server, user, printedAt, footerLeft, footerNote == null || footerNote.isBlank() ? (note == null ? "" : note) : footerNote));
   }



   /***************************************************************************
    ** The same chrome with this title when it has none.
    ***************************************************************************/
   public PrintingChrome withTitleIfBlank(String fallback)
   {
      return (title != null && !title.isBlank() ? this : new PrintingChrome(app, fallback, server, user, printedAt, footerLeft, footerNote));
   }



   /***************************************************************************
    ** The right side of the first header line.
    ***************************************************************************/
   String headerRight()
   {
      StringBuilder out = new StringBuilder();
      for(String part : new String[] {server, user, printedAt})
      {
         if(part != null && !part.isBlank())
         {
            out.append(out.isEmpty() ? "" : " · ").append(part);
         }
      }
      return (out.toString());
   }



   /***************************************************************************
    ** A string value, or the default when absent or blank (bounded length).
    ***************************************************************************/
   private static String value(RunBackendStepInput input, String name, String fallback)
   {
      String value = input == null ? null : input.getValueString(name);
      String chosen = value == null || value.isBlank() ? fallback : value;
      return (chosen == null ? "" : PrintingRecordDocuments.truncate(chosen.strip(), 300));
   }



   /***************************************************************************
    ** The instance's branding name, else "QQQ".
    ***************************************************************************/
   private static String defaultApp()
   {
      try
      {
         var branding = QContext.getQInstance() == null ? null : QContext.getQInstance().getBranding();
         return (branding != null && branding.getAppName() != null ? branding.getAppName() : "QQQ");
      }
      catch(RuntimeException exception)
      {
         return ("QQQ");
      }
   }



   /***************************************************************************
    ** The session user's name, else blank.
    ***************************************************************************/
   private static String defaultUser()
   {
      return (QContext.getQSession() == null || QContext.getQSession().getUser() == null || QContext.getQSession().getUser().getFullName() == null ? "" : QContext.getQSession().getUser().getFullName());
   }
}
