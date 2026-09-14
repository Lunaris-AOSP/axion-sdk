package android.app;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.os.RemoteException;

import java.util.Collections;
import java.util.List;

public class AxSettingsSpoofManager {

    private static volatile AxSettingsSpoofManager sInstance;

    public static AxSettingsSpoofManager getInstance() {
        if (sInstance == null) {
            synchronized (AxSettingsSpoofManager.class) {
                if (sInstance == null) {
                    sInstance = new AxSettingsSpoofManager();
                }
            }
        }
        return sInstance;
    }

    public AxSettingsSpoofManager() {
    }

    public AxSettingsSpoofManager(Context context) {
    }

    public boolean isSpoofSettingEnabled(String packageName, String settingKey) {
        try {
            return getService().isSandboxSpoofSettingEnabled(packageName, settingKey);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setSpoofSettingEnabled(String packageName, String settingKey, boolean enabled) {
        try {
            getService().setSandboxSpoofSettingEnabled(packageName, settingKey, enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public List<String> getEnabledSpoofSettings(String packageName) {
        try {
            List<String> result = getService().getSandboxEnabledSpoofSettings(packageName);
            return result != null ? result : Collections.emptyList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public String getSpoofedSetting(String callingPackage, String settingName) {
        try {
            return getService().getSandboxSpoofedSetting(callingPackage, settingName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IActivityManager getService() {
        return ActivityManager.getService();
    }
}
