# Java compiler safety

The repository compiler runs Error Prone 2.51.0 and NullAway 0.14.3 for
`com.kingsrook.qbits.printing`, including test sources. NullAway findings are
errors; compiler warnings fail through `-Werror` and Maven `failOnWarning`.
This implements K22-CODE-JV-06 and the compiler portion of K22-CODE-02. It is
not a claim that every unrelated coding-standard requirement is complete.

The configuration retains Java 21, compiler plugin 3.14.1, `proc:none`, the
existing parent and the frozen QQQ dependency tuple. The explicit compiler
plugin path loads the analyzers even with annotation processing disabled.
JSpecify 1.0.0 is declared directly for nullable contracts; it was already in
the runtime closure. NullAway's contract annotations are provided-only.
Neither analyzer implementation is an application runtime dependency.

Run the usual quality gate:

```sh
./mvnw -B -ntp -Pui-demo verify
```

CI also runs the bounded compiler-configuration guard:

```sh
python3 .github/scripts/check-java-compiler.py
```

The guard copies the actual POM compiler block into fresh, isolated modules
under `target/compiler-guard`. Both main and test sources have a positive
probe and negative probes for NullAway, Error Prone's `DeadException`, and a
deprecated call rejected by warnings-as-errors. A random Maven failure does
not satisfy a negative probe: the expected diagnostic must be present.
Logs and a result manifest are retained in the normal CI test evidence.
No artifact is installed or published. Optional `--maven`, `--settings`,
`--local-repository` and `--offline` arguments support an isolated local cache.

Null annotations describe existing optional inputs and transitions; they do
not convert absent data into required configuration. Existing lifecycle
checks provide checked initializer contracts. HELLO-dependent work receives
the validated payload explicitly. Scheduled retention captures its service
under the existing missing-configuration error contract. A missing realtime
QQQ instance now fails descriptively at revalidation, without moving host
registration timing or caching user authority.

Narrow member suppressions retain required framework override return types,
exact socket identity fencing and the framework's ordered presentation enum.
Two private accessors document the storage/page lifecycle invariants that
cannot be inferred from separate nullable fields. They preserve the existing
use-after-finish or misconfigured-storage failure rather than inventing valid
null operations. Suppressions are not package-wide, and tests remain analyzed.

The migration separates redundant return-expression parentheses from null
contracts and compiler configuration for review. Public JVM descriptors and
the resolved runtime JAR set are verified against the unchanged baseline.
The ordinary test suite intentionally skips the separately opt-in 10,000-job
qualification unless `printing.qualification.load=true` is supplied.
