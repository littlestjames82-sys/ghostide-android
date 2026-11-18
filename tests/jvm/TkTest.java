package com.ghostide.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** JVM proof suite for Mobile Cycle 3 (S1): the portable
    task record format in TaskStore. Consumes the SAME
    shared fixture as the Python suite
    (ghost-ide/tests/fixtures/task_record_v1.json and its
    expected canonical form): the fixture must validate,
    its canonical form must equal the checked-in string
    byte for byte, and it must still do so after a
    store-and-read round-trip through TaskStore.toJson.
    Also proves the strict validation refuses by name -
    above all the newer-schema refusal, which must name
    the version and never silently mangle the record. */
public class TkTest {
    static int ok = 0, fail = 0;
    static void check(boolean c, String label) {
        if (c) { ok++; System.out.println("  PASS " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }

    static Path fixturePath(String name) {
        String[] tries = {
            "../../../ghost-ide/tests/fixtures/" + name,
            "../../ghost-ide/tests/fixtures/" + name,
            System.getProperty("user.home")
                + "/workspace/ghost-ide/tests/fixtures/" + name,
        };
        for (String t : tries) {
            Path p = Paths.get(t);
            if (Files.isRegularFile(p)) return p;
        }
        return Paths.get(tries[tries.length - 1]);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> freshFixture(String json) {
        return (Map<String, Object>) Json.parse(json);
    }

    public static void main(String[] args) throws Exception {
        String fixtureJson = new String(Files.readAllBytes(
            fixturePath("task_record_v1.json")),
            StandardCharsets.UTF_8);
        String expectedCanonical = new String(
            Files.readAllBytes(fixturePath(
                "task_record_v1.canonical.txt")),
            StandardCharsets.UTF_8).trim();

        /* ---- the shared fixture ---- */
        Object fixture = Json.parse(fixtureJson);
        check(TaskStore.validate(fixture, fixtureJson.length())
                == null,
            "fixture: the shared record validates");
        check(expectedCanonical.equals(
                TaskStore.canonical(fixture)),
            "fixture: canonical form equals the checked-in "
            + "string byte for byte");
        Object roundTripped = Json.parse(
            TaskStore.toJson(fixture));
        check(TaskStore.validate(roundTripped, 0) == null,
            "fixture: the stored form revalidates");
        check(expectedCanonical.equals(
                TaskStore.canonical(roundTripped)),
            "fixture: canonical form is stable through a "
            + "store-and-read round-trip");

        /* ---- summary shape ---- */
        Map<String, Object> sum = TaskStore.summary(
            freshFixture(fixtureJson));
        check("task-20261118-174500-x7k2p9".equals(sum.get("id"))
                && "done".equals(sum.get("status"))
                && "desktop".equals(sum.get("createdBy"))
                && ((Number) sum.get("steps")).longValue() == 4L,
            "summary: id, status, origin and step count read "
            + "from the record");

        /* ---- id rule ---- */
        check(TaskStore.isTaskId("task-20261118-174500-x7k2p9"),
            "id: the fixture id is a task id");
        check(!TaskStore.isTaskId("../escape"),
            "id: traversal is refused");
        check(!TaskStore.isTaskId("has space"),
            "id: a space is refused");
        check(!TaskStore.isTaskId("a..b"),
            "id: a double dot is refused");
        check(!TaskStore.isTaskId(""),
            "id: empty is refused");

        /* ---- validation refuses by name ---- */
        Map<String, Object> m;
        m = freshFixture(fixtureJson);
        m.put("schema", "other");
        check("not a GhostIDE task record".equals(
                TaskStore.validate(m, 0)),
            "refusal: a foreign schema is named");
        m = freshFixture(fixtureJson);
        m.remove("schemaVersion");
        check("task record has no readable schema version"
                .equals(TaskStore.validate(m, 0)),
            "refusal: a missing schema version is named");
        m = freshFixture(fixtureJson);
        m.put("schemaVersion", 2.0);
        String newer = TaskStore.validate(m, 0);
        check(newer != null && newer.contains(
                "written by a newer GhostIDE "
                + "(task schema version 2)")
                && newer.contains("this build reads version 1"),
            "refusal: a newer schema version is refused by "
            + "name, never mangled");
        m = freshFixture(fixtureJson);
        m.put("schemaVersion", 0.0);
        check((TaskStore.validate(m, 0) + "").contains(
                "older than any this build can read"),
            "refusal: schema version 0 is refused by name");
        m = freshFixture(fixtureJson);
        m.put("id", "../escape");
        check("bad task id".equals(TaskStore.validate(m, 0)),
            "refusal: a traversal id is a bad task id");
        m = freshFixture(fixtureJson);
        m.put("kind", "chore");
        check((TaskStore.validate(m, 0) + "").contains(
                "kind must be one of"),
            "refusal: an unknown kind is named");
        m = freshFixture(fixtureJson);
        m.put("title", "");
        check((TaskStore.validate(m, 0) + "").contains(
                "title must be a string"),
            "refusal: an empty title is named");
        m = freshFixture(fixtureJson);
        m.put("createdBy", "phone");
        check((TaskStore.validate(m, 0) + "").contains(
                "createdBy must be desktop or android"),
            "refusal: an unknown origin is named");
        m = freshFixture(fixtureJson);
        m.put("status", "paused");
        check((TaskStore.validate(m, 0) + "").contains(
                "status must be one of"),
            "refusal: an unknown status is named");
        m = freshFixture(fixtureJson);
        @SuppressWarnings("unchecked")
        Map<String, Object> budget =
            (Map<String, Object>) m.get("budget");
        budget.put("stepCap", Boolean.TRUE);
        check((TaskStore.validate(m, 0) + "").contains(
                "budget.stepCap must be an integer"),
            "refusal: a boolean step cap is not an integer");
        m = freshFixture(fixtureJson);
        @SuppressWarnings("unchecked")
        Map<String, Object> appr =
            (Map<String, Object>) m.get("approvals");
        appr.remove("denied");
        check((TaskStore.validate(m, 0) + "").contains(
                "approvals must be an object"),
            "refusal: a missing approval count is named");
        m = freshFixture(fixtureJson);
        @SuppressWarnings("unchecked")
        Map<String, Object> steps =
            (Map<String, Object>) m.get("steps");
        steps.put("count", -1.0);
        check((TaskStore.validate(m, 0) + "").contains(
                "steps must be an object"),
            "refusal: a negative step count is named");
        m = freshFixture(fixtureJson);
        m.put("checkpoint", 42.0);
        check((TaskStore.validate(m, 0) + "").contains(
                "checkpoint must be a string"),
            "refusal: a non-string checkpoint is named");
        m = freshFixture(fixtureJson);
        m.put("createdAt", "yesterday");
        check((TaskStore.validate(m, 0) + "").contains(
                "createdAt must be an ISO 8601"),
            "refusal: a non-timestamp createdAt is named");
        m = freshFixture(fixtureJson);
        m.put("payload", java.util.Arrays.asList(1, 2));
        check((TaskStore.validate(m, 0) + "").contains(
                "payload must be an object"),
            "refusal: a non-object payload is named");
        check("task record must be a JSON object".equals(
                TaskStore.validate(
                    java.util.Arrays.asList(1, 2), 0)),
            "refusal: a non-object body is named");
        check((TaskStore.validate(fixture,
                TaskStore.MAX_BYTES + 1) + "").contains(
                "task record too large (limit 262144 bytes)"),
            "refusal: an oversize body is named with the cap");

        /* ---- forward compatibility ---- */
        m = freshFixture(fixtureJson);
        m.put("futureField", java.util.Arrays.asList(1, 2, 3));
        check(TaskStore.validate(m, 0) == null
                && TaskStore.canonical(m).contains(
                    "\"futureField\":[1,2,3]"),
            "compat: unknown fields are kept, not stripped");

        System.out.println("TkTest: " + ok + " passed, "
            + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
