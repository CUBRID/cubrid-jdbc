# Load-balance integration tests

These tests drive a **real CUBRID cluster** over real brokers. They are not part of `ant test`:
that target discovers `**/*Test.class`, and everything here is named `*IT`, so a normal build
compiles these files but never runs them. Compiling them is deliberate — it keeps the live suite
from drifting away from the production API between cluster runs.

Run them with the `test-it-*` targets described below.

## They skip silently without `lb.it.enabled`

Every IT gates itself on `Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"))`, so a run
without that property passes without touching anything:

```
$ ant test-it-vendor-api
     [java] OK (0 tests)
BUILD SUCCESSFUL
```

**A green build is not evidence that the cluster was exercised.** Worse, the reported count is not
a reliable signal either, because the gate sits in two different places:

| Gate in | Classes | Reports when skipped |
|---|---|---|
| `@BeforeClass` | `LbRoutingDecisionIT`, `LbDatabaseMetaDataIT`, `LbStatementApiIT`, `LbPreparedStatementApiIT`, `LbVendorApiInheritanceIT` | `OK (0 tests)` — the whole class is aborted |
| the `@Test` body | `LbUriUrlIT` | `OK (4 tests)` — **the same count as a real run** |

So `ant test-it-uri-url` prints `OK (4 tests)` whether or not a broker was ever contacted. For
that one, confirm from the cluster side — the fixture table — rather than from the JUnit summary.

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

A two-node master/slave cluster is enough for most targets. `replica=` and a `readWeight` that
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

A value that is empty, blank, or a literal unresolved Ant placeholder (`${lb.it.jdbc.url}`) counts
as unset — that last case is why passing only some of the properties still works.

## Targets

One target per class, plus `test-it-all` to run the lot:

| Target | Class |
|---|---|
| `test-it-routing` | `LbRoutingDecisionIT` |
| `test-it-metadata` | `LbDatabaseMetaDataIT` |
| `test-it-statement` | `LbStatementApiIT` |
| `test-it-prepared` | `LbPreparedStatementApiIT` |
| `test-it-uri-url` | `LbUriUrlIT` |
| `test-it-vendor-api` | `LbVendorApiInheritanceIT` |
| `test-it-all` | all of the above |

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
ant test-it-all \
  -Dlb.it.enabled=true \
  -Dlb.it.jdbc.url='jdbc:cubrid:loadbalance://192.168.2.197:30000:33000,192.168.2.196:30000:33000/tdb?readWeight=master:50,slave:50' \
  -Dlb.it.jdbc.user=dba \
  -Dlb.it.jdbc.password= \
  -Dlb.it.table=lb_scenario_it
```

With a replica rung:

```sh
ant test-it-all \
  -Dlb.it.enabled=true \
  -Dlb.it.jdbc.url='jdbc:cubrid:loadbalance://192.168.2.197:30000:33000,192.168.2.196:30000:33000;replica=192.168.2.195:36000/tdb?readWeight=master:33,slave:33,replica:34' \
  -Dlb.it.jdbc.user=dba -Dlb.it.jdbc.password= -Dlb.it.table=lb_scenario_it
```

## Adding a property

`<java fork="yes">` starts a new JVM, so a `-D` given to `ant` reaches the tests only if that
target lists the key in a `<sysproperty>` element. A property read by a test but missing from the
target is silently ignored — the test sees the class default instead, and nothing reports that the
value was dropped. When you add a property to a test, add it to every `test-it-*` target that runs
that test.

Ant passes an undefined property through as the literal string `${name}`. Read properties the way
this package already does, so that literal is treated as unset:

```java
String raw = System.getProperty("lb.it.table");
tableName = isEffectivePropertyValue(raw) ? raw.trim() : DEFAULT_TABLE;
```

`Boolean.getBoolean` and `Long.getLong` already fall back on their own for a non-boolean /
non-numeric string, so the gates and `lb.it.fixture.waitMs` need no extra guard.
