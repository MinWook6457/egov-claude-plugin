package kr.egovclaude.eclipse.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import kr.egovclaude.eclipse.Activator;
import kr.egovclaude.eclipse.cli.ClaudeListener;
import kr.egovclaude.eclipse.cli.ClaudeSession;
import kr.egovclaude.eclipse.cli.PermissionMode;
import kr.egovclaude.eclipse.cli.SessionStore;
import kr.egovclaude.eclipse.prefs.Prefs;
import kr.egovclaude.eclipse.prompt.EditorContext;
import kr.egovclaude.eclipse.prompt.EgovPrompts;

/**
 * 대화 탭 하나. 탭마다 Claude CLI 프로세스(세션)와 SWT Browser 화면을 하나씩 가진다.
 * Eclipse 4.8 의 Windows SWT Browser 는 IE11 엔진이라 웹뷰 스크립트는 ES5 로 작성했다.
 */
final class ChatTab implements ClaudeListener {

    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_DETAIL = 6000;
    private static final int FLUSH_MS = 120;
    private static final int HISTORY_ENTRIES = 150;
    private static final Set<String> FILE_EDIT_TOOLS = new HashSet<String>(Arrays.asList("Edit", "MultiEdit", "Write"));

    private final ChatView view;
    private final Display display;
    private final Control control;
    private final Browser browser;
    private final ClaudeSession session;
    /** CLI 와의 입출력은 순서가 중요해서 한 스레드에서만 처리한다. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "egov-claude-session");
            t.setDaemon(true);
            return t;
        }
    });

    private final Parser markdownParser;
    private final HtmlRenderer markdownRenderer;

    private IProject project;
    private boolean pageReady;
    private boolean started;
    private boolean busy;
    private String title = "새 대화";
    private final List<String> pendingScripts = new ArrayList<String>();
    private final Map<String, StringBuffer> textBlocks = new ConcurrentHashMap<String, StringBuffer>();
    private final Set<String> dirtyBlocks = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, PermissionRequest> pendingPermissions = new ConcurrentHashMap<String, PermissionRequest>();
    /** tool_use ID → 그 도구가 만드는 변경. 비교 보기와 변경 요약에 쓴다. */
    private final Map<String, FileDiff> toolDiffs = new ConcurrentHashMap<String, FileDiff>();
    /** 이번 요청(턴)에서 바뀐 파일. 경로 → {처음 내용, 마지막 내용} */
    private final Map<String, String[]> turnChanges = new LinkedHashMap<String, String[]>();

    ChatTab(ChatView view, Composite parent, IProject project) {
        this.view = view;
        this.display = parent.getDisplay();
        this.project = project;
        this.session = new ClaudeSession(this);

        List<Extension> extensions = Arrays.<Extension>asList(TablesExtension.create());
        markdownParser = Parser.builder().extensions(extensions).build();
        markdownRenderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build();

        Browser b;
        try {
            b = new Browser(parent, SWT.NONE);
        } catch (Throwable e) {
            Activator.log("SWT Browser unavailable", e);
            Label label = new Label(parent, SWT.WRAP);
            label.setText("이 환경에서는 내장 브라우저(SWT Browser)를 쓸 수 없어 채팅 화면을 띄울 수 없습니다.\n" + e);
            browser = null;
            control = label;
            return;
        }
        browser = b;
        control = b;
        b.setJavascriptEnabled(true);
        new BrowserFunction(b, "egovPost") {
            @Override
            public Object function(Object[] arguments) {
                if (arguments.length > 0 && arguments[0] instanceof String) handleWebMessage((String) arguments[0]);
                return null;
            }
        };
        b.addProgressListener(new ProgressAdapter() {
            @Override
            public void completed(ProgressEvent event) {
                onPageReady();
            }
        });
        b.addLocationListener(new LocationAdapter() {
            @Override
            public void changing(LocationEvent event) {
                // 화면 안의 링크가 채팅 화면 자체를 바꾸지 않게 막고, 웹 주소는 외부 브라우저로 연다.
                if (!pageReady) return;
                event.doit = false;
                if (event.location != null && event.location.matches("(?i)https?:.*")) ChatView.openUrl(event.location);
            }
        });
        b.setText(buildHtml());

        display.timerExec(FLUSH_MS, new Runnable() {
            @Override
            public void run() {
                if (browser.isDisposed()) return;
                flushTextBlocks();
                display.timerExec(FLUSH_MS, this);
            }
        });
    }

    Control getControl() {
        return control;
    }

    IProject getProject() {
        return project;
    }

    String getTitle() {
        return title;
    }

    boolean isBusy() {
        return busy;
    }

    /** 아직 아무 대화도 하지 않은 탭인지. 메뉴에서 보낸 요청을 이 탭에서 받아도 되는지 판단할 때 쓴다. */
    boolean isEmpty() {
        return !started;
    }

    String getSessionId() {
        return session.getSessionId();
    }

    void setFocus() {
        if (browser != null && !browser.isDisposed()) browser.setFocus();
    }

    void dispose() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                session.dispose();
            }
        });
        worker.shutdown();
        if (!control.isDisposed()) control.dispose();
    }

    // ---------------------------------------------------------------- 요청 보내기

    /**
     * 메시지를 보낸다. UI 스레드에서 호출한다.
     *
     * @param label        채팅창에 보여 줄 짧은 제목. null 이면 prompt 를 그대로 보여 준다.
     * @param attachEditor 현재 에디터 위치를 덧붙일지
     */
    void submit(String label, String prompt, boolean attachEditor) {
        if (project == null) project = ChatView.guessProject();
        String text = prompt;
        if (attachEditor) {
            EditorContext ctx = EditorContext.current();
            if (ctx != null && ctx.project.equals(project)) text = prompt + "\n\n" + editorNote(ctx);
        }
        if (!started) {
            started = true;
            setTitle(label != null ? label : prompt);
        }
        turnChanges.clear();
        call("egov.addUser", label != null ? label : prompt, label != null ? prompt : null);
        setBusy(true);

        final File dir = workDir();
        final String finalText = text;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    session.setWorkDir(dir);
                    session.sendUserMessage(finalText);
                } catch (Exception e) {
                    Activator.log("Cannot send to Claude CLI", e);
                    call("egov.addSystem", "error", "Claude CLI 를 시작하지 못했습니다: " + e.getMessage());
                    setBusyAsync(false);
                }
            }
        });
    }

    /** 저장된 세션을 이 탭에 불러온다. 이전 대화를 그려 주고, 다음 메시지부터 그 세션을 이어 간다. */
    void resume(final SessionStore.SessionInfo info) {
        started = true;
        setTitle(info.title);
        final File dir = workDir();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                session.setWorkDir(dir);
                session.resume(info.id);
            }
        });
        int[] dropped = new int[1];
        List<SessionStore.Entry> entries = SessionStore.loadHistory(info.file, HISTORY_ENTRIES, dropped);
        if (dropped[0] > 0) call("egov.addSystem", "info", "앞의 대화 " + dropped[0] + "개는 생략하고 최근 " + entries.size() + "개만 보여 줍니다.");
        int n = 0;
        for (SessionStore.Entry e : entries) {
            String key = "history-" + (n++);
            if ("user".equals(e.kind)) {
                call("egov.addUser", e.text.length() > 2000 ? e.text.substring(0, 2000) + "…" : e.text, null);
            } else if ("assistant".equals(e.kind)) {
                call("egov.upsertText", key, markdownRenderer.render(markdownParser.parse(e.text)));
            } else {
                ToolView tv = describeTool(e.toolName, e.toolInput);
                call("egov.addTool", key, e.toolName, tv.summary, openPath(ChatView.str(e.toolInput, "file_path"), 0), null, null);
                call("egov.toolResult", key, null, false);
            }
        }
        call("egov.addSystem", "info", "이전 세션을 불러왔습니다. 이어서 말하면 이 대화를 계속합니다.");
    }

    private File workDir() {
        return project == null || project.getLocation() == null ? null : project.getLocation().toFile();
    }

    private static String editorNote(EditorContext ctx) {
        String note = "(참고: 사용자는 지금 에디터에서 `" + ctx.location() + "` 를 보고 있다.";
        if (ctx.hasSelection() && ctx.selectedText.length() <= 4000) {
            return note + " 선택한 코드:)\n```" + ctx.language + "\n" + ctx.selectedText + "\n```";
        }
        return note + ")";
    }

    private void setTitle(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        title = t.length() > 24 ? t.substring(0, 24) + "…" : t;
        view.updateTab(this);
    }

    private void setBusy(boolean value) {
        busy = value;
        call("egov.setBusy", value);
        view.updateTab(this);
    }

    private void setBusyAsync(final boolean value) {
        if (display.isDisposed()) return;
        display.asyncExec(new Runnable() {
            @Override
            public void run() {
                if (!control.isDisposed()) setBusy(value);
            }
        });
    }

    // ---------------------------------------------------------------- 웹뷰 → 자바 (UI 스레드)

    private void handleWebMessage(String raw) {
        JsonObject msg;
        try {
            msg = new JsonParser().parse(raw).getAsJsonObject();
        } catch (RuntimeException e) {
            Activator.log("Bad message from webview: " + raw, e);
            return;
        }
        String type = msg.get("type").getAsString();
        if ("send".equals(type)) {
            String text = msg.get("text").getAsString().trim();
            if (!text.isEmpty()) submit(null, text, msg.has("attach") && msg.get("attach").getAsBoolean());
        } else if ("permission".equals(type)) {
            answerPermission(msg.get("id").getAsString(), msg.get("decision").getAsString());
        } else if ("stop".equals(type)) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    session.interrupt();
                }
            });
        } else if ("mode".equals(type)) {
            final PermissionMode mode = PermissionMode.of(msg.get("mode").getAsString());
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    session.setMode(mode);
                }
            });
        } else if ("project".equals(type)) {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(msg.get("name").getAsString());
            if (!p.isAccessible() || p.equals(project)) return;
            if (started) {
                // 대화 중인 탭은 그대로 두고 새 탭에서 다른 프로젝트를 시작한다.
                sendProjects();
                view.newTab(p).setFocus();
            } else {
                project = p;
            }
        } else if ("projects".equals(type)) {
            projectsChanged();
        } else if ("attachDefault".equals(type)) {
            Activator.prefs().setValue(Prefs.INCLUDE_CURRENT_FILE, msg.get("value").getAsBoolean());
        } else if ("open".equals(type)) {
            openFile(msg.get("path").getAsString());
        } else if ("compare".equals(type)) {
            FileDiff d = toolDiffs.get(msg.get("id").getAsString());
            if (d != null) CompareSupport.open(d.path, d.before, d.after, d.created);
        } else if ("compareFile".equals(type)) {
            String[] change = turnChanges.get(msg.get("path").getAsString());
            if (change != null) CompareSupport.open(msg.get("path").getAsString(), change[0], change[1], "1".equals(change[2]));
        } else if ("link".equals(type)) {
            ChatView.openUrl(msg.get("url").getAsString());
        } else if ("quick".equals(type)) {
            runQuickAction(msg.get("action").getAsString());
        }
    }

    private void runQuickAction(String action) {
        if ("crud".equals(action)) {
            CrudDialog.openAndSubmit(view.getSite().getShell(), project);
        } else if ("review".equals(action)) {
            EditorContext ctx = EditorContext.current();
            if (ctx == null) {
                call("egov.addSystem", "info", "점검할 파일을 에디터에서 먼저 열어 주세요.");
            } else {
                ChatView.ask(ctx.project, "[표준·보안 점검] " + ctx.location(), EgovPrompts.review(ctx));
            }
        } else if ("analyze".equals(action)) {
            submit("[프로젝트 구조 분석]", EgovPrompts.analyzeProject(), false);
        }
    }

    private void answerPermission(String requestId, final String decision) {
        final PermissionRequest request = pendingPermissions.remove(requestId);
        if (request == null) return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                session.respondPermission(request, !"deny".equals(decision), "always".equals(decision));
            }
        });
        String label = "always".equals(decision) ? "✔ 허용함 (이 세션 동안 계속)" : "deny".equals(decision) ? "✖ 거부함" : "✔ 허용함";
        call("egov.resolvePermission", requestId, label, !"deny".equals(decision));
    }

    private void openFile(String pathWithLine) {
        String path = pathWithLine;
        int line = -1;
        int colon = pathWithLine.lastIndexOf(':');
        if (colon > 1 && pathWithLine.substring(colon + 1).matches("\\d+")) {
            path = pathWithLine.substring(0, colon);
            line = Integer.parseInt(pathWithLine.substring(colon + 1)) - 1;
        }
        File file = new File(path);
        if (!file.isAbsolute() && workDir() != null) file = new File(workDir(), path);
        IWorkbenchPage page = view.getSite().getPage();
        try {
            IEditorPart editor;
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(file.toURI());
            if (files.length > 0 && files[0].exists()) {
                editor = IDE.openEditor(page, files[0]);
            } else if (file.isFile()) {
                editor = IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toURI()));
            } else {
                call("egov.addSystem", "info", "파일을 찾을 수 없습니다: " + pathWithLine);
                return;
            }
            if (line > 0 && editor instanceof ITextEditor) {
                ITextEditor te = (ITextEditor) editor;
                IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
                if (doc != null && line < doc.getNumberOfLines()) te.selectAndReveal(doc.getLineOffset(line), 0);
            }
        } catch (Exception e) {
            Activator.log("Cannot open " + pathWithLine, e);
        }
    }

    // ---------------------------------------------------------------- CLI → 웹뷰 (백그라운드 스레드)

    @Override
    public void onInit(String sessionId, String model) {
        call("egov.setSession", model == null ? "" : model);
    }

    @Override
    public void onTextDelta(String blockKey, String delta) {
        StringBuffer text = textBlocks.get(blockKey);
        if (text == null) textBlocks.put(blockKey, text = new StringBuffer());
        text.append(delta);
        dirtyBlocks.add(blockKey);
    }

    @Override
    public void onTextBlock(String blockKey, String text) {
        textBlocks.put(blockKey, new StringBuffer(text));
        dirtyBlocks.add(blockKey);
    }

    @Override
    public void onToolUse(String toolUseId, String name, JsonObject input) {
        flushTextBlocks();
        if (FILE_EDIT_TOOLS.contains(name)) {
            FileDiff d = FileDiff.forTool(name, input);
            if (d != null) {
                toolDiffs.put(toolUseId, d);
                call("egov.addEdit", toolUseId, name, relative(d.path), openPath(d.path, d.firstLine),
                        d.added, d.removed, d.rows, d.created, extension(d.path));
                return;
            }
        }
        ToolView v = describeTool(name, input);
        call("egov.addTool", toolUseId, name, v.summary, openPath(ChatView.str(input, "file_path"), 0), v.detail, v.lang);
    }

    @Override
    public void onToolResult(String toolUseId, String content, boolean isError) {
        FileDiff d = toolDiffs.get(toolUseId);
        if (d != null && !isError) {
            refreshFile(d.path);
            synchronized (turnChanges) {
                String[] change = turnChanges.get(d.path);
                if (change == null) turnChanges.put(d.path, new String[]{d.before, d.after, d.created ? "1" : "0"});
                else change[1] = d.after;
            }
        }
        // 파일 수정 성공 메시지("The file ... has been updated")는 카드에 이미 드러나므로 다시 보여 주지 않는다.
        call("egov.toolResult", toolUseId, d != null && !isError ? null : truncate(content, MAX_DETAIL), isError);
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        flushTextBlocks();
        pendingPermissions.put(request.requestId, request);
        boolean canAlways = request.suggestions != null && request.suggestions.size() > 0;
        FileDiff d = request.toolUseId == null ? null : toolDiffs.get(request.toolUseId);
        if (d == null && FILE_EDIT_TOOLS.contains(request.toolName)) d = FileDiff.forTool(request.toolName, request.input);
        ToolView v = describeTool(request.toolName, request.input);
        call("egov.addPermission", request.requestId, request.toolUseId, request.toolName, v.summary,
                d == null ? v.detail : null, d == null ? null : d.rows, canAlways, d == null ? v.lang : extension(d.path));
    }

    @Override
    public void onResult(boolean isError, String resultText, double costUsd, long durationMs, int numTurns) {
        flushTextBlocks();
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        synchronized (turnChanges) {
            for (Map.Entry<String, String[]> e : turnChanges.entrySet()) {
                FileDiff total = FileDiff.between(e.getKey(), e.getValue()[0], e.getValue()[1], "1".equals(e.getValue()[2]));
                Map<String, Object> f = new LinkedHashMap<String, Object>();
                f.put("path", e.getKey());
                f.put("name", new File(e.getKey()).getName());
                f.put("dir", parentOf(relative(e.getKey())));
                f.put("open", openPath(e.getKey(), total.firstLine));
                f.put("added", total.added);
                f.put("removed", total.removed);
                f.put("created", total.created);
                files.add(f);
            }
        }
        if (!files.isEmpty()) call("egov.addChangeSummary", files);
        String meta = String.format(Locale.ROOT, "%.1f초 · %d턴 · $%.4f", durationMs / 1000.0, numTurns, costUsd);
        call("egov.addResult", isError, isError ? (resultText != null ? resultText : "오류가 발생했습니다.") : null, meta);
        setBusyAsync(false);
    }

    @Override
    public void onExit(int exitCode, String stderrTail) {
        pendingPermissions.clear();
        String detail = stderrTail.trim().isEmpty() ? "" : "\n" + truncate(stderrTail.trim(), 2000);
        call("egov.addSystem", "error", "Claude CLI 가 종료되었습니다 (코드 " + exitCode + "). 다음 메시지를 보내면 같은 대화로 다시 연결합니다." + detail);
        setBusyAsync(false);
    }

    private synchronized void flushTextBlocks() {
        if (dirtyBlocks.isEmpty()) return;
        for (Iterator<String> it = dirtyBlocks.iterator(); it.hasNext(); ) {
            String key = it.next();
            it.remove();
            StringBuffer text = textBlocks.get(key);
            if (text == null) continue;
            call("egov.upsertText", key, markdownRenderer.render(markdownParser.parse(text.toString())));
        }
    }

    // ---------------------------------------------------------------- tool 표시

    private static final class ToolView {
        final String summary;
        final String detail;
        final String lang;

        ToolView(String summary, String detail, String lang) {
            this.summary = summary;
            this.detail = detail;
            this.lang = lang;
        }
    }

    private ToolView describeTool(String name, JsonObject in) {
        String path = ChatView.str(in, "file_path");
        String file = relative(path);
        if ("Read".equals(name)) return new ToolView(file, null, null);
        if ("Write".equals(name)) return new ToolView(file, truncate(ChatView.str(in, "content"), MAX_DETAIL), extension(path));
        if ("Edit".equals(name) || "MultiEdit".equals(name)) return new ToolView(file, null, null);
        if ("Bash".equals(name) || "PowerShell".equals(name)) return new ToolView(ChatView.str(in, "command"), ChatView.str(in, "description"), null);
        if ("Grep".equals(name)) return new ToolView(ChatView.str(in, "pattern") + (in.has("path") ? "  in " + relative(ChatView.str(in, "path")) : ""), null, null);
        if ("Glob".equals(name)) return new ToolView(ChatView.str(in, "pattern"), null, null);
        if ("WebFetch".equals(name)) return new ToolView(ChatView.str(in, "url"), ChatView.str(in, "prompt"), null);
        if ("WebSearch".equals(name)) return new ToolView(ChatView.str(in, "query"), null, null);
        if ("Task".equals(name) || "Agent".equals(name)) return new ToolView(ChatView.str(in, "description"), truncate(ChatView.str(in, "prompt"), MAX_DETAIL), null);
        if ("TodoWrite".equals(name)) return new ToolView("할 일 목록 갱신", todos(in), null);
        return new ToolView("", truncate(PRETTY.toJson(in), MAX_DETAIL), "json");
    }

    private static String todos(JsonObject in) {
        if (!in.has("todos") || !in.get("todos").isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : in.getAsJsonArray("todos")) {
            JsonObject t = e.getAsJsonObject();
            String status = String.valueOf(ChatView.str(t, "status"));
            String mark = "completed".equals(status) ? "[x]" : "in_progress".equals(status) ? "[>]" : "[ ]";
            sb.append(mark).append(' ').append(ChatView.str(t, "content")).append('\n');
        }
        return sb.toString();
    }

    private String relative(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        File dir = workDir();
        if (dir != null) {
            String base = dir.getAbsolutePath().replace('\\', '/') + "/";
            if (normalized.regionMatches(true, 0, base, 0, base.length())) return normalized.substring(base.length());
        }
        return normalized;
    }

    private String openPath(String path, int line) {
        if (path == null) return null;
        return relative(path) + (line > 1 ? ":" + line : "");
    }

    private static String parentOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? "" : rel.substring(0, i);
    }

    private static String extension(String path) {
        if (path == null) return null;
        int i = path.lastIndexOf('.');
        return i < 0 ? null : path.substring(i + 1).toLowerCase();
    }

    /** Claude 가 고친 파일을 워크스페이스에 반영해 에디터와 Project Explorer 가 바로 보이게 한다. */
    private void refreshFile(final String path) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
                for (IFile f : root.findFilesForLocationURI(new File(path).toURI())) {
                    try {
                        IResource target = f.exists() ? f : f.getParent();
                        target.refreshLocal(f.exists() ? IResource.DEPTH_ZERO : IResource.DEPTH_ONE, null);
                    } catch (Exception e) {
                        Activator.log("Refresh failed: " + path, e);
                    }
                }
            }
        });
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "\n… (" + (s.length() - max) + "자 생략)";
    }

    // ---------------------------------------------------------------- 프로젝트 목록

    /** 워크스페이스의 프로젝트가 바뀌었을 때. 아직 대화 전이고 프로젝트가 없으면 새로 고른다. */
    void projectsChanged() {
        if (!started && (project == null || !project.isAccessible())) project = ChatView.guessProject();
        sendProjects();
        view.updateTab(this);
    }

    void sendProjects() {
        List<String> names = new ArrayList<String>();
        for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (p.isOpen()) names.add(p.getName());
        }
        call("egov.setProjects", names, project == null ? "" : project.getName());
    }

    // ---------------------------------------------------------------- JS 호출

    private void onPageReady() {
        if (pageReady) return;
        pageReady = true;
        call("egov.init", session.getMode().id(), Prefs.bool(Prefs.INCLUDE_CURRENT_FILE));
        sendProjects();
        for (String script : pendingScripts) browser.execute(script);
        pendingScripts.clear();
    }

    /** egov.xxx(args...) 를 실행한다. 어느 스레드에서 불러도 되고, 페이지 로딩 전이면 모아 두었다가 실행한다. */
    private void call(String function, Object... args) {
        if (browser == null) return;
        StringBuilder js = new StringBuilder(function).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) js.append(',');
            js.append(GSON.toJson(args[i]));
        }
        js.append(");");
        final String script = js.toString();
        if (display.isDisposed()) return;
        display.asyncExec(new Runnable() {
            @Override
            public void run() {
                if (browser.isDisposed()) return;
                if (pageReady) browser.execute(script);
                else pendingScripts.add(script);
            }
        });
    }

    private static String buildHtml() {
        boolean dark = InstanceScope.INSTANCE.getNode("org.eclipse.e4.ui.css.swt.theme").get("themeid", "").toLowerCase().contains("dark");
        FontData font = JFaceResources.getDialogFont().getFontData()[0];
        FontData mono = JFaceResources.getTextFont().getFontData()[0];
        String css = EgovPrompts.loadResource("/webview/chat.css")
                .replace("{{bg}}", dark ? "#2b2b2b" : "#ffffff")
                .replace("{{fg}}", dark ? "#dcdcdc" : "#1f2328")
                .replace("{{strong}}", dark ? "#ffffff" : "#000000")
                .replace("{{muted}}", dark ? "#9da5b4" : "#656d76")
                .replace("{{border}}", dark ? "#474747" : "#d0d7de")
                .replace("{{inputBg}}", dark ? "#232323" : "#ffffff")
                .replace("{{codeBg}}", dark ? "#1e1e1e" : "#f6f8fa")
                .replace("{{chipBg}}", dark ? "#16343a" : "#e6f4f1")
                .replace("{{chipFg}}", dark ? "#7fd1c7" : "#0f6b5f")
                .replace("{{chipBorder}}", dark ? "#24555c" : "#b5ddd6")
                .replace("{{userBg}}", dark ? "#363a40" : "#eef3fb")
                .replace("{{accent}}", dark ? "#589df6" : "#1f6feb")
                .replace("{{addBg}}", dark ? "#1d3a24" : "#e6ffec")
                .replace("{{addFg}}", dark ? "#8fd19e" : "#116329")
                .replace("{{addMark}}", dark ? "#2f6a3c" : "#abf2bc")
                .replace("{{delBg}}", dark ? "#45201f" : "#ffebe9")
                .replace("{{delFg}}", dark ? "#f19c99" : "#82071e")
                .replace("{{delMark}}", dark ? "#7a2d2b" : "#ffc1c0")
                .replace("{{kw}}", dark ? "#cc7832" : "#0033b3")
                .replace("{{str}}", dark ? "#6a8759" : "#067d17")
                .replace("{{num}}", dark ? "#6897bb" : "#1750eb")
                .replace("{{comment}}", dark ? "#808080" : "#8c8c8c")
                .replace("{{tag}}", dark ? "#e8bf6a" : "#0033b3")
                .replace("{{attr}}", dark ? "#bababa" : "#174ad4")
                .replace("{{anno}}", dark ? "#bbb529" : "#9e880d")
                .replace("{{warnBg}}", dark ? "#4a3b12" : "#fff8c5")
                .replace("{{font}}", "'" + font.getName() + "','Malgun Gothic',sans-serif")
                .replace("{{fontSize}}", font.getHeight() + "pt")
                .replace("{{mono}}", "'" + mono.getName() + "','D2Coding',Consolas,monospace");
        return EgovPrompts.loadResource("/webview/chat.html")
                .replace("/*CSS*/", css)
                .replace("/*JS*/", EgovPrompts.loadResource("/webview/chat.js"));
    }
}
