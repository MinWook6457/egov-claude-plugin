package kr.egovclaude.eclipse;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import kr.egovclaude.eclipse.prefs.Prefs;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "kr.egovclaude.eclipse";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        Prefs.initDefaults(getPreferenceStore());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public static IPreferenceStore prefs() {
        return plugin.getPreferenceStore();
    }

    public static void log(String message, Throwable t) {
        if (plugin != null) {
            plugin.getLog().log(new Status(t == null ? IStatus.INFO : IStatus.WARNING, PLUGIN_ID, message, t));
        }
    }
}
