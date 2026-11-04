# eGov Claude

전자정부 표준프레임워크 개발환경(eGovFrameDev)에서 Claude Code 를 쓰는 플러그인입니다.
eGovFrameDev 안에 채팅 창을 띄우고, eGov 계층 규칙에 맞춘 CRUD 생성, 표준·보안 점검, 에디터 우클릭 기능을 제공합니다.

- 대상: eGovFrameDev 3.9.0 (Eclipse 4.8 Photon, Java 8)
- 소스: `eclipse-plugin/`

## 동작 방식

```
eGovFrameDev 채팅 창 ──▶ 각자 PC 의 Claude Code CLI (claude) ──▶ Anthropic
```

- 플러그인 안에 Claude 가 들어 있는 것이 아니라, 각자 PC 에 설치된 Claude Code 를 화면에서 쓰는 방식입니다.
  계정·사용량·`~/.claude` 설정·MCP·스킬은 터미널에서 `claude` 를 쓸 때와 같습니다.
- Claude 와 나눈 대화와 Claude 가 읽은 소스 코드는 각자의 Claude 계정을 통해 Anthropic 으로 전송됩니다.
- 플러그인은 eGov 규칙을 시스템 프롬프트에 덧붙이고, 파일 수정·명령 실행 승인을 화면 버튼으로 받습니다.
- eGov 규칙에는 답변 형식도 들어 있습니다. Claude 는 결론을 첫 문장에 쓰고, 파일·쿼리 ID 는 `백틱`, 꼭 알아야 할 내용은 **굵게**,
  파일을 고쳤으면 끝에 "수정한 곳"(파일·메서드/쿼리 ID·줄 번호)을 정리합니다.

## 설치 (사용자)

미리 Claude Code 를 설치하고 로그인해 두어야 합니다. 터미널에서 `claude` 를 한 번 실행하면 로그인 화면이 나옵니다.

1. eGovFrameDev 에서 **Help > Install New Software...** 를 엽니다.
2. **Add...** 를 누르고 Location 에 아래 주소를 넣습니다(Name 은 아무거나, 예: `eGov Claude`).
   ```
   https://minwook6457.github.io/egov-claude-plugin/update-site/
   ```
   인터넷이 막힌 PC 라면 [Releases](https://github.com/MinWook6457/egov-claude-plugin/releases) 의 zip 을 받아 **Archive...** 로 고릅니다.
3. **eGov Claude** 를 체크하고 Next → 라이선스 동의 → Finish 를 누릅니다.
4. 서명되지 않은 콘텐츠 경고가 나오면 **Install anyway** 를 누르고 재시작합니다.

새 버전은 **Help > Check for Updates** 로 받습니다. zip 으로 설치했다면 새 zip 으로 같은 과정을 반복합니다.

## 사용

| 위치 | 기능 |
|---|---|
| 상단 메뉴 **eGov Claude** / `Ctrl+Alt+E` | 채팅 창 열기, CRUD 생성, 프로젝트 구조 분석 |
| 채팅 창 오른쪽 위 | **+ 새 대화**: 새 탭에서 새 세션 시작 · **세션 기록**: 지난 세션을 골라 새 탭에서 이어 가기 · **설정** |
| 채팅 창 위쪽 | 작업 프로젝트 선택, 권한 모드(승인 요청 / 파일 수정 자동 승인 / 계획 모드 / 모두 허용) |
| 채팅 창 아래쪽 | 질문 입력(Enter 전송, Shift+Enter 줄바꿈), 현재 파일·선택 영역 포함, 빠른 실행(CRUD 생성, 현재 파일 점검, 프로젝트 구조 분석) |
| 에디터 우클릭 > **eGov Claude** | 코드 설명 / 코드 수정 요청 / 단위 테스트 생성 / 표준·보안 점검 / CRUD 생성. Java 뿐 아니라 Mapper XML·JSP·properties 편집기에서도 나옵니다. 코드를 선택하면 선택 영역(파일·행 범위)만 대상으로 합니다. |
| Project Explorer·Package Explorer 우클릭 | 선택한 파일·폴더 표준·보안 점검 |
| Window > Preferences > **eGov Claude** | claude 경로, 모델, 기본 권한 모드, 추가 CLI 인자, eGov 규칙 사용 여부 |

### 채팅 화면에서 보이는 것

- **파일 수정 카드**: 어떤 파일을 고치는지, 몇 줄이 추가·삭제되는지(`+3 −1`)와 줄 번호가 붙은 변경 내용을 보여 줍니다.
  승인 요청 모드에서는 카드 안의 **허용 / 이 세션 동안 계속 허용 / 거부** 버튼으로 승인합니다.
- **변경된 파일 요약**: 응답이 끝나면 이번 요청에서 바뀐 파일 목록이 나옵니다. 파일명을 누르면 바뀐 줄로 이동하고,
  **비교** 를 누르면 Eclipse 비교 편집기에서 수정 전후를 나란히 봅니다.
- **강조 표시**: 파일명·쿼리 ID 는 칩 모양, 중요한 내용은 굵게, `주의` 문단은 노란 상자로 보입니다.
  코드 블록은 Java·SQL·XML 색 구분이 되고, MyBatis 의 `${}` 는 SQL 삽입 위험 표시가 붙습니다.
- **점검 결과 표**: 심각도·위치(파일:행)·문제·근거 규칙·수정 제안을 표로 보여 줍니다. 표가 채팅 창보다 넓으면 표 안에서만 가로로 스크롤됩니다.
- **도구 카드**: Claude 가 파일을 읽거나(Read) 검색하는(Grep) 과정이 접힌 카드로 쌓입니다. 누르면 펼쳐지고, 파일 경로를 누르면 에디터에서 열립니다.
- **탭**: 대화마다 탭이 하나씩 생깁니다. 응답 중인 탭은 제목 앞에 ● 가 붙습니다.
- **세션 기록**: 이 프로젝트 폴더에서 했던 세션(터미널에서 같은 폴더로 실행한 Claude Code 세션 포함)이 최근 순으로 나옵니다.

### 알아 두기

- Claude 는 채팅 창 위쪽에서 고른 **프로젝트 폴더**에서 실행됩니다. 다른 프로젝트 파일에서 메뉴를 누르면 지금 대화는 그대로 두고 새 탭에서 시작합니다.
- Claude 가 고친 파일은 워크스페이스에 바로 반영되어 에디터와 탐색기에서 보입니다.
- 프로젝트별 규칙은 `<프로젝트>/.egov-claude/rules.md` 에 쓰면 함께 적용됩니다. 프로젝트의 `CLAUDE.md` 도 그대로 읽습니다.
- 채팅 창의 프로젝트 목록은 프로젝트를 가져오거나 열고 닫으면 자동으로 바뀝니다.

### 문제 해결

| 증상 | 해결 |
|---|---|
| 상단에 **eGov Claude** 메뉴가 없음 | `eclipse.exe -clean` 으로 한 번 실행합니다. |
| "claude 실행 파일을 찾을 수 없습니다" | Window > Preferences > eGov Claude 에서 경로를 지정합니다. 보통 `C:\Users\<사용자>\.local\bin\claude.exe` 입니다. |
| 프로젝트 선택 칸이 비어 있음 | 프로젝트 선택 칸을 한 번 누르면 목록을 다시 읽습니다. |
| "Claude CLI 가 종료되었습니다" | 메시지 아래 오류 내용을 확인합니다. 로그인이 풀렸으면 터미널에서 `claude` 를 실행해 다시 로그인합니다. 다음 메시지를 보내면 같은 대화로 다시 연결합니다. |
| 그 밖의 오류 | 워크스페이스의 `.metadata/.log` 에서 `kr.egovclaude` 로 찾습니다. |

### 확인 상태

eGovFrameDev 3.9.0 에서 실제로 실행해 확인한 것:
- 플러그인 설치(dropins), 상단 메뉴, 채팅 창(IE11 엔진) 표시, 프로젝트 가져오기 후 목록 갱신
- Mapper XML 에서 코드 선택 → 우클릭 > eGov Claude > 표준·보안 점검 → 스트리밍 답변, 도구 카드, 결과 표, 강조 표시
- 탭 제목·응답 중 표시(●), 연결된 모델 표시

화면에서 아직 직접 확인하지 않은 것(코드와 단위 확인만 함): 파일 수정 카드의 허용/거부, 변경된 파일 요약과 비교 편집기,
세션 기록 대화상자, CRUD 생성 대화상자, Project Explorer 우클릭 점검, 업데이트 사이트 설치 화면.

## 배포 (관리자)

업데이트 사이트는 이 저장소의 `docs/update-site/` 를 GitHub Pages(main 브랜치 `/docs`)로 게시합니다.
빌드에는 eGovFrameDev 의 jar 와 p2 도구가 필요해서 GitHub Actions 가 아니라 내 PC 에서 빌드해 커밋합니다.

```powershell
cd eclipse-plugin
$env:JAVA_HOME = "C:\Program Files\Java\java-21"   # 빌드용 JDK (결과물은 Java 8 바이트코드)
..\gradlew publishSite updateSiteZip               # docs/update-site 갱신 + zip 생성
cd ..
git add docs eclipse-plugin README.md
git commit -m "Release 0.1.x"
git push                                            # 1~2분 뒤 Pages 에 반영
```

| 결과물 | 용도 |
|---|---|
| `docs/update-site/` | GitHub Pages 로 게시되는 업데이트 사이트. 사용자는 Location 을 한 번 등록하고 Check for Updates 로 업데이트를 받습니다. |
| `eclipse-plugin/build/distributions/egov-claude-update-site-<버전>.zip` | 같은 내용을 zip 하나로 묶은 것. GitHub Releases 에 올리거나 메신저로 전달합니다(`gh release create v0.1.x <zip>`). |

- 버전은 `gradle.properties` 의 `pluginVersion` 에 빌드 시각이 붙습니다(예: `0.1.0.v202611041542`). 새로 빌드해 올리면 업데이트로 잡힙니다.
- 기능이 크게 바뀌면 `pluginVersion` 을 올립니다(0.1.0 → 0.2.0).
- 컴파일할 때 참조하는 eGovFrameDev 경로는 `eclipse-plugin/gradle.properties` 의 `eclipseHome` 에서 바꿉니다.

## 개발

```powershell
cd eclipse-plugin
..\gradlew installDropins    # 빌드한 jar 를 eGovFrameDev 의 eclipse\dropins 에 바로 넣어 확인
```

- `installDropins` 는 이전 버전 jar 를 지우고 새 jar 를 넣습니다. Eclipse 를 다시 시작해야 반영됩니다. 메뉴가 안 보이면 `eclipse.exe -clean` 으로 한 번 실행합니다.
- dropins 로 넣은 상태에서 업데이트 사이트로 또 설치하면 충돌하므로, 둘 중 하나만 씁니다.
- 오류는 워크스페이스의 `.metadata/.log` 에 남습니다.

### 소스 구조

```
docs
├── index.html                           GitHub Pages 첫 화면(설치 안내)
└── update-site/                         publishSite 가 만드는 p2 업데이트 사이트 (직접 고치지 않음)
eclipse-plugin
├── META-INF/MANIFEST.MF, plugin.xml     번들 정의, 메뉴·View·설정 페이지 등록
├── feature/feature.xml, site/category.xml 업데이트 사이트용 feature·카테고리
└── src/main
    ├── java/kr/egovclaude/eclipse
    │   ├── cli       ClaudeSession(CLI 프로세스·stream-json 프로토콜), SessionStore(세션 기록), ClaudeCliLocator
    │   ├── prompt    EgovPrompts(시스템 프롬프트·작업별 요청문), EditorContext(현재 파일·선택 영역)
    │   ├── ui        ChatView(탭 관리), ChatTab(대화 한 개), FileDiff(변경 계산), CompareSupport, CrudDialog, SessionDialog
    │   ├── handlers  에디터·프로젝트 메뉴 명령
    │   └── prefs     설정 페이지
    └── resources
        ├── egov/egov-rules.md   내장 eGov 규칙 (팀 표준에 맞게 고쳐 쓰세요)
        └── webview/chat.*       채팅 화면
```

### 구현 메모

- CLI 와는 `claude -p --input-format stream-json --output-format stream-json --permission-prompt-tool stdio` 로 통신합니다.
  권한 요청(`can_use_tool`)을 받아 화면 버튼으로 답합니다.
- eGovFrameDev 3.9(Eclipse 4.8)의 내장 브라우저는 IE11 엔진이라 `webview/chat.js` 는 ES5 로만 씁니다.
  고친 뒤에는 `npx acorn --ecma5 src/main/resources/webview/chat.js` 로 문법을 확인합니다.
- 에디터 우클릭 메뉴는 `popup:org.eclipse.ui.popup.any` 에 넣고 에디터일 때만 보이게 했습니다.
  WTP 의 XML·JSP 편집기는 `#AbstractTextEditorContext` 를 쓰지 않아서 그 위치에 넣으면 Java 편집기에만 나옵니다.
- 빌드는 Tycho 없이 eGovFrameDev 의 `plugins/*.jar` 를 컴파일 클래스패스로 쓰고, 업데이트 사이트는 eGovFrameDev 에 들어 있는 p2 publisher 로 만듭니다.
- 포함된 오픈소스: commonmark-java 0.21.0 (BSD 2-Clause). 라이선스 전문은 플러그인 jar 의 `about_files/` 에 있습니다.
- 라이선스: MIT ([LICENSE](LICENSE))
