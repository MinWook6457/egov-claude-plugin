package kr.egovclaude.eclipse.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * CLI 이벤트 수신자. 모든 콜백은 백그라운드 스레드에서 호출된다.
 * blockKey 는 "메시지ID:블록번호" 형태로, 같은 텍스트 블록을 가리키는 동안 변하지 않는다.
 */
public interface ClaudeListener {

    void onInit(String sessionId, String model);

    void onTextDelta(String blockKey, String delta);

    /** 스트리밍 없이 완성된 텍스트 블록이 도착한 경우 */
    void onTextBlock(String blockKey, String text);

    void onToolUse(String toolUseId, String name, JsonObject input);

    void onToolResult(String toolUseId, String content, boolean isError);

    void onPermissionRequest(PermissionRequest request);

    void onResult(boolean isError, String resultText, double costUsd, long durationMs, int numTurns);

    void onExit(int exitCode, String stderrTail);

    final class PermissionRequest {
        public final String requestId;
        /** 어떤 tool_use 에 대한 요청인지. 화면에서 해당 도구 카드에 승인 버튼을 붙이는 데 쓴다. */
        public final String toolUseId;
        public final String toolName;
        public final JsonObject input;
        public final String description;
        public final JsonArray suggestions;

        public PermissionRequest(String requestId, String toolUseId, String toolName, JsonObject input,
                                 String description, JsonArray suggestions) {
            this.requestId = requestId;
            this.toolUseId = toolUseId;
            this.toolName = toolName;
            this.input = input;
            this.description = description;
            this.suggestions = suggestions;
        }
    }
}
