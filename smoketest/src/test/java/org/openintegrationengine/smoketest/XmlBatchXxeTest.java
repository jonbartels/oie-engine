// SPDX-License-Identifier: MPL-2.0
// SPDX-FileCopyrightText: 2026 Open Integration Engine

package org.openintegrationengine.smoketest;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;

import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.MessageContent;
import com.mirth.connect.model.Channel;

/**
 * Validates CVE-2026-82578 end-to-end: the XML batch adaptor parsed untrusted batch XML with a
 * DOCTYPE-resolving parser, expanding entities (the same parse that resolves external entities for
 * file exfiltration).
 *
 * <p>
 * The channel splits a batch by element name. Two batches go through it:
 * <ul>
 * <li>A <b>benign control</b> that must split and produce a child containing {@code OIE_BENIGN_CONTROL}
 * -- this proves the batch-splitting path actually works, so the "no marker" check below cannot pass
 * merely because the channel/transport failed for an unrelated reason.</li>
 * <li>A <b>malicious</b> batch whose DOCTYPE entity, if expanded, would put {@code OIE_XXE_MARKER}
 * into a split child. Post-fix the DOCTYPE is rejected and no child is produced.</li>
 * </ul>
 *
 * <p>
 * This asserts a specific marker rather than treating any exception as success. The external-file
 * exfiltration vector (a {@code SYSTEM} entity) is covered precisely and hermetically by
 * {@code XMLBatchAdaptorSecurityTest.rejectsExternalEntityDoctype} in the server module; here the
 * fixed parser rejects internal and external DOCTYPEs identically ({@code disallow-doctype-decl}), so
 * the internal marker is the assertable end-to-end oracle.
 */
class XmlBatchXxeTest {

    private static final String CHANNEL_ID = "5ec00002-0000-4000-8000-00000000ba7c";
    private static final String XXE_MARKER = "OIE_XXE_MARKER";
    private static final String BENIGN_MARKER = "OIE_BENIGN_CONTROL";

    @Test
    void doctypeEntityInBatchIsNotExpanded() throws Exception {
        OieServer server = SharedServer.get();
        Channel channel = SecurityChannels.xmlBatchXxe(CHANNEL_ID, "SEC XML BATCH XXE", "message");
        String channelId = server.deployChannel(channel, "xml-batch-xxe");
        try {
            // Positive control: a well-formed batch must split and produce a child with the marker.
            String benign = "<batch><message>" + BENIGN_MARKER + "</message></batch>";
            server.submitMessage(channelId, benign, new LinkedHashMap<>());

            Duration window = min(HarnessConfig.TIMEOUT, Duration.ofSeconds(20));
            if (!waitForMarker(server, channelId, BENIGN_MARKER, window)) {
                fail("benign control batch never produced a split child containing '" + BENIGN_MARKER
                        + "' -- the batch-splitting path is broken, so the XXE assertion would be meaningless");
            }

            // Malicious batch: if the DOCTYPE entity is expanded, a split child contains the marker.
            String malicious = "<?xml version=\"1.0\"?>"
                    + "<!DOCTYPE batch [<!ENTITY x \"" + XXE_MARKER + "\">]>"
                    + "<batch><message>&x;</message></batch>";
            try {
                server.submitMessage(channelId, malicious, new LinkedHashMap<>());
            } catch (Exception batchRejected) {
                // Post-fix the batch parse throws; no expanded child is produced. Acceptable.
            }

            // Give any (pre-fix) expanded child time to be stored, then assert the marker never appears.
            long deadline = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < deadline) {
                for (Message message : server.getMessages(channelId, 50)) {
                    if (containsMarker(message, XXE_MARKER)) {
                        fail("XML batch adaptor expanded a DOCTYPE entity (message contained '" + XXE_MARKER
                                + "') -- CVE-2026-82578 is present");
                    }
                }
                Thread.sleep(500);
            }
        } finally {
            server.removeChannel(channelId);
        }
    }

    private static boolean waitForMarker(OieServer server, String channelId, String marker, Duration window)
            throws Exception {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            for (Message message : server.getMessages(channelId, 50)) {
                if (containsMarker(message, marker)) {
                    return true;
                }
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static boolean containsMarker(Message message, String marker) {
        if (message.getConnectorMessages() == null) {
            return false;
        }
        for (ConnectorMessage connectorMessage : message.getConnectorMessages().values()) {
            if (contentContainsMarker(connectorMessage.getRaw(), marker)
                    || contentContainsMarker(connectorMessage.getTransformed(), marker)
                    || contentContainsMarker(connectorMessage.getEncoded(), marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contentContainsMarker(MessageContent content, String marker) {
        return content != null && content.getContent() != null && content.getContent().contains(marker);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
