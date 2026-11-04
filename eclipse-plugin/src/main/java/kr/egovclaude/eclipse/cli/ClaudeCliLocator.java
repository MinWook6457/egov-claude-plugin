package kr.egovclaude.eclipse.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import kr.egovclaude.eclipse.prefs.Prefs;

/** claude 실행 파일을 찾는다. 설정값이 있으면 그것을, 없으면 흔한 설치 위치와 PATH 를 차례로 본다. */
public final class ClaudeCliLocator {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private ClaudeCliLocator() {
    }

    public static String locate() {
        String configured = Prefs.string(Prefs.CLAUDE_PATH);
        if (!configured.isEmpty()) {
            return new File(configured).isFile() ? configured : null;
        }
        return autoDetect();
    }

    public static String autoDetect() {
        String home = System.getProperty("user.home");
        List<String> candidates = new ArrayList<String>();
        if (WINDOWS) {
            // 네이티브 설치본(claude.exe)을 우선한다. npm 설치본(.cmd)은 cmd.exe 를 거쳐 실행된다.
            candidates.add(home + "\\.local\\bin\\claude.exe");
            addFromPath(candidates, "claude.exe");
            addFromPath(candidates, "claude.cmd");
            String appData = System.getenv("APPDATA");
            if (appData != null) candidates.add(appData + "\\npm\\claude.cmd");
        } else {
            candidates.add(home + "/.local/bin/claude");
            candidates.add(home + "/.claude/local/claude");
            addFromPath(candidates, "claude");
            candidates.add("/opt/homebrew/bin/claude");
            candidates.add("/usr/local/bin/claude");
        }
        for (String c : candidates) {
            if (new File(c).isFile()) return c;
        }
        return null;
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    private static void addFromPath(List<String> out, String name) {
        String path = System.getenv("PATH");
        if (path == null) return;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            File f = new File(dir, name);
            if (f.isFile()) {
                out.add(f.getAbsolutePath());
                return;
            }
        }
    }
}
