# Load-balance integration tests

These tests drive a **real CUBRID cluster** over real brokers. They are not part of
`./gradlew test`: that task leaves out every `*IT` class, so a normal build compiles these files
but never runs them. Compiling them is deliberate — it keeps the live suite from drifting away
from the production API between cluster runs.

Run them with the `integrationTest` task described below.

## They skip silently without `lb.it.enabled`

Every IT gates itself on `Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"))`, so a run
without that property passes without touching anything:

```
$ ./gradlew integrationTest --tests '*LbVendorApiInheritanceIT'
BUILD SUCCESSFUL
```

**A green build is not evidence that the cluster was exercised.** Read the test report
(`build/reports/tests/integrationTest/index.html`) with care too, because the gate sits in two
different places:

| Gate in | Classes | Report when skipped |
|---|---|---|
| `@BeforeClass` | `LbRoutingDecisionIT`, `LbDatabaseMetaDataIT`, `LbStatementApiIT`, `LbPreparedStatementApiIT`, `LbVendorApiInheritanceIT` | **no entry for the class** — the whole class is aborted |
| the `@Test` body | `LbUriUrlIT` | 4 tests, 3 of them skipped |

So for the five classes gated in `@BeforeClass`, a skipped run leaves no trace. Check that the
report lists the class with the full count below, or confirm from the cluster side — the fixture
table.

The Ant targets give even less: their JUnit summary reads the same whether or not the cluster was
reached. `ant test-it-uri-url` prints `OK (4 tests)`, and the targets of the other five classes print
`OK (0 tests)`.

Full counts, for comparing against a real run:

| Class | `@Test` |
|---|---:|
| `LbVendorApiInheritanceIT` | 23 |
| `LbRoutingDecisionIT` | 11 |
| `LbPreparedStatementApiIT` | 7 |
| `LbStatementApiIT` | 6 |
| `LbUriUrlIT` | 4 |
| `LbDatabaseMetaDataIT` | 2 |

Every class is gated only on `lb.it.enabled`; none needs a second property.

## What the cluster needs

The URL you pass decides the topology, so the cluster must actually provide the roles it names:

| Role | Broker | Serves |
|---|---|---|
| master | RW broker on the master node | writes, and reads pinned to master (transactions, `TO_RW` hints) |
| slave | RO broker on a standby node | distributed reads |
| replica | SO broker on a replica node | distributed reads (only if `replica=` is in the URL) |

A two-node master/slave cluster is enough for most classes. `replica=` and a `readWeight` that
gives the replica a share are needed only when you want the replica rung covered.

The account in `lb.it.jdbc.user` must be able to `CREATE TABLE` and `DROP TABLE`: each suite
creates its own fixture table (`id INT PRIMARY KEY, v INT, doc CLOB`), seeds it, and waits for the
rows to reach every read endpoint before asserting. Replication lag is tolerated up to
`lb.it.fixture.waitMs`.

## Properties

| Property | Default | Meaning |
|---|---|---|
| `lb.it.enabled` | *(unset → skip)* | **Required.** Anything other than `true` skips every test. |
| `lb.it.jdbc.url` | *(unset)* | Full LB URL. Takes precedence over `lb.it.jdbc.baseUrl`. |
| `lb.it.jdbc.baseUrl` | per-class default | Fallback URL when `lb.it.jdbc.url` is unset. |
| `lb.it.jdbc.user` | `dba` | Database user. Needs DDL rights for the fixture table. |
| `lb.it.jdbc.password` | `""` | Password. |
| `lb.it.table` | per-class default | Fixture table name, e.g. `lb_scenario_it`. |
| `lb.it.fixture.waitMs` | `20000` | Cap on waiting for fixture rows to replicate to every read endpoint. |

`lb.it.jdbc.url`, `lb.it.jdbc.baseUrl` and `lb.it.table` count as unset when the value is empty,
blank, or a literal unresolved Ant placeholder (`${lb.it.jdbc.url}`). `lb.it.jdbc.user` and
`lb.it.jdbc.password` are used as given in every class but `LbUriUrlIT`, and an Ant target passes
the literal `${name}` for a key it was not given. So with `integrationTest` any property but
`lb.it.enabled` can be left out, while an Ant target also needs the user and the password.

## Running

`./gradlew integrationTest` runs every IT, and `--tests` picks classes, e.g.
`--tests '*LbRoutingDecisionIT'`. Every `-Dlb.it.*` given to Gradle reaches the tests. The Ant
targets in `build.xml` (one `test-it-*` per class, plus `test-it-all`) still work as well.

What each class covers:

- **`LbRoutingDecisionIT`** — routing decisions. Asserts on the routing events the driver records
  (`sqlType`, `routeTarget`) rather than on wall-clock behaviour: reads go to RO, reads inside a
  transaction fall back to master, hints override, unclassifiable SQL is treated as a write.
- **`LbStatementApiIT`** / **`LbPreparedStatementApiIT`** — the `Statement` and
  `PreparedStatement` surfaces against live brokers, including the paths that delegate to a
  physical statement.
- **`LbDatabaseMetaDataIT`** — `DatabaseMetaData` served from the bound read endpoint.
- **`LbUriUrlIT`** — the `jdbc:cubrid:loadbalance://` URI URL format end to end.
- **`LbVendorApiInheritanceIT`** — an application never modified for load balancing can still cast
  to the CUBRID vendor types. Doubles accept calls a live CAS rejects, which is why this one has to
  run against real brokers.

## Examples

Master/slave cluster, every suite:

```sh
./gradlew integrationTest \
  -Dlb.it.enabled=true \
  -Dlb.it.jdbc.url='jdbc:cubrid:loadbalance://192.168.2.197:30000:33000,192.168.2.196:30000:33000/tdb?readWeight=master:50,slave:50' \
  -Dlb.it.jdbc.user=dba \
  -Dlb.it.jdbc.password= \
  -Dlb.it.table=lb_scenario_it
```

With a replica rung:

```sh
./gradlew integrationTest \
  -Dlb.it.enabled=true \
  -Dlb.it.jdbc.url='jdbc:cubrid:loadbalance://192.168.2.197:30000:33000,192.168.2.196:30000:33000;replica=192.168.2.195:36000/tdb?readWeight=master:33,slave:33,replica:34' \
  -Dlb.it.jdbc.user=dba -Dlb.it.jdbc.password= -Dlb.it.table=lb_scenario_it
```

## Adding a property

The `integrationTest` task passes every `lb.it.*` system property given to Gradle to the test
JVM, so a test can read a new property without any change to the build. An Ant target passes only
the keys it lists in `<sysproperty>`: add a new key to every `test-it-*` target that runs the test.

Ant passes an undefined property through as the literal string `${name}`. Read properties the way
this package already does, so that an empty or blank value and that literal are treated as unset:

```java
String raw = System.getProperty("lb.it.table");
tableName = isEffectivePropertyValue(raw) ? raw.trim() : DEFAULT_TABLE;
```

`Boolean.getBoolean` and `Long.getLong` already fall back on their own for a non-boolean /
non-numeric string, so the gates and `lb.it.fixture.waitMs` need no extra guard.
