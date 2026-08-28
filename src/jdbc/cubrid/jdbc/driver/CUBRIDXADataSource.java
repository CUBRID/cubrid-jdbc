/*
 * Copyright (C) 2008 Search Solution Corporation.
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

package cubrid.jdbc.driver;

import java.io.Serializable;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.sql.XAConnection;
import javax.sql.XADataSource;

/**
 * Title: CUBRID JDBC Driver Description:
 *
 * @version 3.0
 */
public class CUBRIDXADataSource extends CUBRIDPoolDataSourceBase
        implements XADataSource, Referenceable, Serializable {
    private static final long serialVersionUID = -7015869630223848825L;

    public CUBRIDXADataSource() {
        super();
    }

    protected CUBRIDXADataSource(Reference ref) {
        super();
        setProperties(ref);
    }

    /**
     * Refuses a {@code loadbalance://} URL at configuration time.
     *
     * <p>An XA branch runs with autocommit off, and a load-balance session pins every statement of
     * an open transaction to the write leg, so an XA data source would distribute nothing: the read
     * legs would sit idle for the whole branch. Refusing here rather than at {@link
     * #getXAConnection()} makes the failure land on the JNDI bind or the bean definition that set
     * the property, which is where the fix belongs.
     *
     * <p>This is also the one place where a load-balance URL was previously accepted and then
     * ignored: {@link #getXAConnection(String, String)} builds its connection from {@code
     * serverName}/{@code portNumber}/{@code databaseName} and never reads the URL, so the session
     * silently went to a different host than the one configured.
     *
     * @throws IllegalArgumentException if {@code urlString} is a loadbalance:// URL
     */
    @Override
    public void setUrl(String urlString) {
        rejectLoadBalanceUrl(urlString);
        super.setUrl(urlString);
    }

    /*
     * javax.sql.XADataSource interface
     */

    public synchronized XAConnection getXAConnection() throws SQLException {
        return getXAConnection(getUser(), getPassword());
    }

    public synchronized XAConnection getXAConnection(String username, String passwd)
            throws SQLException {
        // Second gate: this data source is Serializable, and deserialization restores the url field
        // directly without going through setUrl(). Without this check a serialized-then-restored
        // data source would keep the silently-ignored loadbalance:// URL.
        if (getUrl() != null
                && CUBRIDDriver.detectUrlMode(getUrl()) == CUBRIDDriver.UrlMode.URI_LOADBALANCE) {
            throw new CUBRIDException(
                    CUBRIDJDBCErrorCode.invalid_url, loadBalanceRefusalMessage(), null);
        }

        return (new CUBRIDXAConnection(
                this, getServerName(), getPortNumber(), getDatabaseName(), username, passwd));
    }

    private static void rejectLoadBalanceUrl(String urlString) {
        if (urlString == null) {
            return;
        }
        if (CUBRIDDriver.detectUrlMode(urlString) == CUBRIDDriver.UrlMode.URI_LOADBALANCE) {
            throw new IllegalArgumentException(loadBalanceRefusalMessage());
        }
    }

    private static String loadBalanceRefusalMessage() {
        return "a loadbalance:// URL cannot be used with CUBRIDXADataSource (XA/distributed"
                + " transactions); an XA branch keeps autocommit off, and a load-balance session"
                + " pins every statement of an open transaction to the master, so no read would be"
                + " distributed; configure this XA data source with a classic URL, and use a"
                + " separate loadbalance:// javax.sql.DataSource for the read paths";
    }

    /*
     * javax.naming.Referenceable interface
     */

    public synchronized Reference getReference() throws NamingException {
        Reference ref =
                new Reference(
                        this.getClass().getName(),
                        "cubrid.jdbc.driver.CUBRIDDataSourceObjectFactory",
                        null);

        ref = getProperties(ref);
        return ref;
    }

    /* JDK 1.7 */
    public Logger getParentLogger() {
        throw new java.lang.UnsupportedOperationException();
    }
}
