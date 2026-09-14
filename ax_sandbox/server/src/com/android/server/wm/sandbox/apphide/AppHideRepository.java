package com.android.server.wm.sandbox.apphide;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.os.BackgroundThread;
import com.android.server.wm.sandbox.SandboxConfigStorage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AppHideRepository {
    private static final String KEY_HIDDEN_PKGS = "hidden_pkgs";
    private static final String KEY_LAUNCHER_HIDDEN_PKGS = "launcher_hidden_pkgs";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mBgHandler;

    private final Set<String> mHiddenPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> mLauncherHiddenPackages = ConcurrentHashMap.newKeySet();

    public AppHideRepository(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mBgHandler = BackgroundThread.getHandler();
    }

    public void init() {
        ContentObserver observer = new ContentObserver(mBgHandler) {
            @Override
            public void onChange(boolean selfChange) {
                loadConfig();
            }
        };
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SandboxConfigStorage.SETTING_SANDBOX_CONFIG),
                false, observer, UserHandle.USER_ALL);
        loadConfig();
    }

    public boolean isPackageHidden(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        return mHiddenPackages.contains(packageName);
    }

    public boolean isPackageHiddenFromLauncher(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        return mLauncherHiddenPackages.contains(packageName);
    }

    public List<String> getHiddenPackages() {
        return new ArrayList<>(mHiddenPackages);
    }

    public List<String> getHiddenFromLauncherPackages() {
        return new ArrayList<>(mLauncherHiddenPackages);
    }

    public Set<String> getAllHiddenPackages() {
        Set<String> all = new HashSet<>(mHiddenPackages);
        all.addAll(mLauncherHiddenPackages);
        return all;
    }

    public boolean setPackageHidden(String packageName, boolean hidden) {
        if (TextUtils.isEmpty(packageName)) return false;
        boolean changed = hidden ? mHiddenPackages.add(packageName) : mHiddenPackages.remove(packageName);
        if (changed) {
            scheduleSave();
            broadcastPackageChange(packageName);
        }
        return changed;
    }

    public boolean setPackageHiddenFromLauncher(String packageName, boolean hidden) {
        if (TextUtils.isEmpty(packageName)) return false;
        boolean changed = hidden ? mLauncherHiddenPackages.add(packageName) : mLauncherHiddenPackages.remove(packageName);
        if (changed) {
            scheduleSave();
            broadcastPackageChange(packageName);
        }
        return changed;
    }

    private void loadConfig() {
        JSONObject config = SandboxConfigStorage.readConfig(mContentResolver);
        Set<String> hiddenPkgs = loadSet(config, KEY_HIDDEN_PKGS);
        Set<String> launcherHiddenPkgs = loadSet(config, KEY_LAUNCHER_HIDDEN_PKGS);

        mHiddenPackages.removeIf(p -> !hiddenPkgs.contains(p));
        mHiddenPackages.addAll(hiddenPkgs);
        mLauncherHiddenPackages.removeIf(p -> !launcherHiddenPkgs.contains(p));
        mLauncherHiddenPackages.addAll(launcherHiddenPkgs);
    }

    private Set<String> loadSet(JSONObject config, String key) {
        Set<String> result = new HashSet<>();
        JSONArray arr = config.optJSONArray(key);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String pkg = arr.optString(i);
                if (!TextUtils.isEmpty(pkg)) {
                    result.add(pkg);
                }
            }
        }
        return result;
    }

    private void scheduleSave() {
        mBgHandler.post(() -> {
            JSONArray hiddenArr = new JSONArray(mHiddenPackages);
            JSONArray launcherHiddenArr = new JSONArray(mLauncherHiddenPackages);
            SandboxConfigStorage.updateConfig(mContentResolver, config -> {
                try {
                    config.put(KEY_HIDDEN_PKGS, hiddenArr);
                    config.put(KEY_LAUNCHER_HIDDEN_PKGS, launcherHiddenArr);
                } catch (JSONException ignored) {
                }
            });
        });
    }

    private void broadcastPackageChange(String packageName) {
        mBgHandler.post(() -> {
            try {
                int uid = mContext.getPackageManager().getApplicationInfo(packageName, 0).uid;
                Intent intent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
                intent.setData(Uri.fromParts("package", packageName, null));
                intent.putExtra(Intent.EXTRA_UID, uid);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.getUserId(uid));
                intent.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, new String[]{packageName});
                intent.putExtra(Intent.EXTRA_DONT_KILL_APP, true);
                mContext.sendBroadcastAsUser(intent, UserHandle.of(UserHandle.getUserId(uid)));
            } catch (Exception ignored) {
            }
        });
    }
}
