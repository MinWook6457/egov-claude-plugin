package kr.egovclaude.eclipse.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Claude Code 가 저장한 세션 기록(~/.claude/projects/&lt;작업폴더&gt;/&lt;세션ID&gt;.jsonl)을 읽는다.
 * 터미널에서 같은 폴더로 실행한 세션도 함께 보인다.
 */
public final class SessionStore {

    private static final int TITLE_SCAN_LINES = 300;

    private SessionStore() {
    }

    public static final class SessionInfo {
        public final String id;
        public final String title;
        public final long lastModified;
        public final File file;

        SessionInfo(String id, String title, long lastModified, File file) {
            this.id = id;
            this.title = title;
            this.lastModified = lastModified;
            this.file = file;
        }
    }

    /** 이전 대화의 한 항목. kind 는 user, assistant, tool 중 하나. */
    public static final class Entry {
        public final String kind;
        public final String text;
        public final String toolName;
        public final JsonObject toolInput;

        Entry(String kind, String text, String toolName, JsonObject toolInput) {
            this.kind = kind;
            this.text = text;
            this.toolName = toolName;
            this.toolInput = toolInput;
        }
    }

    /** Claude Code 는 작업 폴더 경로의 영문·숫자 이외 문자를 '-' 로 바꾼 이름의 폴더에 세션을 저장한다. */
    public static File sessionDir(File workDir) {
        String name = workDir.getAbsolutePath().replaceAll("[^A-Za-z0-9]", "-");
        return new File(System.getProperty("user.home"), ".claude/projects/" + name);
    }

    /** 최근에 쓴 순서로 세션 목록을 돌려준다. 사용자 메시지가 없는 빈 세션은 뺀다. */
    public static List<SessionInfo> list(File workDir, int limit) {
        File dir = sessionDir(workDir);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files == null) return Collections.emptyList();
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        List<SessionInfo> out = new ArrayList<SessionInfo>();
        for (File f : files) {
            if (out.size() >= limit) break;
            String title = readTitle(f);
            if (title == null) continue;
            out.add(new SessionInfo(f.getName().substring(0, f.getName().length() - ".jsonl".length()), title, f.lastModified(), f));
        }
        return out;
    }

    /** 사용자가 붙인 제목(custom-title) → 요약(summary) → 첫 질문 순으로 제목을 고른다. */
    private static String readTitle(File f) {
        String custom = null;
        String summary = null;
        String firstPrompt = null;
        JsonParser parser = new JsonParser();
        try (BufferedReader r = reader(f)) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < TITLE_SCAN_LINES) {
                JsonObject o = parse(parser, line);
                if (o == null) continue;
                String type = ClaudeSession.str(o, "type");
                if ("custom-title".equals(type)) custom = ClaudeSession.str(o, "customTitle");
                else if ("summary".equals(type) && summary == null) summary = ClaudeSession.str(o, "summary");
                else if ("user".equals(type) && firstPrompt == null && isRealUserLine(o)) firstPrompt = userText(o);
            }
        } catch (IOException e) {
            return null;
        }
        if (firstPrompt == null && custom == null) return null;
        String title = custom != null ? custom : summary != null ? summary : firstPrompt;
        title = title.replaceAll("\\s+", " ").trim();
        return title.length() > 90 ? title.substring(0, 90) + "…" : title;
    }

    /** 화면에 다시 그릴 대화 항목을 읽는다. 너무 길면 뒤쪽 maxEntries 개만 남긴다. 앞에서 버린 개수를 dropped[0] 에 담는다. */
    public static List<Entry> loadHistory(File f, int maxEntries, int[] dropped) {
        LinkedList<Entry> out = new LinkedList<Entry>();
        JsonParser parser = new JsonParser();
        int drop = 0;
        try (BufferedReader r = reader(f)) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonObject o = parse(parser, line);
                if (o == null) continue;
                String type = ClaudeSession.str(o, "type");
                if ("user".equals(type) && isRealUserLine(o)) {
                    out.add(new Entry("user", userText(o), null, null));
                } else if ("assistant".equals(type) && !isSidechain(o)) {
                    JsonObject message = o.getAsJsonObject("message");
                    JsonArray content = message == null ? null : message.getAsJsonArray("content");
                    if (content == null) continue;
                    for (JsonElement e : content) {
                        JsonObject block = e.getAsJsonObject();
                        String bt = ClaudeSession.str(block, "type");
                        if ("text".equals(bt)) {
                            String text = ClaudeSession.str(block, "text");
                            if (text != null && !text.trim().isEmpty()) out.add(new Entry("assistant", text, null, null));
                        } else if ("tool_use".equals(bt)) {
                            JsonObject input = block.has("input") && block.get("input").isJsonObject()
                                    ? block.getAsJsonObject("input") : new JsonObject();
                            out.add(new Entry("tool", null, ClaudeSession.str(block, "name"), input));
                        }
                    }
                }
                while (out.size() > maxEntries) {
                    out.removeFirst();
                    drop++;
                }
            }
        } catch (IOException ignored) {
        }
        if (dropped != null && dropped.length > 0) dropped[0] = drop;
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** 도구 결과, 내부 메타 메시지, 슬래시 명령 출력이 아닌 사람이 쓴 메시지인지 */
    private static boolean isRealUserLine(JsonObject o) {
        if (isSidechain(o)) return false;
        if (o.has("isMeta") && o.get("isMeta").getAsBoolean()) return false;
        String text = userText(o);
        return text != null && !text.trim().isEmpty() && !text.trim().startsWith("<");
    }

    private static boolean isSidechain(JsonObject o) {
        return o.has("isSidechain") && o.get("isSidechain").getAsBoolean();
    }

    private static String userText(JsonObject o) {
        JsonObject message = o.getAsJsonObject("message");
        if (message == null || !message.has("content")) return null;
        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) return content.getAsString();
        if (!content.isJsonArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : content.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject block = e.getAsJsonObject();
            String bt = ClaudeSession.str(block, "type");
            if ("tool_result".equals(bt)) return null;
            if ("text".equals(bt)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(ClaudeSession.str(block, "text"));
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static JsonObject parse(JsonParser parser, String line) {
        if (line.isEmpty() || line.charAt(0) != '{') return null;
        try {
            JsonElement e = parser.parse(line);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BufferedReader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
    }
}
