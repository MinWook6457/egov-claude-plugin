package kr.egovclaude.eclipse.ui;

import java.io.File;
import java.util.Arrays;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import kr.egovclaude.eclipse.prompt.EgovPrompts;

/** 테이블 정의를 받아 eGov 표준 CRUD 생성 요청을 만든다. */
public class CrudDialog extends TitleAreaDialog {

    private final IProject project;
    private Text koreanName;
    private Text entityName;
    private Text basePackage;
    private Text moduleName;
    private Text tableDefinition;
    private Combo dataAccess;
    private Button paging;
    private Button idGeneration;
    private Button jsp;
    private Button unitTest;
    private EgovPrompts.CrudSpec spec;

    public CrudDialog(Shell parent, IProject project) {
        super(parent);
        this.project = project;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    public static void openAndSubmit(Shell shell, IProject project) {
        CrudDialog dialog = new CrudDialog(shell, project);
        if (dialog.open() != OK) return;
        EgovPrompts.CrudSpec spec = dialog.spec;
        ChatView.ask(project, "[CRUD 생성] " + spec.koreanName + " (" + spec.basePackage + "." + spec.moduleName + ")",
                EgovPrompts.crud(spec));
    }

    @Override
    public void create() {
        super.create();
        getShell().setText("eGov CRUD 생성");
        setTitle("eGov 표준 CRUD 생성");
        setMessage("테이블 정의를 넣으면 VO → Service → ServiceImpl → DAO/Mapper → MyBatis XML → Controller → JSP 를 만들도록 요청합니다."
                + (project == null ? "" : "\n대상 프로젝트: " + project.getName()));
        getButton(IDialogConstants.OK_ID).setText("Claude 에게 요청");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        c.setLayout(new GridLayout(2, false));

        koreanName = field(c, "업무명(한글):", "예: 게시판");
        entityName = field(c, "클래스 접두사:", "예: Board");
        basePackage = field(c, "기본 패키지:", "");
        basePackage.setText(guessBasePackage());
        moduleName = field(c, "업무 패키지:", "예: board");
        entityName.addListener(SWT.Modify, new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!moduleName.isFocusControl()) moduleName.setText(entityName.getText().trim().toLowerCase());
            }
        });

        new Label(c, SWT.NONE).setText("데이터 접근 방식:");
        dataAccess = new Combo(c, SWT.READ_ONLY);
        dataAccess.setItems(new String[]{"@Mapper 인터페이스", "EgovAbstractMapper DAO"});
        dataAccess.select(0);

        Label tableLabel = new Label(c, SWT.NONE);
        tableLabel.setText("테이블 정의:");
        tableLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        tableDefinition = new Text(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        tableDefinition.setFont(JFaceResources.getTextFont());
        tableDefinition.setMessage("CREATE TABLE 문이나 컬럼 목록(컬럼명, 타입, 설명)을 붙여 넣으세요");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 560;
        gd.heightHint = 200;
        tableDefinition.setLayoutData(gd);

        new Label(c, SWT.NONE);
        Composite options = new Composite(c, SWT.NONE);
        options.setLayout(new GridLayout(2, false));
        paging = check(options, "목록 검색·페이징 (PaginationInfo)", true);
        idGeneration = check(options, "EgovIdGnrService 로 키 생성", false);
        jsp = check(options, "JSP 화면 생성", true);
        unitTest = check(options, "ServiceImpl 단위 테스트", false);
        return area;
    }

    private static Text field(Composite c, String label, String hint) {
        new Label(c, SWT.NONE).setText(label);
        Text t = new Text(c, SWT.BORDER);
        t.setMessage(hint);
        t.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return t;
    }

    private static Button check(Composite c, String label, boolean selected) {
        Button b = new Button(c, SWT.CHECK);
        b.setText(label);
        b.setSelection(selected);
        return b;
    }

    @Override
    protected void okPressed() {
        String error = validate();
        if (error != null) {
            setErrorMessage(error);
            return;
        }
        spec = new EgovPrompts.CrudSpec();
        spec.koreanName = koreanName.getText().trim();
        spec.entityName = entityName.getText().trim();
        spec.basePackage = basePackage.getText().trim();
        spec.moduleName = moduleName.getText().trim();
        spec.tableDefinition = tableDefinition.getText();
        spec.useMapperInterface = dataAccess.getSelectionIndex() == 0;
        spec.paging = paging.getSelection();
        spec.idGeneration = idGeneration.getSelection();
        spec.jsp = jsp.getSelection();
        spec.unitTest = unitTest.getSelection();
        super.okPressed();
    }

    private String validate() {
        if (koreanName.getText().trim().isEmpty()) return "업무명을 입력하세요.";
        if (!entityName.getText().trim().matches("[A-Z][A-Za-z0-9]*")) return "영문 대문자로 시작하는 클래스 접두사를 입력하세요. 예: Board";
        if (!basePackage.getText().trim().matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")) return "올바른 패키지 이름이 아닙니다.";
        if (!moduleName.getText().trim().matches("[a-z][a-z0-9_]*")) return "업무 패키지는 영문 소문자로 입력하세요.";
        if (tableDefinition.getText().trim().isEmpty()) return "테이블 정의를 입력하세요.";
        return null;
    }

    /** src/main/java/egovframework 아래 첫 번째 하위 패키지를 찾는다. 없으면 eGov 예제 기본값. */
    private String guessBasePackage() {
        if (project != null && project.getLocation() != null) {
            File egov = new File(project.getLocation().toFile(), "src/main/java/egovframework");
            String[] children = egov.list();
            if (children != null) {
                Arrays.sort(children);
                for (String name : children) {
                    if (!name.equals("com") && !name.equals("rte") && new File(egov, name).isDirectory()) {
                        return "egovframework." + name;
                    }
                }
            }
        }
        return "egovframework.example";
    }
}
