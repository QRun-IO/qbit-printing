// SPDX-License-Identifier: Apache-2.0
import com.kingsrook.qbits.printing.*;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.*;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.*;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.module.filesystem.s3.model.metadata.*;
import com.kingsrook.qqq.backend.module.filesystem.base.model.metadata.Cardinality;
import com.kingsrook.qqq.backend.module.filesystem.s3.utils.S3ClientFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.json.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Non-listening synthetic harness. Never routes work to a Windows device. */
public final class S3PrintingQualification {
  private static final String TABLE = "printing_documents";
  private static final JSONArray CHECKS = new JSONArray();
  private static final JSONArray DOCUMENTS = new JSONArray();
  private static final Map<String, String> OBJECT_KEYS = new HashMap<>();
  private static DriverDataSource database;
  private static S3Client s3;
  private static String bucket;
  private static String otherBucket;

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    String run = args[2];
    bucket = "printing-fixture-" + run;
    otherBucket = "printing-canary-" + run;
    S3BackendMetaData backend = new S3BackendMetaData().withName("printing_s3_" + run)
      .withBucketName(bucket).withEndpoint(args[1]).withPathStyleAccess(true).withRegion("us-east-1")
      .withAccessKey(System.getenv("MINIO_ROOT_USER")).withSecretKey(System.getenv("MINIO_ROOT_PASSWORD"));
    backend.setConnectTimeoutMillis(3000);
    backend.setSocketTimeoutMillis(15000);
    QInstance instance = new QInstance();
    // Required metadata only: this harness opens no HTTP endpoint or user session.
    instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("fixture").withType(QAuthenticationType.MOCK));
    instance.addBackend(backend);
    instance.addTable(new QTableMetaData().withName(TABLE).withBackendName(backend.getName())
      .withBackendDetails(new S3TableBackendDetails().withCardinality(Cardinality.ONE)
        .withFileNameFieldName("id").withContentsFieldName("content")).withPrimaryKeyField("id")
      .withField(new QFieldMetaData("id", QFieldType.STRING)).withField(new QFieldMetaData("content", QFieldType.BLOB)));
    database = new DriverDataSource("jdbc:h2:file:" + output.resolve("synthetic-data").toString().replace('\\', '/'), "sa", "");
    try {
      s3 = S3ClientFactory.getClient(backend);
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      s3.createBucket(CreateBucketRequest.builder().bucket(otherBucket).build());
      s3.putObject(PutObjectRequest.builder().bucket(bucket).key("unrelated/canary.txt").build(), RequestBody.fromString("synthetic unrelated object"));
      s3.putObject(PutObjectRequest.builder().bucket(otherBucket).key("canary.txt").build(), RequestBody.fromString("synthetic separate bucket"));
      PrintingService a = service(instance, "fixture-tenant-a");
      PrintingService b = service(instance, "fixture-tenant-b");
      String deviceA = enroll(a);
      String deviceB = enroll(b);
      byte[] pdf = PrintingTestDocuments.pdf();
      byte[] zpl = "^XA^FO20,20^ADN,30,18^FDSYNTHETIC STORAGE TEST ONLY^FS^XZ".getBytes(StandardCharsets.US_ASCII);
      byte[] large = new byte[6 * 1024 * 1024 + 71];
      Arrays.fill(large, (byte)'A');
      System.arraycopy("^XA^FX".getBytes(StandardCharsets.US_ASCII), 0, large, 0, 6);
      System.arraycopy("^FS^XZ".getBytes(StandardCharsets.US_ASCII), 0, large, large.length - 6, 6);
      String pdfJob = submitCheck(a, deviceA, "small-pdf", "PDF", pdf, false);
      finish(a, deviceA, pdfJob, "SPOOLED");
      String zplJob = submitCheck(a, deviceA, "small-zpl", "ZPL", zpl, false);
      finish(a, deviceA, zplJob, "SPOOLED");
      String largeJob = submitCheck(a, deviceA, "multipart-zpl", "ZPL", large, true);
      finish(a, deviceA, largeJob, "SPOOLED");
      String foreignJob = submitCheck(b, deviceB, "foreign-zpl", "ZPL", zpl, false);
      finish(b, deviceB, foreignJob, "SPOOLED");
      String foreignRef = reference(foreignJob);
      String ownRef = reference(pdfJob);
      check(!ownRef.split("/")[1].equals(foreignRef.split("/")[1]), "tenant prefixes differ");
      expectStatus(404, () -> a.content(deviceA, foreignJob), "foreign tenant job download denied");
      expectStatus(404, () -> a.content(deviceB, foreignJob), "foreign tenant device rejected");
      execute("UPDATE printing_job SET content_reference=? WHERE id=?", foreignRef, pdfJob);
      expectStatus(409, () -> a.content(deviceA, pdfJob), "foreign stored reference rejected");
      execute("UPDATE printing_job SET content_reference=? WHERE id=?", ownRef, pdfJob);
      check(Arrays.equals(zpl, b.content(deviceB, foreignJob)), "foreign object unchanged after rejection");
      String failed = submitCheck(a, deviceA, "protected-failed", "ZPL", zpl, false);
      finish(a, deviceA, failed, "FAILED");
      String unknown = submitCheck(a, deviceA, "protected-unknown", "ZPL", zpl, false);
      finish(a, deviceA, unknown, "OUTCOME_UNKNOWN");
      String active = a.submit(request(deviceA, "protected-queued", "ZPL", zpl)).getString("jobId");
      String old = Instant.now().minusSeconds(400L * 86400).toString();
      for(String id : List.of(pdfJob, largeJob, foreignJob, failed, unknown, active)) {
        execute("UPDATE printing_job SET created_at=?,completed_at=? WHERE id=?", old, old, id);
      }
      int before = objectCount();
      check(a.runRetention() == 2, "tenant A retention removes exactly two aged successful documents");
      check(objectCount() == before - 2, "object count reflects exact two removals");
      missing(OBJECT_KEYS.get(ownRef));
      missing(OBJECT_KEYS.get(reference(largeJob)));
      expectStatus(410, () -> a.content(deviceA, pdfJob), "expired document returns 410");
      check(a.submit(request(deviceA, "small-pdf", "PDF", pdf)).getString("jobId").equals(pdfJob), "idempotent replay survives retention without reupload");
      check(objectCount() == before - 2, "post-retention replay creates no object");
      for(String id : List.of(zplJob, foreignJob, failed, unknown, active)) {
        check(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey(reference(id))).build()).contentLength() == zpl.length,
          "protected object retained: " + status(id));
      }
      check(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key("unrelated/canary.txt").build()).asUtf8String().equals("synthetic unrelated object"), "unrelated same-bucket canary preserved");
      check(s3.getObjectAsBytes(GetObjectRequest.builder().bucket(otherBucket).key("canary.txt").build()).asUtf8String().equals("synthetic separate bucket"), "separate bucket canary preserved");
      check(a.runRetention() == 0, "repeated cleanup is idempotent");
      check(b.runRetention() == 1, "tenant B cleanup independently removes its one eligible document");
      check(s3.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).build()).uploads().isEmpty(), "no incomplete multipart uploads remain");
      check(QContext.getQSession() == null, "storage restores original absent QQQ session");
      JSONObject result = new JSONObject().put("passed", true).put("timestamp", Instant.now().toString())
        .put("checks", CHECKS).put("documents", DOCUMENTS).put("runtimeJarCount", 204)
        .put("appRevision", System.getenv("PRINTING_SOURCE_COMMIT"))
        .put("h2Version", org.h2.engine.Constants.VERSION).put("retentionDays", a.getRetentionSettings().getInt("days"))
        .put("transport", "loopback HTTP S3-compatible only").put("hardwarePrinting", false)
        .put("qualifies", "QQQ StorageAction / AWS SDK v2 S3 data-plane behavior against disposable MinIO")
        .put("doesNotQualify", new JSONArray(List.of("AWS IAM", "remote TLS", "production bucket ownership", "Object Lock/version lifecycle", "remote deployment", "hardware printing")));
      Files.writeString(output.resolve("results.json"), result.toString(2) + "\n");
      System.out.println("PASS " + CHECKS.length() + " checks; " + DOCUMENTS.length() + " synthetic document round trips");
    } finally {
      Files.writeString(output.resolve("completed-checks.json"), new JSONObject().put("checks", CHECKS).put("documents", DOCUMENTS).toString(2) + "\n");
      S3ClientFactory.clearCache();
      QContext.clear();
    }
  }
  private static PrintingService service(QInstance instance, String tenant) throws Exception {
    PrintingService service = new PrintingService(database, tenant);
    service.initialize(); service.configureStorage(instance, TABLE); return service;
  }
  private static String enroll(PrintingService service) throws Exception {
    String id = service.enroll(service.createEnrollment("fixture", "Synthetic station")
      .put("deviceName", "Synthetic station").put("agentVersion", "isolated-storage-fixture")).getString("deviceId");
    service.inventory(id, new JSONArray().put(new JSONObject().put("printerId", "synthetic-never-dispatched")
      .put("name", "Synthetic storage-only destination").put("status", "READY").put("formats", new JSONArray(List.of("PDF", "ZPL")))));
    service.enablePrinter(id, "synthetic-never-dispatched", true); return id;
  }
  private static JSONObject request(String device, String key, String format, byte[] bytes) {
    return new JSONObject().put("deviceId", device).put("printerId", "synthetic-never-dispatched").put("title", "Synthetic storage qualification")
      .put("format", format).put("contentBase64", Base64.getEncoder().encodeToString(bytes)).put("idempotencyKey", key);
  }
  private static String submitCheck(PrintingService service, String device, String key, String format, byte[] bytes, boolean multipart) throws Exception {
    JSONObject request = request(device, key, format, bytes);
    String id = service.submit(request).getString("jobId");
    int count = objectCount();
    check(id.equals(service.submit(request).getString("jobId")) && count == objectCount(), key + " stable replay creates no object");
    check(service.claim(device).getJSONObject("job").getString("jobId").equals(id), key + " claimed through production service");
    byte[] actual = service.content(device, id);
    check(Arrays.equals(bytes, actual) && sha(bytes).equals(sha(actual)), key + " download bytes and SHA256 match");
    String ref = reference(id);
    String actualKey = objectKey(ref);
    HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(actualKey).build());
    check(head.contentLength() == bytes.length, key + " object length exact");
    check(head.contentType().equals(format.equals("PDF") ? "application/pdf" : "application/vnd.zebra-zpl"), key + " content type correct");
    check(head.eTag().contains("-2") == multipart, key + " expected single/multipart object ETag");
    try(var c = database.getConnection(); var statement = c.prepareStatement("SELECT content_base64,content_storage_table FROM printing_job WHERE id=?")) {
      statement.setString(1, id); try(var rows = statement.executeQuery()) { check(rows.next() && rows.getString(1) == null && TABLE.equals(rows.getString(2)), key + " payload external, no inline database copy"); }
    }
    DOCUMENTS.put(new JSONObject().put("case", key).put("format", format).put("bytes", bytes.length).put("sha256", sha(bytes)).put("multipart", multipart).put("etag", head.eTag())
      .put("objectKeyEqualsStoredReference", actualKey.equals(ref)).put("objectKeyPrefixBeforeReference", actualKey.substring(0, actualKey.length() - ref.length())));
    return id;
  }
  private static void finish(PrintingService service, String device, String id, String status) throws Exception {
    for(String phase : List.of("RECEIVED", "SUBMITTING", status)) service.event(device, id, new JSONObject().put("eventId", UUID.randomUUID().toString()).put("status", phase).put("message", "Synthetic receipt; no physical device or spooler used"));
  }
  private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private static int objectCount() { return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents().size(); }
  private static String objectKey(String reference) {
    String known = OBJECT_KEYS.get(reference);
    if(known != null) return known;
    List<String> matches = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents().stream()
      .map(S3Object::key).filter(key -> key.endsWith(reference)).toList();
    if(matches.size() != 1) throw new AssertionError("Expected exactly one synthetic object for stored reference");
    String key = matches.getFirst();
    String prefix = key.substring(0, key.length() - reference.length());
    if(!prefix.isEmpty() && !prefix.matches("\\\\+")) throw new AssertionError("Unexpected physical S3 key prefix");
    OBJECT_KEYS.put(reference, key);
    return key;
  }
  private static String reference(String id) throws Exception { return field(id, "content_reference"); }
  private static String status(String id) throws Exception { return field(id, "status"); }
  private static String field(String id, String field) throws Exception {
    try(var c = database.getConnection(); var statement = c.prepareStatement("SELECT " + field + " FROM printing_job WHERE id=?")) {
      statement.setString(1,id); try(var rows=statement.executeQuery()) { if(!rows.next()) throw new AssertionError("Missing synthetic job"); return rows.getString(1); }
    }
  }
  private static void execute(String sql, String... args) throws Exception {
    try(var c = database.getConnection(); var statement = c.prepareStatement(sql)) { for(int i=0; i<args.length; i++) statement.setString(i+1,args[i]); statement.executeUpdate(); }
  }
  private static void check(boolean pass, String label) { if(!pass) throw new AssertionError(label); CHECKS.put(label); }
  private static void missing(String ref) {
    try { s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(ref).build()); throw new AssertionError("Expected removed object"); }
    catch(S3Exception exception) { check(exception.statusCode()==404,"eligible object absent from S3 after cleanup"); }
  }
  @FunctionalInterface private interface Checked { void run() throws Exception; }
  private static void expectStatus(int status, Checked operation, String label) throws Exception {
    try { operation.run(); throw new AssertionError(label); } catch(PrintingException exception) { check(exception.getStatus()==status,label); }
  }
}
