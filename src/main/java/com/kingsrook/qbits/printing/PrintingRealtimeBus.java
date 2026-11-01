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


import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;


/** Host-owned commit notification bridge. Callbacks only schedule bounded work. */
public class PrintingRealtimeBus
{
   private final CopyOnWriteArrayList<BiConsumer<String, String>> listeners = new CopyOnWriteArrayList<>();
   private final java.util.concurrent.ConcurrentHashMap<String, Owner> owners = new java.util.concurrent.ConcurrentHashMap<>();


   /** Atomically fence the preceding connection across route providers sharing this host bus. */
   public void acquire(String tenant, String device, String generation, Runnable replaced)
   {
      Owner previous = owners.put(tenant + ":" + device, new Owner(generation, replaced));
      if(previous != null)
      {
         previous.replaced().run();
      }
   }


   /** A replaced connection cannot claim or send even if its local close callback is delayed. */
   public boolean owns(String tenant, String device, String generation)
   {
      Owner current = owners.get(tenant + ":" + device);
      return (current != null && current.generation().equals(generation));
   }


   /** Release only the exact generation; old disconnects cannot remove a replacement. */
   public void release(String tenant, String device, String generation)
   {
      owners.computeIfPresent(tenant + ":" + device, (key, owner) -> owner.generation().equals(generation) ? null : owner);
   }


   /** Ownership contains no device token or user credential. */
   private record Owner(String generation, Runnable replaced)
   {
   }


   /** Register before reading the durable snapshot to avoid a reconnect lost wakeup. */
   public AutoCloseable subscribe(BiConsumer<String, String> listener)
   {
      listeners.add(listener);
      return (() -> listeners.remove(listener));
   }


   /** Publish only after commit. Shared-host implementations must preserve this nonblocking contract. */
   public void publish(String tenant, String device)
   {
      for(BiConsumer<String, String> listener : listeners)
      {
         try
         {
            listener.accept(tenant, device);
         }
         catch(RuntimeException ignored)
         {
            // A stopped listener must not turn a successful commit into an apparent failure.
            // Reconnection always reconstructs work from the durable database.
         }
      }
   }
}
