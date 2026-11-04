# 전자정부 표준프레임워크(eGovFrame) 개발 규칙

이 프로젝트는 전자정부 표준프레임워크 기반 Java 웹 애플리케이션이다. 코드를 읽거나 만들 때 아래 규칙을 따른다.
답변은 한국어로 하고, 코드 주석도 기존 코드의 언어와 밀도를 따른다.

## 1. 버전 먼저 확인
- 코드를 만들기 전에 `pom.xml`(또는 `build.gradle`)에서 eGovFrame 실행환경 버전을 확인한다.
  - 4.x 이상: 패키지 접두사가 `org.egovframe.rte.*` 이다. Spring 5.3 이상, Java 8/11/17.
  - 3.x 이하: 패키지 접두사가 `egovframework.rte.*` 이다.
- 버전에 맞지 않는 import 를 섞지 않는다. 기존 코드에서 쓰는 import 를 우선한다.
- 실행환경이 Boot 기반(`egovframe-boot-*`)이면 XML 설정 대신 Java Config/`application.properties` 관례를 따른다.

## 2. 계층 구조와 이름
업무 단위 패키지는 `egovframework.{시스템}.{업무}` 아래에 둔다. 기존 프로젝트의 기본 패키지를 그대로 쓴다.

| 계층 | 위치 | 이름 규칙 | 핵심 |
|---|---|---|---|
| Controller | `{업무}.web` | `XxxController` | `@Controller`, 서비스는 `@Resource(name = "xxxService")` 로 주입 |
| Service 인터페이스 | `{업무}.service` | `XxxService` | 업무 메서드 선언, `throws Exception` |
| Service 구현 | `{업무}.service.impl` | `XxxServiceImpl` | `@Service("xxxService")`, `extends EgovAbstractServiceImpl implements XxxService` |
| DAO (방식 A) | `{업무}.service.impl` | `XxxDAO` | `@Repository("xxxDAO")`, `extends EgovAbstractMapper` |
| Mapper (방식 B) | `{업무}.service.impl` | `XxxMapper` | 인터페이스에 eGov `@Mapper("xxxMapper")` |
| VO | `{업무}.service` | `XxxVO`, 검색조건 `XxxDefaultVO` | `Serializable`, 페이징 필드는 DefaultVO 에 |
| MyBatis XML | `src/main/resources/egovframework/mapper/{업무}/` | `Xxx_SQL_{dbms}.xml` 또는 기존 규칙 | namespace 는 DAO 쿼리 ID 접두사 또는 Mapper FQCN |
| JSP | `src/main/webapp/WEB-INF/jsp/egovframework/{업무}/` | `XxxList.jsp`, `XxxRegist.jsp` 등 | JSTL, `<c:out>` |

- 주요 클래스(4.x 기준, 3.x 는 `egovframework.rte.*`):
  - `org.egovframe.rte.fdl.cmmn.EgovAbstractServiceImpl` (`egovLogger`, `processException(...)` 제공)
  - `org.egovframe.rte.psl.dataaccess.EgovAbstractMapper` (`selectList`, `selectOne`, `insert`, `update`, `delete`)
  - `org.egovframe.rte.psl.dataaccess.mapper.Mapper` (Mapper 인터페이스 어노테이션)
  - `org.egovframe.rte.fdl.idgnr.EgovIdGnrService` (ID 생성, `getNextStringId()`)
  - `org.egovframe.rte.fdl.property.EgovPropertyService` (`pageUnit`, `pageSize` 등)
  - `org.egovframe.rte.ptl.mvc.tags.ui.pagination.PaginationInfo` (목록 페이징)
- 주입은 프로젝트 관례를 따른다. 기존 코드가 `@Resource(name=...)` 를 쓰면 그대로 쓰고, 생성자 주입으로 바꾸지 않는다.
- 공통컴포넌트(`egovframework.com.*`)가 있으면 새로 만들기 전에 재사용할 수 있는지 먼저 찾는다
  (예: `ComDefaultVO`, `EgovMessageSource`, `EgovStringUtil`, `LoginVO`, `EgovUserDetailsHelper`).

## 3. 설정 파일
- Spring 설정: `src/main/resources/egovframework/spring/context-*.xml`(4.x 이전 템플릿) 또는 `com/context-*.xml`.
- MVC 설정: `WEB-INF/config/egovframework/springmvc/*-servlet.xml` (또는 `dispatcher-servlet.xml`).
- 메시지: `egovframework/message/message-common_ko.properties`.
- 새 Mapper XML 이나 패키지를 추가하면 `context-mapper.xml` 의 mapperLocations, `component-scan` 범위에 포함되는지 확인한다.

## 4. 보안 약점 (행정안전부 소프트웨어 보안약점 진단가이드 기준)
- SQL: MyBatis 에서 `${}` 대신 `#{}` 를 쓴다. 정렬 컬럼처럼 `${}` 가 꼭 필요하면 화이트리스트로 검증한다.
- XSS: JSP 출력은 `<c:out value="${...}"/>` 또는 `fn:escapeXml` 을 쓴다. 날것의 `${}` 출력은 피한다.
- 경로 조작: 파일 다운로드/업로드 경로에 사용자 입력을 그대로 쓰지 않는다(`EgovWebUtil.filePathBlackList` 등 활용).
- 예외 처리: `e.printStackTrace()` 대신 로거를 쓰고, 화면에 스택트레이스를 노출하지 않는다.
- 인증/인가: 관리 기능에는 권한 확인을 넣는다. 비밀번호는 `EgovFileScrty.encryptPassword` 등 단방향 해시를 쓴다.
- 하드코딩된 비밀번호, 접속정보, 키를 코드에 넣지 않는다(Globals.properties 또는 암호화 설정).
- 입력값 검증: 서버 측 검증(`EgovBeanValidator`, `@Valid`, commons-validator)을 넣는다.

## 5. 작업 방식
- 파일을 고치기 전에 같은 업무의 기존 Controller/Service/DAO/XML 을 먼저 읽고 스타일(들여쓰기, 주석 형식, 로그 방식)을 맞춘다.
- 클래스 상단 주석은 프로젝트 기존 형식(`@Class Name`, `@Modification Information` 표 등)이 있으면 따른다.
- 만든 파일 목록과 수정한 설정 파일을 마지막에 정리해서 알려 준다.
- 빌드 확인이 필요하면 `mvn -q compile` 처럼 짧게 실행하고, 실패하면 원인을 고친다.

## 6. 답변 형식
답변은 IDE 채팅 창에 마크다운으로 표시된다. 읽는 사람이 한눈에 결과를 파악할 수 있게 쓴다.
- 첫 문장에 결론이나 결과를 쓴다. 예: "채번 쿼리를 A안으로 고쳤습니다. 커밋은 하지 않았습니다."
- 파일명, 클래스·메서드명, 쿼리 ID, 테이블·컬럼명, 설정 키는 항상 `백틱`으로 감싼다.
- 사용자가 꼭 알아야 하는 것(배포 순서, 재기동 필요, 데이터 영향, 아직 확인 안 한 것)은 **굵게** 쓴다.
  위험하거나 되돌리기 어려운 내용은 문단을 `**주의**` 로 시작한다.
- 파일을 고쳤으면 끝에 "수정한 곳" 목록을 둔다. 항목마다 `파일` `메서드/쿼리ID`: 줄 번호, 무엇을 어떻게 바꿨는지 한 줄.
- 사용자가 직접 돌려 볼 SQL·명령은 언어를 지정한 코드 블록(```sql, ```bash)으로 준다.
- 표는 비교나 점검 결과처럼 항목이 여러 개일 때만 쓴다.
