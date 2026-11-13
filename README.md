## CUBRID Database Management System
CUBRID is a DBMS being supported by an active community of open source developers 
and provides better performance and features necessary for Web services. 

This Software is released under Apache License 2.0 and BSD according to CUBRID components.
For brevity, CUBRID Server Engine is under Apache License 2.0 and CUBRID APIs and Connectors are under BSD License.
For details, please refer to the CUBRID License Page(http://www.cubrid.org/cubrid).

Below You will see the brief list of sections to guide You to easily get started. 

## MAJOR REFERENCES
- CUBRID Official Site: http://www.cubrid.org ,  http://www.cubrid.com
- CUBRID Development Site(Global): http://jira.cubrid.org
- CUBRID Manuals: http://www.cubrid.org/manuals 
- CUBRID JDBC Manuals (V11.3) : https://www.cubrid.org/manual/en/11.3/api/jdbc.html (Eng)
  https://www.cubrid.org/manual/ko/11.3/api/jdbc.html (Kor)

## DOWNLOADS and FILE REPOSITORIES
- http://www.cubrid.org/downloads
- http://ftp.cubrid.org

## HOW TO BUILD CUBRID JDBC Driver
### CUBRID JDBC Driver Source Build Guide (ANT Build Project)
- Windows
  ```
  build.bat 
  ```
    
- Linux

  ```
  ./build.sh 
  ```

### CUBRID JDBC Driver 
- implements an interface to enable access 
  from applications in Java to CUBRID database server. 
  The driver has been developed based on the JDBC 2.0 specification 
  and the default driver provided is complied with JDK 1.8.

### Load-balanced reads (`loadbalance://`)
The driver can spread reads over the brokers of a CUBRID HA cluster by URL alone; application
code does not change. Writes and every statement of an open transaction go to the master's
read-write broker; reads go to the brokers chosen by `readWeight`.

- URL forms
  ```
  # classic (unchanged)
  jdbc:cubrid:<host>:<port>:<db>:<user>:<password>:[?options]

  # single node, URI form
  jdbc:cubrid://<host>[:<port>]/<db>:<user>:<password>:[?options]

  # load-balanced: the first host is the master, the others slaves; replicas after ;replica=
  jdbc:cubrid:loadbalance://node1,node2;replica=rep1/<db>:<user>:<password>:?rwPort=33000&roPort=33002&soPort=33004&readWeight=slave:2,master:1,replica:1
  ```
  As in the classic form, `<db>:<user>:<password>:` is always written in full; the user and
  password may be left empty (`<db>:::`) and passed to `getConnection(url, user, password)`.
- Main options (load-balanced URLs only)

  | option | meaning |
  |---|---|
  | `rwPort` / `roPort` / `soPort` | read-write, read-only and replica broker ports when a host does not give them inline (`node1:33000:33002`) |
  | `readWeight` | share of read sessions per role, `slave:n,master:n,replica:n` (0 to 10000; default 1 for every role present) |
  | `metricsEnabled`, `metricsExport` (`prometheus`, `csv`), `metricsPrometheusPort`, `metricsPrometheusBind`, `metricsIntervalSec` | metrics export, off by default; `/metrics` listens on loopback unless bound elsewhere |
  | `lbLogSql` (`off`, `masked`, `raw`), `lbLogSqlMaxLen`, `lbLogDistIntervalSec` | what the diagnostic log records |

  The same keys may also be given as connection properties, which win over the URL as for any
  other driver option. `altHosts`, `loadBalance` and `useLazyConnection` do not apply and are
  ignored with a warning.
- Hints: `/*+ TO_RW */` (alias `TO_MASTER`) sends that one statement to the master,
  `/*+ TO_RO */` (alias `TO_SLAVE`) to the session's read broker. Only the first `/*+ ... */`
  right after `SELECT` is read as a load-balance hint.
- Logging: the logger is `cubrid.jdbc.lb`, configured in `logging.properties`, for example
  ```
  cubrid.jdbc.lb.level = WARNING
  cubrid.jdbc.lb.handlers = cubrid.jdbc.lb.log.LbFileHandler
  cubrid.jdbc.lb.useParentHandlers = false
  cubrid.jdbc.lb.log.LbFileHandler.pattern = /var/log/app/cubrid_lb.%g.log
  ```
- Constraints
  - Every table read through a load-balanced URL needs a primary key: CUBRID HA replicates rows
    only through the primary key index, so a slave or replica returns no row of a table without
    one. Read such a table with `/*+ TO_RW */`.
  - Not available with `CUBRIDConnectionPoolDataSource` / the built-in pool, XA data sources or
    CUBRID SHARD.

GETTING HELP
============
If You encounter any difficulties with getting started, or just have some
questions, or find bugs, or have some suggestions, we kindly ask You to 
post your thoughts on CUBRID Forum at http://forum.cubrid.org.

Sincerely,
Your CUBRID Development Team.
