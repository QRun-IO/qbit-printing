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

// SPDX-License-Identifier: Apache-2.0

package com.kingsrook.qbits.printing;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.filesystem.base.model.metadata.Cardinality;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3BackendMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.S3TableBackendDetails;
import com.kingsrook.qqq.backend.module.filesystem.s3.utils.S3ClientFactory;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncher;
import com.kingsrook.qqq.middleware.javalin.QApplicationLauncherConfig;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;


/***************************************************************************
 ** Docker-only developer host. Compose publishes its port to host loopback only.
 ***************************************************************************/
public final class DevHost extends PrintingLocalHost
{
   private static final String TABLE = "printing_documents";



   /***************************************************************************
    ** Configure the isolated developer database and synthetic account from its private environment.
    ***************************************************************************/
   private DevHost() throws Exception
   {
      super(new PrintingLocalSettings("jdbc:h2:file:/data/printing", "sa", required("QQQ_DATABASE_PASSWORD"), "development",
         Integer.parseInt(required("QQQ_HTTP_PORT")), required("QQQ_MANAGEMENT_TOKEN"), "developer", required("QQQ_ADMIN_PASSWORD")));
   }



   /***************************************************************************
    ** Wait for owned storage and launch the loopback-published developer host.
    ***************************************************************************/
   public static void main(String[] args) throws Exception
   {
      waitStorage();
      if(args.length == 1 && "--wait-storage".equals(args[0]))
      {
         return;
      }
      DevHost host = new DevHost();
      QApplicationLauncher.run(host, new QApplicationLauncherConfig().withServerCustomizer(server ->
      {
         host.configure(server, true);
         // Container interface only. The immutable Compose mapping binds host 127.0.0.1.
         var original = server.getJavalinConfigCustomizer();
         server.withJavalinConfigCustomizer(config ->
         {
            original.accept(config);
            config.jetty.host = "0.0.0.0";
         });
      }));
   }



   /***************************************************************************
    ** Add the isolated MinIO-backed document table and make sure its bucket exists.
    ***************************************************************************/
   @Override
   public QInstance defineQInstance() throws QException
   {
      QInstance instance = super.defineQInstance();
      S3BackendMetaData backend = new S3BackendMetaData().withName("printing_development_storage").withBucketName("printing-development")
         .withEndpoint("http://127.0.0.1:9000").withPathStyleAccess(true).withRegion("us-east-1")
         .withAccessKey(required("MINIO_ROOT_USER")).withSecretKey(required("MINIO_ROOT_PASSWORD"));
      backend.setConnectTimeoutMillis(3000);
      backend.setSocketTimeoutMillis(15000);
      instance.addBackend(backend);
      instance.addTable(new QTableMetaData().withName(TABLE).withBackendName(backend.getName())
         .withBackendDetails(new S3TableBackendDetails().withCardinality(Cardinality.ONE).withFileNameFieldName("id").withContentsFieldName("content"))
         .withPrimaryKeyField("id").withField(new QFieldMetaData("id", QFieldType.STRING)).withField(new QFieldMetaData("content", QFieldType.BLOB)));
      var storage = S3ClientFactory.getClient(backend);
      try
      {
         storage.headBucket(HeadBucketRequest.builder().bucket("printing-development").build());
      }
      catch(S3Exception missing)
      {
         if(missing.statusCode() != 404)
         {
            throw missing;
         }
         storage.createBucket(CreateBucketRequest.builder().bucket("printing-development").build());
      }
      PrintingQBitConfig config = instance.getQBits().values().stream().map(value -> value.getConfig())
         .filter(PrintingQBitConfig.class::isInstance).map(PrintingQBitConfig.class::cast).findFirst().orElseThrow();
      config.withStorageTableName(TABLE);
      config.getService().configureStorage(instance, TABLE);
      return instance;
   }



   /***************************************************************************
    ** Bound startup retries to the owned MinIO health endpoint.
    ***************************************************************************/
   private static void waitStorage() throws Exception
   {
      try(HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build())
      {
         long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
         while(System.nanoTime() < deadline)
         {
            try
            {
               if(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:9000/minio/health/live"))
                  .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200)
               {
                  return;
               }
            }
            catch(IOException notReady)
            {
               // Only the fixture startup window retries; application work is never retried here.
            }
            Thread.sleep(200);
         }
         throw new IllegalStateException("Owned MinIO startup timed out");
      }
   }



   /***************************************************************************
    ** Read a required fixture setting without exposing its value.
    ***************************************************************************/
   private static String required(String name)
   {
      String value = System.getenv(name);
      if(value == null || value.isBlank())
      {
         throw new IllegalArgumentException("Missing development environment setting: " + name);
      }
      return value;
   }
}
