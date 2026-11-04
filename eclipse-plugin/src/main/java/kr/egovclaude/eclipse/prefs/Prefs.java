package kr.egovclaude.eclipse.prefs;

import org.eclipse.jface.preference.IPreferenceStore;

import kr.egovclaude.eclipse.Activator;

/** Window > Preferences > eGov Claude 설정 키. */
public final class Prefs {

    /** claude 실행 파일 경로. 비어 있으면 자동 탐색한다. */
    public static final String CLAUDE_PATH = "claudePath";
    /** 모델 별칭 또는 전체 이름(sonnet, opus, haiku ...). 비어 있으면 CLI 기본값. */
    public static final String MODEL = "model";
    /** default | acceptEdits | plan | bypassPermissions */
    public static final String PERMISSION_MODE = "permissionMode";
    /** CLI 에 그대로 덧붙일 추가 인자 */
    public static final String EXTRA_ARGS = "extraArgs";
    /** 전자정부프레임워크 규칙을 시스템 프롬프트에 덧붙일지 여부 */
    public static final String USE_EGOV_RULES = "useEgovRules";
    /** 메시지를 보낼 때 현재 열린 파일 정보를 기본으로 포함할지 여부 */
    public static final String INCLUDE_CURRENT_FILE = "includeCurrentFile";

    private Prefs() {
    }

    public static void initDefaults(IPreferenceStore store) {
        store.setDefault(CLAUDE_PATH, "");
        store.setDefault(MODEL, "");
        store.setDefault(PERMISSION_MODE, "default");
        store.setDefault(EXTRA_ARGS, "");
        store.setDefault(USE_EGOV_RULES, true);
        store.setDefault(INCLUDE_CURRENT_FILE, true);
    }

    public static String string(String key) {
        String v = Activator.prefs().getString(key);
        return v == null ? "" : v.trim();
    }

    public static boolean bool(String key) {
        return Activator.prefs().getBoolean(key);
    }
}
