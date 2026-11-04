package kr.egovclaude.eclipse.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import kr.egovclaude.eclipse.Activator;
import kr.egovclaude.eclipse.cli.ClaudeCliLocator;
import kr.egovclaude.eclipse.cli.PermissionMode;
import kr.egovclaude.eclipse.prompt.EgovPrompts;

public class EgovClaudePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public EgovClaudePreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.prefs());
        String detected = ClaudeCliLocator.autoDetect();
        setDescription("Claude Code CLI 연동 설정입니다.\n"
                + "claude 경로를 비워 두면 자동으로 찾습니다" + (detected == null ? " (현재 찾지 못함)." : ": " + detected) + "\n"
                + "프로젝트별 규칙은 프로젝트 폴더의 " + EgovPrompts.PROJECT_RULES_PATH + " 에 쓰면 함께 적용됩니다.");
    }

    @Override
    public void init(IWorkbench workbench) {
    }

    @Override
    protected void createFieldEditors() {
        FileFieldEditor path = new FileFieldEditor(Prefs.CLAUDE_PATH, "claude 실행 파일:", getFieldEditorParent());
        path.setEmptyStringAllowed(true);
        addField(path);
        addField(new StringFieldEditor(Prefs.MODEL, "모델 (예: sonnet, opus, haiku):", getFieldEditorParent()));

        PermissionMode[] modes = PermissionMode.values();
        String[][] entries = new String[modes.length][2];
        for (int i = 0; i < modes.length; i++) {
            entries[i][0] = modes[i].label();
            entries[i][1] = modes[i].id();
        }
        addField(new ComboFieldEditor(Prefs.PERMISSION_MODE, "기본 권한 모드:", entries, getFieldEditorParent()));
        addField(new StringFieldEditor(Prefs.EXTRA_ARGS, "추가 CLI 인자:", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Prefs.USE_EGOV_RULES, "전자정부프레임워크 규칙을 시스템 프롬프트에 추가", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Prefs.INCLUDE_CURRENT_FILE, "메시지에 현재 열린 파일/선택 영역 정보를 기본으로 포함", getFieldEditorParent()));
    }
}
