package com.presenceiq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * All mutations require authenticated roles. Live imagery is forwarded to the
 * internal Python service and is NOT saved by the Java API.
 */
@RestController
@RequestMapping("/api")
public class PresenceController {
    private final PresenceService svc;
    private final RestClient vision;
    private final String visionToken;

    public PresenceController(PresenceService svc,
            @Value("${app.vision.url}") String visionUrl,
            @Value("${app.vision.token}") String visionToken) {
        this.svc=svc;
        this.vision=RestClient.builder().baseUrl(visionUrl).build();
        this.visionToken=visionToken;
    }

    @GetMapping("/health")
    public Map<String,String> health() {
        return Map.of("status","ok","service","presenceiq-api");
    }
    @GetMapping("/me")
    public Map<String,Object> me(Authentication auth) {
        return Map.of("username",auth.getName(),
                "roles",auth.getAuthorities().stream().map(Object::toString).toList());
    }
    @GetMapping("/dashboard")
    public Document dashboard() { return svc.dashboard(); }

    @GetMapping("/sessions")
    public List<Document> sessions() { return svc.sessions(); }
    @PostMapping("/sessions")
    @PreAuthorize("hasRole('ADMIN')")
    public Document createSession(@RequestBody Map<String,Object> body,Authentication auth) {
        return svc.createSession(body,auth.getName());
    }
    @GetMapping("/sessions/{id}")
    public Document session(@PathVariable String id) { return svc.session(id); }
    @PostMapping("/sessions/{id}/start")
    @PreAuthorize("hasRole('ADMIN')")
    public Document start(@PathVariable String id, Authentication auth) {
        return svc.transition(id,"ACTIVE",auth.getName());
    }
    @PostMapping("/sessions/{id}/close")
    @PreAuthorize("hasRole('ADMIN')")
    public Document close(@PathVariable String id, Authentication auth) {
        Document closed = svc.transition(id,"CLOSED",auth.getName());
        String alias = Objects.toString(closed.getOrDefault("cameraAlias",""),"");
        if (!alias.isBlank()) {
            Document camera = svc.camera(alias);
            if ("RTSP".equals(camera.getString("sourceType"))) {
                try { forward("/streams/"+alias+"/stop",Map.of("session_id",id)); }
                catch (RuntimeException ignored) {
                    // A stopped backend ingestion endpoint also forces the RTSP worker to exit.
                }
            }
        }
        return closed;
    }
    @GetMapping("/sessions/{id}/observations")
    public List<Document> observations(@PathVariable String id) { return svc.observations(id); }
    @GetMapping("/sessions/{id}/windows")
    public List<Document> windows(@PathVariable String id) { return svc.windows(id); }
    @PostMapping("/sessions/{id}/observations")
    @PreAuthorize("hasRole('ADMIN')")
    public Document manualObservation(@PathVariable String id,@RequestBody Map<String,Object> body,Authentication auth) {
        return svc.addObservation(id,body,"MANUAL_DEMO",auth.getName());
    }
    @PostMapping("/internal/sessions/{id}/observations")
    @PreAuthorize("hasAnyRole('INGEST','ADMIN')")
    public Document ingest(@PathVariable String id,@RequestBody Map<String,Object> body,Authentication auth) {
        return svc.addObservation(id,body,"EDGE_CAMERA",auth.getName());
    }

    @PostMapping("/internal/sessions/{id}/replay/batch")
    @PreAuthorize("hasAnyRole('INGEST','ADMIN')")
    public Map<String,Object> replayBatch(@PathVariable String id,
            @RequestBody List<Map<String,Object>> observations, Authentication auth) {
        if (observations.isEmpty() || observations.size()>200)
            throw PresenceService.bad("Replay batch must contain 1 to 200 frames.");
        int processed=0;
        for (Map<String,Object> row : observations) {
            svc.addObservation(id,row,"V11_3_REPLAY",auth.getName());
            processed++;
        }
        return Map.of("status","ACCEPTED","processed",processed,
                "note","Replay observations are historical occupancy evidence, not named attendance.");
    }

    @GetMapping("/reviews")
    public List<Document> reviews(@RequestParam(defaultValue="PENDING") String status) {
        return svc.reviews(status);
    }
    @PostMapping("/reviews/{id}/decision")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public Document decide(@PathVariable String id,@RequestBody Map<String,Object> body,Authentication auth) {
        return svc.decideReview(id,body,auth.getName());
    }
    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Document> audit() { return svc.auditEvents(); }

    @GetMapping(value="/sessions/{id}/report.csv",produces="text/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String id) {
        String content=svc.csvReport(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"presenceiq-session-"+id+".csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(content.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/identities")
    @PreAuthorize("hasRole('ADMIN')")
    public Document identity(@RequestBody Map<String,Object> body, Authentication auth) {
        return svc.registerIdentity(body,auth.getName());
    }
    @GetMapping("/identities")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public List<Document> identities() { return svc.identities(); }
    @PostMapping("/checkins")
    @PreAuthorize("hasRole('ADMIN')")
    public Document checkin(@RequestBody Map<String,Object> body, Authentication auth) {
        return svc.createCheckin(body,auth.getName());
    }
    @GetMapping("/sessions/{id}/checkins")
    public List<Document> checkins(@PathVariable String id) { return svc.checkins(id); }
    @PostMapping("/checkins/{id}/verify")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public Document verifyCheckin(@PathVariable String id,@RequestBody Map<String,Object> body,Authentication auth) {
        return svc.verifyCheckin(id,body,auth.getName());
    }

    @GetMapping("/cameras")
    public List<Document> cameras() { return svc.cameras(); }
    @PostMapping("/cameras")
    @PreAuthorize("hasRole('ADMIN')")
    public Document camera(@RequestBody Map<String,Object> body, Authentication auth) {
        return svc.createCamera(body,auth.getName());
    }

    private void requireVision() {
        if (visionToken.length()<20 || visionToken.startsWith("REPLACE_"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Vision service credentials have not been configured.");
    }
    private Map<?,?> forward(String uri, Object payload) {
        requireVision();
        try {
            Map<?,?> result=vision.post().uri(uri).header("X-Vision-Token",visionToken)
                    .contentType(MediaType.APPLICATION_JSON).body(payload)
                    .retrieve().body(Map.class);
            return result==null?Map.of():result;
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Vision service is unavailable or returned an error.");
        }
    }

    /**
     * Browser webcam: explicit user permission via getUserMedia in the frontend.
     * No image files are persisted. Only numeric evidence enters MongoDB.
     */
    @PostMapping(value="/sessions/{id}/frame",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String,Object> detectFrame(@PathVariable String id,
            @RequestPart("file") MultipartFile image,Authentication auth) throws IOException {
        svc.session(id); // validate existence before forwarding
        requireVision();
        if (image.isEmpty() || image.getSize()>2_500_000)
            throw PresenceService.bad("JPEG must be between 1 byte and 2.5 MB.");
        if(!List.of("image/jpeg","image/png").contains(image.getContentType()))
            throw PresenceService.bad("Only JPEG or PNG images are accepted.");
        MultiValueMap<String,Object> data=new LinkedMultiValueMap<>();
        byte[] bytes=image.getBytes();
        data.add("file",new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "frame.jpg"; }
        });
        data.add("session_id",id);
        Map<?,?> detected;
        try {
            detected=vision.post().uri("/detect").header("X-Vision-Token",visionToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(data)
                    .retrieve().body(Map.class);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Vision inference unavailable; check model weights and service logs.");
        }
        if(detected==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Vision service returned no result.");
        Object countValue=detected.get("count");
        Object trackedValue=detected.get("trackedCount");
        Object confValue=detected.get("meanConfidence");
        if(!(countValue instanceof Number) || !(trackedValue instanceof Number) || !(confValue instanceof Number))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Vision response schema mismatch.");
        Document obs=svc.addObservation(id,Map.of(
                "count",countValue,"trackedCount",trackedValue,
                "meanConfidence",confValue),"BROWSER_WEBCAM",auth.getName());
        Map<String,Object> res=new LinkedHashMap<>();
        res.put("observation",obs);
        res.put("detections",detected.containsKey("detections") ? detected.get("detections") : List.of());
        res.put("note","Temporary track IDs are not verified identities.");
        return res;
    }

    // RTSP source URLs remain on the Python edge host. Public API accepts only aliases.
    @PostMapping("/sessions/{id}/streams/{alias}/start")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<?,?> startStream(@PathVariable String id,@PathVariable String alias) {
        if (!"ACTIVE".equals(svc.session(id).getString("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Start the session first.");
        Document camera=svc.camera(alias);
        if(!"RTSP".equals(camera.getString("sourceType"))) throw PresenceService.bad("Camera is not RTSP.");
        return forward("/streams/"+alias+"/start",Map.of("session_id",id));
    }
    @PostMapping("/sessions/{id}/streams/{alias}/stop")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<?,?> stopStream(@PathVariable String id,@PathVariable String alias) {
        svc.session(id); svc.camera(alias);
        return forward("/streams/"+alias+"/stop",Map.of("session_id",id));
    }
}
