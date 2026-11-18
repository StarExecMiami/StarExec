package org.starexec.test.junit.app;

import org.junit.jupiter.api.Test;
import org.starexec.app.Starexec;
import org.starexec.servlets.EmailExecutorContextListener;
import org.starexec.servlets.UploadJobWorker;

import javax.servlet.annotation.WebListener;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class StartupConfigurationTests {
    private static final List<String> EXPECTED_LISTENERS = Arrays.asList(
        Starexec.class.getName(),
        EmailExecutorContextListener.class.getName(),
        UploadJobWorker.class.getName()
    );

    @Test
    public void webXmlDeclaresStartupListenersInOrder() throws Exception {
        File webXml = resolveWebXml();
        assertTrue(webXml.exists(), "Expected web.xml to exist at " + webXml.getAbsolutePath());

        List<String> listenerClasses = readListenerClasses(webXml);
        assertTrue(
            listenerClasses.size() >= EXPECTED_LISTENERS.size(),
            "web.xml must declare at least the startup listeners in order"
        );
        assertEquals(
            EXPECTED_LISTENERS,
            listenerClasses.subList(0, EXPECTED_LISTENERS.size()),
            "web.xml startup listener order changed"
        );
    }

    @Test
    public void startupListenersAreDeclaredOnlyInWebXml() {
        assertNull(Starexec.class.getAnnotation(WebListener.class),
            "Starexec must not use @WebListener because web.xml controls ordering");
        assertNull(EmailExecutorContextListener.class.getAnnotation(WebListener.class),
            "EmailExecutorContextListener must not be auto-discovered by annotation scanning");
        assertNull(UploadJobWorker.class.getAnnotation(WebListener.class),
            "UploadJobWorker must not be auto-discovered by annotation scanning");
    }

    private static File resolveWebXml() {
        List<File> candidates = Arrays.asList(
            new File("src/main/webapp/WEB-INF/web.xml"),
            new File("starexec-app/src/main/webapp/WEB-INF/web.xml")
        );

        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate;
            }
        }

        StringBuilder message = new StringBuilder("Could not locate web.xml. Tried:");
        for (File candidate : candidates) {
            message.append('\n').append(" - ").append(candidate.getAbsolutePath());
        }
        fail(message.toString());
        return candidates.get(0);
    }

    private static List<String> readListenerClasses(File webXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(webXml);
        NodeList listeners = document.getElementsByTagName("listener");
        List<String> listenerClasses = new ArrayList<>();

        for (int i = 0; i < listeners.getLength(); i++) {
            Node node = listeners.item(i);
            if (!(node instanceof Element)) {
                fail("Expected <listener> element but found: " + node.getClass().getName());
            }

            Element listener = (Element) node;
            String listenerClass = null;
            for (int j = 0; j < listener.getChildNodes().getLength(); j++) {
                Node child = listener.getChildNodes().item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE && "listener-class".equals(child.getNodeName())) {
                    listenerClass = child.getTextContent().trim();
                    break;
                }
            }

            if (listenerClass == null) {
                fail("Missing <listener-class> inside listener element #" + (i + 1));
            }

            listenerClasses.add(listenerClass);
        }

        return listenerClasses;
    }
}
