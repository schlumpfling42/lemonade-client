package net.rka.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests: result formatting, HTML to text, and the private-address guard. */
class WebToolsTest {

    @Test
    void aDownloadRefusesLocalAddressesAndPathsOutsideTheFolderAndLeavesNothing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        var parameters = dev.langchain4j.invocation.InvocationParameters.from(WorkFolder.PARAMETER, folder);
        var tools = new WebTools();

        assertThrows(Exception.class, () -> tools.downloadFile("http://127.0.0.1:9/file.bin", "gradle/file.bin", parameters));
        assertThrows(Exception.class, () -> tools.downloadFile("https://example.com/file.bin", "../outside.bin", parameters));
        try (var files = java.nio.file.Files.walk(folder)) {
            assertEquals(java.util.List.of(folder), files.toList(), "no file, no half-written .part file");
        }
    }

    @Test
    void everyDownloadIsAskedOfTheUser() {
        assertTrue(ToolRegistry.of(new WebTools()).specifications().stream().anyMatch(t -> t.name().equals("downloadFile")));
        assertTrue(ToolRegistry.of(new WebTools()).needsApproval(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name("downloadFile").arguments("{}").build(), TrustedCommands.of(java.util.List.of()), false));
    }

    @Test
    void formatsSearxngResults() throws Exception {
        String json = """
                {"results": [
                  {"title": "LangChain4j", "url": "https://docs.langchain4j.dev/", "content": "Java  LLM\\n library"},
                  {"title": "No URL"},
                  {"title": "Second", "url": "https://example.com/2", "content": ""},
                  {"title": "Third", "url": "https://example.com/3"}
                ]}""";
        String text = WebTools.formatResults(new ObjectMapper().readTree(json), 2);
        assertEquals("""
                1. LangChain4j
                   https://docs.langchain4j.dev/
                   Java LLM library
                2. Second
                   https://example.com/2""", text);
        assertEquals("No results.", WebTools.formatResults(new ObjectMapper().readTree("{\"results\": []}"), 5));
    }

    @Test
    void htmlBecomesReadableMainText() {
        String html = """
                <html><head><title> Hello </title><style>p{}</style></head><body>
                <nav>Menu</nav><main><h1>Heading</h1><p>First   paragraph.</p>
                <script>alert(1)</script><p>Second.</p></main><footer>Footer</footer></body></html>""";
        String text = WebTools.toText(URI.create("https://example.com/"), "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8));
        assertTrue(text.startsWith("Title: Hello\nURL: https://example.com/\n\n"), text);
        assertTrue(text.contains("Heading") && text.contains("First paragraph.") && text.contains("Second."), text);
        assertFalse(text.contains("Menu") || text.contains("Footer") || text.contains("alert"), text);
    }

    @Test
    void refusesBinaryContent() {
        assertThrows(IllegalArgumentException.class,
                () -> WebTools.toText(URI.create("https://example.com/a.png"), "image/png", new byte[] {1, 2}));
    }

    @Test
    void refusesLocalAndPrivateTargets() {
        for (String url : new String[] {"http://localhost/", "http://127.0.0.1:8123/", "http://192.168.68.59:8888/",
                "http://10.0.0.1/", "http://[::1]/", "file:///etc/passwd", "ftp://example.com/"}) {
            assertThrows(IllegalArgumentException.class, () -> WebTools.checkPublicUrl(url), url);
        }
    }

    @Test
    void classifiesAddresses() throws Exception {
        assertTrue(WebTools.isPrivate(InetAddress.getByName("172.16.0.5")));
        assertTrue(WebTools.isPrivate(InetAddress.getByName("100.64.1.1")));
        assertTrue(WebTools.isPrivate(InetAddress.getByName("fd00::1")));
        assertTrue(WebTools.isPrivate(InetAddress.getByName("169.254.1.1")));
        assertFalse(WebTools.isPrivate(InetAddress.getByName("93.184.216.34")));
        assertFalse(WebTools.isPrivate(InetAddress.getByName("2606:4700::1111")));
    }
}
