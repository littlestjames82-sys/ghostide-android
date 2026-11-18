package com.ghostide.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mobile Cycle 3 (S1): the portable task record format,
 * {@code ghostide.task} version 1 - the phone mirror of
 * server.py's task store. docs/TASK_FORMAT.md in the
 * ghost-ide repo is the single definition both sides
 * implement: the same fields, the same strict validation
 * in the same order with the same refusal words, and the
 * same canonical form. Kept pure (no Android imports) so
 * the JVM suite can drive it with the shared fixture the
 * Python suite also consumes.
 *
 * A task record is data: it describes work and never
 * resumes it (resuming is M2). Nothing here executes,
 * interprets or syncs anything.
 */
public final class TaskStore {

    public static final String SCHEMA = "ghostide.task";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BYTES = 256 * 1024;

    private static final Pattern ID_RE = Pattern.compile(
        "^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$");
    private static final Pattern TS_RE = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
        + "(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");

    private static final List<String> KINDS = Arrays.asList(
        "agent-run", "plan", "note");
    private static final List<String> STATUSES = Arrays.asList(
        "draft", "running", "waiting-approval", "stopped",
        "done", "failed");

    private TaskStore() {}

    public static boolean isTaskId(String id) {
        return id != null && ID_RE.matcher(id).matches()
            && !id.contains("..");
    }

    private static boolean isInt(Object v) {
        return v instanceof Number
            && ((Number) v).doubleValue()
                == Math.floor(((Number) v).doubleValue())
            && Math.abs(((Number) v).doubleValue()) < 9.0e15;
    }

    private static long intVal(Object v) {
        return ((Number) v).longValue();
    }

    private static int chars(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String strVal(Map<String, Object> m,
                                 String key) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    /**
     * The shared strict validation from TASK_FORMAT.md,
     * in the document's order. Returns the refusal words,
     * or null when the record is a valid version 1 task
     * record. {@code rawLen} is the incoming body size;
     * readers validating a stored record pass 0 (the cap
     * is enforced on the way in, and the stored
     * pretty-printed file can be larger than the body).
     */
    public static String validate(Object parsed, int rawLen) {
        if (rawLen > MAX_BYTES)
            return "task record too large (limit "
                + MAX_BYTES + " bytes)";
        if (!(parsed instanceof Map))
            return "task record must be a JSON object";
        @SuppressWarnings("unchecked")
        Map<String, Object> rec = (Map<String, Object>) parsed;
        if (!SCHEMA.equals(rec.get("schema")))
            return "not a GhostIDE task record";
        Object ver = rec.get("schemaVersion");
        if (!isInt(ver))
            return "task record has no readable schema version";
        long v = intVal(ver);
        if (v > SCHEMA_VERSION)
            return "task record was written by a newer "
                + "GhostIDE (task schema version " + v
                + "); this build reads version "
                + SCHEMA_VERSION;
        if (v < 1)
            return "task record schema version " + v
                + " is older than any this build can read";
        if (!isTaskId(strVal(rec, "id")))
            return "bad task id";
        if (!KINDS.contains(rec.get("kind")))
            return "task record kind must be one of "
                + "agent-run, plan, note";
        String title = strVal(rec, "title");
        if (title == null || chars(title) < 1
                || chars(title) > 200)
            return "task record title must be a string of "
                + "1 to 200 characters";
        String goal = strVal(rec, "goal");
        if (goal == null || chars(goal) > 20000)
            return "task record goal must be a string of "
                + "at most 20000 characters";
        String ws = strVal(rec, "workspace");
        if (ws == null || chars(ws) > 1000)
            return "task record workspace must be a string "
                + "of at most 1000 characters";
        Object by = rec.get("createdBy");
        if (!"desktop".equals(by) && !"android".equals(by))
            return "task record createdBy must be desktop "
                + "or android";
        if (!STATUSES.contains(rec.get("status")))
            return "task record status must be one of "
                + "draft, running, waiting-approval, "
                + "stopped, done, failed";
        Object budget = rec.get("budget");
        if (!(budget instanceof Map))
            return "task record budget.stepCap must be an "
                + "integer from 0 to 100000";
        Object cap = ((Map<?, ?>) budget).get("stepCap");
        if (!isInt(cap) || intVal(cap) < 0
                || intVal(cap) > 100000)
            return "task record budget.stepCap must be an "
                + "integer from 0 to 100000";
        Object appr = rec.get("approvals");
        boolean apprOk = appr instanceof Map;
        if (apprOk) {
            Map<?, ?> a = (Map<?, ?>) appr;
            for (String k : new String[] {
                    "asked", "allowed", "denied"}) {
                Object n = a.get(k);
                if (!isInt(n) || intVal(n) < 0) {
                    apprOk = false;
                    break;
                }
            }
        }
        if (!apprOk)
            return "task record approvals must be an object "
                + "with asked, allowed and denied counts at "
                + "or above 0";
        Object steps = rec.get("steps");
        boolean stepsOk = steps instanceof Map;
        if (stepsOk) {
            Map<?, ?> s = (Map<?, ?>) steps;
            Object count = s.get("count");
            Object label = s.get("lastLabel");
            stepsOk = isInt(count) && intVal(count) >= 0
                && label instanceof String
                && chars((String) label) <= 300;
        }
        if (!stepsOk)
            return "task record steps must be an object "
                + "with a count at or above 0 and a "
                + "lastLabel string of at most 300 "
                + "characters";
        for (String key : new String[] {
                "checkpoint", "planPath"}) {
            Object val = rec.get(key);
            if (val != null && (!(val instanceof String)
                    || chars((String) val) > 1000))
                return "task record " + key + " must be a "
                    + "string of at most 1000 characters "
                    + "or null";
        }
        for (String key : new String[] {
                "createdAt", "updatedAt"}) {
            String ts = strVal(rec, key);
            if (ts == null || !TS_RE.matcher(ts).matches())
                return "task record " + key + " must be an "
                    + "ISO 8601 timestamp string";
        }
        if (rec.containsKey("provenance")
                && !(rec.get("provenance") instanceof Map))
            return "task record provenance must be an object";
        if (rec.containsKey("payload")
                && !(rec.get("payload") instanceof Map))
            return "task record payload must be an object";
        return null;
    }

    /** The list-view summary of one valid record, in the
        desktop server's shape. */
    public static Map<String, Object> summary(
            Map<String, Object> rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rec.get("id"));
        out.put("kind", rec.get("kind"));
        out.put("title", rec.get("title"));
        out.put("status", rec.get("status"));
        out.put("createdBy", rec.get("createdBy"));
        out.put("createdAt", rec.get("createdAt"));
        out.put("updatedAt", rec.get("updatedAt"));
        long count = 0;
        Object steps = rec.get("steps");
        if (steps instanceof Map) {
            Object c = ((Map<?, ?>) steps).get("count");
            if (isInt(c)) count = intVal(c);
        }
        out.put("steps", count);
        return out;
    }

    /* ---------- canonical form ---------- */

    private static String numStr(Number n) {
        double d = n.doubleValue();
        if (d == Math.floor(d) && Math.abs(d) < 1e15)
            return Long.toString((long) d);
        return Double.toString(d);
    }

    /** The canonical form from TASK_FORMAT.md: sorted
        keys, no spare whitespace, integral numbers
        without a decimal point. */
    public static String canonical(Object v) {
        StringBuilder b = new StringBuilder();
        canonicalInto(v, b);
        return b.toString();
    }

    private static void canonicalInto(Object v,
                                      StringBuilder b) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof Boolean) {
            b.append(v.toString());
            return;
        }
        if (v instanceof Number) {
            b.append(numStr((Number) v));
            return;
        }
        if (v instanceof String) {
            b.append(Json.quote((String) v));
            return;
        }
        if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object x : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                canonicalInto(x, b);
            }
            b.append(']');
            return;
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet())
                keys.add(String.valueOf(k));
            Collections.sort(keys);
            b.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) b.append(',');
                first = false;
                b.append(Json.quote(k)).append(':');
                canonicalInto(m.get(k), b);
            }
            b.append('}');
            return;
        }
        b.append("null");
    }

    /* ---------- storage form ---------- */

    /** The stored form: the desktop's json.dumps with a
        one-space indent, mirrored closely enough that a
        record written here reads identically there (the
        canonical form above is the byte-exact contract;
        the stored layout is a courtesy). */
    public static String toJson(Object v) {
        StringBuilder b = new StringBuilder();
        prettyInto(v, b, 0);
        return b.toString();
    }

    private static void indent(StringBuilder b, int n) {
        for (int i = 0; i < n; i++) b.append(' ');
    }

    private static void prettyInto(Object v, StringBuilder b,
                                   int depth) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof Boolean) {
            b.append(v.toString());
            return;
        }
        if (v instanceof Number) {
            b.append(numStr((Number) v));
            return;
        }
        if (v instanceof String) {
            b.append(Json.quote((String) v));
            return;
        }
        if (v instanceof List) {
            List<?> list = (List<?>) v;
            if (list.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(b, depth + 1);
                prettyInto(list.get(i), b, depth + 1);
                if (i + 1 < list.size()) b.append(',');
                b.append('\n');
            }
            indent(b, depth);
            b.append(']');
            return;
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                indent(b, depth + 1);
                b.append(Json.quote(String.valueOf(
                    e.getKey()))).append(": ");
                prettyInto(e.getValue(), b, depth + 1);
                if (++i < m.size()) b.append(',');
                b.append('\n');
            }
            indent(b, depth);
            b.append('}');
            return;
        }
        b.append("null");
    }
}
