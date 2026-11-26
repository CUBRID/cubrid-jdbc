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

package cubrid.jdbc.lb.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import cubrid.jdbc.driver.ConnectionProperties;
import java.sql.SQLException;
import org.junit.Test;

public class LoadBalanceSettingsFromUrlTest {

    private static LoadBalanceSettings configOf(String url) throws SQLException {
        return LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url));
    }

    @Test
    public void mapsRolesPortsAndExplicitReadWeight() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2,node3;replica=rep1/testdb:dba:secret:"
                                + "?rwPort=33000&roPort=33002&soPort=33004"
                                + "&readWeight=slave:40,master:10,replica:50");

        // roles + ports
        ResolvedRoleTopology t = c.getResolvedTopology();
        assertEquals("node1:33000", t.getMaster().getRw().getId());
        assertEquals("node1:33002", t.getMaster().getRo().getId());
        assertEquals(2, t.getSlaves().size());
        assertEquals("node2:33000", t.getSlaves().get(0).getRw().getId());
        assertEquals("node3:33002", t.getSlaves().get(1).getRo().getId());
        assertEquals("rep1:33004", t.getReplicas().get(0).getSo().getId());

        // readWeight
        ReadWeight w = c.getReadWeight();
        assertEquals(40, w.weightOf(NodeRole.SLAVE));
        assertEquals(10, w.weightOf(NodeRole.MASTER));
        assertEquals(50, w.weightOf(NodeRole.REPLICA));

        // credentials + db
        assertEquals("testdb", c.getDatabaseName());
        assertEquals("dba", c.getUrlUser());
        assertEquals("secret", c.getUrlPassword());
        assertEquals("", c.getUrlVariant());
    }

    /** An LB option in Properties configures LB as one in the URL does, and wins, as in core. */
    @Test
    public void lbOptionsInPropertiesWinOverTheUrl() throws SQLException {
        java.util.Properties info = new java.util.Properties();
        info.setProperty("readWeight", "master:3,slave:1");
        LoadBalanceSettings c =
                LoadBalanceSettings.fromUrl(
                        LoadBalanceUrlParser.parse(
                                "jdbc:cubrid:loadbalance://node1,node2/testdb:::?readWeight=slave:1"),
                        info);

        assertEquals(3, c.getReadWeight().weightOf(NodeRole.MASTER));
        assertEquals(1, c.getReadWeight().weightOf(NodeRole.SLAVE));
    }

    /**
     * Like a broker port in the URL, the exporter port must be 1-65535: refused as SQLException.
     */
    @Test
    public void prometheusPortOutsideThePortRangeIsRefused() {
        for (String port : new String[] {"0", "70000", "x"}) {
            try {
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2/testdb:::?metricsEnabled=true"
                                + "&metricsExport=prometheus&metricsPrometheusPort="
                                + port);
                fail("port " + port + " must be refused");
            } catch (SQLException expected) {
                assertTrue(
                        expected.getMessage(),
                        expected.getMessage().contains("metricsPrometheusPort"));
            }
        }
    }

    /**
     * m35: a metrics value is checked as core checks its properties - even with metrics off (core
     * refuses {@code logSlowQueries=false&slowQueryThresholdMillis=-1}) and with no trimming.
     */
    @Test
    public void metricsValuesAreCheckedAsCoreChecksItsProperties() throws SQLException {
        final String url = "jdbc:cubrid:loadbalance://node1,node2/testdb:::?";
        final String[][] refused = {
            {"metricsPrometheusPort=70000", "metricsPrometheusPort"},
            {
                "metricsEnabled=true&metricsExport=csv&metricsPrometheusPort=0",
                "metricsPrometheusPort"
            },
            {"metricsPrometheusPort= 9400", "metricsPrometheusPort"},
            {"metricsIntervalSec=0", "metricsIntervalSec"},
            {"metricsEnabled=true&metricsIntervalSec=x", "metricsIntervalSec"},
            {"metricsEnabled=maybe", "metricsEnabled"},
            {"metricsEnabled=true&metricsExport=prometheus,promethues", "metricsExport"},
        };
        for (String[] each : refused) {
            try {
                configOf(url + each[0]);
                fail(each[0] + " must be refused");
            } catch (SQLException expected) {
                assertTrue(
                        expected.getMessage(),
                        expected.getMessage().contains("uncompitable value for the " + each[1]));
            }
        }

        // core takes on/yes and reads them as false: useSSL=on leaves SSL off
        assertFalse(
                configOf(url + "metricsEnabled=on&metricsExport=csv")
                        .getMetricsConfig()
                        .isEnabled());
        MetricsConfig m =
                configOf(url + "metricsEnabled=TRUE&metricsExport=CSV&metricsPrometheusPort=+9401")
                        .getMetricsConfig();
        assertTrue(m.isEnabled() && m.exportsCsv());
        assertEquals(9401, m.getPrometheusPort());
    }

    /**
     * The broker ports and the LB log options are checked as core checks its properties too: no
     * trimming, a bad value refuses the URL instead of a WARN and the default, {@code +n} is taken.
     */
    @Test
    public void portAndLogValuesAreCheckedAsCoreChecksItsProperties() throws SQLException {
        final String url = "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/testdb:::?";
        final String[][] refused = {
            {"rwPort= 33000", "rwPort"},
            {"roPort=33002 &rwPort=33000", "roPort"},
            {"soPort= 33004", "soPort"},
            {"lbLogSql=bogus", "lbLogSql"},
            {"lbLogSql= raw", "lbLogSql"},
            {"lbLogSqlMaxLen=-1", "lbLogSqlMaxLen"},
            {"lbLogSqlMaxLen= 5", "lbLogSqlMaxLen"},
            {"lbLogDistIntervalSec=x", "lbLogDistIntervalSec"},
            {"lbLogSqlMaxLen=5 ", "lbLogSqlMaxLen"},
        };
        for (String[] each : refused) {
            try {
                configOf(url + each[0]);
                fail(each[0] + " must be refused");
            } catch (SQLException expected) {
                assertTrue(
                        expected.getMessage(),
                        expected.getMessage().contains("uncompitable value for the " + each[1]));
            }
        }

        LoadBalanceSettings c =
                configOf(
                        url + "rwPort=+33000&lbLogSql=RAW&lbLogSqlMaxLen=0&lbLogDistIntervalSec=0");
        assertEquals("node1:33000", c.getResolvedTopology().getMaster().getRw().getId());
        assertTrue(c.getLbLogConfig().isSqlRaw());
        assertEquals(0, c.getLbLogConfig().getSqlMaxLen());

        for (String name :
                new String[] {"rwPort", "lbLogSql", "lbLogSqlMaxLen", "lbLogDistIntervalSec"}) {
            java.util.Properties info = new java.util.Properties();
            info.setProperty(name, "");
            try {
                LoadBalanceSettings.fromUrl(
                        LoadBalanceUrlParser.parse(url.substring(0, url.length() - 1)), info);
                fail("an empty " + name + " property must be refused");
            } catch (SQLException expected) {
                assertTrue(
                        expected.getMessage(),
                        expected.getMessage().contains("'' uncompitable value for the " + name));
            }
        }
    }

    /**
     * Core refuses an empty value given as a connection property for a boolean or integer property,
     * as its URL refuses {@code key=}. A text property takes it.
     */
    @Test
    public void emptyMetricsValueInPropertiesIsRefusedAsCoreRefusesIt() throws SQLException {
        final String url = "jdbc:cubrid:loadbalance://node1,node2/testdb:::";
        for (String name :
                new String[] {"metricsEnabled", "metricsPrometheusPort", "metricsIntervalSec"}) {
            java.util.Properties info = new java.util.Properties();
            info.setProperty(name, "");
            try {
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url), info);
                fail("an empty " + name + " property must be refused");
            } catch (SQLException expected) {
                assertTrue(
                        expected.getMessage(),
                        expected.getMessage().contains("'' uncompitable value for the " + name));
            }
        }

        java.util.Properties text = new java.util.Properties();
        text.setProperty("metricsExport", "");
        text.setProperty("metricsPrometheusBind", "");
        assertFalse(
                LoadBalanceSettings.fromUrl(LoadBalanceUrlParser.parse(url), text)
                        .getMetricsConfig()
                        .isEnabled());
    }

    /** LB keeps the statement cache, so it refuses a bad cache value as a core connect does. */
    @Test
    public void statementCacheValuesAreRefusedAsCoreRefusesThem() {
        for (String bad :
                new String[] {
                    "preparedStmtCacheSize=0",
                    "preparedStmtCacheSqlLimit=x",
                    "usePreparedStmtCache=maybe"
                }) {
            SQLException core = null;
            try {
                new ConnectionProperties().setProperties(bad);
            } catch (SQLException e) {
                core = e;
            }
            assertNotNull(bad, core);
            try {
                configOf("jdbc:cubrid:loadbalance://node1,node2/testdb:::?" + bad);
                fail(bad + " must be refused");
            } catch (SQLException lb) {
                assertEquals(bad, core.getErrorCode(), lb.getErrorCode());
                assertEquals(bad, core.getMessage(), lb.getMessage());
            }
        }
    }

    /** As core reads them: the URL, then the properties, and core's defaults for the rest. */
    @Test
    public void statementCacheOptionsInPropertiesWinOverTheUrl() throws SQLException {
        java.util.Properties info = new java.util.Properties();
        info.setProperty("preparedStmtCacheSize", "7");
        ConnectionProperties cache =
                LoadBalanceSettings.fromUrl(
                                LoadBalanceUrlParser.parse(
                                        "jdbc:cubrid:loadbalance://node1,node2/testdb:::"
                                                + "?usePreparedStmtCache=true&preparedStmtCacheSize=2"),
                                info)
                        .getStatementCacheOptions();

        assertTrue(cache.getPrepStmtCache());
        assertEquals(7, cache.getPrepStmtCacheSize());
        assertEquals(256, cache.getPrepStmtCacheSqlLimit());
        assertFalse(
                configOf("jdbc:cubrid:loadbalance://node1,node2/testdb:::")
                        .getStatementCacheOptions()
                        .getPrepStmtCache());
    }

    /**
     * As on a core connect, the {@code CUBRID_JDBC_PROP} file's options take the place of the URL's
     * for the legs and the statement cache, less those LB keeps off the legs; LB's own options
     * still come from the URL.
     */
    @Test
    public void propFileTakesThePlaceOfTheUrlOptions() throws SQLException {
        java.util.Properties file = new java.util.Properties();
        file.setProperty("altHosts", "node3:33000");
        file.setProperty("loadBalance", "true");
        file.setProperty("useLazyConnection", "true");
        file.setProperty("usePreparedStmtCache", "true");
        file.setProperty("connectTimeout", "5");
        LoadBalanceSettings c =
                LoadBalanceSettings.fromUrl(
                        LoadBalanceUrlParser.parse(
                                "jdbc:cubrid:loadbalance://node1,node2/testdb:::"
                                        + "?charSet=utf-8&readWeight=master:1"
                                        + "&preparedStmtCacheSize=3"),
                        null,
                        file);

        String leg = c.buildPhysicalJdbcSpec(null, new Endpoint("node1", 33000)).getJdbcUrl();
        assertTrue(leg, leg.endsWith("?connectTimeout=5"));
        assertTrue(c.getStatementCacheOptions().getPrepStmtCache());
        assertEquals(
                "the file replaces the URL's options, as in core",
                25,
                c.getStatementCacheOptions().getPrepStmtCacheSize());
        assertEquals(1, c.getReadWeight().weightOf(NodeRole.MASTER));
    }

    /** A file that only sets keys LB keeps off the legs is still a valid file. */
    @Test
    public void propFileWithOnlyClassicOnlyKeysLeavesTheLegsWithoutOptions() throws SQLException {
        java.util.Properties file = new java.util.Properties();
        file.setProperty("altHosts", "node3:33000");
        LoadBalanceSettings c =
                LoadBalanceSettings.fromUrl(
                        LoadBalanceUrlParser.parse(
                                "jdbc:cubrid:loadbalance://node1,node2/testdb:::?charSet=utf-8"),
                        null,
                        file);

        String leg = c.buildPhysicalJdbcSpec(null, new Endpoint("node1", 33000)).getJdbcUrl();
        assertFalse(leg, leg.contains("?"));
    }

    @Test
    public void appliesDefaultReadWeightWhenAbsent() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2;replica=rep1/testdb:::?rwPort=33000&roPort=33002&soPort=33004");

        ReadWeight w = c.getReadWeight();
        assertEquals(1, w.weightOf(NodeRole.MASTER));
        assertEquals(1, w.weightOf(NodeRole.SLAVE));
        assertEquals(1, w.weightOf(NodeRole.REPLICA));
    }

    @Test
    public void pinsSection52FixedDefaults() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2/testdb:::?rwPort=33000&roPort=33002");

        assertTrue(c.isRoPhysicalFailoverToRw());
        assertTrue(c.isRuntimeFailoverEnabled());
        assertTrue(c.isRuntimeFailoverRetryOnce());
        assertEquals(1, c.getRuntimeFailoverMaxAttempts());
        assertEquals(8192, c.getSqlClassifyCacheMaxEntries());
    }

    @Test
    public void retainsCommonOptionsAndDropsModeMismatchedOnes() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid:loadbalance://node1,node2/testdb:::"
                                + "?rwPort=33000&roPort=33002&charSet=utf-8&altHosts=h9:1");

        assertTrue(c.getPropagatedOptions().containsKey("charSet"));
        // altHosts is classic-only -> ignored in loadbalance mode
        assertFalse(c.getPropagatedOptions().containsKey("altHosts"));
    }

    @Test
    public void variantIsCarriedThrough() throws SQLException {
        LoadBalanceSettings c =
                configOf(
                        "jdbc:cubrid-mysql:loadbalance://node1/testdb:::?rwPort=33000&roPort=33002");

        assertEquals("-mysql", c.getUrlVariant());
    }

    @Test
    public void propagatesPortResolutionErrors() {
        // an invalid global port value fails resolution -> the error surfaces through
        // LoadBalanceSettings (omitted ports now default, so a bad value is the resolution error).
        try {
            configOf("jdbc:cubrid:loadbalance://node1,node2/testdb:::?rwPort=notaport");
            fail("expected SQLException for invalid rwPort value");
        } catch (SQLException expected) {
        }
    }

    /**
     * An option the broker URL cannot carry passed here and then failed every leg's connect with an
     * invalid URL, far from its cause. It is refused where it was written.
     */
    @Test
    public void rejectsAnOptionTheBrokerUrlCannotCarry() throws SQLException {
        for (String option : new String[] {"charset=", "charSet=a?b", "log-file=x"}) {
            try {
                configOf("jdbc:cubrid:loadbalance://node1,node2/testdb:::?" + option);
                fail(option + " must be refused while parsing");
            } catch (SQLException expected) {
                String key = option.substring(0, option.indexOf('='));
                assertTrue(expected.getMessage(), expected.getMessage().contains(key));
            }
        }

        configOf("jdbc:cubrid:loadbalance://node1,node2/testdb:::?charset=utf-8&queryTimeout=10");
    }
}
