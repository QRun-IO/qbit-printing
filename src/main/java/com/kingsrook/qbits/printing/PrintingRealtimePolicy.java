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


import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;


/** Validated administrator policy, independent of any individual client connection. */
public final class PrintingRealtimePolicy
{
   /** Canonical policy defaults and validation ranges. */
   private static final Map<String, long[]> FIELDS = fields();
   private final JSONObject values;


   /** Keep the validated policy private. */
   private PrintingRealtimePolicy(JSONObject values)
   {
      this.values = values;
   }


   /** Operational defaults; administrators can explicitly change every limit. */
   public static PrintingRealtimePolicy defaults()
   {
      JSONObject values = new JSONObject();
      FIELDS.forEach((name, range) -> values.put(name, range[0]));
      return new PrintingRealtimePolicy(values);
   }


   /** Require a complete policy and reject unknown, fractional or out-of-range values. */
   public static PrintingRealtimePolicy fromJson(JSONObject input)
   {
      if(!input.keySet().equals(FIELDS.keySet()))
      {
         throw new PrintingException(400, "Realtime settings require exactly the documented policy fields");
      }
      JSONObject values = new JSONObject();
      FIELDS.forEach((name, range) ->
      {
         Object raw = input.get(name);
         if(!(raw instanceof Number number) || !number.toString().matches("[0-9]+"))
         {
            throw new PrintingException(400, "Realtime setting must be an integer: " + name);
         }
         long value;
         try
         {
            value = Long.parseLong(number.toString());
         }
         catch(NumberFormatException exception)
         {
            throw new PrintingException(400, "Realtime setting exceeds its range: " + name);
         }
         if(value < range[1] || value > range[2])
         {
            throw new PrintingException(400, "Realtime setting exceeds its range: " + name);
         }
         values.put(name, value);
      });
      PrintingRealtimePolicy policy = new PrintingRealtimePolicy(values);
      policy.atLeast("maxQueuedFrameBytes", policy.get("maxFrameBytes"));
      policy.atLeast("maxStagedBytes", Math.max(policy.get("maxPdfBytes"), policy.get("maxZplBytes")));
      policy.atLeast("downloadTimeoutMillis", Math.max(policy.get("downloadConnectTimeoutMillis"), policy.get("downloadIdleTimeoutMillis")));
      policy.atLeast("heartbeatTimeoutSeconds", 2 * policy.get("heartbeatIntervalSeconds"));
      policy.atLeast("reconnectMaxDelayMillis", policy.get("reconnectMinDelayMillis"));
      return policy;
   }


   /** Read a validated named setting without exposing mutable JSON. */
   public long get(String name)
   {
      return values.getLong(name);
   }


   /** Wire settings include a server-generated durable revision. */
   public JSONObject toJson(long revision)
   {
      return new JSONObject(values.toString()).put("revision", revision);
   }


   /** Submission and retained-document retrieval use the configured format bound. */
   public long documentLimit(String format)
   {
      if(!"PDF".equals(format) && !"ZPL".equals(format))
      {
         throw new PrintingException(400, "Only PDF and ZPL are supported");
      }
      return get("PDF".equals(format) ? "maxPdfBytes" : "maxZplBytes");
   }


   /** A client must explicitly support the policy; never silently lower administrator limits. */
   public void requireClientLimits(JSONObject limits)
   {
      for(String name : new String[] {"maxPdfBytes", "maxZplBytes", "maxFrameBytes", "maxQueuedFrames", "maxQueuedFrameBytes", "maxConcurrentDownloads", "maxConcurrentPrints", "maxStagedBytes", "maxCopies"})
      {
         Object value = limits.opt(name);
         if(!(value instanceof Number number) || !number.toString().matches("[0-9]+") || number.longValue() < get(name))
         {
            throw new PrintingException(409, "Client cannot support configured realtime limit: " + name);
         }
      }
   }


   /** Validate a cross-field lower bound. */


   private void atLeast(String name, long minimum)
   {
      if(get(name) < minimum)
      {
         throw new PrintingException(400, "Realtime settings conflict at " + name);
      }
   }


   /** Canonical policy defaults and validation ranges. */
   private static Map<String, long[]> fields()
   {
      Map<String, long[]> fields = new LinkedHashMap<>();
      fields.put("maxPdfBytes", new long[] {104857600, 1, Integer.MAX_VALUE});
      fields.put("maxCopies", new long[] {100, 1, 32767});
      fields.put("maxZplBytes", new long[] {20971520, 1, Integer.MAX_VALUE});
      fields.put("maxFrameBytes", new long[] {1048576, 8192, 16777216});
      fields.put("maxQueuedFrames", new long[] {64, 8, 4096});
      fields.put("maxQueuedFrameBytes", new long[] {4194304, 8192, 67108864});
      fields.put("maxConcurrentDownloads", new long[] {1, 1, 16});
      fields.put("maxConcurrentPrints", new long[] {1, 1, 16});
      fields.put("maxStagedBytes", new long[] {536870912, 1, 9007199254740991L});
      fields.put("maxDownloadBytesPerSecond", new long[] {0, 0, Integer.MAX_VALUE});
      fields.put("requestTimeoutMillis", new long[] {30000, 1000, 300000});
      fields.put("downloadConnectTimeoutMillis", new long[] {30000, 1000, 300000});
      fields.put("webSocketHandshakeTimeoutMillis", new long[] {30000, 1000, 300000});
      fields.put("ackTimeoutMillis", new long[] {15000, 1000, 300000});
      fields.put("downloadIdleTimeoutMillis", new long[] {30000, 1000, 3600000});
      fields.put("downloadTimeoutMillis", new long[] {600000, 1000, 86400000});
      fields.put("nativeSubmissionTimeoutMillis", new long[] {120000, 1000, 86400000});
      fields.put("heartbeatIntervalSeconds", new long[] {20, 5, 300});
      fields.put("heartbeatTimeoutSeconds", new long[] {60, 10, 900});
      fields.put("reconnectMinDelayMillis", new long[] {1000, 100, 60000});
      fields.put("reconnectMaxDelayMillis", new long[] {30000, 1000, 300000});
      fields.put("reconnectJitterPercent", new long[] {20, 0, 50});
      return java.util.Collections.unmodifiableMap(fields);
   }
}
