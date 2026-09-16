/*
 * Copyright (c) Open Integration Engine. All rights reserved.
 *
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.plugins.datatypes.xml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringReader;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Guards the XXE hardening of the XML batch adaptor (CVE-2026-82578). The adaptor used to let
 * {@code XPath.evaluate(InputSource)} build its own DOCTYPE-resolving parser; it now parses untrusted
 * batches through {@link XMLBatchAdaptor#parseBatchSecurely} with {@code disallow-doctype-decl} and
 * external access disabled.
 *
 * <p>
 * These are pure unit tests against the exact hardened parse path -- no live server. The benign
 * control proves the parser still works (and stays namespace-aware), so the rejection tests are not
 * false-passing on an unrelated failure.
 */
public class XMLBatchAdaptorSecurityTest {

    private static InputSource source(String xml) {
        return new InputSource(new StringReader(xml));
    }

    @Test
    public void rejectsExternalEntityDoctype() {
        // An external SYSTEM entity is the file-exfiltration vector. It must be rejected at parse time.
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE batch [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>"
                + "<batch><message>&x;</message></batch>";
        try {
            XMLBatchAdaptor.parseBatchSecurely(source(xml));
            fail("expected the hardened parser to reject a DOCTYPE (external entity)");
        } catch (Exception e) {
            // disallow-doctype-decl surfaces as a SAXParseException mentioning DOCTYPE. Assert a
            // specific rejection rather than accepting any exception, so transport/other failures
            // could not masquerade as success.
            assertTrue("expected a DOCTYPE rejection but was: " + e,
                    String.valueOf(e.getMessage()).toUpperCase().contains("DOCTYPE"));
        }
    }

    @Test
    public void rejectsInternalEntityDoctype() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE batch [<!ENTITY x \"OIE_XXE_MARKER\">]>"
                + "<batch><message>&x;</message></batch>";
        try {
            XMLBatchAdaptor.parseBatchSecurely(source(xml));
            fail("expected the hardened parser to reject a DOCTYPE (internal entity)");
        } catch (Exception e) {
            assertTrue("expected a DOCTYPE rejection but was: " + e,
                    String.valueOf(e.getMessage()).toUpperCase().contains("DOCTYPE"));
        }
    }

    @Test
    public void parsesBenignBatchAndSplitsByElementName() throws Exception {
        String xml = "<batch><message>hello</message><message>world</message></batch>";

        Document document = XMLBatchAdaptor.parseBatchSecurely(source(xml));
        assertNotNull(document);

        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList nodes = (NodeList) xpath.evaluate("//*[local-name()='message']", document, XPathConstants.NODESET);
        assertEquals(2, nodes.getLength());
        assertEquals("hello", nodes.item(0).getTextContent());
    }

    @Test
    public void parsesNamespacedBatchNamespaceAware() throws Exception {
        // Locks in setNamespaceAware(true): a namespace-uri() predicate must still match, matching the
        // behavior of the previous XPath.evaluate(InputSource) path.
        String xml = "<b:batch xmlns:b=\"urn:oie:batch\">"
                + "<b:message>ns-hello</b:message>"
                + "</b:batch>";

        Document document = XMLBatchAdaptor.parseBatchSecurely(source(xml));

        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList nodes = (NodeList) xpath.evaluate(
                "//*[local-name()='message' and namespace-uri()='urn:oie:batch']", document, XPathConstants.NODESET);
        assertEquals("namespace-aware parsing should let a namespace-uri() predicate match", 1, nodes.getLength());
        assertEquals("ns-hello", nodes.item(0).getTextContent());
    }
}
