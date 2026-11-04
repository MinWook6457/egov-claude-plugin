package kr.egovclaude.eclipse.ui;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import kr.egovclaude.eclipse.cli.ClaudeSession;

/**
 * Edit/MultiEdit/Write 도구가 파일을 어떻게 바꾸는지 계산한다.
 * 파일 전체에 수정을 적용해 보고 줄 단위로 비교하므로 실제 줄 번호와 앞뒤 문맥을 보여 줄 수 있다.
 */
final class FileDiff {

    private static final int CONTEXT = 3;
    private static final int MAX_ROWS = 400;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    /** 가운데 바뀐 구간이 이보다 크면 LCS 대신 전부 삭제/추가로 표시한다. */
    private static final long MAX_LCS_CELLS = 4_000_000L;

    final String path;
    final String before;
    final String after;
    final boolean created;
    int added;
    int removed;
    /** 첫 번째로 바뀐 줄(1부터). 파일을 열 때 이 줄로 이동한다. */
    int firstLine = 1;
    final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

    private FileDiff(String path, String before, String after, boolean created) {
        this.path = path;
        this.before = before;
        this.after = after;
        this.created = created;
        compute();
    }

    static FileDiff between(String path, String before, String after, boolean created) {
        return new FileDiff(path, before, after, created);
    }

    /** 도구 입력으로 변경 전/후 내용을 만든다. 계산할 수 없으면 null. */
    static FileDiff forTool(String tool, JsonObject in) {
        String path = ClaudeSession.str(in, "file_path");
        if (path == null) return null;
        File file = new File(path);
        boolean exists = file.isFile();
        if (exists && file.length() > MAX_FILE_BYTES) return snippetOnly(path, tool, in);
        String current = exists ? read(file) : "";
        if (current == null) return snippetOnly(path, tool, in);

        if ("Write".equals(tool)) {
            String content = norm(ClaudeSession.str(in, "content"));
            return new FileDiff(path, current, content, !exists);
        }
        if ("Edit".equals(tool)) {
            String[] r = applyEdit(current, norm(ClaudeSession.str(in, "old_string")), norm(ClaudeSession.str(in, "new_string")),
                    in.has("replace_all") && in.get("replace_all").getAsBoolean());
            return r == null ? snippetOnly(path, tool, in) : new FileDiff(path, r[0], r[1], false);
        }
        if ("MultiEdit".equals(tool) && in.has("edits") && in.get("edits").isJsonArray()) {
            String before = current;
            String after = current;
            for (JsonElement e : in.getAsJsonArray("edits")) {
                JsonObject edit = e.getAsJsonObject();
                String[] r = applyEdit(after, norm(ClaudeSession.str(edit, "old_string")), norm(ClaudeSession.str(edit, "new_string")),
                        edit.has("replace_all") && edit.get("replace_all").getAsBoolean());
                if (r == null) return snippetOnly(path, tool, in);
                if (after.equals(current)) before = r[0];
                after = r[1];
            }
            return new FileDiff(path, before, after, false);
        }
        return null;
    }

    /**
     * 현재 파일 내용에 Edit 를 적용한 {변경 전, 변경 후} 를 돌려준다.
     * 자동 승인 모드에서는 화면에 그리기 전에 이미 저장됐을 수 있어서, new_string 만 보이면 거꾸로 되돌려 변경 전을 만든다.
     */
    private static String[] applyEdit(String content, String oldText, String newText, boolean all) {
        if (oldText.isEmpty()) return null;
        if (content.contains(oldText)) {
            return new String[]{content, all ? content.replace(oldText, newText) : replaceFirst(content, oldText, newText)};
        }
        if (!newText.isEmpty() && content.contains(newText)) {
            return new String[]{all ? content.replace(newText, oldText) : replaceFirst(content, newText, oldText), content};
        }
        return null;
    }

    /** 파일을 읽을 수 없을 때 old/new 문자열만으로 비교한다. 줄 번호는 1부터 센다. */
    private static FileDiff snippetOnly(String path, String tool, JsonObject in) {
        if ("Write".equals(tool)) return new FileDiff(path, "", norm(ClaudeSession.str(in, "content")), false);
        StringBuilder b = new StringBuilder();
        StringBuilder a = new StringBuilder();
        if (in.has("edits") && in.get("edits").isJsonArray()) {
            for (JsonElement e : in.getAsJsonArray("edits")) {
                b.append(norm(ClaudeSession.str(e.getAsJsonObject(), "old_string"))).append('\n');
                a.append(norm(ClaudeSession.str(e.getAsJsonObject(), "new_string"))).append('\n');
            }
        } else {
            b.append(norm(ClaudeSession.str(in, "old_string")));
            a.append(norm(ClaudeSession.str(in, "new_string")));
        }
        return new FileDiff(path, b.toString(), a.toString(), false);
    }

    // ---------------------------------------------------------------- 줄 비교

    private void compute() {
        String[] a = lines(before);
        String[] b = lines(after);
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) prefix++;
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix
                && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) suffix++;

        // 전체 줄에 대한 연산 목록: ' ' 같음, '-' 삭제, '+' 추가
        List<char[]> ops = new ArrayList<char[]>();
        List<int[]> idx = new ArrayList<int[]>();
        for (int i = 0; i < prefix; i++) push(ops, idx, ' ', i, i);
        middle(a, b, prefix, a.length - suffix, prefix, b.length - suffix, ops, idx);
        for (int k = suffix; k > 0; k--) push(ops, idx, ' ', a.length - k, b.length - k);

        // 바뀐 줄 앞뒤 CONTEXT 줄만 남기고 나머지는 '…' 로 접는다.
        boolean[] keep = new boolean[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i)[0] != ' ') {
                for (int j = Math.max(0, i - CONTEXT); j <= Math.min(ops.size() - 1, i + CONTEXT); j++) keep[j] = true;
            }
        }
        boolean first = true;
        boolean gap = false;
        for (int i = 0; i < ops.size(); i++) {
            char op = ops.get(i)[0];
            int ai = idx.get(i)[0];
            int bi = idx.get(i)[1];
            if (op == '-') removed++;
            if (op == '+') added++;
            if (op != ' ' && first) {
                firstLine = op == '+' ? bi + 1 : Math.max(1, bi + 1);
                first = false;
            }
            if (!keep[i]) {
                gap = true;
                continue;
            }
            if (rows.size() >= MAX_ROWS) continue;
            if (gap && !rows.isEmpty()) rows.add(row("~", null, null, ""));
            gap = false;
            rows.add(row(String.valueOf(op), op == '+' ? null : ai + 1, op == '-' ? null : bi + 1, op == '+' ? b[bi] : a[ai]));
        }
        if (rows.size() >= MAX_ROWS) rows.add(row("~", null, null, "… 표시 줄 수 제한으로 생략"));
    }

    private static void middle(String[] a, String[] b, int a0, int a1, int b0, int b1, List<char[]> ops, List<int[]> idx) {
        int n = a1 - a0;
        int m = b1 - b0;
        if ((long) n * m > MAX_LCS_CELLS) {
            for (int i = a0; i < a1; i++) push(ops, idx, '-', i, b0);
            for (int j = b0; j < b1; j++) push(ops, idx, '+', a1, j);
            return;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[a0 + i].equals(b[b0 + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a[a0 + i].equals(b[b0 + j])) {
                push(ops, idx, ' ', a0 + i++, b0 + j++);
            } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                // 같은 자리에서 바뀐 줄은 삭제(-)를 먼저, 추가(+)를 나중에 보여 준다.
                push(ops, idx, '-', a0 + i++, b0 + j);
            } else {
                push(ops, idx, '+', a0 + i, b0 + j++);
            }
        }
    }

    private static void push(List<char[]> ops, List<int[]> idx, char op, int ai, int bi) {
        ops.add(new char[]{op});
        idx.add(new int[]{ai, bi});
    }

    private static Map<String, Object> row(String kind, Integer oldNo, Integer newNo, String text) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("k", kind);
        r.put("o", oldNo);
        r.put("n", newNo);
        r.put("s", text);
        return r;
    }

    // ---------------------------------------------------------------- helpers

    private static String[] lines(String s) {
        if (s.isEmpty()) return new String[0];
        String t = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        return t.split("\n", -1);
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace("\r\n", "\n");
    }

    private static String replaceFirst(String s, String target, String replacement) {
        int i = s.indexOf(target);
        return i < 0 ? s : s.substring(0, i) + replacement + s.substring(i + target.length());
    }

    /** 워크스페이스에 지정된 파일 인코딩(eGov 3.x 는 UTF-8 이 대부분, 오래된 프로젝트는 EUC-KR)으로 읽는다. */
    static String read(File file) {
        Charset cs = StandardCharsets.UTF_8;
        try {
            IFile[] files = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(file.toURI());
            if (files.length > 0 && files[0].exists()) cs = Charset.forName(files[0].getCharset());
        } catch (Exception ignored) {
        }
        try {
            return norm(new String(Files.readAllBytes(file.toPath()), cs));
        } catch (Exception e) {
            return null;
        }
    }
}
