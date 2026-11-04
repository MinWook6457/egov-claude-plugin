package kr.egovclaude.eclipse.prompt;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

/** 에디터에서 지금 보고 있는 파일과 선택 영역. UI 스레드에서 만든다. */
public final class EditorContext {

    public final IProject project;
    /** 프로젝트 폴더 기준 경로 */
    public final String path;
    public final String language;
    public final int startLine;
    public final int endLine;
    public final String selectedText;

    private EditorContext(IProject project, String path, String language, int startLine, int endLine, String selectedText) {
        this.project = project;
        this.path = path;
        this.language = language;
        this.startLine = startLine;
        this.endLine = endLine;
        this.selectedText = selectedText;
    }

    public boolean hasSelection() {
        return selectedText != null && !selectedText.isEmpty();
    }

    public String location() {
        return hasSelection() ? path + " (" + startLine + "~" + endLine + "행)" : path;
    }

    public static EditorContext current() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart editor = page == null ? null : page.getActiveEditor();
        return editor == null ? null : of(editor);
    }

    public static EditorContext of(IEditorPart editor) {
        IFile file = editor.getEditorInput().getAdapter(IFile.class);
        if (file == null) return null;
        String path = file.getProjectRelativePath().toString();
        String language = file.getFileExtension() == null ? "" : file.getFileExtension().toLowerCase();

        ITextEditor textEditor = editor instanceof ITextEditor ? (ITextEditor) editor : editor.getAdapter(ITextEditor.class);
        if (textEditor != null && textEditor.getSelectionProvider() != null) {
            ISelection sel = textEditor.getSelectionProvider().getSelection();
            if (sel instanceof ITextSelection && ((ITextSelection) sel).getLength() > 0) {
                ITextSelection ts = (ITextSelection) sel;
                return new EditorContext(file.getProject(), path, language,
                        ts.getStartLine() + 1, ts.getEndLine() + 1, ts.getText());
            }
        }
        return new EditorContext(file.getProject(), path, language, 0, 0, null);
    }
}
