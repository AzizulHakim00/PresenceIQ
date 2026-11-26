package com.presenceiq;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisionMultipartTest {
    @Test
    void sendsImageAndSessionWithoutHttp2Upgrade() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> protocol=new AtomicReference<>();
        AtomicReference<String> upgrade=new AtomicReference<>();
        AtomicReference<String> contentType=new AtomicReference<>();
        AtomicReference<String> body=new AtomicReference<>();
        server.createContext("/detect", exchange -> {
            protocol.set(exchange.getProtocol());
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.ISO_8859_1));
            byte[] result="{\"count\":1,\"trackedCount\":1,\"meanConfidence\":0.8,\"detections\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,result.length);
            try(var output=exchange.getResponseBody()){ output.write(result); }
        });
        server.start();
        try {
            PresenceService service=mock(PresenceService.class);
            when(service.session("test-session")).thenReturn(new Document("status","ACTIVE"));
            when(service.addObservation(eq("test-session"),anyMap(),eq("BROWSER_WEBCAM"),eq("admin")))
                    .thenReturn(new Document("count",1));
            PresenceController controller=new PresenceController(service,
                    "http://127.0.0.1:"+server.getAddress().getPort(),"test-token-long-enough-only-for-ci");
            var image=new MockMultipartFile("file","camera.jpg","image/jpeg",new byte[]{1,2,3});
            Map<String,Object> result=controller.detectFrame("test-session",image,
                    new UsernamePasswordAuthenticationToken("admin","unused"));
            assertEquals("HTTP/1.1",protocol.get());
            assertNull(upgrade.get());
            assertTrue(contentType.get().startsWith("multipart/form-data;"));
            assertTrue(body.get().contains("name=\"file\""));
            assertTrue(body.get().contains("filename=\"frame.jpg\""));
            assertTrue(body.get().contains("Content-Type: image/jpeg"));
            assertTrue(body.get().contains("name=\"session_id\""));
            assertTrue(body.get().contains("test-session"));
            assertEquals(1,((Document)result.get("observation")).getInteger("count"));
        } finally {
            server.stop(0);
        }
    }
}
