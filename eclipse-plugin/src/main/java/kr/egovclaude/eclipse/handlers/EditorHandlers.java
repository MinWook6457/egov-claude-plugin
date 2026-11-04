package kr.egovclaude.eclipse.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import kr.egovclaude.eclipse.prompt.EditorContext;
import kr.egovclaude.eclipse.prompt.EgovPrompts;
import kr.egovclaude.eclipse.ui.ChatView;

/** 에디터 우클릭 메뉴 > eGov Claude 에 들어가는 명령들. */
public final class EditorHandlers {

    private EditorHandlers() {
    }

    abstract static class Base extends AbstractHandler {

        @Override
        public Object execute(ExecutionEvent event) {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            EditorContext ctx = editor == null ? null : EditorContext.of(editor);
            if (ctx == null) return null;
            String prompt = buildPrompt(HandlerUtil.getActiveShell(event), ctx);
            if (prompt != null) ChatView.ask(ctx.project, title() + " " + ctx.location(), prompt);
            return null;
        }

        abstract String title();

        abstract String buildPrompt(Shell shell, EditorContext ctx);
    }

    public static final class Explain extends Base {
        @Override
        String title() {
            return "[코드 설명]";
        }

        @Override
        String buildPrompt(Shell shell, EditorContext ctx) {
            return EgovPrompts.explain(ctx);
        }
    }

    public static final class Modify extends Base {
        @Override
        String title() {
            return "[코드 수정]";
        }

        @Override
        String buildPrompt(Shell shell, EditorContext ctx) {
            InputDialog dialog = new InputDialog(shell, "eGov Claude: 코드 수정",
                    "어떻게 고칠지 적어 주세요. (" + ctx.location() + ")", "", null) {
                @Override
                protected int getInputTextStyle() {
                    return SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP;
                }

                @Override
                protected org.eclipse.swt.widgets.Control createDialogArea(org.eclipse.swt.widgets.Composite parent) {
                    org.eclipse.swt.widgets.Control area = super.createDialogArea(parent);
                    org.eclipse.swt.layout.GridData gd = (org.eclipse.swt.layout.GridData) getText().getLayoutData();
                    gd.heightHint = 100;
                    gd.widthHint = 480;
                    return area;
                }
            };
            if (dialog.open() != IDialogConstants.OK_ID) return null;
            String request = dialog.getValue().trim();
            return request.isEmpty() ? null : EgovPrompts.modify(ctx, request);
        }
    }

    public static final class UnitTest extends Base {
        @Override
        String title() {
            return "[테스트 생성]";
        }

        @Override
        String buildPrompt(Shell shell, EditorContext ctx) {
            return EgovPrompts.unitTest(ctx);
        }
    }

    public static final class Review extends Base {
        @Override
        String title() {
            return "[표준·보안 점검]";
        }

        @Override
        String buildPrompt(Shell shell, EditorContext ctx) {
            return EgovPrompts.review(ctx);
        }
    }
}
