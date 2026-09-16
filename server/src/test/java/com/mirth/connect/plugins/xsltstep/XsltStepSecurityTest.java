/*
 * Copyright (c) Open Integration Engine. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.plugins.xsltstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the XXE hardening of the XSLT transformer step (CVE-2026-78224). The step emits JavaScript
 * that builds a TransformerFactory at runtime, so the fix lives in the generated script text.
 */
public class XsltStepSecurityTest {

    @Test
    public void generatedScriptHardensTransformerFactoryAgainstXxe() {
        XsltStep step = new XsltStep();
        step.setSourceXml("connectorMessage.getRawData()");
        step.setResultVariable("xsltResult");
        step.setTemplate("'<xsl:stylesheet version=\"1.0\"/>'");

        String script = step.getScript(false);

        // The attacker-controlled source XML: external DTDs/entities denied outright (closes the CVE).
        assertTrue("external DTD access should be denied ('')",
                script.contains("ACCESS_EXTERNAL_DTD, ''"));
        // The author-controlled stylesheet: restricted to the 'file' protocol (OWASP "restrict rather
        // than close"), so local xsl:import/include/document() work but http(s) SSRF is denied. It
        // must NOT be blocked outright ('') -- that would break legitimate includes.
        assertTrue("external stylesheet access should be restricted to the file protocol",
                script.contains("ACCESS_EXTERNAL_STYLESHEET, 'file'"));
        assertFalse("external stylesheet access must not be blocked outright (breaks includes)",
                script.contains("ACCESS_EXTERNAL_STYLESHEET, ''"));
        // FEATURE_SECURE_PROCESSING is intentionally not emitted: on the JDK's built-in Xalan it
        // disables Java XSLT extension functions and would break existing stylesheets. Blocking
        // external access is sufficient for the CVE.
        assertFalse("secure processing must not be enabled (it breaks Java extension functions)",
                script.contains("FEATURE_SECURE_PROCESSING"));
        // Fail closed: the hardening must not be wrapped in a try/catch that would let a factory
        // silently ignore the restrictions and run with external access still open.
        assertFalse("XXE hardening must not be swallowed", script.contains("catch"));
    }

    @Test
    public void hardeningAlsoAppliedOnTheIteratorPath() throws Exception {
        XsltStep step = new XsltStep();
        step.setSourceXml("connectorMessage.getRawData()");
        step.setResultVariable("xsltResult");
        step.setTemplate("'<xsl:stylesheet version=\"1.0\"/>'");

        String script = step.getIterationScript(false, new java.util.LinkedList<>());

        assertTrue("external DTD access should be denied on the iterator path",
                script.contains("ACCESS_EXTERNAL_DTD, ''"));
        assertTrue("external stylesheet access should be restricted to 'file' on the iterator path",
                script.contains("ACCESS_EXTERNAL_STYLESHEET, 'file'"));
        assertFalse("XXE hardening must not be swallowed on the iterator path", script.contains("catch"));
    }
}
