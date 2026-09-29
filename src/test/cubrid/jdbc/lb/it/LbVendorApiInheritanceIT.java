/*
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors
 *   may be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package cubrid.jdbc.lb.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.driver.CUBRIDDatabaseMetaData;
import cubrid.jdbc.driver.CUBRIDPreparedStatement;
import cubrid.jdbc.driver.CUBRIDResultSet;
import cubrid.jdbc.driver.CUBRIDResultSetMetaData;
import cubrid.jdbc.driver.CUBRIDStatement;
import cubrid.jdbc.jci.CUBRIDCommandType;
import cubrid.jdbc.jci.UConnection;
import cubrid.jdbc.lb.LoadBalanceConnection;
import cubrid.jdbc.lb.SessionLeg;
import cubrid.jdbc.lb.config.Endpoint;
import cubrid.jdbc.lb.metrics.RuntimeMetrics;
import cubrid.sql.CUBRIDOID;
import cubrid.sql.CUBRIDOIDImpl;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Live check that an application which was never changed for load balancing can cast what the
 * driver returns to {@link CUBRIDConnection} and drive the CUBRID extension API through it:
 *
 * <pre>
 *   CUBRIDConnection c = (CUBRIDConnection) conn;
 *   c.setLockTimeout(1000);
 * </pre>
 *
 * <p>What this adds over the unit tests: those drive the physical legs through {@link
 * java.lang.reflect.Proxy} doubles, so they prove the routing but never speak the CAS protocol.
 * Here the calls go to real brokers, so a vendor call the doubles accept but a live CAS rejects
 * shows up. Leg-by-leg attribution stays the unit tests' job; this asks whether the calls work at
 * all against a live cluster, and whether the session is still usable afterwards.
 */
public final class LbVendorApiInheritanceIT {

    private static final String DEFAULT_BASE_URL = "jdbc:cubrid:localhost:30000:tdb:public::";
    private static final String DEFAULT_USER = "dba";

    private static String lbJdbcUrl;
    private static String jdbcUser;
    private static String jdbcPassword;

    private Connection connection;

    @BeforeClass
    public static void setUpClass() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("lb.it.enabled"));
        Class.forName("cubrid.jdbc.driver.CUBRIDDriver");

        lbJdbcUrl = resolveLbJdbcUrl();
        jdbcUser = System.getProperty("lb.it.jdbc.user", DEFAULT_USER);
        jdbcPassword = System.getProperty("lb.it.jdbc.password", "");
    }

    @Before
    public void openConnection() throws Exception {
        Properties info = new Properties();
        info.setProperty("user", jdbcUser);
        info.setProperty("password", jdbcPassword);
        connection = DriverManager.getConnection(lbJdbcUrl, info);
    }

    @After
    public void closeConnection() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void driverReturnsSomethingCastableToCubridConnection() throws Exception {
        CUBRIDConnection c = (CUBRIDConnection) connection;
        assertNotNull(c);
        assertTrue(
                "the driver must still return the load-balance connection",
                connection instanceof LoadBalanceConnection);
        assertSame(connection, connection.unwrap(CUBRIDConnection.class));
    }

    @Test
    public void everythingReportsTheSameConnectionObject() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            assertSame(connection, stmt.getConnection());
            assertNotNull((CUBRIDConnection) stmt.getConnection());

            DatabaseMetaData metaData = connection.getMetaData();
            assertSame(connection, metaData.getConnection());
        } finally {
            stmt.close();
        }
    }

    @Test
    public void lockTimeoutReachesLiveBrokers() throws Exception {
        CUBRIDConnection c = (CUBRIDConnection) connection;

        c.setLockTimeout(1000);
        assertSessionStillUsable();
    }

    @Test
    public void charsetReachesLiveBrokers() throws Exception {
        CUBRIDConnection c = (CUBRIDConnection) connection;

        c.setCharset("utf-8");
        assertSessionStillUsable();
    }

    /**
     * The write leg's answer is the one returned, so setting the mode twice must report back what
     * the first call installed. That round trip is what proves the call reached a live CAS.
     */
    @Test
    public void casChangeModeRoundTripsThroughTheWriteLeg() throws Exception {
        CUBRIDConnection c = (CUBRIDConnection) connection;

        c.setCASChangeMode(CUBRIDConnection.CAS_CHANGE_MODE_KEEP);
        int previous = c.setCASChangeMode(CUBRIDConnection.CAS_CHANGE_MODE_AUTO);

        assertEquals(
                "the second call must report the mode the first one installed",
                CUBRIDConnection.CAS_CHANGE_MODE_KEEP,
                previous);
        assertSessionStillUsable();
    }

    /**
     * {@code CUBRIDConnection} exposes one socket and this session has two: it must be the write
     * leg.
     */
    @Test
    public void getUConnectionResolvesToTheWriteLegSocket() throws Exception {
        CUBRIDConnection c = (CUBRIDConnection) connection;
        UConnection physical = c.getUConnection();
        assertNotNull("the write leg's socket must be reported", physical);

        Endpoint writeLeg = ((LoadBalanceConnection) connection).getCurrentEp(SessionLeg.RW);
        assertEquals(
                "reported socket must be the bound write leg",
                writeLeg.getPort(),
                physical.getCasPort());
    }

    /**
     * {@code CUBRIDConnection.toString()} builds its text by dereferencing {@code u_con} four
     * times, which is null here, so leaving it inherited is a guaranteed NullPointerException on a
     * method loggers and debuggers reach by themselves.
     */
    @Test
    public void toStringIsSafeAndNamesBothLegs() throws Exception {
        String text = connection.toString();

        assertNotNull(text);
        assertTrue("must name the write leg: " + text, text.indexOf("rw=") >= 0);
        assertTrue("must name the read leg: " + text, text.indexOf("read=") >= 0);
        assertTrue("must not report an unbound session: " + text, text.indexOf("unbound") < 0);
    }

    @Test
    public void shardApiIsRefusedByName() throws Exception {
        CUBRIDConnection c = (CUBRIDConnection) connection;

        try {
            c.getShardMetaData();
            fail("SHARD metadata must be refused on a load-balance connection");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().indexOf("getShardMetaData()") >= 0);
        }

        try {
            c.isShard();
            fail("isShard() must be refused on a load-balance connection");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().indexOf("isShard()") >= 0);
        }

        assertSessionStillUsable();
    }

    /**
     * The statement half of the same contract. Run against live brokers because the failure mode
     * the unit guard cannot reach is real: an inherited vendor body dereferencing the {@code
     * u_stmt} a logical statement does not own throws only when it actually runs.
     */
    @Test
    public void createStatementReturnsSomethingCastableToCubridStatement() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            CUBRIDStatement vendor = (CUBRIDStatement) stmt;
            assertNotNull(vendor);
            assertSame(stmt, stmt.unwrap(CUBRIDStatement.class));

            ResultSet rs = vendor.executeQuery("SELECT 1 FROM db_root");
            try {
                assertTrue(rs.next());
                // While the result set is open. Closing it runs CUBRIDStatement.complete(), which
                // nulls u_stmt, after which the type reads UNKNOWN. A classic (non-LB) connection
                // to the same broker gives the same two values, and matching it is the contract.
                assertEquals(CUBRIDCommandType.CUBRID_STMT_SELECT, vendor.getStatementType());
            } finally {
                rs.close();
            }
            assertEquals(CUBRIDCommandType.CUBRID_STMT_UNKNOWN, vendor.getStatementType());
            assertNotNull(vendor.getQueryplan("SELECT 1 FROM db_root"));
            assertSame(connection, vendor.getConnection());
        } finally {
            stmt.close();
        }
    }

    @Test
    public void prepareStatementReturnsSomethingCastableToCubridPreparedStatement()
            throws Exception {
        PreparedStatement ps = connection.prepareStatement("SELECT ? FROM db_root");
        try {
            CUBRIDPreparedStatement vendor = (CUBRIDPreparedStatement) ps;
            CUBRIDStatement alsoAStatement = (CUBRIDStatement) ps;
            assertNotNull(vendor);
            assertNotNull(alsoAStatement);
            assertSame(ps, ps.unwrap(CUBRIDPreparedStatement.class));

            vendor.setInt(1, 7);
            ResultSet rs = vendor.executeQuery();
            try {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            } finally {
                rs.close();
            }
            assertTrue(vendor.hasResultSet());
            assertNotNull(vendor.getMetaData());
            assertSame(connection, alsoAStatement.getConnection());
        } finally {
            ps.close();
        }
    }

    /**
     * The {@code Statement}-level API on a prepared statement is served by the delegate rather than
     * by inheritance. A forwarder left out would silently answer from the vendor field default, so
     * the values are read back rather than only set.
     */
    @Test
    public void statementLevelApiOnPreparedStatementReachesTheLiveBroker() throws Exception {
        PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM db_root");
        try {
            ps.setMaxRows(1);
            ps.setQueryTimeout(30);
            ps.setFetchSize(2);

            assertEquals(1, ps.getMaxRows());
            assertEquals(30, ps.getQueryTimeout());
            assertEquals(2, ps.getFetchSize());

            ResultSet rs = ps.executeQuery();
            try {
                assertTrue(rs.next());
            } finally {
                rs.close();
            }
            assertNull(ps.getWarnings());
        } finally {
            ps.close();
        }
    }

    /** SHARD is refused on statements for the same reason it is refused on the connection. */
    @Test
    public void statementShardApiIsRefusedByName() throws Exception {
        Statement stmt = connection.createStatement();
        PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM db_root");
        try {
            try {
                ((CUBRIDStatement) stmt).getShardId();
                fail("getShardId() must be refused on a load-balance statement");
            } catch (RuntimeException expected) {
                assertTrue(expected.getMessage().indexOf("getShardId()") >= 0);
            }

            try {
                ((CUBRIDPreparedStatement) ps).getShardId();
                fail("getShardId() must be refused on a load-balance prepared statement");
            } catch (RuntimeException expected) {
                assertTrue(expected.getMessage().indexOf("getShardId()") >= 0);
            }
        } finally {
            ps.close();
            stmt.close();
        }

        assertSessionStillUsable();
    }

    /**
     * Connection-level metadata used to be a dynamic proxy, which implements interfaces only, so
     * this cast raised ClassCastException. It is a real subclass now. Run live because a missing
     * forwarder fails as an inherited body reading a null {@code u_con}, which shows only when that
     * metadata call runs against a broker.
     */
    @Test
    public void getMetaDataReturnsSomethingCastableToCubridDatabaseMetaData() throws Exception {
        DatabaseMetaData md = connection.getMetaData();

        CUBRIDDatabaseMetaData vendor = (CUBRIDDatabaseMetaData) md;
        assertNotNull(vendor);
        assertSame(md, md.unwrap(CUBRIDDatabaseMetaData.class));

        // JDBC: the metadata must report the connection that produced it — the logical LB
        // connection, not the physical write endpoint.
        assertSame(connection, md.getConnection());

        assertNotNull(md.getDatabaseProductName());
        assertNotNull(md.getDriverName());
        assertNotNull(md.getUserName());
        assertTrue(md.getDriverMajorVersion() >= 0);

        ResultSet tables = md.getTables(null, null, "%", new String[] {"TABLE"});
        try {
            assertNotNull(tables);
        } finally {
            tables.close();
        }

        assertSessionStillUsable();
    }

    /** SHARD is refused on the metadata, as it is on the connection and on statements. */
    @Test
    public void metaDataShardApiIsRefusedByName() throws Exception {
        CUBRIDDatabaseMetaData md = (CUBRIDDatabaseMetaData) connection.getMetaData();

        try {
            md.getShardId();
            fail("SHARD metadata id must be refused on a load-balance connection");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().indexOf("getShardId()") >= 0);
        }

        try {
            md.getShardDBName();
            fail("SHARD metadata db name must be refused on a load-balance connection");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().indexOf("getShardDBName()") >= 0);
        }

        assertSessionStillUsable();
    }

    /**
     * {@code CUBRIDOIDImpl.getNewInstance(CUBRIDConnection, String)} is the vendor way to rebuild
     * an OID from its string form. It takes {@code CUBRIDConnection} by declaration, so on a
     * load-balance URL it used to be unreachable: the argument could not be produced. It is
     * reachable now, and the OID it returns holds the logical connection, so every OID operation
     * resolves the write endpoint per call and {@code oid.getConnection()} reports the LB
     * connection. A classic connection to the same broker gives identical results on every step,
     * error paths included.
     *
     * <p>Two limits are deliberate. OID operations run on the write endpoint and outside {@code
     * ExecuteFailoverHandler}, so they neither distribute nor fail over. And an OID taken from a
     * physical {@code ResultSet} instead of this factory carries the physical connection.
     */
    @Test
    public void vendorOidFactoryAcceptsTheLoadBalanceConnection() throws Exception {
        String table = "lb_vendor_oid_it";
        Statement stmt = connection.createStatement();
        try {
            dropQuietly(stmt, table);
            // DONT_REUSE_OID: a REUSE_OID class is non-referable and its instances' OIDs cannot be
            // returned at all, on any connection.
            stmt.executeUpdate(
                    "CREATE TABLE " + table + " (id INT, name VARCHAR(20)) DONT_REUSE_OID");
            stmt.executeUpdate("INSERT INTO " + table + " VALUES (1, 'alpha')");

            String oidString;
            // TO_RW: the row was just written, so a read endpoint may not have it yet.
            ResultSet rs = stmt.executeQuery("SELECT /*+ TO_RW */ " + table + " FROM " + table);
            try {
                assertTrue(rs.next());
                CUBRIDOID fromResultSet = ((CUBRIDResultSet) rs).getOID(1);
                oidString = fromResultSet.getOidString();

                // An OID read from a result set is built deep in JCI from the socket's registered
                // owner, not by LB. It reported a physical leg until this session started claiming
                // ownership of the value objects its legs produce, and a physical connection handed
                // out that way can be committed or closed behind the session's back.
                assertSame(
                        "an OID from a result set must report the logical connection",
                        connection,
                        fromResultSet.getConnection());
            } finally {
                rs.close();
            }
            assertNotNull(oidString);

            CUBRIDOID oid = CUBRIDOIDImpl.getNewInstance((CUBRIDConnection) connection, oidString);

            assertSame(connection, oid.getConnection());
            assertEquals(oidString, oid.getOidString());
            assertTrue(oid.getTableName().toLowerCase().indexOf(table) >= 0);

            ResultSet values = oid.getValues(new String[] {"id", "name"});
            try {
                assertTrue(values.next());
                assertEquals(1, values.getInt(1));
                assertEquals("alpha", values.getString(2));
            } finally {
                values.close();
            }

            // An unknown attribute must be a SQLException, not a NullPointerException from a null
            // u_con on the logical connection.
            try {
                oid.getValues(new String[] {"no_such_column"});
                fail("an unknown OID attribute must be refused");
            } catch (SQLException expected) {
                assertNotNull(expected.getMessage());
            }
        } finally {
            dropQuietly(stmt, table);
            stmt.close();
        }

        assertSessionStillUsable();
    }

    private static void dropQuietly(final Statement stmt, final String table) {
        try {
            stmt.executeUpdate("DROP TABLE " + table);
        } catch (SQLException ignored) {
        }
    }

    /**
     * LB hands out the physical {@code ResultSet}, so {@code rs.getStatement()} would name the
     * physical statement and, through it, a physical connection. Committing or closing that way
     * reaches one leg only, silently. The physical result set is made to report the logical
     * statement instead.
     *
     * <p>Run live because the redirect is set on the real driver object; an offline double cannot
     * show that the result set the broker produced carries it.
     */
    @Test
    public void resultSetsFromEveryExecutePathReportTheLogicalOwners() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM db_root");
            try {
                assertTrue(rs.next());
                assertSame(
                        "rs.getStatement() must be the statement the application called",
                        stmt,
                        rs.getStatement());
                assertSame(
                        "and through it, the logical connection — not a physical leg",
                        connection,
                        rs.getStatement().getConnection());
            } finally {
                rs.close();
            }
            // getResultSet() reaches the physical statement directly on some paths; it must stamp
            // too.
            assertTrue(stmt.execute("SELECT 1 FROM db_root"));
            ResultSet again = stmt.getResultSet();
            try {
                assertSame(stmt, again.getStatement());
            } finally {
                again.close();
            }
        } finally {
            stmt.close();
        }

        PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM db_root");
        try {
            ResultSet rs = ps.executeQuery();
            try {
                assertTrue(rs.next());
                // The prepared statement itself, not its LBStatement delegate.
                assertSame(ps, rs.getStatement());
                assertSame(connection, rs.getStatement().getConnection());
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }

        assertSessionStillUsable();
    }

    /**
     * LB hands out the physical {@code ResultSet}, so {@code rs.getStatement()} would name the
     * physical statement, and {@code rs.getStatement().getConnection()} a physical leg. An
     * application reaching back through that path and committing would commit one leg only, with no
     * exception anywhere. Run live because the physical statement and connection only exist against
     * real brokers.
     */
    @Test
    public void resultSetIsCastableToCubridResultSetAndNamesTheLogicalOwners() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM db_root");
            try {
                assertTrue(rs.next());

                assertSame(
                        "rs.getStatement() must be the statement the application called",
                        stmt,
                        rs.getStatement());
                assertSame(
                        "and through it, the logical connection — not a physical leg",
                        connection,
                        rs.getStatement().getConnection());

                // The physical object is still handed out unwrapped: the vendor cast an existing
                // application performs must keep working.
                assertNotNull((CUBRIDResultSet) rs);
                assertNotNull((CUBRIDResultSetMetaData) rs.getMetaData());
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
    }

    /** Same contract for a prepared statement, which must name itself and not its delegate. */
    @Test
    public void preparedResultSetNamesThePreparedStatement() throws Exception {
        PreparedStatement ps = connection.prepareStatement("SELECT ? FROM db_root");
        try {
            ps.setInt(1, 7);
            ResultSet rs = ps.executeQuery();
            try {
                assertTrue(rs.next());

                assertSame(ps, rs.getStatement());
                assertSame(connection, rs.getStatement().getConnection());
                assertSame(ps, ps.getResultSet().getStatement());
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    /**
     * The path that used to corrupt the session: reach the connection through the result set and
     * change transaction state on it. It must land on the logical connection, so the change is
     * visible to the session rather than to one leg.
     */
    @Test
    public void transactionStateChangedThroughTheResultSetReachesTheSession() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM db_root");
            Connection reached;
            try {
                assertTrue(rs.next());
                reached = rs.getStatement().getConnection();
            } finally {
                rs.close();
            }

            assertSame(connection, reached);
            assertTrue(reached.getAutoCommit());

            reached.setAutoCommit(false);
            try {
                assertFalse(
                        "the logical session must observe the change", connection.getAutoCommit());
                reached.commit();
            } finally {
                reached.setAutoCommit(true);
            }
        } finally {
            stmt.close();
        }

        assertSessionStillUsable();
    }

    /**
     * {@code prepareCall} hands out the physical {@code CallableStatement} unwrapped, so the OUT
     * parameter API and the vendor casts on it keep working. The cost is that {@code
     * getConnection()} on it would be the physical leg, and an application walking back through it
     * could commit or close that leg while the session knew nothing. LB stamps such statements to
     * report this session instead.
     *
     * <p>Driven through a physical statement rather than {@code prepareCall}: a {@code CALL} needs
     * a stored procedure this suite cannot deploy. What is verified is the mechanism the {@code
     * prepareCall} path uses - the redirect takes effect on a real statement, is reversible, and
     * does not move the statement's execution off its socket.
     */
    @Test
    public void physicalStatementCanBeMadeToReportTheLogicalConnection() throws Exception {
        Connection physical =
                DriverManager.getConnection(resolvePhysicalUrl(), jdbcUser, jdbcPassword);
        try {
            CUBRIDStatement physicalStmt = (CUBRIDStatement) physical.createStatement();
            try {
                assertSame(physical, physicalStmt.getConnection());

                physicalStmt.setReportedConnection(connection);
                assertSame(
                        "a statement handed out unwrapped must report the logical connection",
                        connection,
                        physicalStmt.getConnection());

                // The redirect must not move execution: the statement still runs on its own socket.
                ResultSet rs = physicalStmt.executeQuery("SELECT 1 FROM db_root");
                try {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                } finally {
                    rs.close();
                }

                physicalStmt.setReportedConnection(null);
                assertSame(
                        "clearing the redirect restores the physical owner",
                        physical,
                        physicalStmt.getConnection());
            } finally {
                physicalStmt.close();
            }
        } finally {
            physical.close();
        }
    }

    /**
     * A logical statement already reports the logical connection, so the redirect is meaningless on
     * one and is refused rather than silently accepted — accepting it would let a caller make a
     * statement claim an owner it does not have.
     */
    @Test
    public void logicalStatementRefusesTheOwnerRedirect() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            ((CUBRIDStatement) stmt).setReportedConnection(connection);
            fail("a logical statement must refuse the owner redirect");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().indexOf("setReportedConnection(Connection)") >= 0);
        } finally {
            stmt.close();
        }

        assertSessionStillUsable();
    }

    /**
     * A user-defined session variable lives on the one physical connection that ran the {@code
     * SET}. A read endpoint does not have it and does not answer NULL: the server fails with {@code
     * Session variable '@name' not defined}. So setting a variable and reading it back, which works
     * on a classic URL, used to break on a loadbalance URL as soon as the read was routed to a read
     * leg. The classifier now treats a {@code @} reference as session-dependent and sends the read
     * to the write endpoint, where the {@code SET} went.
     *
     * <p>Live, because that is the only place the failure exists: offline the classification can be
     * asserted, but not that the value comes back from the endpoint the routing chose.
     *
     * <p>Not fixed by this: the variable is physical-connection state, so a failover that rebinds
     * the write leg loses it.
     */
    @Test
    public void sessionVariableSurvivesTheRoundTripWithoutAHint() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            stmt.execute("SET @lb_it_var = 'set-on-the-write-leg'");

            // No TO_RW hint: the classifier must route this read to the write endpoint on its own.
            ResultSet rs = stmt.executeQuery("SELECT @lb_it_var");
            try {
                assertTrue(rs.next());
                assertEquals("set-on-the-write-leg", rs.getString(1));
            } finally {
                rs.close();
            }

            // Also when the variable is one term among others.
            ResultSet mixed = stmt.executeQuery("SELECT @lb_it_var, 1 FROM db_root");
            try {
                assertTrue(mixed.next());
                assertEquals("set-on-the-write-leg", mixed.getString(1));
            } finally {
                mixed.close();
            }
        } finally {
            stmt.close();
        }
    }

    /**
     * The counterpart: an {@code @} that is data must not cost the statement its read distribution.
     * A SELECT carrying an email address stays a read, so it is still served by a read endpoint.
     */
    @Test
    public void anAtSignInsideALiteralKeepsTheStatementOnAReadEndpoint() throws Exception {
        // Routing events are off by default; this test asserts on them, so turn them on for this
        // connection. @Before opens a fresh one per test, so nothing else sees the change.
        ((LoadBalanceConnection) connection).getRuntimeMetrics().setEnabled(true);

        Statement stmt = connection.createStatement();
        try {
            ResultSet rs =
                    stmt.executeQuery(
                            "SELECT 1 FROM db_root WHERE 'user@example.com' = 'user@example.com'");
            try {
                assertTrue(rs.next());
            } finally {
                rs.close();
            }

            String event =
                    findLastRoutingEvent(
                            ((LoadBalanceConnection) connection).getRuntimeMetrics(), "sqlType=");
            assertTrue(
                    "a literal @ must not promote the statement to the write endpoint: " + event,
                    event.indexOf("sqlType=READ") >= 0);
        } finally {
            stmt.close();
        }
    }

    private static String findLastRoutingEvent(final RuntimeMetrics metrics, final String needle) {
        java.util.List<String> events = metrics.getEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).indexOf(needle) >= 0) {
                return events.get(i);
            }
        }

        return "(no routing event recorded)";
    }

    private void assertSessionStillUsable() throws Exception {
        Statement stmt = connection.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT 1 FROM db_root");
            try {
                assertTrue("the session must still serve queries", rs.next());
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
    }

    /**
     * A classic URL for the write endpoint this session is bound to, derived from the live
     * connection rather than from a separate property, so it always names the same broker and
     * database the LB URL does.
     */
    private String resolvePhysicalUrl() throws SQLException {
        LoadBalanceConnection lb = (LoadBalanceConnection) connection;
        Endpoint writeEndpoint = lb.getCurrentRwEndpoint();
        assertNotNull("the session must be bound before deriving a physical URL", writeEndpoint);

        return "jdbc:cubrid:"
                + writeEndpoint.getHost()
                + ":"
                + writeEndpoint.getPort()
                + ":"
                + lb.getLbConfig().getDatabaseName()
                + ":::";
    }

    private static String resolveLbJdbcUrl() {
        String directUrl = System.getProperty("lb.it.jdbc.url");
        if (isEffectivePropertyValue(directUrl)) {
            return directUrl.trim();
        }

        String baseUrlRaw = System.getProperty("lb.it.jdbc.baseUrl");
        return isEffectivePropertyValue(baseUrlRaw) ? baseUrlRaw.trim() : DEFAULT_BASE_URL;
    }

    private static boolean isEffectivePropertyValue(final String value) {
        return value != null && value.trim().length() > 0 && value.indexOf("${") < 0;
    }
}
