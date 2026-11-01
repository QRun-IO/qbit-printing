/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2026.  Kingsrook, LLC
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


import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Immutable reported Windows queue defaults; these never change printer stock.
 *******************************************************************************/
public record PrintingMediaProfile(String id, String revision, String name, int widthMicrons, int heightMicrons, int dpiX, int dpiY)
{
   /***************************************************************************
    ** Validate the bounded wire record without coercion or guessed dimensions.
    ***************************************************************************/
   public static PrintingMediaProfile parse(JSONObject value)
   {
      if(!value.keySet().containsAll(Set.of("id", "revision", "name", "widthMicrons", "heightMicrons", "dpiX", "dpiY", "source", "applyMode"))
         || !"WINDOWS_QUEUE_DEFAULT".equals(value.opt("source")) || !"DRIVER_DEFAULT".equals(value.opt("applyMode")))
      {
         throw new PrintingException(400, "Invalid reported media profile");
      }
      String id = text(value, "id", 128);
      String revision = text(value, "revision", 64);
      if(!id.matches("[A-Za-z0-9._-]+") || !revision.matches("[0-9a-f]{64}"))
      {
         throw new PrintingException(400, "Invalid media profile identity");
      }
      return new PrintingMediaProfile(id, revision, text(value, "name", 512), integer(value, "widthMicrons", 2000000),
         integer(value, "heightMicrons", 2000000), integer(value, "dpiX", 9600), integer(value, "dpiY", 9600));
   }


   /***************************************************************************
    ** Canonical public facts are separate from the immutable job selection.
    ***************************************************************************/
   public JSONObject json()
   {
      return new JSONObject().put("id", id).put("revision", revision).put("name", name).put("widthMicrons", widthMicrons)
         .put("heightMicrons", heightMicrons).put("dpiX", dpiX).put("dpiY", dpiY)
         .put("source", "WINDOWS_QUEUE_DEFAULT").put("applyMode", "DRIVER_DEFAULT");
   }


   /***************************************************************************
    ** Missing inventory facts mean no known stock, including older clients.
    ***************************************************************************/
   static JSONArray inventory(JSONObject printer)
   {
      JSONArray values = printer.has("mediaProfiles") ? printer.optJSONArray("mediaProfiles") : new JSONArray();
      if(values == null || values.length() > 32)
      {
         throw new PrintingException(400, "Invalid media profile list");
      }
      Set<String> ids = new HashSet<>();
      JSONArray result = new JSONArray();
      for(Object item : values)
      {
         if(!(item instanceof JSONObject object))
         {
            throw new PrintingException(400, "Invalid media profile");
         }
         PrintingMediaProfile profile = parse(object);
         if(!ids.add(profile.id()))
         {
            throw new PrintingException(400, "Duplicate media profile identity");
         }
         result.put(profile.json());
      }
      return result;
   }


   /***************************************************************************
    ** Validate a selection independently before comparing it with current facts.
    ***************************************************************************/
   static void selection(JSONObject options)
   {
      if(!options.has("mediaProfile"))
      {
         return;
      }
      JSONObject selected = options.optJSONObject("mediaProfile");
      if(selected == null || !selected.keySet().equals(Set.of("id", "revision"))
         || !text(selected, "id", 128).matches("[A-Za-z0-9._-]+") || !text(selected, "revision", 64).matches("[0-9a-f]{64}"))
      {
         throw new PrintingException(400, "Invalid media profile selection");
      }
   }


   /***************************************************************************
    ** Reject changed defaults before admission or document storage writes.
    ***************************************************************************/
   static void requireCurrent(JSONObject printer, JSONObject options)
   {
      if(!options.has("mediaProfile"))
      {
         return;
      }
      JSONObject selected = options.getJSONObject("mediaProfile");
      JSONArray profiles = new JSONArray(printer.optString("media_profiles_json", "[]"));
      for(Object item : profiles)
      {
         PrintingMediaProfile profile = parse((JSONObject) item);
         if(profile.id().equals(selected.getString("id")) && profile.revision().equals(selected.getString("revision")))
         {
            return;
         }
      }
      throw new PrintingException(409, "Printer media changed or is unavailable; select the current queue default again");
   }


   /***************************************************************************
    ** Text identities and labels retain their exact value.
    ***************************************************************************/
   private static String text(JSONObject value, String key, int maximum)
   {
      if(!(value.opt(key) instanceof String text) || text.isBlank() || text.length() > maximum)
      {
         throw new PrintingException(400, "Invalid media profile " + key);
      }
      return text;
   }


   /***************************************************************************
    ** Accept integral JSON values only, within the supported physical bounds.
    ***************************************************************************/
   private static int integer(JSONObject value, String key, int maximum)
   {
      Object raw = value.opt(key);
      if(!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < 1 || ((Number) raw).longValue() > maximum)
      {
         throw new PrintingException(400, "Invalid media profile " + key);
      }
      return ((Number) raw).intValue();
   }
}
