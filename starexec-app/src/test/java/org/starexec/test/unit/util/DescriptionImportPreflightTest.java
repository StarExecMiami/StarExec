package org.starexec.util;

import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DescriptionImportPreflightTest {

    @BeforeClass
    public static void initializeValidator() {
        Validator.initialize();
    }

    @Test
    public void jobImportPreflightValidatesEveryDescriptionBeforeCreation() throws Exception {
        JobUtil jobUtil = new JobUtil();

        assertTrue(jobUtil.validateJobDescriptions(jobElements("C++ solver - 2017-05-22")));
        assertFalse(jobUtil.validateJobDescriptions(jobElements("safe", "&lt;script&gt;stored-xss&lt;/script&gt;")));
    }

    @Test
    public void spaceImportPreflightValidatesEveryDescriptionBeforeCreation() throws Exception {
        BatchUtil batchUtil = new BatchUtil();

        assertTrue(batchUtil.validateSpaceDescriptions(spaceElements("C++ solver - 2017-05-22")));
        assertFalse(batchUtil.validateSpaceDescriptions(spaceElements("safe", "&lt;script&gt;stored-xss&lt;/script&gt;")));
    }

    private NodeList jobElements(String... descriptions) throws Exception {
        StringBuilder xml = new StringBuilder("<Jobs>");
        for (String description : descriptions) {
            xml.append("<Job><JobAttributes><description value=\"")
                .append(description)
                .append("\"/></JobAttributes></Job>");
        }
        xml.append("</Jobs>");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(
            new InputSource(new StringReader(xml.toString()))
        );
        return document.getElementsByTagName("Job");
    }

    private NodeList spaceElements(String... descriptions) throws Exception {
        StringBuilder xml = new StringBuilder("<Spaces>");
        for (String description : descriptions) {
            xml.append("<Space><SpaceAttributes><description value=\"")
                .append(description)
                .append("\"/></SpaceAttributes></Space>");
        }
        xml.append("</Spaces>");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document document = factory.newDocumentBuilder().parse(
            new InputSource(new StringReader(xml.toString()))
        );
        return document.getElementsByTagName("Space");
    }
}
