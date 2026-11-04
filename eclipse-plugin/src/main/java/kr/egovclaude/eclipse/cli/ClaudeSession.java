package kr.egovclaude.eclipse.cli;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import kr.egovclaude.eclipse.Activator;
import kr.egovclaude.eclipse.prefs.Prefs;
import kr.egovclaude.eclipse.prompt.EgovPrompts;

/**
 * Claude Code CLI 를 stream-json 입출력 모드로 띄워 두고 한 줄짜리 JSON 메시지를 주고받는다.
 * <p>
 * 권한 확인은 --permission-prompt-tool stdio 로 받는다. CLI 가 control_request(can_use_tool)를
 * 보내면 화면에서 허용/거부를 고른 뒤 control_response 로 답한다.
 */
public final class ClaudeSession {

    private final ClaudeListener listener;
    private final JsonParser parser = new JsonParser();

    private File workDir;
    private Process process;
    private Writer stdin;
    private volatile String sessionId;
    private volatile String currentMessageId = "";
    private PermissionMode mode;
    private int requestSeq;
    /** 스트리밍으로 이미 화면에 그린 메시지 ID. 완성본 assistant 메시지에서 텍스트를 다시 그리지 않으려고 쓴다. */
    private final Set<String> streamedMessages = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<Process> stoppedProcesses = Collections.newSetFromMap(new ConcurrentHashMap<Process, Boolean>());

    public ClaudeSession(ClaudeListener listener) {
        this.listener = listener;
        this.mode = PermissionMode.of(Prefs.string(Prefs.PERMISSION_MODE));
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /** 대화가 시작된 적이 있는지. 작업 폴더를 바꿀 때 기존 대화를 버릴지 판단하는 데 쓴다. */
    public boolean hasConversation() {
        return sessionId != null || isRunning();
    }

    public synchronized File getWorkDir() {
        return workDir;
    }

    /** 작업 폴더(보통 Eclipse 프로젝트 폴더)를 바꾼다. 바뀌면 기존 대화는 끝낸다. */
    public synchronized void setWorkDir(File dir) {
        if (dir != null && dir.equals(workDir)) return;
        reset();
        workDir = dir;
    }

    /** 저장된 세션을 이어 간다. 다음 메시지를 보낼 때 --resume 으로 시작한다. */
    public synchronized void resume(String id) {
        stopProcess();
        sessionId = id;
        streamedMessages.clear();
    }

    public String getSessionId() {
        return sessionId;
    }

    public synchronized PermissionMode getMode() {
        return mode;
    }

    public synchronized void sendUserMessage(String text) throws IOException {
        ensureStarted();
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", contents);
        JsonObject root = new JsonObject();
        root.addProperty("type", "user");
        root.add("message", message);
        write(root);
    }

    public synchronized void setMode(PermissionMode newMode) {
        this.mode = newMode;
        if (isRunning()) {
            JsonObject req = new JsonObject();
            req.addProperty("subtype", "set_permission_mode");
            req.addProperty("mode", newMode.id());
            sendControlRequest(req);
        }
    }

    /** 진행 중인 응답을 멈춘다. 세션은 유지된다. */
    public synchronized void interrupt() {
        if (!isRunning()) return;
        JsonObject req = new JsonObject();
        req.addProperty("subtype", "interrupt");
        sendControlRequest(req);
    }

    public synchronized void respondPermission(ClaudeListener.PermissionRequest request, boolean allow,
                                               boolean rememberForSession) {
        JsonObject decision = new JsonObject();
        if (allow) {
            decision.addProperty("behavior", "allow");
            decision.add("updatedInput", request.input);
            if (rememberForSession && request.suggestions != null) {
                decision.add("updatedPermissions", request.suggestions);
            }
        } else {
            decision.addProperty("behavior", "deny");
            decision.addProperty("message", "사용자가 거부했습니다.");
        }
        JsonObject response = new JsonObject();
        response.addProperty("subtype", "success");
        response.addProperty("request_id", request.requestId);
        response.add("response", decision);
        JsonObject root = new JsonObject();
        root.addProperty("type", "control_response");
        root.add("response", response);
        safeWrite(root);
    }

    /** 프로세스를 종료하고 대화 기록을 버린다. 다음 메시지부터 새 세션이 시작된다. */
    public synchronized void reset() {
        stopProcess();
        sessionId = null;
        streamedMessages.clear();
    }

    public synchronized void dispose() {
        stopProcess();
    }

    // ---------------------------------------------------------------- process

    private void ensureStarted() throws IOException {
        if (isRunning()) return;

        String exe = ClaudeCliLocator.locate();
        if (exe == null) {
            throw new IOException("claude 실행 파일을 찾을 수 없습니다. "
                    + "Claude Code 를 설치하거나 Window > Preferences > eGov Claude 에서 경로를 지정하세요.");
        }

        List<String> cmd = new ArrayList<String>();
        if (ClaudeCliLocator.isWindows() && exe.toLowerCase().endsWith(".cmd")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.addAll(Arrays.asList(exe,
                "-p",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--permission-prompt-tool", "stdio",
                "--permission-mode", mode.id()));
        String model = Prefs.string(Prefs.MODEL);
        if (!model.isEmpty()) {
            cmd.add("--model");
            cmd.add(model);
        }
        File promptFile = EgovPrompts.writeSystemPromptFile(workDir);
        if (promptFile != null) {
            // 여러 줄 문자열을 명령행으로 넘기면 Windows 에서 깨지기 쉬워 파일로 전달한다.
            cmd.add("--append-system-prompt-file");
            cmd.add(promptFile.getAbsolutePath());
        }
        if (sessionId != null) {
            // 프로세스가 죽은 뒤 다시 보내면 같은 대화를 이어 간다.
            cmd.add("--resume");
            cmd.add(sessionId);
        }
        cmd.addAll(splitArgs(Prefs.string(Prefs.EXTRA_ARGS)));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir);
        pb.environment().put("CLAUDE_CODE_ENTRYPOINT", "egov-claude-eclipse");

        Activator.log("Starting Claude CLI: " + cmd, null);
        final Process p = pb.start();
        process = p;
        stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));

        final StringBuffer stderrTail = new StringBuffer();
        daemon("claude-stderr", new Runnable() {
            @Override
            public void run() {
                readStderr(p, stderrTail);
            }
        });
        daemon("claude-stdout", new Runnable() {
            @Override
            public void run() {
                readStdout(p, stderrTail);
            }
        });

        JsonObject init = new JsonObject();
        init.addProperty("subtype", "initialize");
        sendControlRequest(init);
    }

    private void stopProcess() {
        Process p = process;
        process = null;
        stdin = null;
        if (p == null) return;
        stoppedProcesses.add(p);
        try {
            // 표준입력을 닫으면 CLI 가 스스로 정리하고 끝난다.
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void readStdout(Process p, StringBuffer stderrTail) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    handleMessage(parser.parse(line).getAsJsonObject());
                } catch (RuntimeException e) {
                    Activator.log("Unparsable CLI output: " + line, e);
                }
            }
        } catch (IOException ignored) {
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        // reset()/dispose() 로 일부러 끈 프로세스는 알리지 않는다.
        if (stoppedProcesses.remove(p)) return;
        synchronized (this) {
            if (process == p) {
                process = null;
                stdin = null;
            }
        }
        listener.onExit(code, stderrTail.toString());
    }

    private void readStderr(Process p, StringBuffer tail) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.append(line).append('\n');
                if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
            }
        } catch (IOException ignored) {
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- protocol

    private void handleMessage(JsonObject o) {
        String type = str(o, "type");
        if (type == null) return;
        boolean fromSubagent = o.has("parent_tool_use_id") && !o.get("parent_tool_use_id").isJsonNull();

        if ("system".equals(type)) {
            if ("init".equals(str(o, "subtype"))) {
                sessionId = str(o, "session_id");
                listener.onInit(sessionId, str(o, "model"));
            }
        } else if ("stream_event".equals(type)) {
            if (!fromSubagent) handleStreamEvent(o.getAsJsonObject("event"));
        } else if ("assistant".equals(type)) {
            if (!fromSubagent) handleAssistant(o.getAsJsonObject("message"));
        } else if ("user".equals(type)) {
            if (!fromSubagent) handleToolResults(o.getAsJsonObject("message"));
        } else if ("control_request".equals(type)) {
            handleControlRequest(o);
        } else if ("result".equals(type)) {
            boolean isError = o.has("is_error") && o.get("is_error").getAsBoolean();
            listener.onResult(isError, str(o, "result"),
                    o.has("total_cost_usd") ? o.get("total_cost_usd").getAsDouble() : 0,
                    o.has("duration_ms") ? o.get("duration_ms").getAsLong() : 0,
                    o.has("num_turns") ? o.get("num_turns").getAsInt() : 0);
        } else if ("control_response".equals(type)) {
            JsonObject r = o.getAsJsonObject("response");
            if (r != null && "error".equals(str(r, "subtype"))) {
                Activator.log("Control request failed: " + r, null);
            }
        }
    }

    private void handleStreamEvent(JsonObject event) {
        if (event == null) return;
        String type = str(event, "type");
        if ("message_start".equals(type)) {
            JsonObject m = event.getAsJsonObject("message");
            if (m != null && str(m, "id") != null) currentMessageId = str(m, "id");
        } else if ("content_block_delta".equals(type)) {
            JsonObject delta = event.getAsJsonObject("delta");
            if (delta != null && "text_delta".equals(str(delta, "type"))) {
                streamedMessages.add(currentMessageId);
                listener.onTextDelta(currentMessageId + ":" + event.get("index").getAsInt(), str(delta, "text"));
            }
        }
    }

    private void handleAssistant(JsonObject message) {
        if (message == null) return;
        String id = str(message, "id");
        if (id == null) id = "msg";
        JsonArray content = message.getAsJsonArray("content");
        if (content == null) return;
        for (int i = 0; i < content.size(); i++) {
            JsonObject block = content.get(i).getAsJsonObject();
            String blockType = str(block, "type");
            if ("text".equals(blockType) && !streamedMessages.contains(id)) {
                listener.onTextBlock(id + ":final" + i, str(block, "text"));
            } else if ("tool_use".equals(blockType)) {
                JsonObject input = block.has("input") && block.get("input").isJsonObject()
                        ? block.getAsJsonObject("input") : new JsonObject();
                listener.onToolUse(str(block, "id"), str(block, "name"), input);
            }
        }
    }

    private void handleToolResults(JsonObject message) {
        if (message == null || !message.has("content") || !message.get("content").isJsonArray()) return;
        for (JsonElement e : message.getAsJsonArray("content")) {
            JsonObject block = e.getAsJsonObject();
            if (!"tool_result".equals(str(block, "type"))) continue;
            boolean isError = block.has("is_error") && block.get("is_error").getAsBoolean();
            listener.onToolResult(str(block, "tool_use_id"), flattenContent(block.get("content")), isError);
        }
    }

    private void handleControlRequest(JsonObject o) {
        String requestId = str(o, "request_id");
        JsonObject request = o.getAsJsonObject("request");
        if ("can_use_tool".equals(str(request, "subtype"))) {
            JsonObject input = request.has("input") && request.get("input").isJsonObject()
                    ? request.getAsJsonObject("input") : new JsonObject();
            JsonArray suggestions = request.has("permission_suggestions") && request.get("permission_suggestions").isJsonArray()
                    ? request.getAsJsonArray("permission_suggestions") : null;
            listener.onPermissionRequest(new ClaudeListener.PermissionRequest(requestId, str(request, "tool_use_id"), str(request, "tool_name"),
                    input, str(request, "description"), suggestions));
            return;
        }
        // 이 플러그인이 다루지 않는 요청(훅 콜백, SDK MCP 등)은 오류로 답해 CLI 가 기다리지 않게 한다.
        JsonObject response = new JsonObject();
        response.addProperty("subtype", "error");
        response.addProperty("request_id", requestId);
        response.addProperty("error", "Unsupported control request: " + str(request, "subtype"));
        JsonObject root = new JsonObject();
        root.addProperty("type", "control_response");
        root.add("response", response);
        safeWrite(root);
    }

    private void sendControlRequest(JsonObject request) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "control_request");
        root.addProperty("request_id", "egov-" + (++requestSeq));
        root.add("request", request);
        safeWrite(root);
    }

    private synchronized void safeWrite(JsonObject json) {
        try {
            write(json);
        } catch (IOException e) {
            Activator.log("Failed to write to Claude CLI", e);
        }
    }

    private synchronized void write(JsonObject json) throws IOException {
        if (stdin == null) throw new IOException("Claude CLI 프로세스가 실행 중이 아닙니다.");
        stdin.write(json.toString());
        stdin.write('\n');
        stdin.flush();
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    /** 따옴표를 존중해 공백으로 인자를 나눈다. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (char c : s.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }

    private static String flattenContent(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && "text".equals(str(part.getAsJsonObject(), "type"))) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(str(part.getAsJsonObject(), "text"));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    public static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }
}
