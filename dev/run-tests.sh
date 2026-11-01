#!/bin/sh
set -eu
case "$TEST_RUN" in run-[a-f0-9]*) ;; *) echo 'Invalid owned test run' >&2; exit 2;; esac
mkdir -p "/work/$TEST_RUN/s3"
java -Xmx768m -Dlog4j.configurationFile=/app/log4j2.xml -cp '/app/classes:/app/lib/*:/app/fixture' com.kingsrook.qbits.printing.DevHost --wait-storage
java -Xmx768m -Dlog4j.configurationFile=/app/log4j2.xml -cp '/app/classes:/app/lib/*:/app/fixture' S3PrintingQualification "/work/$TEST_RUN/s3" http://127.0.0.1:9000 "$TEST_RUN"
exec java -Xmx768m -Dlog4j.configurationFile=/app/log4j2.xml -cp '/app/classes:/app/lib/*:/app/fixture' com.kingsrook.qbits.printing.WorkloadFixture "/work/$TEST_RUN" "$WORKLOAD_MODE"
