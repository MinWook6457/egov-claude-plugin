package kr.egovclaude.eclipse.prompt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import kr.egovclaude.eclipse.Activator;
import kr.egovclaude.eclipse.prefs.Prefs;

/** 시스템 프롬프트와 작업별 요청 문구를 만든다. */
public final class EgovPrompts {

    /** 선택 영역이 이보다 길면 코드를 붙이지 않고 위치만 알려 Claude 가 직접 읽게 한다. */
    private static final int INLINE_CODE_LIMIT = 12000;

    public static final String PROJECT_RULES_PATH = ".egov-claude/rules.md";

    private EgovPrompts() {
    }

    /** 내장 eGov 규칙과 프로젝트 규칙(.egov-claude/rules.md)을 합쳐 임시 파일로 쓴다. 둘 다 없으면 null. */
    public static File writeSystemPromptFile(File projectDir) {
        StringBuilder sb = new StringBuilder();
        if (Prefs.bool(Prefs.USE_EGOV_RULES)) {
            sb.append(loadResource("/egov/egov-rules.md"));
        }
        if (projectDir != null) {
            File projectRules = new File(projectDir, PROJECT_RULES_PATH);
            if (projectRules.isFile()) {
                try {
                    sb.append("\n\n# 이 프로젝트의 추가 규칙 (").append(PROJECT_RULES_PATH).append(")\n\n")
                            .append(new String(Files.readAllBytes(projectRules.toPath()), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Activator.log("Cannot read " + projectRules, e);
                }
            }
        }
        if (sb.length() == 0) return null;
        try {
            File file = File.createTempFile("egov-claude-system-", ".md");
            file.deleteOnExit();
            Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            Activator.log("Cannot write system prompt file", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- 에디터 작업

    public static String explain(EditorContext ctx) {
        return "다음 코드가 무슨 일을 하는지 설명해 줘. 전자정부프레임워크의 어느 계층(Controller/Service/DAO/Mapper/설정)에 해당하는지, "
                + "호출 흐름에서 앞뒤로 어떤 클래스나 SQL 과 연결되는지도 알려 줘. 파일은 수정하지 마.\n\n"
                + codeSection(ctx);
    }

    public static String modify(EditorContext ctx, String request) {
        return "아래 코드를 다음 요청대로 고쳐 줘. eGov 계층 규칙과 이 프로젝트의 기존 스타일을 지키고, "
                + "연관된 Service/DAO/Mapper XML/JSP 도 함께 바꿔야 하면 같이 고쳐 줘.\n\n"
                + "요청: " + request + "\n\n"
                + codeSection(ctx);
    }

    public static String unitTest(EditorContext ctx) {
        return "아래 코드에 대한 JUnit 단위 테스트를 만들어 줘.\n"
                + "- 먼저 pom.xml 에서 JUnit 버전(4/5)과 spring-test, mockito 유무를 확인하고 그에 맞춰 작성해.\n"
                + "- ServiceImpl 은 DAO/Mapper 를 모킹하고, DAO/Mapper 는 기존 테스트 설정(context-*.xml)이 있으면 그것으로 통합 테스트를 만들어.\n"
                + "- 테스트는 src/test/java 아래 같은 패키지에 `대상클래스Test` 이름으로 둬.\n\n"
                + codeSection(ctx);
    }

    public static String review(EditorContext ctx) {
        return reviewInstructions() + "\n\n" + codeSection(ctx);
    }

    public static String reviewFiles(List<String> paths) {
        StringBuilder sb = new StringBuilder(reviewInstructions()).append("\n\n점검 대상(디렉터리면 그 아래 Java/XML/JSP 전체):\n");
        for (String p : paths) sb.append("- ").append(p).append('\n');
        return sb.toString();
    }

    private static String reviewInstructions() {
        return "다음 코드를 전자정부프레임워크 개발 표준과 소프트웨어 보안약점 기준으로 점검해 줘. 파일은 수정하지 말고 결과만 알려 줘.\n"
                + "점검 항목:\n"
                + "1. 계층 구조와 이름 규칙 (Controller/Service/ServiceImpl/DAO·Mapper/VO, @Service·@Repository 이름, EgovAbstractServiceImpl 상속)\n"
                + "2. 보안 약점: SQL 삽입(${} 사용), XSS(<c:out> 미사용), 경로 조작, 오류 메시지 노출, 하드코딩된 비밀정보, 인가 누락, 입력값 검증 누락\n"
                + "3. 예외·로그 처리 (printStackTrace, 빈 catch, egovLogger/processException 사용)\n"
                + "4. 트랜잭션·자원 관리, NullPointer 위험\n"
                + "결과는 표로 정리해 줘: | 심각도(상/중/하) | 위치(파일:행) | 문제 | 근거 규칙 | 수정 제안 |\n"
                + "문제가 없으면 없다고 짧게 말해 줘.";
    }

    // ---------------------------------------------------------------- 프로젝트 작업

    public static String analyzeProject() {
        return "이 프로젝트의 구조를 파악해 줘. 파일은 수정하지 마.\n"
                + "- eGovFrame 실행환경 버전, Spring/Java 버전, 빌드 도구\n"
                + "- 기본 패키지와 업무(모듈) 목록\n"
                + "- 데이터 접근 방식(EgovAbstractMapper DAO 인지 @Mapper 인터페이스인지), 사용하는 DBMS\n"
                + "- 공통컴포넌트 사용 여부와 주요 설정 파일 위치(context-*.xml, dispatcher-servlet.xml, globals.properties)\n"
                + "마지막에 이 프로젝트에서 새 업무를 추가할 때 따라야 할 규칙을 5줄 이내로 정리해 줘.";
    }

    public static String crud(CrudSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("전자정부프레임워크 표준 구조로 '").append(spec.koreanName).append("'(").append(spec.entityName)
                .append(") CRUD 기능을 만들어 줘.\n\n");
        sb.append("## 조건\n");
        sb.append("- 기본 패키지: `").append(spec.basePackage).append('.').append(spec.moduleName).append("`\n");
        sb.append("- 업무명(영문): ").append(spec.entityName).append("\n");
        sb.append("- 데이터 접근: ").append(spec.useMapperInterface
                ? "eGov `@Mapper` 어노테이션을 붙인 Mapper 인터페이스 (`" + spec.entityName + "Mapper`)"
                : "`EgovAbstractMapper` 를 상속한 DAO 클래스 (`" + spec.entityName + "DAO`)").append("\n");
        sb.append("- 기능: 목록(검색·페이징").append(spec.paging ? " 포함" : " 없음").append("), 상세, 등록, 수정, 삭제\n");
        if (spec.idGeneration) sb.append("- 키 생성: `EgovIdGnrService` 사용 (context-idgen.xml 에 빈 추가 필요 시 함께 추가)\n");
        sb.append("- 화면: ").append(spec.jsp ? "JSP 목록/상세·등록 화면 포함 (JSTL, `<c:out>`, 기존 JSP 레이아웃 따름)" : "화면 없이 Controller 까지만").append("\n");
        if (spec.unitTest) sb.append("- ServiceImpl 단위 테스트 포함\n");
        sb.append("\n## 테이블 정의\n```sql\n").append(spec.tableDefinition.trim()).append("\n```\n\n");
        sb.append("## 진행 순서\n"
                + "1. pom.xml 에서 eGovFrame 버전을 확인하고, 같은 프로젝트의 기존 업무 하나를 골라 Controller~XML~JSP 스타일을 먼저 읽어.\n"
                + "2. VO/DefaultVO → Service → ServiceImpl → DAO(Mapper) → MyBatis XML → Controller → JSP 순서로 만들어.\n"
                + "3. 새 XML/패키지가 설정(mapperLocations, component-scan)에 잡히는지 확인하고 필요하면 설정을 고쳐.\n"
                + "4. 끝나면 만든 파일과 바꾼 설정 파일 목록, 접속 URL 을 정리해 줘.");
        return sb.toString();
    }

    public static final class CrudSpec {
        public String koreanName;
        public String entityName;
        public String basePackage;
        public String moduleName;
        public String tableDefinition;
        public boolean useMapperInterface;
        public boolean paging;
        public boolean idGeneration;
        public boolean jsp;
        public boolean unitTest;
    }

    // ---------------------------------------------------------------- helpers

    private static String codeSection(EditorContext ctx) {
        if (!ctx.hasSelection()) {
            return "대상 파일: `" + ctx.path + "` (파일 전체를 직접 읽어서 판단해 줘)";
        }
        if (ctx.selectedText.length() > INLINE_CODE_LIMIT) {
            return "대상: `" + ctx.location() + "` (분량이 많아 붙이지 않았으니 해당 범위를 직접 읽어 줘)";
        }
        return "대상: `" + ctx.location() + "`\n```" + ctx.language + "\n" + ctx.selectedText + "\n```";
    }

    public static String loadResource(String path) {
        InputStream in = EgovPrompts.class.getResourceAsStream(path);
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Activator.log("Cannot load " + path, e);
            return "";
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
