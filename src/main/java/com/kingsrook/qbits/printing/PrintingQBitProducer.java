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
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.ChildRecordListRenderer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QVirtualFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitProducer;
import com.kingsrook.qqq.backend.core.model.metadata.security.QSecurityKeyType;
import com.kingsrook.qqq.backend.core.model.metadata.security.RecordSecurityLock;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Association;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.ExposedJoin;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QSupplementalTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSTableBackendDetails;


/*******************************************************************************
 ** QQQ 4.1 printing administration metadata. Secret/payload columns stay private.
 *******************************************************************************/
public class PrintingQBitProducer implements QBitProducer
{
   private PrintingQBitConfig config;



   /***************************************************************************
    ** Supply the existing backend and instance-local service.
    ***************************************************************************/
   public PrintingQBitProducer withConfig(PrintingQBitConfig value)
   {
      config = value;
      return (this);
   }



   /***************************************************************************
    ** Register device, printer and job tables and the Printing navigation app.
    ***************************************************************************/
   @Override
   public void produce(QInstance instance, String namespace) throws QException
   {
      if(config == null || config.getBackendName() == null || config.getService() == null)
      {
         throw new QException("Printing requires a backend name and PrintingService");
      }
      if(namespace != null && !namespace.isBlank() && !"printing".equals(namespace))
      {
         throw new QException("The initial printing QBit supports the printing namespace only");
      }
      config.getService().withRetentionPolicy(config.getRetentionPolicy()).withAdmissionLimits(config.getAdmissionLimits());
      config.getService().configureStorage(instance, config.getStorageTableName());
      instance.addQBit(new QBitMetaData().withGroupId("com.kingsrook.qbits").withArtifactId("qbit-printing").withVersion(version()).withNamespace("printing").withConfig(config));
      instance.addSecurityKeyType(new QSecurityKeyType().withName("printing_tenant"));
      QTableMetaData devices = table(PrintingTables.DEVICE, "Print devices", List.of("id", "tenant_id", "facility_id", "name", "display_name", "computer_name", "description", "location", "agent_version", "last_seen_at"));
      devices.withField(new QFieldMetaData("enabled", QFieldType.INTEGER).withLabel("Enabled").withPossibleValueSourceName("printing_enabled"));
      devices.withField(new QFieldMetaData("dispatch_paused", QFieldType.INTEGER).withLabel("Printing paused").withPossibleValueSourceName("printing_paused"));
      devices.withField(new QFieldMetaData("connection_state", QFieldType.STRING).withLabel("Requested connection").withPossibleValueSourceName("printing_connectionState"));
      devices.withField(new QFieldMetaData("control_revision", QFieldType.LONG).withLabel("Requested control revision"));
      devices.withField(new QFieldMetaData("control_applied_revision", QFieldType.LONG).withLabel("Observed control revision"));
      devices.withField(new QFieldMetaData("control_version", QFieldType.INTEGER).withLabel("Agent control version"));
      devices.withField(new QFieldMetaData("control_capabilities", QFieldType.STRING).withLabel("Agent control capabilities"));
      devices.withField(new QFieldMetaData("control_last_poll_at", QFieldType.STRING).withLabel("Last control connection"));
      devices.withField(new QFieldMetaData("revoked_at", QFieldType.STRING).withLabel("Access revoked at"));
      devices.withVirtualField(new QVirtualFieldMetaData("device_status", QFieldType.STRING).withLabel("Device status").withPossibleValueSourceName("printing_deviceStatus").withIsQuerySelectable(true));
      devices.withVirtualField(new QVirtualFieldMetaData("control_status", QFieldType.STRING).withLabel("Control application").withPossibleValueSourceName("printing_controlApplication").withIsQuerySelectable(true));
      devices.withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(PrintingDeviceStatus.class));
      devices.getField("facility_id").withLabel("Facility").withPossibleValueSourceName(PrintingFacilities.register(instance, config));
      QTableMetaData printers = table(PrintingTables.PRINTER, "Printers", List.of("id", "tenant_id", "device_id", "printer_id", "name", "display_name", "queue_name", "description", "location", "driver_name", "port_name", "status", "formats", "last_seen_at"));
      printers.withField(new QFieldMetaData("enabled", QFieldType.INTEGER).withLabel("Approved for printing").withPossibleValueSourceName("printing_approved"));
      printers.withField(new QFieldMetaData("available", QFieldType.INTEGER).withLabel("Exposed by device").withPossibleValueSourceName("printing_available"));
      QTableMetaData jobs = table(PrintingTables.JOB, "Print jobs", List.of("id", "tenant_id", "device_id", "printer_row_id", "title", "format", "content_hash", "options_json", "idempotency_key", "created_at", "status", "message", "spooler_job_id"));
      jobs.withField(new QFieldMetaData("content_length", QFieldType.INTEGER).withLabel("Document size (bytes)"));
      QTableMetaData commands = table(PrintingTables.COMMAND, "Device control history", List.of("id", "tenant_id", "device_id", "request_id", "action", "job_id", "actor", "status", "created_at", "expires_at", "result_code"));
      commands.withField(new QFieldMetaData("cancelled_job_count", QFieldType.INTEGER).withLabel("Queued jobs canceled"));
      commands.getField("device_id").withLabel("Print device").withPossibleValueSourceName(PrintingTables.DEVICE);
      commands.getField("job_id").withLabel("Print job").withPossibleValueSourceName(PrintingTables.JOB);
      commands.getField("action").withPossibleValueSourceName("printing_controlAction");
      commands.getField("status").withLabel("Recorded status").withPossibleValueSourceName("printing_commandStatus");
      commands.withVirtualField(new QVirtualFieldMetaData("command_status", QFieldType.STRING).withLabel("Current status")
         .withPossibleValueSourceName("printing_commandStatus").withIsQuerySelectable(true));
      commands.withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(PrintingCommandStatus.class));
      commands.withRecordLabelFormatAndFields("%s", "action");
      jobs.getField("printer_row_id").withLabel("Printer").withPossibleValueSourceName(PrintingTables.PRINTER);
      jobs.getField("format").withPossibleValueSourceName("printing_format");
      jobs.getField("status").withPossibleValueSourceName("printing_jobStatus");
      printers.getField("status").withPossibleValueSourceName("printing_printerStatus");
      for(QTableMetaData profileTable : List.of(devices, printers))
      {
         profileTable.getField("name").withMaxLength(1024);
         profileTable.getField("display_name").withLabel("Custom name").withMaxLength(512);
         profileTable.getField("description").withMaxLength(4096);
         profileTable.getField("location").withMaxLength(512);
      }
      devices.getField("computer_name").withLabel("Computer name").withMaxLength(1024);
      printers.getField("queue_name").withLabel("Windows queue name").withMaxLength(1024);
      jobs.getField("title").withMaxLength(1024);
      binaryValues(instance, "printing_enabled", "Disabled", "Enabled");
      binaryValues(instance, "printing_approved", "Not approved", "Approved");
      binaryValues(instance, "printing_available", "Not exposed", "Exposed");
      binaryValues(instance, "printing_paused", "Running", "Paused");
      enumValues(instance, "printing_connectionState", List.of("CONNECTED", "DISCONNECTED"));
      enumValues(instance, "printing_deviceStatus", List.of("ONLINE", "OFFLINE", "PAUSED", "DISCONNECTED", "REVOKED", "UNKNOWN"));
      enumValues(instance, "printing_controlApplication", List.of("APPLIED", "PENDING", "UNKNOWN"));
      enumValues(instance, "printing_commandStatus", List.of("PENDING", "SUCCEEDED", "FAILED", "EXPIRED", "OUTCOME_UNKNOWN", "UNKNOWN"));
      enumValues(instance, "printing_controlAction", List.of("PAUSE_DISPATCH", "RESUME_DISPATCH", "DISCONNECT", "RECONNECT", "REVOKE", "REFRESH_INVENTORY", "CLEAR_QUEUE", "CANCEL_SPOOL"));
      enumValues(instance, "printing_format", List.of("PDF", "ZPL"));
      enumValues(instance, "printing_jobStatus", List.of("QUEUED", "CLAIMED", "RECEIVED", "SUBMITTING", "SPOOLED", "SPOOLER_COMPLETED", "FAILED", "OUTCOME_UNKNOWN"));
      enumValues(instance, "printing_printerStatus", List.of("READY", "OFFLINE", "PAUSED", "ERROR", "UNKNOWN"));
      devices.withRecordLabelFormatAndFields("%s (%s)", "name", "facility_id");
      printers.withRecordLabelFormatAndFields("%s", "name");
      jobs.withRecordLabelFormatAndFields("%s", "title");
      printers.getField("device_id").withPossibleValueSourceName(PrintingTables.DEVICE);
      jobs.getField("device_id").withLabel("Print device").withPossibleValueSourceName(PrintingTables.DEVICE);
      printers.getField("device_id").withLabel("Print device");
      instance.addPossibleValueSource(com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource.newForTable(PrintingTables.DEVICE));
      instance.addPossibleValueSource(com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource.newForTable(PrintingTables.PRINTER));
      instance.addPossibleValueSource(com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource.newForTable(PrintingTables.JOB));
      layout(devices, List.of("name", "device_status", "control_status", "location", "facility_id", "last_seen_at"));
      layout(printers, List.of("name", "location", "description", "device_id", "status", "enabled", "available", "last_seen_at"));
      layout(jobs, List.of("title", "device_id", "printer_row_id", "status", "created_at", "format"));
      layout(commands, List.of("device_id", "action", "command_status", "created_at", "expires_at", "result_code", "cancelled_job_count"));
      instance.addTable(devices);
      instance.addTable(printers);
      instance.addTable(jobs);
      instance.addTable(commands);
      related(instance, devices, printers, "device_id", "Printers");
      related(instance, devices, jobs, "device_id", "Jobs and queue");
      related(instance, devices, commands, "device_id", "Control history");
      related(instance, printers, jobs, "printer_row_id", "Jobs and queue");
      QAppMetaData app = new QAppMetaData().withName("printing").withLabel("Printing").withIcon(new QIcon("print")).withChild(devices).withChild(printers).withChild(jobs).withChild(commands);
      app.withPermissionRules(PrintingProcesses.permissionRules());
      PrintingProcesses.register(instance, app);
      PrintingListProcesses.register(instance);
      PrintingRenderProcesses.register(instance);
      PrintingDeviceProcesses.register(instance);
      PrintingUiApi.register(instance);
      PrintingRetentionProcesses.register(instance, config);
      PrintingRealtimeProcesses.register(instance, app);
      PrintingHome.register(instance, app);
      instance.addApp(app);
      QAppMetaData parent = instance.getApp("admin");
      if(parent != null)
      {
         parent.withChild(app);
      }
   }



   /***************************************************************************
    ** Native related lists open the current device's work without raw-ID input.
    ***************************************************************************/
   private static void related(QInstance instance, QTableMetaData parent, QTableMetaData child, String childField, String label)
   {
      QJoinMetaData join = new QJoinMetaData().withLeftTable(parent.getName()).withRightTable(child.getName())
         .withType(JoinType.ONE_TO_MANY).withJoinOn(new JoinOn("id", childField)).withInferredName();
      instance.addJoin(join);
      parent.withAssociation(new Association().withName(child.getName()).withAssociatedTableName(child.getName()).withJoinName(join.getName()));
      parent.withExposedJoin(new ExposedJoin().withJoinTable(child.getName()).withJoinPath(List.of(join.getName())).withLabel(label));
      instance.addWidget(ChildRecordListRenderer.widgetMetaDataBuilder(join).withLabel(label).withCanAddChildRecord(false).withMaxRows(50).getWidgetMetaData());
      parent.withSection(new QFieldSection(join.getName(), label, new QIcon("list"), Tier.T2, null).withWidgetName(join.getName()));
      if(PrintingTables.JOB.equals(child.getName()))
      {
         String queueWidget = parent.getName() + "_queue";
         var widget = ChildRecordListRenderer.widgetMetaDataBuilder(join).withLabel("Current print queue").withCanAddChildRecord(false).withMaxRows(50)
            .withBaseFilter(new QQueryFilter().withCriteria("status", QCriteriaOperator.IN, List.of("QUEUED", "CLAIMED", "RECEIVED", "SUBMITTING")))
            .withOrderBys(List.of(new QFilterOrderBy("created_at", true))).getWidgetMetaData().withName(queueWidget);
         instance.addWidget(widget);
         parent.withSection(new QFieldSection(queueWidget, "Current print queue", new QIcon("pending_actions"), Tier.T2, null).withWidgetName(queueWidget));
      }
   }



   /***************************************************************************
    ** Read the build-produced JAR manifest; IDE/test class directories identify themselves honestly.
    ***************************************************************************/
   private static String version()
   {
      String version = PrintingQBitProducer.class.getPackage().getImplementationVersion();
      return (version == null || version.isBlank() ? "development" : version);
   }



   /***************************************************************************
    ** Produce read-only table metadata protected by tenant and administrator keys.
    ***************************************************************************/
   private QTableMetaData table(String name, String label, List<String> columns)
   {
      QTableMetaData table = new QTableMetaData().withName(name).withLabel(label).withBackendName(config.getBackendName()).withPrimaryKeyField("id")
         .withBackendDetails(new RDBMSTableBackendDetails().withTableName(name))
         .withCapabilities(Capability.TABLE_QUERY, Capability.TABLE_GET, Capability.TABLE_COUNT)
         .withDisabledCapabilities(Capability.allWriteCapabilities())
         .withPermissionRules(new QPermissionRules().withLevel(com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel.HAS_ACCESS_PERMISSION).withCustomPermissionChecker(new QCodeReference(PrintingReadPermission.class)))
         .withRecordSecurityLock(new RecordSecurityLock().withFieldName("tenant_id").withSecurityKeyType("printing_tenant"));
      for(String column : columns)
      {
         table.withField(new QFieldMetaData(column, QFieldType.STRING).withLabel(column.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + column.substring(1).replace('_', ' ')));
      }
      return (table);
   }


   /***************************************************************************
    ** Display stored integer flags as useful choices in tables and filters.
    ***************************************************************************/
   private static void binaryValues(QInstance instance, String name, String zero, String one)
   {
      instance.addPossibleValueSource(new QPossibleValueSource().withName(name).withType(QPossibleValueSourceType.ENUM)
         .withEnumValues(List.of(new QPossibleValue<>(0, zero), new QPossibleValue<>(1, one))));
   }



   /***************************************************************************
    ** Namespace fixed protocol values without changing their wire representation.
    ***************************************************************************/
   private static void enumValues(QInstance instance, String name, List<String> values)
   {
      java.util.ArrayList<QPossibleValue<?>> options = new java.util.ArrayList<>();
      for(String value : values)
      {
         options.add(new QPossibleValue<>(value, value.replace('_', ' ')));
      }
      instance.addPossibleValueSource(new QPossibleValueSource().withName(name).withType(QPossibleValueSourceType.ENUM).withEnumValues(options));
   }



   /***************************************************************************
    ** Put operational fields first; identifiers and diagnostics remain secondary.
    ***************************************************************************/
   private static void layout(QTableMetaData table, List<String> primary)
   {
      table.getField("tenant_id").withIsHidden(true);
      java.util.LinkedHashMap<String, QFieldMetaData> ordered = new java.util.LinkedHashMap<>();
      primary.stream().filter(table.getFields()::containsKey).forEach(name -> ordered.put(name, table.getField(name)));
      ordered.putAll(table.getFields());
      table.setFields(ordered);
      QFieldSection summary = new QFieldSection(table.getName() + "_summary", "Summary", new QIcon("print"), Tier.T1,
         primary.stream().filter(table.getFields()::containsKey).toList());
      if(table.getVirtualFields() != null && primary.stream().anyMatch(table.getVirtualFields()::containsKey))
      {
         summary.withAlternative(com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSectionAlternativeType.RECORD_VIEW,
            section -> section.setFieldNames(primary));
      }
      table.withSection(summary);
      List<String> details = table.getFields().keySet().stream().filter(name -> !primary.contains(name) && !Boolean.TRUE.equals(table.getField(name).getIsHidden())).toList();
      table.withSection(new QFieldSection(table.getName() + "_details", "Details", new QIcon("info"), Tier.T3, details));
      table.withSupplementalMetaData(new DefaultColumns(primary.stream().filter(name -> !"description".equals(name)).toList()));
   }



   /***************************************************************************
    ** Existing Next UI contract for compact, useful initial table columns.
    ***************************************************************************/
   public static class DefaultColumns extends QSupplementalTableMetaData
   {
      private final List<String> defaultColumns;



      /***************************************************************************
       ** Keep the hint immutable and safe to share in metadata clones.
       ***************************************************************************/
      public DefaultColumns(List<String> columns)
      {
         defaultColumns = List.copyOf(columns);
      }



      /***************************************************************************
       ** Existing frontend supplemental type.
       ***************************************************************************/
      @Override
      public String getType()
      {
         return ("nextUi");
      }



      /***************************************************************************
       ** Include column hints in the table's frontend metadata.
       ***************************************************************************/
      @Override
      public boolean includeInFullFrontendMetaData()
      {
         return (true);
      }



      /***************************************************************************
       ** Ordered operational columns.
       ***************************************************************************/
      public List<String> getDefaultColumns()
      {
         return (defaultColumns);
      }



      /***************************************************************************
       ** The list is immutable, so the base clone already contains all state.
       ***************************************************************************/
      @Override
      protected QSupplementalTableMetaData finishClone(QSupplementalTableMetaData clone)
      {
         return (clone);
      }
   }

}




