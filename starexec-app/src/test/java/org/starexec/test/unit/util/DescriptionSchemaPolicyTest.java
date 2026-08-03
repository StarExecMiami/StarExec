package org.starexec.test.unit.util;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class DescriptionSchemaPolicyTest {

    private static final Path SOURCE_JOB_SCHEMA = Path.of("src/main/resources/schemas/jobSchemaTypes.xsd");
    private static final Path PUBLIC_JOB_SCHEMA = Path.of("src/main/webapp/public/jobSchemaTypes.xsd");
    private static final Path SOURCE_SPACE_SCHEMA = Path.of("src/main/resources/schemas/batchSpaceSchema.xsd");
    private static final Path PUBLIC_SPACE_SCHEMA = Path.of("src/main/webapp/public/batchSpaceSchema.xsd");

    @Test
    public void schemaPoliciesMatchServerDescriptionBehavior() throws Exception {
        assertEquals(Files.readString(SOURCE_JOB_SCHEMA), Files.readString(PUBLIC_JOB_SCHEMA));
        assertEquals(Files.readString(SOURCE_SPACE_SCHEMA), Files.readString(PUBLIC_SPACE_SCHEMA));

        String jobPattern = readDescriptionPattern(SOURCE_JOB_SCHEMA);
        String spacePattern = readDescriptionPattern(SOURCE_SPACE_SCHEMA);
        assertEquals(jobPattern, spacePattern);

        Schema schema = compileDescriptionSchema(jobPattern);
        assertAccepted(schema, "C++");
        assertAccepted(schema, "2017-05-22");
        assertAccepted(schema, "https://my-tool.example.com");

        for (char forbidden : new char[]{'<', '>', '"', '\'', '%', ';', ')', '(', '&'}) {
            assertRejected(schema, "before" + forbidden + "after");
        }
    }

    private String readDescriptionPattern(Path schemaPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        Document document = factory.newDocumentBuilder().parse(schemaPath.toFile());
        NodeList simpleTypes = document.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "simpleType");
        for (int index = 0; index < simpleTypes.getLength(); index++) {
            Element simpleType = (Element) simpleTypes.item(index);
            if ("Description".equals(simpleType.getAttribute("name"))) {
                Element pattern = (Element) simpleType
                    .getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "pattern")
                    .item(0);
                return pattern.getAttribute("value");
            }
        }
        throw new AssertionError("Description pattern not found in " + schemaPath);
    }

    private Schema compileDescriptionSchema(String descriptionPattern) throws SAXException {
        String schemaText = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="description">
                <xs:simpleType>
                  <xs:restriction base="xs:string">
                    <xs:pattern value="%s"/>
                  </xs:restriction>
                </xs:simpleType>
              </xs:element>
            </xs:schema>
            """.formatted(escapeXmlAttribute(descriptionPattern));
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        return schemaFactory.newSchema(new StreamSource(new StringReader(schemaText)));
    }

    private void assertAccepted(Schema schema, String description) throws Exception {
        schema.newValidator().validate(new StreamSource(new StringReader(descriptionXml(description))));
    }

    private void assertRejected(Schema schema, String description) throws Exception {
        try {
            schema.newValidator().validate(new StreamSource(new StringReader(descriptionXml(description))));
            fail("Expected schema to reject description containing: " + description);
        } catch (SAXException expected) {
            // Expected validation failure.
        }
    }

    private String descriptionXml(String description) {
        return "<description>" + escapeXmlText(description) + "</description>";
    }

    private String escapeXmlAttribute(String value) {
        return escapeXmlText(value).replace("\"", "&quot;");
    }

    private String escapeXmlText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
