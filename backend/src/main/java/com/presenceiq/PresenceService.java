package com.presenceiq;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * PresenceIQ observation domain.
 *
 * Safety invariant: temporary tracker IDs and occupancy observations NEVER create
 * a named attendance decision or a verified person identity.
 */
@Service
public class PresenceService {
    private final MongoTemplate mongo;
    public PresenceService(MongoTemplate mongo) { this.mongo = mongo; }

    static String text(Object raw, int max, String label) {
        String s = raw == null ? "" : raw.toString().trim();
        if (s.isBlank() || s.length() > max) throw bad(label + " is missing or too long.");
        return s;
    }
    static int integer(Object raw, int min, int max, String field) {
        try {
            if (raw == null) throw new IllegalArgumentException();
            int n = Integer.parseInt(raw.toString());
            if (n < min || n > max) throw new IllegalArgumentException();
            return n;
        } catch (Exception ex) { throw bad("Invalid " + field + " (expected " + min + ".." + max + ")."); }
    }
    static double decimal(Object raw, double min, double max, String field) {
        try {
            if (raw == null) throw new IllegalArgumentException();
            double n = Double.parseDouble(raw.toString());
            if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException();
            return n;
        } catch (Exception ex) { throw bad("Invalid " + field + "."); }
    }
    static ResponseStatusException bad(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, text);
    }
    static String choice(Object raw, String field, String... options) {
        String s = text(raw, 60, field).toUpperCase(Locale.ROOT);
        if (!Arrays.asList(options).contains(s)) throw bad("Unsupported " + field + ".");
        return s;
    }
    static Document object(String id) { return new Document("_id", id); }
    static String newId() { return UUID.randomUUID().toString(); }
    static String now() { return Instant.now().toString(); }

    void audit(String actor, String verb, String entity, String entityId, Object details) {
        mongo.insert(object(newId()).append("actor", actor).append("verb", verb)
                .append("entity", entity).append("entityId", entityId)
                .append("details", details).append("occurredAt", now()), "audit_events");
    }
    Document require(String collection, String id) {
        Document doc = mongo.findById(id, Document.class, collection);
        if (doc == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, collection + " record not found");
        return doc;
    }
    List<Document> records(String collection, Criteria criteria, String sortField, int limit) {
        Query q = new Query(criteria).with(Sort.by(Sort.Direction.DESC, sortField)).limit(limit);
        return mongo.find(q, Document.class, collection);
    }
    static int asInt(Object n, int fallback) { return n instanceof Number ? ((Number)n).intValue() : fallback; }
    static double asDouble(Object n, double fallback) { return n instanceof Number ? ((Number)n).doubleValue() : fallback; }

    // Session Manager
    public Document createSession(Map<String,Object> input, String actor) {
        String title = text(input.get("title"), 120, "title");
        String environment = choice(input.get("environment"), "environment", "CLASSROOM","OFFICE","INDUSTRIAL");
        int planned = integer(input.getOrDefault("plannedMinutes", 30), 1, 1440, "plannedMinutes");
        String cameraAlias = Objects.toString(input.getOrDefault("cameraAlias", ""), "").trim();
        if (!cameraAlias.isBlank()) require("cameras", cameraAlias);
        Document s = object(newId()).append("title", title).append("environment", environment)
                .append("cameraAlias", cameraAlias).append("plannedMinutes", planned)
                .append("status", "DRAFT").append("createdBy", actor).append("createdAt", now());
        mongo.insert(s,"sessions");
        audit(actor,"SESSION_CREATED","session",s.getString("_id"),Map.of("environment",environment));
        return s;
    }
    public List<Document> sessions() {
        return records("sessions",new Criteria(),"createdAt",100);
    }
    public Document session(String id) { return require("sessions",id); }

    public Document transition(String id, String next, String actor) {
        String prior = next.equals("ACTIVE") ? "DRAFT" : "ACTIVE";
        Update update = new Update().set("status",next);
        if (next.equals("ACTIVE")) update.set("startedAt",now());
        else if (next.equals("CLOSED")) update.set("closedAt",now());
        Document doc = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id).and("status").is(prior)),
            update,FindAndModifyOptions.options().returnNew(true),Document.class,"sessions");
        if (doc == null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Session status does not allow this transition.");
        audit(actor,"SESSION_"+next,"session",id,Map.of("previousStatus",prior));
        return doc;
    }

    // Time windows aggregate observations, not individual identities.
    public Document addObservation(String id, Map<String,Object> raw, String source, String actor) {
        Document s = require("sessions",id);
        if (!"ACTIVE".equals(s.getString("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Session must be ACTIVE");
        }
        int people = integer(raw.get("count"),0,1000,"count");
        int tracked = integer(raw.getOrDefault("trackedCount",0),0,1000,"trackedCount");
        if (tracked>people) throw bad("trackedCount cannot exceed count");
        double conf = decimal(raw.getOrDefault("meanConfidence",0.0),0.0,1.0,"meanConfidence");
        Instant start = Instant.parse(s.getString("startedAt"));
        long currentElapsed = Math.max(0,Duration.between(start,Instant.now()).toSeconds());
        int elapsed = raw.containsKey("elapsedSeconds")
            ? integer(raw.get("elapsedSeconds"),0,86400,"elapsedSeconds")
            : (int)Math.min(currentElapsed,86400);
        int window = windowFor(elapsed,asInt(s.get("plannedMinutes"),30));
        Query lastQ = Query.query(Criteria.where("sessionId").is(id))
                            .with(Sort.by(Sort.Direction.DESC,"elapsedSeconds")).limit(1);
        Document latest = mongo.findOne(lastQ,Document.class,"observations");
        boolean gap = latest != null && elapsed - asInt(latest.get("elapsedSeconds"),elapsed) > 20;
        boolean needsReview = people==0 || (people>0 && conf<0.50) || gap;
        Document obs = object(newId()).append("sessionId",id).append("source",source)
            .append("elapsedSeconds",elapsed).append("windowIndex",window)
            .append("count",people).append("trackedCount",tracked)
            .append("meanConfidence",conf).append("gapFlag",gap)
            .append("needsReview",needsReview).append("recordedAt",now());
        mongo.insert(obs,"observations");
        if (needsReview) {
            Query existing = Query.query(Criteria.where("sessionId").is(id)
                    .and("windowIndex").is(window).and("status").is("PENDING"));
            if (!mongo.exists(existing,"reviews")) {
                Document review = object(newId()).append("sessionId",id)
                    .append("observationId",obs.getString("_id"))
                    .append("windowIndex",window).append("status","PENDING")
                    .append("reason",people==0?"NO_PERSON_OBSERVED":gap?"OBSERVATION_GAP":"LOW_CONFIDENCE")
                    .append("createdAt",now());
                mongo.insert(review,"reviews");
                audit(actor,"REVIEW_QUEUED","review",review.getString("_id"),
                    Map.of("windowIndex",window,"reason",review.getString("reason")));
            }
        }
        return obs;
    }
    public static int windowFor(int elapsedSeconds, int plannedMinutes) {
        return Math.min(4, Math.max(0, (int)Math.floor(elapsedSeconds * 5.0 / (plannedMinutes*60.0))));
    }
    public List<Document> observations(String id) {
        require("sessions",id);
        Query q=Query.query(Criteria.where("sessionId").is(id)).with(Sort.by("elapsedSeconds"));
        return mongo.find(q,Document.class,"observations");
    }
    public List<Document> windows(String id) {
        Document s=require("sessions",id);
        int planned=asInt(s.get("plannedMinutes"),30);
        List<Document> obs=observations(id);
        List<Document> result=new ArrayList<>();
        for (int i=0;i<5;i++) {
            final int index=i;
            List<Document> part=obs.stream().filter(o->asInt(o.get("windowIndex"),-1)==index).toList();
            int n=part.size();
            double avgCount=part.stream().mapToInt(o->asInt(o.get("count"),0)).average().orElse(0);
            double avgConf=part.stream().mapToDouble(o->asDouble(o.get("meanConfidence"),0)).average().orElse(0);
            long problems=part.stream().filter(o->Boolean.TRUE.equals(o.get("needsReview"))).count();
            String status=n==0?"NO_DATA":problems>0 || n<3?"REVIEW_REQUIRED":"OBSERVED";
            result.add(new Document("window",i+1)
                .append("startSeconds",(int)Math.round(i*planned*60/5.0))
                .append("endSeconds",(int)Math.round((i+1)*planned*60/5.0))
                .append("samples",n).append("meanObservedPeople",Math.round(avgCount*100.0)/100.0)
                .append("meanConfidence",Math.round(avgConf*100.0)/100.0)
                .append("reviewSignals",problems).append("evidenceStatus",status));
        }
        return result;
    }

    // Human review is explicitly about evidence, never an automatic attendance approval.
    public List<Document> reviews(String status) {
        Criteria c="ALL".equalsIgnoreCase(status) ? new Criteria() :
            Criteria.where("status").is(choice(status,"status","PENDING","ACCEPT_EVIDENCE","REJECT_EVIDENCE","NEEDS_MORE_EVIDENCE"));
        return records("reviews",c,"createdAt",200);
    }
    public Document decideReview(String id, Map<String,Object> input, String actor) {
        String decision=choice(input.get("decision"),"decision",
            "ACCEPT_EVIDENCE","REJECT_EVIDENCE","NEEDS_MORE_EVIDENCE");
        String note=Objects.toString(input.getOrDefault("note",""),"").trim();
        if (note.length()>1500) throw bad("Note is too long.");
        Document review = mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id).and("status").is("PENDING")),
            new Update().set("status",decision).set("decisionNote",note)
                .set("reviewer",actor).set("reviewedAt",now()),
            FindAndModifyOptions.options().returnNew(true),Document.class,"reviews");
        if (review==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Review not pending or does not exist.");
        audit(actor,"EVIDENCE_"+decision,"review",id,Map.of("note",note));
        return review;
    }
    public List<Document> auditEvents() { return records("audit_events",new Criteria(),"occurredAt",200); }

    // Authorized identity claims are independent of the computer-vision tracker.
    public Document registerIdentity(Map<String,Object> input, String actor) {
        String externalCode=text(input.get("externalCode"),80,"externalCode");
        String displayName=text(input.get("displayName"),120,"displayName");
        String kind=choice(input.get("memberType"),"memberType","STUDENT","EMPLOYEE");
        if(mongo.exists(Query.query(Criteria.where("externalCode").is(externalCode)),"identities"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"External code already registered");
        Document d=object(newId()).append("externalCode",externalCode).append("displayName",displayName)
            .append("memberType",kind).append("createdAt",now()).append("registeredBy",actor);
        mongo.insert(d,"identities");
        audit(actor,"IDENTITY_REGISTERED","identity",d.getString("_id"),Map.of("memberType",kind));
        return d;
    }
    public List<Document> identities() { return records("identities",new Criteria(),"createdAt",250); }

    public Document createCheckin(Map<String,Object> input, String actor) {
        String id=text(input.get("identityId"),100,"identityId");
        String sid=text(input.get("sessionId"),100,"sessionId");
        require("identities",id);
        Document s=require("sessions",sid);
        if(!"ACTIVE".equals(s.getString("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Session must be active.");
        String method=choice(input.get("method"),"method","QR","NFC","MANUAL");
        String evidence=text(input.get("evidenceRef"),120,"evidenceRef");
        Document c=object(newId()).append("identityId",id).append("sessionId",sid)
            .append("method",method).append("evidenceRef",evidence).append("status","CLAIMED")
            .append("createdBy",actor).append("createdAt",now());
        mongo.insert(c,"checkins");
        audit(actor,"IDENTITY_CLAIMED","checkin",c.getString("_id"),Map.of("method",method));
        return c;
    }
    public List<Document> checkins(String sessionId) {
        return records("checkins",Criteria.where("sessionId").is(sessionId),"createdAt",250);
    }
    public Document verifyCheckin(String id, Map<String,Object> input, String actor) {
        String verdict=choice(input.get("decision"),"decision","VERIFIED","REJECTED");
        String note=text(input.get("note"),500,"note");
        Document c=mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id).and("status").is("CLAIMED")),
            new Update().set("status",verdict).set("reviewedBy",actor)
                .set("reviewNote",note).set("reviewedAt",now()),
            FindAndModifyOptions.options().returnNew(true),Document.class,"checkins");
        if(c==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Check-in not pending.");
        audit(actor,"CHECKIN_"+verdict,"checkin",id,Map.of("note",note));
        return c;
    }

    // Camera registry stores aliases only, never RTSP credentials.
    public Document createCamera(Map<String,Object> input, String actor) {
        String alias=text(input.get("alias"),64,"alias");
        if(!alias.matches("[A-Za-z0-9_-]+")) throw bad("Camera alias: letters, digits, _ and - only");
        String label=text(input.get("label"),100,"label");
        String kind=choice(input.get("sourceType"),"sourceType","BROWSER","RTSP");
        if(mongo.exists(Query.query(Criteria.where("_id").is(alias)),"cameras"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Camera alias exists");
        Document camera=object(alias).append("label",label).append("sourceType",kind)
            .append("createdBy",actor).append("createdAt",now());
        mongo.insert(camera,"cameras");
        audit(actor,"CAMERA_CREATED","camera",alias,Map.of("sourceType",kind));
        return camera;
    }
    public List<Document> cameras() { return records("cameras",new Criteria(),"createdAt",200); }
    public Document camera(String alias) { return require("cameras",alias); }

    public Document dashboard() {
        return new Document("sessions",mongo.count(new Query(),"sessions"))
            .append("activeSessions",mongo.count(Query.query(Criteria.where("status").is("ACTIVE")),"sessions"))
            .append("pendingReviews",mongo.count(Query.query(Criteria.where("status").is("PENDING")),"reviews"))
            .append("registeredIdentities",mongo.count(new Query(),"identities"))
            .append("cameras",mongo.count(new Query(),"cameras"));
    }
    public static String safeCell(Object value) {
        String s=Objects.toString(value,"");
        if(!s.isEmpty() && "=+-@".indexOf(s.charAt(0))>=0) s="'"+s;
        return "\"" + s.replace("\"","\"\"").replace("\r"," ").replace("\n"," ") + "\"";
    }
    public String csvReport(String id) {
        Document s=require("sessions",id);
        StringBuilder out=new StringBuilder("session_id,session_title,window,window_start_seconds,window_end_seconds,samples,average_people,average_confidence,evidence_status,review_signals\r\n");
        for(Document w:windows(id)){
            Object[] cells={id,s.get("title"),w.get("window"),w.get("startSeconds"),w.get("endSeconds"),w.get("samples"),w.get("meanObservedPeople"),w.get("meanConfidence"),w.get("evidenceStatus"),w.get("reviewSignals")};
            out.append(Arrays.stream(cells).map(PresenceService::safeCell).collect(Collectors.joining(","))).append("\r\n");
        }
        return out.toString();
    }
}
