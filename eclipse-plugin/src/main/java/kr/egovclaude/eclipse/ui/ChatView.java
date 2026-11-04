package kr.egovclaude.eclipse.ui;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

import com.google.gson.JsonObject;

import kr.egovclaude.eclipse.Activator;
import kr.egovclaude.eclipse.cli.ClaudeSession;
import kr.egovclaude.eclipse.cli.SessionStore;
import kr.egovclaude.eclipse.prompt.EditorContext;

/**
 * eGov Claude View. 대화마다 탭을 하나씩 열고, 저장된 세션을 골라 이어 갈 수 있다.
 */
public class ChatView extends ViewPart {

    public static final String ID = "kr.egovclaude.eclipse.chatView";
    public static final String PREFERENCE_PAGE_ID = "kr.egovclaude.eclipse.preferences";

    private CTabFolder folder;
    private final List<ChatTab> tabs = new ArrayList<ChatTab>();
    private IResourceChangeListener projectListener;

    // ---------------------------------------------------------------- 외부에서 쓰는 진입점

    /**
     * View 를 열고 요청을 보낸다. 지금 탭이 비어 있거나 같은 프로젝트면 그 탭에서, 아니면 새 탭에서 보낸다.
     * project 가 null 이면 지금 탭의 프로젝트를 쓴다.
     */
    public static void ask(IProject project, String label, String prompt) {
        ChatView view = open();
        if (view == null) return;
        ChatTab tab = view.activeTab();
        boolean reuse = tab != null && !tab.isBusy()
                && (tab.isEmpty() || project == null || project.equals(tab.getProject()));
        if (!reuse) tab = view.newTab(project != null ? project : tab == null ? guessProject() : tab.getProject());
        tab.submit(label, prompt, false);
    }

    public static ChatView open() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) return null;
        try {
            IViewPart part = window.getActivePage().showView(ID);
            return part instanceof ChatView ? (ChatView) part : null;
        } catch (Exception e) {
            Activator.log("Cannot open eGov Claude view", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- View

    @Override
    public void createPartControl(Composite parent) {
        folder = new CTabFolder(parent, SWT.TOP | SWT.CLOSE | SWT.FLAT);
        folder.setSimple(false);
        folder.setTabHeight(24);
        folder.setMRUVisible(true);
        // 오른쪽 위 버튼 때문에 탭 제목이 한두 글자로 잘리지 않게 한다.
        folder.setMinimumCharacters(8);

        // 채팅 창을 연 뒤에 프로젝트를 가져오거나 열고 닫아도 프로젝트 목록이 따라오게 한다.
        projectListener = new IResourceChangeListener() {
            @Override
            public void resourceChanged(IResourceChangeEvent event) {
                IResourceDelta delta = event.getDelta();
                if (delta == null) return;
                boolean projectsChanged = false;
                for (IResourceDelta child : delta.getAffectedChildren()) {
                    int kind = child.getKind();
                    if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED
                            || (child.getFlags() & IResourceDelta.OPEN) != 0) {
                        projectsChanged = true;
                    }
                }
                if (!projectsChanged || folder.isDisposed()) return;
                folder.getDisplay().asyncExec(new Runnable() {
                    @Override
                    public void run() {
                        if (folder.isDisposed()) return;
                        for (ChatTab tab : tabs) tab.projectsChanged();
                    }
                });
            }
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(projectListener, IResourceChangeEvent.POST_CHANGE);
        // Eclipse 를 막 켰을 때는 워크스페이스 복원이 끝나기 전에 View 가 먼저 열려 프로젝트 목록이 비어 있을 수 있다.
        folder.getDisplay().timerExec(3000, new Runnable() {
            @Override
            public void run() {
                if (folder.isDisposed()) return;
                for (ChatTab tab : tabs) tab.projectsChanged();
            }
        });

        ToolBar bar = new ToolBar(folder, SWT.FLAT | SWT.RIGHT);
        item(bar, "+ 새 대화", "새 탭에서 새 대화를 시작합니다", new Runnable() {
            @Override
            public void run() {
                ChatTab current = activeTab();
                newTab(current == null ? guessProject() : current.getProject()).setFocus();
            }
        });
        item(bar, "세션 기록", "이 프로젝트에서 했던 Claude 세션을 골라 이어 갑니다", new Runnable() {
            @Override
            public void run() {
                openSessionDialog();
            }
        });
        item(bar, "설정", "Window > Preferences > eGov Claude", new Runnable() {
            @Override
            public void run() {
                PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), PREFERENCE_PAGE_ID, null, null);
                dialog.open();
            }
        });
        folder.setTopRight(bar, SWT.RIGHT);

        folder.addCTabFolder2Listener(new CTabFolder2Adapter() {
            @Override
            public void close(CTabFolderEvent event) {
                ChatTab tab = (ChatTab) event.item.getData();
                tabs.remove(tab);
                tab.dispose();
                // 마지막 탭을 닫으면 빈 탭을 하나 남긴다.
                if (tabs.isEmpty()) {
                    final IProject p = tab.getProject();
                    folder.getDisplay().asyncExec(new Runnable() {
                        @Override
                        public void run() {
                            if (!folder.isDisposed() && tabs.isEmpty()) newTab(p);
                        }
                    });
                }
            }
        });
        newTab(guessProject());
    }

    private static void item(ToolBar bar, String text, String tooltip, final Runnable action) {
        ToolItem item = new ToolItem(bar, SWT.PUSH);
        item.setText(text);
        item.setToolTipText(tooltip);
        item.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                action.run();
            }
        });
    }

    @Override
    public void setFocus() {
        ChatTab tab = activeTab();
        if (tab != null) tab.setFocus();
        else if (folder != null) folder.setFocus();
    }

    @Override
    public void dispose() {
        if (projectListener != null) ResourcesPlugin.getWorkspace().removeResourceChangeListener(projectListener);
        for (ChatTab tab : tabs) tab.dispose();
        tabs.clear();
        super.dispose();
    }

    // ---------------------------------------------------------------- 탭

    ChatTab newTab(IProject project) {
        CTabItem item = new CTabItem(folder, SWT.CLOSE);
        ChatTab tab = new ChatTab(this, folder, project);
        item.setData(tab);
        item.setControl(tab.getControl());
        tabs.add(tab);
        folder.setSelection(item);
        updateTab(tab);
        return tab;
    }

    ChatTab activeTab() {
        CTabItem item = folder == null ? null : folder.getSelection();
        return item == null ? null : (ChatTab) item.getData();
    }

    /** 탭 제목을 갱신한다. 응답 중이면 앞에 ● 를 붙인다. UI 스레드에서 호출한다. */
    void updateTab(ChatTab tab) {
        if (folder == null || folder.isDisposed()) return;
        for (CTabItem item : folder.getItems()) {
            if (item.getData() != tab) continue;
            String project = tab.getProject() == null ? "" : tab.getProject().getName() + " · ";
            item.setText((tab.isBusy() ? "● " : "") + tab.getTitle());
            item.setToolTipText(project + tab.getTitle() + (tab.getSessionId() == null ? "" : "\n세션 " + tab.getSessionId()));
        }
    }

    private void openSessionDialog() {
        ChatTab current = activeTab();
        IProject project = current == null || current.getProject() == null ? guessProject() : current.getProject();
        if (project == null || project.getLocation() == null) return;
        List<String> openIds = new ArrayList<String>();
        for (ChatTab t : tabs) {
            if (t.getSessionId() != null) openIds.add(t.getSessionId());
        }
        SessionDialog dialog = new SessionDialog(getSite().getShell(), project, openIds);
        if (dialog.open() != Window.OK || dialog.getSelected() == null) return;
        SessionStore.SessionInfo info = dialog.getSelected();

        // 이미 열려 있는 세션이면 그 탭으로 이동한다.
        for (CTabItem item : folder.getItems()) {
            ChatTab t = (ChatTab) item.getData();
            if (info.id.equals(t.getSessionId())) {
                folder.setSelection(item);
                return;
            }
        }
        ChatTab tab = current != null && current.isEmpty() && project.equals(current.getProject()) ? current : newTab(project);
        tab.resume(info);
        tab.setFocus();
    }

    // ---------------------------------------------------------------- 공용 helpers

    /** 활성 에디터의 프로젝트 → 탐색기에서 선택한 프로젝트 → 첫 번째 열린 프로젝트 순으로 고른다. */
    static IProject guessProject() {
        EditorContext ctx = EditorContext.current();
        if (ctx != null) return ctx.project;
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window != null) {
            ISelection sel = window.getSelectionService().getSelection();
            if (sel instanceof IStructuredSelection && !sel.isEmpty()) {
                IResource r = Adapters.adapt(((IStructuredSelection) sel).getFirstElement(), IResource.class);
                if (r != null && r.getProject() != null) return r.getProject();
            }
        }
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (p.isOpen()) return p;
        }
        return null;
    }

    static void openUrl(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(new URL(url));
        } catch (Exception e) {
            Activator.log("Cannot open " + url, e);
        }
    }

    static String str(JsonObject o, String key) {
        return ClaudeSession.str(o, key);
    }
}
