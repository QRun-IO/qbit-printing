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


import java.util.List;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionEnforcement;
import com.kingsrook.qqq.backend.core.actions.values.SearchPossibleValueSourceAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.values.SearchPossibleValueSourceInput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import org.jspecify.annotations.Nullable;


/*******************************************************************************
 ** Native facility choices share the host's current session and record security.
 *******************************************************************************/
final class PrintingFacilities
{
   static final String DEFAULT_SOURCE = "printing_facility";



   /***************************************************************************
    ** Prevent instantiation of this metadata and validation helper.
    ***************************************************************************/
   private PrintingFacilities()
   {
   }



   /***************************************************************************
    ** A configured source must exist; standalone hosts receive one named choice.
    ***************************************************************************/
   static String register(QInstance instance, PrintingQBitConfig config) throws QException
   {
      String source = config.getFacilityPossibleValueSourceName();
      if(source == null || source.isBlank())
      {
         instance.addPossibleValueSource(new QPossibleValueSource().withName(DEFAULT_SOURCE).withType(QPossibleValueSourceType.ENUM)
            .withEnumValues(List.of(new QPossibleValue<>("default", "Default facility"))));
         return DEFAULT_SOURCE;
      }
      if(instance.getPossibleValueSource(source) == null)
      {
         throw new QException("The configured printing facility possible-value source does not exist");
      }
      return source;
   }



   /***************************************************************************
    ** Reuse the same source for enrollment and read-only device display.
    ***************************************************************************/
   static QFieldMetaData field(QInstance instance)
   {
      String source = instance.getTable(PrintingTables.DEVICE).getField("facility_id").getPossibleValueSourceName();
      return new QFieldMetaData("facilityId", QFieldType.STRING).withLabel("Facility").withIsRequired(true).withMaxLength(200)
         .withPossibleValueSourceName(source).withDefaultValue(DEFAULT_SOURCE.equals(source) ? "default" : null);
   }



   /***************************************************************************
    ** Re-query the selected ID under the caller's session, never a system session.
    ***************************************************************************/
   static String validate(@Nullable String selected) throws QException
   {
      if(selected == null || selected.isBlank() || selected.length() > 200)
      {
         throw new QException("Select an available facility");
      }
      String source = QContext.getQInstance().getTable(PrintingTables.DEVICE).getField("facility_id").getPossibleValueSourceName();
      SearchPossibleValueSourceInput input = new SearchPossibleValueSourceInput()
         .withPossibleValueSourceName(source).withIdList(List.of(selected));
      input.setPermissionEnforcement(PermissionEnforcement.ENFORCE);
      var options = new SearchPossibleValueSourceAction().execute(input);
      if(options.getResults() == null || options.getResults().stream().noneMatch(option -> selected.equals(String.valueOf(option.getId()))))
      {
         throw new QException("The selected facility is unavailable or is not accessible to this user");
      }
      return selected;
   }
}
