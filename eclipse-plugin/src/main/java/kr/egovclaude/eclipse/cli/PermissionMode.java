package kr.egovclaude.eclipse.cli;

/** Claude Code CLI 의 --permission-mode 값과 화면 표시명. */
public enum PermissionMode {
    DEFAULT("default", "승인 요청 (기본)"),
    ACCEPT_EDITS("acceptEdits", "파일 수정 자동 승인"),
    PLAN("plan", "계획 모드 (읽기 전용)"),
    BYPASS("bypassPermissions", "모두 허용 (주의)");

    private final String id;
    private final String label;

    PermissionMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static PermissionMode of(String id) {
        for (PermissionMode m : values()) {
            if (m.id.equals(id)) return m;
        }
        return DEFAULT;
    }
}
