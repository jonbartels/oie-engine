/*
 * Copyright (c) Open Integration Engine. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.connectors.jdbc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit coverage for the SQL-injection fix in the JDBC connector metadata endpoint (CVE-2026-82583).
 *
 * <p>
 * {@code getTables} used to execute the caller-supplied {@code selectLimit} as SQL. It now ignores
 * that parameter and resolves the metadata-probe template server-side from the built-in driver list,
 * keyed by the JDBC driver class. These tests pin that behavior with no database or live server: the
 * unknown-driver case is the proof that a caller-chosen value can never reach {@code executeQuery}.
 */
public class DatabaseConnectorServletTest {

    @Test
    public void resolvesBuiltInDriverTemplates() {
        assertEquals("SELECT * FROM ? LIMIT 1", DatabaseConnectorServlet.resolveSelectLimit("org.postgresql.Driver"));
        assertEquals("SELECT * FROM ? LIMIT 1", DatabaseConnectorServlet.resolveSelectLimit("com.mysql.cj.jdbc.Driver"));
        assertEquals("SELECT * FROM ? WHERE ROWNUM < 2", DatabaseConnectorServlet.resolveSelectLimit("oracle.jdbc.driver.OracleDriver"));
        assertEquals("SELECT TOP 1 * FROM ?", DatabaseConnectorServlet.resolveSelectLimit("com.microsoft.sqlserver.jdbc.SQLServerDriver"));
    }

    @Test
    public void resolvesAlternativeDriverClassName() {
        // The legacy MySQL driver class is registered as an alternative class name.
        assertEquals("SELECT * FROM ? LIMIT 1", DatabaseConnectorServlet.resolveSelectLimit("com.mysql.jdbc.Driver"));
    }

    @Test
    public void unknownOrInjectedDriverUsesGenericMetadataPath() {
        // Anything not built in resolves to "" -> DatabaseMetaData.getColumns(), never executeQuery.
        assertEquals("", DatabaseConnectorServlet.resolveSelectLimit("com.attacker.EvilDriver"));
        assertEquals("", DatabaseConnectorServlet.resolveSelectLimit(""));
        assertEquals("", DatabaseConnectorServlet.resolveSelectLimit("   "));
        assertEquals("", DatabaseConnectorServlet.resolveSelectLimit(null));
    }

    @Test
    public void quotesPlainSchemaAndTableIdentifiers() {
        assertEquals("\"myschema\".\"mytable\"", DatabaseConnectorServlet.quoteSchemaTable("myschema", "mytable"));
        assertEquals("\"mytable\"", DatabaseConnectorServlet.quoteSchemaTable(null, "mytable"));
        assertEquals("\"mytable\"", DatabaseConnectorServlet.quoteSchemaTable("", "mytable"));
    }

    @Test
    public void escapesEmbeddedQuotesToBlockSecondOrderInjection() {
        // A database identifier containing a double quote must not break out of the quoting: the
        // embedded quote is doubled so the value stays a single quoted identifier.
        assertEquals("\"normal\"\" AS n JOIN secret ON 1=1 --\"",
                DatabaseConnectorServlet.quoteSchemaTable(null, "normal\" AS n JOIN secret ON 1=1 --"));
        assertEquals("\"ev\"\"il\".\"ta\"\"ble\"",
                DatabaseConnectorServlet.quoteSchemaTable("ev\"il", "ta\"ble"));
    }
}
