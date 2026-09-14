package com.android.server.wm.sandbox.isolation;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SandboxIsolationRepository {
    public static final String KEY_SANDBOXED_PKGS = "sandboxed_pkgs";
    public static final String KEY_HIDEDEVOPTS_PKGS = "hidedevopts_pkgs";
    public static final String KEY_GID_RESTRICTIONS = "gid_restrictions";
    public static final String KEY_DATA_ISOLATION = "data_isolation_pkgs";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mBgHandler;

    private final Set<String> mSandboxedPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> mHideDevOptsPackages = ConcurrentHashMap.newKeySet();
    private final Map<String, int[]> mGidRestrictions = new ConcurrentHashMap<>();
    private final Set<String> mDataIsolationPackages = ConcurrentHashMap.newKeySet();

    public SandboxIsolationRepository(Context context) {
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

    public boolean isPackageSandboxed(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        return mSandboxedPackages.contains(packageName);
    }

    public List<String> getSandboxedPackages() {
        return new ArrayList<>(mSandboxedPackages);
    }

    public boolean setPackageSandboxed(String packageName, boolean sandboxed) {
        if (TextUtils.isEmpty(packageName)) return false;
        boolean changed = sandboxed ? mSandboxedPackages.add(packageName) : mSandboxedPackages.remove(packageName);
        if (changed) {
            scheduleSave();
            broadcastPackageChange(packageName);
        }
        return changed;
    }

    public boolean isDevOptionsHidden(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        return mHideDevOptsPackages.contains(packageName);
    }

    public List<String> getDevOptionsHiddenPackages() {
        return new ArrayList<>(mHideDevOptsPackages);
    }

    public boolean setDevOptionsHidden(String packageName, boolean hidden) {
        if (TextUtils.isEmpty(packageName)) return false;
        boolean changed = hidden ? mHideDevOptsPackages.add(packageName) : mHideDevOptsPackages.remove(packageName);
        if (changed) {
            scheduleSave();
        }
        return changed;
    }

    public void setRestrictedGids(String packageName, int[] gids) {
        if (TextUtils.isEmpty(packageName)) return;
        if (gids == null || gids.length == 0) {
            mGidRestrictions.remove(packageName);
        } else {
            mGidRestrictions.put(packageName, gids);
        }
        scheduleSave();
    }

    public int[] getRestrictedGids(String packageName) {
        if (TextUtils.isEmpty(packageName)) return null;
        return mGidRestrictions.get(packageName);
    }

    public boolean isDataIsolationEnabled(String packageName) {
        if (TextUtils.isEmpty(packageName)) return false;
        return mDataIsolationPackages.contains(packageName);
    }

    public boolean setDataIsolationEnabled(String packageName, boolean enabled) {
        if (TextUtils.isEmpty(packageName)) return false;
        boolean changed = enabled ? mDataIsolationPackages.add(packageName) : mDataIsolationPackages.remove(packageName);
        if (changed) {
            scheduleSave();
        }
        return changed;
    }

    private void loadConfig() {
        JSONObject config = SandboxConfigStorage.readConfig(mContentResolver);

        Set<String> sandboxed = loadSet(config, KEY_SANDBOXED_PKGS);
        Set<String> devOpts = loadSet(config, KEY_HIDEDEVOPTS_PKGS);
        Set<String> dataIso = loadSet(config, KEY_DATA_ISOLATION);

        mSandboxedPackages.removeIf(p -> !sandboxed.contains(p));
        mSandboxedPackages.addAll(sandboxed);

        mHideDevOptsPackages.removeIf(p -> !devOpts.contains(p));
        mHideDevOptsPackages.addAll(devOpts);

        mDataIsolationPackages.removeIf(p -> !dataIso.contains(p));
        mDataIsolationPackages.addAll(dataIso);

        loadGidRestrictions(config);
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

    private void loadGidRestrictions(JSONObject config) {
        JSONObject gidObj = config.optJSONObject(KEY_GID_RESTRICTIONS);
        if (gidObj == null) {
            mGidRestrictions.clear();
            return;
        }
        Map<String, int[]> newMap = new HashMap<>();
        Iterator<String> keys = gidObj.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            JSONArray arr = gidObj.optJSONArray(pkg);
            if (arr != null && arr.length() > 0) {
                int[] gids = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    gids[i] = arr.optInt(i);
                }
                newMap.put(pkg, gids);
            }
        }
        mGidRestrictions.keySet().removeIf(k -> !newMap.containsKey(k));
        mGidRestrictions.putAll(newMap);
    }

    private void scheduleSave() {
        mBgHandler.post(() -> {
            JSONArray sandboxedArr = new JSONArray(mSandboxedPackages);
            JSONArray devOptsArr = new JSONArray(mHideDevOptsPackages);
            JSONArray isolationArr = new JSONArray(mDataIsolationPackages);
            JSONObject gidObj = new JSONObject();
            try {
                for (Map.Entry<String, int[]> entry : mGidRestrictions.entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (int gid : entry.getValue()) {
                        arr.put(gid);
                    }
                    gidObj.put(entry.getKey(), arr);
                }
            } catch (JSONException ignored) {
            }
            SandboxConfigStorage.updateConfig(mContentResolver, config -> {
                try {
                    config.put(KEY_SANDBOXED_PKGS, sandboxedArr);
                    config.put(KEY_HIDEDEVOPTS_PKGS, devOptsArr);
                    config.put(KEY_DATA_ISOLATION, isolationArr);
                    config.put(KEY_GID_RESTRICTIONS, gidObj);
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
