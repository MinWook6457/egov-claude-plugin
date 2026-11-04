package kr.egovclaude.eclipse.ui;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

/** Claude 가 바꾼 파일의 변경 전/후를 Eclipse 비교 편집기(Compare)로 연다. */
final class CompareSupport {

    private CompareSupport() {
    }

    static void open(String path, final String before, final String after, boolean created) {
        final String name = new File(path).getName();
        final String type = name.lastIndexOf('.') < 0 ? ITypedElement.TEXT_TYPE : name.substring(name.lastIndexOf('.') + 1);
        CompareConfiguration config = new CompareConfiguration();
        config.setLeftLabel("변경 후 (Claude) · " + name);
        config.setRightLabel(created ? "새 파일" : "변경 전 · " + name);
        config.setLeftEditable(false);
        config.setRightEditable(false);
        CompareEditorInput input = new CompareEditorInput(config) {
            @Override
            protected Object prepareInput(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                return new DiffNode(new Text("변경 후", type, after), new Text("변경 전", type, before));
            }
        };
        input.setTitle("eGov Claude 변경 비교: " + name);
        CompareUI.openCompareEditor(input);
    }

    private static final class Text implements ITypedElement, IEncodedStreamContentAccessor {
        private final String name;
        private final String type;
        private final String content;

        Text(String name, String type, String content) {
            this.name = name;
            this.type = type;
            this.content = content;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Image getImage() {
            return null;
        }

        @Override
        public String getType() {
            return type;
        }

        @Override
        public InputStream getContents() {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String getCharset() {
            return "UTF-8";
        }
    }
}
