// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Open Integration Engine

package org.openintegrationengine.smoketest;

import com.mirth.connect.donkey.model.channel.SourceConnectorPropertiesInterface;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Transformer;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.datatypes.xml.XMLBatchProperties;
import com.mirth.connect.plugins.datatypes.xml.XMLBatchProperties.SplitType;
import com.mirth.connect.plugins.datatypes.xml.XMLDataTypeProperties;

/**
 * Builds the XML-batch channel the batch-XXE smoke test deploys. It starts from a known-good VM
 * reader -> VM writer no-op channel ({@code fixtures/security/base-vm-noop.xml}, a copy of the
 * 101-raw-no-op fixture) and customises only the source transformer, so the test does not have to
 * hand-author connector XML. The serializer is already initialised by {@link OieServer}.
 *
 * <p>
 * The XSLT-step XXE repro is a fixture test under {@code ci/tests} instead; only the batch case needs
 * code, because a rejected batch surfaces as a submit-time exception the fixture runner cannot model.
 */
final class SecurityChannels {

    private static final String BASE_RESOURCE = "fixtures/security/base-vm-noop.xml";

    private SecurityChannels() {
    }

    private static Channel base(String id, String name) {
        Channel channel = ObjectXMLSerializer.getInstance().deserialize(Harness.resource(BASE_RESOURCE), Channel.class);
        channel.setId(id);
        channel.setName(name);
        return channel;
    }

    /**
     * A channel with an XML data type source and batch processing enabled, splitting on an element
     * name. Used to prove CVE-2026-82578: the batch adaptor parses the untrusted XML and resolves
     * DOCTYPE entities (pre-fix) or rejects the DOCTYPE (post-fix).
     */
    static Channel xmlBatchXxe(String id, String name, String splitElement) {
        Channel channel = base(id, name);
        Connector source = channel.getSourceConnector();

        ((SourceConnectorPropertiesInterface) source.getProperties()).getSourceConnectorProperties()
                .setProcessBatch(true);

        Transformer transformer = source.getTransformer();
        transformer.setInboundDataType("XML");

        XMLDataTypeProperties xmlProperties = new XMLDataTypeProperties();
        XMLBatchProperties batchProperties = (XMLBatchProperties) xmlProperties.getBatchProperties();
        batchProperties.setSplitType(SplitType.Element_Name);
        batchProperties.setElementName(splitElement);
        transformer.setInboundProperties(xmlProperties);

        return channel;
    }
}
