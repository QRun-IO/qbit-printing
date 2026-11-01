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


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.middleware.javalin.specs.extensions.MiddlewareSpecExtensionsMetaData;
import com.kingsrook.qqq.middleware.javalin.specs.v1.MiddlewareVersionV1;
import com.kingsrook.qqq.middleware.javalin.tools.UiApiContract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/** Published native extension contract and semantic compatibility baseline. */
class PrintingUiApiContractTest
{
   /** Regeneration is explicit; ordinary builds detect accidental contract drift. */
   @Test
   void publishedContractMatchesRegisteredSpecs() throws Exception
   {
      var extension = PrintingUiApi.extension();
      var version = new MiddlewareVersionV1();
      String generated = UiApiContract.generateExtensionYaml(version, extension);
      Path contract = Path.of("src/main/resources/openapi/qqq-v1/printing.yaml");
      Path baseline = Path.of("src/test/resources/openapi/printing-1.0.0.yaml");
      if(Boolean.getBoolean("printing.openapi.update"))
      {
         Files.createDirectories(contract.getParent());
         Files.writeString(contract, generated);
      }
      if(Boolean.getBoolean("printing.openapi.initializeBaseline") && !Files.exists(baseline))
      {
         Files.createDirectories(baseline.getParent());
         Files.writeString(baseline, generated);
      }
      assertEquals(List.of(), UiApiContract.check(generated, Files.readString(contract), Files.readString(baseline), 2,
         "mvnw.cmd -Dtest=PrintingUiApiContractTest -Dprinting.openapi.update=true test"));
      var model = version.generateExtensionOpenAPIModel("qqq", extension);
      assertEquals(17, model.getPaths().size());
      assertTrue(model.getPaths().keySet().stream().allMatch(path -> path.startsWith("/qqq/v1/qbit/printing/")));
      assertEquals("2.2.0", extension.getSemanticVersion());
      assertTrue(generated.contains("X-QQQ-Device-Token"));
      assertTrue(generated.contains("application/octet-stream"));
      assertTrue(generated.contains("principalId"));
   }


   /** Registration replaces this extension without duplicating its native routes. */
   @Test
   void registrationIsStable()
   {
      QInstance instance = new QInstance();
      PrintingUiApi.register(instance);
      PrintingUiApi.register(instance);
      assertEquals(Set.of("printing"), MiddlewareSpecExtensionsMetaData.of(instance).getExtensions().keySet());
   }
}
