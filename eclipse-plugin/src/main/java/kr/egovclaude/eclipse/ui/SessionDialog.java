package kr.egovclaude.eclipse.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import kr.egovclaude.eclipse.cli.SessionStore;

/** 프로젝트 폴더에서 했던 Claude 세션을 최근 순으로 보여 주고 하나를 고르게 한다. */
final class SessionDialog extends TitleAreaDialog {

    private static final int LIMIT = 200;

    private final IProject project;
    private final List<String> openIds;
    private List<SessionStore.SessionInfo> sessions;
    private Table table;
    private SessionStore.SessionInfo selected;

    SessionDialog(Shell parent, IProject project, List<String> openIds) {
        super(parent);
        this.project = project;
        this.openIds = openIds;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    SessionStore.SessionInfo getSelected() {
        return selected;
    }

    @Override
    public void create() {
        super.create();
        getShell().setText("eGov Claude 세션 기록");
        setTitle(project.getName() + " 세션 기록");
        setMessage(sessions.isEmpty()
                ? "이 프로젝트 폴더에서 저장된 Claude 세션이 없습니다."
                : "이어 갈 세션을 고르세요. 터미널에서 같은 폴더로 실행한 Claude Code 세션도 함께 보입니다.");
        getButton(IDialogConstants.OK_ID).setText("새 탭에서 열기");
        getButton(IDialogConstants.OK_ID).setEnabled(false);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        c.setLayout(new GridLayout(1, false));

        final Text filter = new Text(c, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        filter.setMessage("제목으로 찾기");
        filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        table = new Table(c, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 720;
        gd.heightHint = 360;
        table.setLayoutData(gd);
        column("마지막 사용", 130);
        column("제목 (첫 질문)", 520);
        column("", 60);

        sessions = SessionStore.list(project.getLocation().toFile(), LIMIT);
        fill("");
        filter.addModifyListener(new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {
                fill(filter.getText().trim().toLowerCase(Locale.ROOT));
            }
        });
        table.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                getButton(IDialogConstants.OK_ID).setEnabled(table.getSelectionCount() > 0);
            }

            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
                okPressed();
            }
        });
        return area;
    }

    private void column(String text, int width) {
        TableColumn col = new TableColumn(table, SWT.LEFT);
        col.setText(text);
        col.setWidth(width);
    }

    private void fill(String query) {
        table.removeAll();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (SessionStore.SessionInfo s : sessions) {
            if (!query.isEmpty() && !s.title.toLowerCase(Locale.ROOT).contains(query)) continue;
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[]{fmt.format(new Date(s.lastModified)), s.title, openIds.contains(s.id) ? "열려 있음" : ""});
            item.setData(s);
        }
    }

    @Override
    protected void okPressed() {
        TableItem[] sel = table.getSelection();
        if (sel.length == 0) return;
        selected = (SessionStore.SessionInfo) sel[0].getData();
        super.okPressed();
    }
}
