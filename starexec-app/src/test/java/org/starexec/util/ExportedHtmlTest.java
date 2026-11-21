package org.starexec.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportedHtmlTest {
    @Test
    void preservesUtf8AndLineEndingsInExportedHtml() throws Exception {
        String html = "<!doctype html>\r\n<script>\n"
                + "const label = \"résultat\"\r\n"
                + "// Explanation\n\n"
                + "document.title = label\n</script>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertEquals(html, Util.getWebPage(url, List.of()));
        } finally {
            server.stop(0);
        }
    }
}
