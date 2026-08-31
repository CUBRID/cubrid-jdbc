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

package cubrid.jdbc.lb;

import cubrid.jdbc.driver.CUBRIDDatabaseMetaData;
import cubrid.jdbc.driver.CUBRIDDriver;
import cubrid.jdbc.lb.failover.ExecuteFailoverHandler;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

/**
 * Connection-level metadata for a load-balanced session, served from the write endpoint.
 *
 * <p>A subclass rather than a {@link java.lang.reflect.Proxy}, so that {@code
 * (CUBRIDDatabaseMetaData) conn.getMetaData()} works unmodified: a proxy implements interfaces
 * only, so that cast could never succeed against one. The cost is verbosity - every inherited
 * method must be forwarded explicitly, because the inherited bodies read a {@code u_con} that a
 * logical connection does not own.
 *
 * <p>Each call re-resolves the write endpoint through {@link
 * LoadBalanceConnection#rwMetaDataRecordingCall(String)} instead of capturing one physical {@code
 * DatabaseMetaData} at construction, so a metadata object keeps working across an RW rebind.
 * Building this on the physical RW connection would inherit the 173 standard methods untouched, but
 * the captured socket would go stale on failover.
 */
public class LBDatabaseMetaData extends CUBRIDDatabaseMetaData {

    private final LoadBalanceConnection owner;

    LBDatabaseMetaData(final LoadBalanceConnection owner) {
        super(owner);
        this.owner = owner;
    }

    /**
     * JDBC requires {@code getConnection()} to return the connection that produced this metadata —
     * the logical LB connection, not the physical write endpoint. Returning the physical one would
     * let a caller {@code close()} or {@code setAutoCommit()} it directly and corrupt the LB
     * session.
     */
    @Override
    public Connection getConnection() throws SQLException {
        return owner;
    }

    /**
     * Answered here rather than forwarded. Forwarding would hand out the physical {@code
     * CUBRIDDatabaseMetaData}, which defeats the {@link #getConnection()} contract above (the
     * physical metadata reports the physical connection) and exposes the SHARD-only extensions that
     * this class refuses.
     */
    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }

        throw LbExceptions.notSupportedApi(
                "DatabaseMetaData.unwrap(" + (iface == null ? "null" : iface.getName()) + ")");
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return iface != null && iface.isInstance(this);
    }

    /**
     * SHARD is not supported on a load-balanced session: the session spans several brokers of one
     * HA cluster with no shards behind them, so there is no shard id or shard metadata to report.
     * Refusing is safer than quietly returning a meaningless value. Unlike the statement and
     * connection shard accessors, these declare {@code throws SQLException}, so the refusal is
     * checked here.
     */
    @Override
    public int getShardId() throws SQLException {
        throw LbExceptions.notSupportedApi("DatabaseMetaData.getShardId()");
    }

    @Override
    public void setShardId(final int sid) throws SQLException {
        throw LbExceptions.notSupportedApi("DatabaseMetaData.setShardId(int)");
    }

    @Override
    public String getShardDBName() throws SQLException {
        throw LbExceptions.notSupportedApi("DatabaseMetaData.getShardDBName()");
    }

    @Override
    public String getShardDBServer() throws SQLException {
        throw LbExceptions.notSupportedApi("DatabaseMetaData.getShardDBServer()");
    }

    /**
     * Resolve the write endpoint's metadata for one call and record the routing decision.
     *
     * @param commandName the metadata call being served, for the routing log
     * @return the physical metadata of the current write endpoint
     * @throws SQLException if the write endpoint cannot be bound
     */
    private DatabaseMetaData rwMeta(final String commandName) throws SQLException {
        return owner.rwMetaDataRecordingCall(commandName);
    }

    /**
     * Serve one call that <b>sends a request</b> to the write endpoint, under the failover handler.
     *
     * <p>{@link #rwMeta} resolves the handle inside the closure, so a rebind is followed by a fresh
     * handle rather than by a query on the connection that just died. Twelve of the 173 methods
     * need this - the ones whose physical implementation touches {@code u_con}; the others answer
     * from local constants and are left as plain {@code rwMeta(...)} forwards.
     *
     * @param <T> the call's result type
     * @param commandName the metadata call being served
     * @param query resolve-and-run
     * @return the call's result
     * @throws SQLException if the call fails after the leg was recovered
     */
    private <T> T rwMetaQuery(
            final String commandName, final ExecuteFailoverHandler.SqlExecution<T> query)
            throws SQLException {
        return owner.runRwMetaQuery(commandName, query);
    }

    /* ===== standard DatabaseMetaData surface: served from the write endpoint =====*/

    /**
     * One line each on purpose. The behaviour is the physical driver's; this class only decides
     * <em>which</em> endpoint answers. A method left inherited would read the null {@code u_con} of
     * a logical connection, so {@code LbDatabaseMetaDataCastTest} fails the build if one is
     * missing.
     */
    public boolean allProceduresAreCallable() throws SQLException {
        return rwMeta("allProceduresAreCallable").allProceduresAreCallable();
    }

    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return rwMeta("allTablesAreSelectable").allTablesAreSelectable();
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
        return rwMeta("autoCommitFailureClosesAllResultSets")
                .autoCommitFailureClosesAllResultSets();
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
        return rwMeta("dataDefinitionCausesTransactionCommit")
                .dataDefinitionCausesTransactionCommit();
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
        return rwMeta("dataDefinitionIgnoredInTransactions").dataDefinitionIgnoredInTransactions();
    }

    @Override
    public boolean deletesAreDetected(final int a0) throws SQLException {
        return rwMeta("deletesAreDetected").deletesAreDetected(a0);
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
        return rwMeta("doesMaxRowSizeIncludeBlobs").doesMaxRowSizeIncludeBlobs();
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws SQLException {
        return rwMeta("generatedKeyAlwaysReturned").generatedKeyAlwaysReturned();
    }

    @Override
    public ResultSet getAttributes(
            final String a0, final String a1, final String a2, final String a3)
            throws SQLException {
        return rwMeta("getAttributes").getAttributes(a0, a1, a2, a3);
    }

    @Override
    public ResultSet getBestRowIdentifier(
            final String a0, final String a1, final String a2, final int a3, final boolean a4)
            throws SQLException {
        return rwMetaQuery(
                "getBestRowIdentifier",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getBestRowIdentifier")
                                .getBestRowIdentifier(a0, a1, a2, a3, a4);
                    }
                });
    }

    @Override
    public String getCatalogSeparator() throws SQLException {
        return rwMeta("getCatalogSeparator").getCatalogSeparator();
    }

    @Override
    public String getCatalogTerm() throws SQLException {
        return rwMeta("getCatalogTerm").getCatalogTerm();
    }

    @Override
    public ResultSet getCatalogs() throws SQLException {
        return rwMeta("getCatalogs").getCatalogs();
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return rwMeta("getClientInfoProperties").getClientInfoProperties();
    }

    @Override
    public ResultSet getColumnPrivileges(
            final String a0, final String a1, final String a2, final String a3)
            throws SQLException {
        return rwMetaQuery(
                "getColumnPrivileges",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getColumnPrivileges").getColumnPrivileges(a0, a1, a2, a3);
                    }
                });
    }

    @Override
    public ResultSet getColumns(final String a0, final String a1, final String a2, final String a3)
            throws SQLException {
        return rwMetaQuery(
                "getColumns",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getColumns").getColumns(a0, a1, a2, a3);
                    }
                });
    }

    @Override
    public ResultSet getCrossReference(
            final String a0,
            final String a1,
            final String a2,
            final String a3,
            final String a4,
            final String a5)
            throws SQLException {
        return rwMetaQuery(
                "getCrossReference",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getCrossReference")
                                .getCrossReference(a0, a1, a2, a3, a4, a5);
                    }
                });
    }

    @Override
    public int getDatabaseMajorVersion() throws SQLException {
        return rwMeta("getDatabaseMajorVersion").getDatabaseMajorVersion();
    }

    @Override
    public int getDatabaseMinorVersion() throws SQLException {
        return rwMeta("getDatabaseMinorVersion").getDatabaseMinorVersion();
    }

    @Override
    public String getDatabaseProductName() throws SQLException {
        return rwMeta("getDatabaseProductName").getDatabaseProductName();
    }

    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return rwMetaQuery(
                "getDatabaseProductVersion",
                new ExecuteFailoverHandler.SqlExecution<String>() {
                    public String run() throws SQLException {
                        return rwMeta("getDatabaseProductVersion").getDatabaseProductVersion();
                    }
                });
    }

    @Override
    public int getDefaultTransactionIsolation() throws SQLException {
        return rwMeta("getDefaultTransactionIsolation").getDefaultTransactionIsolation();
    }

    /**
     * The two driver-version accessors are the only inherited methods that declare no {@code
     * throws} clause, so they cannot resolve an endpoint - binding one may fail. They need none:
     * the value is this driver's own build constant, identical on every leg.
     */
    @Override
    public int getDriverMajorVersion() {
        return CUBRIDDriver.major_version;
    }

    @Override
    public int getDriverMinorVersion() {
        return CUBRIDDriver.minor_version;
    }

    @Override
    public String getDriverName() throws SQLException {
        return rwMeta("getDriverName").getDriverName();
    }

    @Override
    public String getDriverVersion() throws SQLException {
        return rwMeta("getDriverVersion").getDriverVersion();
    }

    @Override
    public ResultSet getExportedKeys(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMetaQuery(
                "getExportedKeys",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getExportedKeys").getExportedKeys(a0, a1, a2);
                    }
                });
    }

    @Override
    public String getExtraNameCharacters() throws SQLException {
        return rwMeta("getExtraNameCharacters").getExtraNameCharacters();
    }

    @Override
    public ResultSet getFunctionColumns(
            final String a0, final String a1, final String a2, final String a3)
            throws SQLException {
        return rwMeta("getFunctionColumns").getFunctionColumns(a0, a1, a2, a3);
    }

    @Override
    public ResultSet getFunctions(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMeta("getFunctions").getFunctions(a0, a1, a2);
    }

    @Override
    public String getIdentifierQuoteString() throws SQLException {
        return rwMeta("getIdentifierQuoteString").getIdentifierQuoteString();
    }

    @Override
    public ResultSet getImportedKeys(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMetaQuery(
                "getImportedKeys",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getImportedKeys").getImportedKeys(a0, a1, a2);
                    }
                });
    }

    @Override
    public ResultSet getIndexInfo(
            final String a0, final String a1, final String a2, final boolean a3, final boolean a4)
            throws SQLException {
        return rwMetaQuery(
                "getIndexInfo",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getIndexInfo").getIndexInfo(a0, a1, a2, a3, a4);
                    }
                });
    }

    @Override
    public int getJDBCMajorVersion() throws SQLException {
        return rwMeta("getJDBCMajorVersion").getJDBCMajorVersion();
    }

    @Override
    public int getJDBCMinorVersion() throws SQLException {
        return rwMeta("getJDBCMinorVersion").getJDBCMinorVersion();
    }

    @Override
    public int getMaxBinaryLiteralLength() throws SQLException {
        return rwMeta("getMaxBinaryLiteralLength").getMaxBinaryLiteralLength();
    }

    @Override
    public int getMaxCatalogNameLength() throws SQLException {
        return rwMeta("getMaxCatalogNameLength").getMaxCatalogNameLength();
    }

    @Override
    public int getMaxCharLiteralLength() throws SQLException {
        return rwMeta("getMaxCharLiteralLength").getMaxCharLiteralLength();
    }

    @Override
    public int getMaxColumnNameLength() throws SQLException {
        return rwMeta("getMaxColumnNameLength").getMaxColumnNameLength();
    }

    @Override
    public int getMaxColumnsInGroupBy() throws SQLException {
        return rwMeta("getMaxColumnsInGroupBy").getMaxColumnsInGroupBy();
    }

    @Override
    public int getMaxColumnsInIndex() throws SQLException {
        return rwMeta("getMaxColumnsInIndex").getMaxColumnsInIndex();
    }

    @Override
    public int getMaxColumnsInOrderBy() throws SQLException {
        return rwMeta("getMaxColumnsInOrderBy").getMaxColumnsInOrderBy();
    }

    @Override
    public int getMaxColumnsInSelect() throws SQLException {
        return rwMeta("getMaxColumnsInSelect").getMaxColumnsInSelect();
    }

    @Override
    public int getMaxColumnsInTable() throws SQLException {
        return rwMeta("getMaxColumnsInTable").getMaxColumnsInTable();
    }

    @Override
    public int getMaxConnections() throws SQLException {
        return rwMeta("getMaxConnections").getMaxConnections();
    }

    @Override
    public int getMaxCursorNameLength() throws SQLException {
        return rwMeta("getMaxCursorNameLength").getMaxCursorNameLength();
    }

    @Override
    public int getMaxIndexLength() throws SQLException {
        return rwMeta("getMaxIndexLength").getMaxIndexLength();
    }

    @Override
    public int getMaxProcedureNameLength() throws SQLException {
        return rwMeta("getMaxProcedureNameLength").getMaxProcedureNameLength();
    }

    @Override
    public int getMaxRowSize() throws SQLException {
        return rwMeta("getMaxRowSize").getMaxRowSize();
    }

    @Override
    public int getMaxSchemaNameLength() throws SQLException {
        return rwMeta("getMaxSchemaNameLength").getMaxSchemaNameLength();
    }

    @Override
    public int getMaxStatementLength() throws SQLException {
        return rwMeta("getMaxStatementLength").getMaxStatementLength();
    }

    @Override
    public int getMaxStatements() throws SQLException {
        return rwMeta("getMaxStatements").getMaxStatements();
    }

    @Override
    public int getMaxTableNameLength() throws SQLException {
        return rwMeta("getMaxTableNameLength").getMaxTableNameLength();
    }

    @Override
    public int getMaxTablesInSelect() throws SQLException {
        return rwMeta("getMaxTablesInSelect").getMaxTablesInSelect();
    }

    @Override
    public int getMaxUserNameLength() throws SQLException {
        return rwMeta("getMaxUserNameLength").getMaxUserNameLength();
    }

    @Override
    public String getNumericFunctions() throws SQLException {
        return rwMeta("getNumericFunctions").getNumericFunctions();
    }

    @Override
    public ResultSet getPrimaryKeys(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMetaQuery(
                "getPrimaryKeys",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getPrimaryKeys").getPrimaryKeys(a0, a1, a2);
                    }
                });
    }

    @Override
    public ResultSet getProcedureColumns(
            final String a0, final String a1, final String a2, final String a3)
            throws SQLException {
        return rwMeta("getProcedureColumns").getProcedureColumns(a0, a1, a2, a3);
    }

    @Override
    public String getProcedureTerm() throws SQLException {
        return rwMeta("getProcedureTerm").getProcedureTerm();
    }

    @Override
    public ResultSet getProcedures(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMeta("getProcedures").getProcedures(a0, a1, a2);
    }

    @Override
    public ResultSet getPseudoColumns(
            final String a0, final String a1, final String a2, final String a3)
            throws SQLException {
        return rwMeta("getPseudoColumns").getPseudoColumns(a0, a1, a2, a3);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return rwMeta("getResultSetHoldability").getResultSetHoldability();
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws SQLException {
        return rwMeta("getRowIdLifetime").getRowIdLifetime();
    }

    @Override
    public String getSQLKeywords() throws SQLException {
        return rwMeta("getSQLKeywords").getSQLKeywords();
    }

    @Override
    public int getSQLStateType() throws SQLException {
        return rwMeta("getSQLStateType").getSQLStateType();
    }

    @Override
    public String getSchemaTerm() throws SQLException {
        return rwMeta("getSchemaTerm").getSchemaTerm();
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return rwMeta("getSchemas").getSchemas();
    }

    @Override
    public ResultSet getSchemas(final String a0, final String a1) throws SQLException {
        return rwMeta("getSchemas").getSchemas(a0, a1);
    }

    @Override
    public String getSearchStringEscape() throws SQLException {
        return rwMeta("getSearchStringEscape").getSearchStringEscape();
    }

    @Override
    public String getStringFunctions() throws SQLException {
        return rwMeta("getStringFunctions").getStringFunctions();
    }

    @Override
    public ResultSet getSuperTables(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMetaQuery(
                "getSuperTables",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getSuperTables").getSuperTables(a0, a1, a2);
                    }
                });
    }

    @Override
    public ResultSet getSuperTypes(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMeta("getSuperTypes").getSuperTypes(a0, a1, a2);
    }

    @Override
    public String getSystemFunctions() throws SQLException {
        return rwMeta("getSystemFunctions").getSystemFunctions();
    }

    @Override
    public ResultSet getTablePrivileges(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMetaQuery(
                "getTablePrivileges",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getTablePrivileges").getTablePrivileges(a0, a1, a2);
                    }
                });
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return rwMeta("getTableTypes").getTableTypes();
    }

    @Override
    public ResultSet getTables(final String a0, final String a1, final String a2, final String[] a3)
            throws SQLException {
        return rwMetaQuery(
                "getTables",
                new ExecuteFailoverHandler.SqlExecution<ResultSet>() {
                    public ResultSet run() throws SQLException {
                        return rwMeta("getTables").getTables(a0, a1, a2, a3);
                    }
                });
    }

    @Override
    public String getTimeDateFunctions() throws SQLException {
        return rwMeta("getTimeDateFunctions").getTimeDateFunctions();
    }

    @Override
    public ResultSet getTypeInfo() throws SQLException {
        return rwMeta("getTypeInfo").getTypeInfo();
    }

    @Override
    public ResultSet getUDTs(final String a0, final String a1, final String a2, final int[] a3)
            throws SQLException {
        return rwMeta("getUDTs").getUDTs(a0, a1, a2, a3);
    }

    @Override
    public String getURL() throws SQLException {
        return rwMeta("getURL").getURL();
    }

    @Override
    public String getUserName() throws SQLException {
        return rwMeta("getUserName").getUserName();
    }

    @Override
    public ResultSet getVersionColumns(final String a0, final String a1, final String a2)
            throws SQLException {
        return rwMeta("getVersionColumns").getVersionColumns(a0, a1, a2);
    }

    @Override
    public boolean insertsAreDetected(final int a0) throws SQLException {
        return rwMeta("insertsAreDetected").insertsAreDetected(a0);
    }

    @Override
    public boolean isCatalogAtStart() throws SQLException {
        return rwMeta("isCatalogAtStart").isCatalogAtStart();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return rwMeta("isReadOnly").isReadOnly();
    }

    @Override
    public boolean locatorsUpdateCopy() throws SQLException {
        return rwMeta("locatorsUpdateCopy").locatorsUpdateCopy();
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws SQLException {
        return rwMeta("nullPlusNonNullIsNull").nullPlusNonNullIsNull();
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        return rwMeta("nullsAreSortedAtEnd").nullsAreSortedAtEnd();
    }

    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return rwMeta("nullsAreSortedAtStart").nullsAreSortedAtStart();
    }

    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return rwMeta("nullsAreSortedHigh").nullsAreSortedHigh();
    }

    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return rwMeta("nullsAreSortedLow").nullsAreSortedLow();
    }

    @Override
    public boolean othersDeletesAreVisible(final int a0) throws SQLException {
        return rwMeta("othersDeletesAreVisible").othersDeletesAreVisible(a0);
    }

    @Override
    public boolean othersInsertsAreVisible(final int a0) throws SQLException {
        return rwMeta("othersInsertsAreVisible").othersInsertsAreVisible(a0);
    }

    @Override
    public boolean othersUpdatesAreVisible(final int a0) throws SQLException {
        return rwMeta("othersUpdatesAreVisible").othersUpdatesAreVisible(a0);
    }

    @Override
    public boolean ownDeletesAreVisible(final int a0) throws SQLException {
        return rwMeta("ownDeletesAreVisible").ownDeletesAreVisible(a0);
    }

    @Override
    public boolean ownInsertsAreVisible(final int a0) throws SQLException {
        return rwMeta("ownInsertsAreVisible").ownInsertsAreVisible(a0);
    }

    @Override
    public boolean ownUpdatesAreVisible(final int a0) throws SQLException {
        return rwMeta("ownUpdatesAreVisible").ownUpdatesAreVisible(a0);
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws SQLException {
        return rwMeta("storesLowerCaseIdentifiers").storesLowerCaseIdentifiers();
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
        return rwMeta("storesLowerCaseQuotedIdentifiers").storesLowerCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws SQLException {
        return rwMeta("storesMixedCaseIdentifiers").storesMixedCaseIdentifiers();
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
        return rwMeta("storesMixedCaseQuotedIdentifiers").storesMixedCaseQuotedIdentifiers();
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws SQLException {
        return rwMeta("storesUpperCaseIdentifiers").storesUpperCaseIdentifiers();
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
        return rwMeta("storesUpperCaseQuotedIdentifiers").storesUpperCaseQuotedIdentifiers();
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws SQLException {
        return rwMeta("supportsANSI92EntryLevelSQL").supportsANSI92EntryLevelSQL();
    }

    @Override
    public boolean supportsANSI92FullSQL() throws SQLException {
        return rwMeta("supportsANSI92FullSQL").supportsANSI92FullSQL();
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws SQLException {
        return rwMeta("supportsANSI92IntermediateSQL").supportsANSI92IntermediateSQL();
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws SQLException {
        return rwMeta("supportsAlterTableWithAddColumn").supportsAlterTableWithAddColumn();
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws SQLException {
        return rwMeta("supportsAlterTableWithDropColumn").supportsAlterTableWithDropColumn();
    }

    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return rwMeta("supportsBatchUpdates").supportsBatchUpdates();
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws SQLException {
        return rwMeta("supportsCatalogsInDataManipulation").supportsCatalogsInDataManipulation();
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
        return rwMeta("supportsCatalogsInIndexDefinitions").supportsCatalogsInIndexDefinitions();
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
        return rwMeta("supportsCatalogsInPrivilegeDefinitions")
                .supportsCatalogsInPrivilegeDefinitions();
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws SQLException {
        return rwMeta("supportsCatalogsInProcedureCalls").supportsCatalogsInProcedureCalls();
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws SQLException {
        return rwMeta("supportsCatalogsInTableDefinitions").supportsCatalogsInTableDefinitions();
    }

    @Override
    public boolean supportsColumnAliasing() throws SQLException {
        return rwMeta("supportsColumnAliasing").supportsColumnAliasing();
    }

    @Override
    public boolean supportsConvert() throws SQLException {
        return rwMeta("supportsConvert").supportsConvert();
    }

    @Override
    public boolean supportsConvert(final int a0, final int a1) throws SQLException {
        return rwMeta("supportsConvert").supportsConvert(a0, a1);
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws SQLException {
        return rwMeta("supportsCoreSQLGrammar").supportsCoreSQLGrammar();
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws SQLException {
        return rwMeta("supportsCorrelatedSubqueries").supportsCorrelatedSubqueries();
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
        return rwMeta("supportsDataDefinitionAndDataManipulationTransactions")
                .supportsDataDefinitionAndDataManipulationTransactions();
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
        return rwMeta("supportsDataManipulationTransactionsOnly")
                .supportsDataManipulationTransactionsOnly();
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws SQLException {
        return rwMeta("supportsDifferentTableCorrelationNames")
                .supportsDifferentTableCorrelationNames();
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws SQLException {
        return rwMeta("supportsExpressionsInOrderBy").supportsExpressionsInOrderBy();
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws SQLException {
        return rwMeta("supportsExtendedSQLGrammar").supportsExtendedSQLGrammar();
    }

    @Override
    public boolean supportsFullOuterJoins() throws SQLException {
        return rwMeta("supportsFullOuterJoins").supportsFullOuterJoins();
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws SQLException {
        return rwMeta("supportsGetGeneratedKeys").supportsGetGeneratedKeys();
    }

    @Override
    public boolean supportsGroupBy() throws SQLException {
        return rwMeta("supportsGroupBy").supportsGroupBy();
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws SQLException {
        return rwMeta("supportsGroupByBeyondSelect").supportsGroupByBeyondSelect();
    }

    @Override
    public boolean supportsGroupByUnrelated() throws SQLException {
        return rwMeta("supportsGroupByUnrelated").supportsGroupByUnrelated();
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws SQLException {
        return rwMeta("supportsIntegrityEnhancementFacility")
                .supportsIntegrityEnhancementFacility();
    }

    @Override
    public boolean supportsLikeEscapeClause() throws SQLException {
        return rwMeta("supportsLikeEscapeClause").supportsLikeEscapeClause();
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws SQLException {
        return rwMeta("supportsLimitedOuterJoins").supportsLimitedOuterJoins();
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws SQLException {
        return rwMeta("supportsMinimumSQLGrammar").supportsMinimumSQLGrammar();
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws SQLException {
        return rwMeta("supportsMixedCaseIdentifiers").supportsMixedCaseIdentifiers();
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
        return rwMeta("supportsMixedCaseQuotedIdentifiers").supportsMixedCaseQuotedIdentifiers();
    }

    @Override
    public boolean supportsMultipleOpenResults() throws SQLException {
        return rwMeta("supportsMultipleOpenResults").supportsMultipleOpenResults();
    }

    @Override
    public boolean supportsMultipleResultSets() throws SQLException {
        return rwMeta("supportsMultipleResultSets").supportsMultipleResultSets();
    }

    @Override
    public boolean supportsMultipleTransactions() throws SQLException {
        return rwMeta("supportsMultipleTransactions").supportsMultipleTransactions();
    }

    @Override
    public boolean supportsNamedParameters() throws SQLException {
        return rwMeta("supportsNamedParameters").supportsNamedParameters();
    }

    @Override
    public boolean supportsNonNullableColumns() throws SQLException {
        return rwMeta("supportsNonNullableColumns").supportsNonNullableColumns();
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
        return rwMeta("supportsOpenCursorsAcrossCommit").supportsOpenCursorsAcrossCommit();
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
        return rwMeta("supportsOpenCursorsAcrossRollback").supportsOpenCursorsAcrossRollback();
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
        return rwMeta("supportsOpenStatementsAcrossCommit").supportsOpenStatementsAcrossCommit();
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
        return rwMeta("supportsOpenStatementsAcrossRollback")
                .supportsOpenStatementsAcrossRollback();
    }

    @Override
    public boolean supportsOrderByUnrelated() throws SQLException {
        return rwMeta("supportsOrderByUnrelated").supportsOrderByUnrelated();
    }

    @Override
    public boolean supportsOuterJoins() throws SQLException {
        return rwMeta("supportsOuterJoins").supportsOuterJoins();
    }

    @Override
    public boolean supportsPositionedDelete() throws SQLException {
        return rwMeta("supportsPositionedDelete").supportsPositionedDelete();
    }

    @Override
    public boolean supportsPositionedUpdate() throws SQLException {
        return rwMeta("supportsPositionedUpdate").supportsPositionedUpdate();
    }

    @Override
    public boolean supportsResultSetConcurrency(final int a0, final int a1) throws SQLException {
        return rwMeta("supportsResultSetConcurrency").supportsResultSetConcurrency(a0, a1);
    }

    @Override
    public boolean supportsResultSetHoldability(final int a0) throws SQLException {
        return rwMeta("supportsResultSetHoldability").supportsResultSetHoldability(a0);
    }

    @Override
    public boolean supportsResultSetType(final int a0) throws SQLException {
        return rwMeta("supportsResultSetType").supportsResultSetType(a0);
    }

    @Override
    public boolean supportsSavepoints() throws SQLException {
        return rwMeta("supportsSavepoints").supportsSavepoints();
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws SQLException {
        return rwMeta("supportsSchemasInDataManipulation").supportsSchemasInDataManipulation();
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws SQLException {
        return rwMeta("supportsSchemasInIndexDefinitions").supportsSchemasInIndexDefinitions();
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
        return rwMeta("supportsSchemasInPrivilegeDefinitions")
                .supportsSchemasInPrivilegeDefinitions();
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws SQLException {
        return rwMeta("supportsSchemasInProcedureCalls").supportsSchemasInProcedureCalls();
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws SQLException {
        return rwMeta("supportsSchemasInTableDefinitions").supportsSchemasInTableDefinitions();
    }

    @Override
    public boolean supportsSelectForUpdate() throws SQLException {
        return rwMeta("supportsSelectForUpdate").supportsSelectForUpdate();
    }

    @Override
    public boolean supportsStatementPooling() throws SQLException {
        return rwMeta("supportsStatementPooling").supportsStatementPooling();
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
        return rwMeta("supportsStoredFunctionsUsingCallSyntax")
                .supportsStoredFunctionsUsingCallSyntax();
    }

    @Override
    public boolean supportsStoredProcedures() throws SQLException {
        return rwMeta("supportsStoredProcedures").supportsStoredProcedures();
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws SQLException {
        return rwMeta("supportsSubqueriesInComparisons").supportsSubqueriesInComparisons();
    }

    @Override
    public boolean supportsSubqueriesInExists() throws SQLException {
        return rwMeta("supportsSubqueriesInExists").supportsSubqueriesInExists();
    }

    @Override
    public boolean supportsSubqueriesInIns() throws SQLException {
        return rwMeta("supportsSubqueriesInIns").supportsSubqueriesInIns();
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws SQLException {
        return rwMeta("supportsSubqueriesInQuantifieds").supportsSubqueriesInQuantifieds();
    }

    @Override
    public boolean supportsTableCorrelationNames() throws SQLException {
        return rwMeta("supportsTableCorrelationNames").supportsTableCorrelationNames();
    }

    @Override
    public boolean supportsTransactionIsolationLevel(final int a0) throws SQLException {
        return rwMeta("supportsTransactionIsolationLevel").supportsTransactionIsolationLevel(a0);
    }

    @Override
    public boolean supportsTransactions() throws SQLException {
        return rwMeta("supportsTransactions").supportsTransactions();
    }

    @Override
    public boolean supportsUnion() throws SQLException {
        return rwMeta("supportsUnion").supportsUnion();
    }

    @Override
    public boolean supportsUnionAll() throws SQLException {
        return rwMeta("supportsUnionAll").supportsUnionAll();
    }

    @Override
    public boolean updatesAreDetected(final int a0) throws SQLException {
        return rwMeta("updatesAreDetected").updatesAreDetected(a0);
    }

    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return rwMeta("usesLocalFilePerTable").usesLocalFilePerTable();
    }

    @Override
    public boolean usesLocalFiles() throws SQLException {
        return rwMeta("usesLocalFiles").usesLocalFiles();
    }
}
