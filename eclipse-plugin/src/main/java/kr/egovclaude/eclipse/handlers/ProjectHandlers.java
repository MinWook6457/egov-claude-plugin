package kr.egovclaude.eclipse.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

import kr.egovclaude.eclipse.prompt.EditorContext;
import kr.egovclaude.eclipse.prompt.EgovPrompts;
import kr.egovclaude.eclipse.ui.ChatView;
import kr.egovclaude.eclipse.ui.CrudDialog;

/** 상단 메뉴와 Project Explorer 우클릭 메뉴에 들어가는 명령들. */
public final class ProjectHandlers {

    private ProjectHandlers() {
    }

    /** 선택한 리소스 → 활성 에디터 순으로 대상 프로젝트를 찾는다. 못 찾으면 null(채팅창의 현재 프로젝트). */
    static IProject targetProject(ExecutionEvent event) {
        ISelection sel = HandlerUtil.getCurrentSelection(event);
        if (sel instanceof IStructuredSelection && !sel.isEmpty()) {
            IResource r = Adapters.adapt(((IStructuredSelection) sel).getFirstElement(), IResource.class);
            if (r != null) return r.getProject();
        }
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        EditorContext ctx = editor == null ? null : EditorContext.of(editor);
        return ctx == null ? null : ctx.project;
    }

    public static final class OpenChat extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            ChatView.open();
            return null;
        }
    }

    public static final class GenerateCrud extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            CrudDialog.openAndSubmit(HandlerUtil.getActiveShell(event), targetProject(event));
            return null;
        }
    }

    public static final class AnalyzeProject extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            ChatView.ask(targetProject(event), "[프로젝트 구조 분석]", EgovPrompts.analyzeProject());
            return null;
        }
    }

    public static final class ReviewResources extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            ISelection sel = HandlerUtil.getCurrentSelection(event);
            if (!(sel instanceof IStructuredSelection)) return null;
            List<String> paths = new ArrayList<String>();
            IProject project = null;
            for (Object o : ((IStructuredSelection) sel).toList()) {
                IResource r = Adapters.adapt(o, IResource.class);
                if (r == null || r.getProject() == null) continue;
                // Claude 는 한 프로젝트 폴더에서 동작하므로 첫 번째 프로젝트의 리소스만 점검한다.
                if (project == null) project = r.getProject();
                if (!project.equals(r.getProject())) continue;
                paths.add(r instanceof IProject ? "." : r.getProjectRelativePath().toString());
            }
            if (paths.isEmpty()) return null;
            String label = paths.size() == 1 ? paths.get(0) : paths.get(0) + " 외 " + (paths.size() - 1) + "개";
            ChatView.ask(project, "[표준·보안 점검] " + label, EgovPrompts.reviewFiles(paths));
            return null;
        }
    }
}
