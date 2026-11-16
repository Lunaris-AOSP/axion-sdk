package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.AxSandboxManager.AppLockState.LOCKED;
import static android.app.AxSandboxManager.AppLockState.NONE;
import static android.app.AxSandboxManager.AppLockState.UNLOCKED;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AxSandboxManager.AppLockState;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Slog;
import android.view.Display;

import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAppSessionListener;
import com.android.server.wm.ActivityRecord;
import com.android.server.wm.sandbox.applock.AppLockRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AppLockService {

    private static final String TAG = "AppLockService";
    public static final String SANDBOX_PACKAGE = "com.android.axion.sandbox";
    public static final String APPLOCKER_PACKAGE = "com.android.applocker";
    private static final String APPLOCKER_ACTIVITY = "com.android.applocker.AuthenticateActivity";

    private static final String EXTRA_LOCKED_UID = "LOCKED_UID";
    private static final String EXTRA_LOCKED_PACKAGE = "LOCKED_PACKAGE";
    private static final String EXTRA_LOCKED_COMPONENT = "LOCKED_COMPONENT";
    private static final String EXTRA_USER_ID = "user_id";
    private static final String ACTION_SYSTEM_UNLOCK = "com.android.axion.sandbox.action.SYSTEM_UNLOCK";

    public static final int LOCK_BEHAVIOR_ON_LEAVE = 0;
    public static final int LOCK_BEHAVIOR_TIMEOUT = 1;
    public static final int LOCK_BEHAVIOR_ON_SCREEN_OFF = 2;
    public static final int LOCK_BEHAVIOR_ON_KILL = 3;

    public static final Set<String> BLACKLISTED_PACKAGES = Set.of(
            "com.android.systemui",
            "com.android.launcher3",
            "android",
            "com.android.settings",
            SANDBOX_PACKAGE,
            APPLOCKER_PACKAGE
    );

    private static volatile AppLockService sInstance;

    public static AppLockService getInstance() {
        return sInstance;
    }

    private final Context mContext;
    private final ActivityTaskManagerService mAtms;
    private final AppLockRepository mRepository;
    private LauncherApps mLauncherApps;

    private final RemoteCallbackList<IAppLockStateListener> mAppLockStateListeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<IAppSessionListener> mAppSessionListeners = new RemoteCallbackList<>();

    private final Set<String> mUnlockedApps = ConcurrentHashMap.newKeySet();
    private final Set<String> mPendingUnlocks = ConcurrentHashMap.newKeySet();
    private final Map<String, ActivityRecord> mPendingTargets = new ConcurrentHashMap<>();
    private final Map<String, Long> mUnlockTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Runnable> mTimeoutRunnables = new ConcurrentHashMap<>();

    private Intent mConfirmIntent;
    private int mRequestCode;
    private boolean mKeyguardDone = true;
    private String mLastFocusedAppKey = null;
    private int mLastFocusedTaskId = INVALID_TASK_ID;

    public static final int CONFIRM_REQUEST_CODE = 0x0A584C4B;

    public AppLockService(Context context, ActivityTaskManagerService atms, AppLockRepository repository) {
        mContext = context;
        mAtms = atms;
        mRepository = repository;
        mRequestCode = CONFIRM_REQUEST_CODE;
        sInstance = this;
    }

    private LauncherApps getLauncherApps() {
        if (mLauncherApps == null && mContext != null) {
            mLauncherApps = mContext.getSystemService(LauncherApps.class);
        }
        return mLauncherApps;
    }

    public void setKeyguardDone(boolean done) {
        mKeyguardDone = done;
        Slog.d(TAG, "setKeyguardDone done=" + done + " behavior=" + mRepository.getLockBehavior());
        if (!done && mRepository.getLockBehavior() == LOCK_BEHAVIOR_ON_SCREEN_OFF) {
            clearUnlockedApp();
        }
    }

    public boolean hasAppLock(String packageName, int userId) {
        return computeAppLockState(packageName, userId).hasAppLock();
    }

    public AppLockState computeAppLockState(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return NONE;
        if (BLACKLISTED_PACKAGES.contains(packageName)) return NONE;
        if (!mRepository.isAppLocked(packageName, userId)) return NONE;
        if (!mKeyguardDone) return LOCKED;
        return isSessionUnlocked(packageName, userId) ? UNLOCKED : LOCKED;
    }

    public int getAppLockState(String packageName, int userId) {
        return computeAppLockState(packageName, userId).ordinal();
    }

    public boolean isAppLocked(String packageName, int userId) {
        return computeAppLockState(packageName, userId) == LOCKED;
    }

    public boolean isAppLocked(ActivityRecord r) {
        if (r == null || !hasLockedPackages() || r.isNoDisplay() || r.isActivityTypeHomeOrRecents()) {
            return false;
        }
        return isAppLocked(r.packageName, r.getUid(), r.mActivityComponent);
    }

    public boolean isAppLocked(String packageName, int uid, ComponentName component) {
        if (TextUtils.isEmpty(packageName)) return false;
        if (BLACKLISTED_PACKAGES.contains(packageName)) return false;
        int userId = UserHandle.getUserId(uid);
        if (!mRepository.isAppLocked(packageName, userId)) return false;
        if (component != null && isAuthActivity(component)) return false;
        return !isSessionUnlocked(packageName, userId);
    }

    public boolean hasLockedPackages() {
        return mRepository.hasLockedPackages();
    }

    public void addLockedApp(String packageName, int userId) {
        if (isPackageLockable(packageName, userId)) {
            if (mRepository.addLockedApp(packageName, userId)) {
                notifyAppLockStateChanged(packageName, true);
            }
        }
    }

    public void removeLockedApp(String packageName, int userId) {
        if (mRepository.removeLockedApp(packageName, userId)) {
            markSessionLocked(packageName, userId);
            notifyAppLockStateChanged(packageName, false);
        }
    }

    public List<String> getLockedPackages(int userId) {
        return mRepository.getLockedPackages(userId);
    }

    public List<String> getLockablePackages(int userId) {
        List<String> result = new ArrayList<>();
        LauncherApps launcherApps = getLauncherApps();
        if (launcherApps != null) {
            try {
                List<LauncherActivityInfo> activities = launcherApps.getActivityList(
                        null, UserHandle.of(userId));
                Set<String> seen = new HashSet<>();
                for (LauncherActivityInfo info : activities) {
                    String pkgName = info.getApplicationInfo().packageName;
                    if (!BLACKLISTED_PACKAGES.contains(pkgName) && !seen.contains(pkgName)) {
                        result.add(pkgName);
                        seen.add(pkgName);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (result.isEmpty()) {
            try {
                PackageManager pm = mContext.getPackageManager();
                if (pm != null) {
                    List<ApplicationInfo> apps = pm.getInstalledApplicationsAsUser(0, userId);
                    for (ApplicationInfo appInfo : apps) {
                        if (BLACKLISTED_PACKAGES.contains(appInfo.packageName)) continue;
                        if (isSystemUid(appInfo.uid)) continue;
                        if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                            result.add(appInfo.packageName);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static boolean isSystemUid(int uid) {
        int appId = UserHandle.getAppId(uid);
        return appId == Process.ROOT_UID || appId == Process.SYSTEM_UID;
    }

    public boolean isPackageLockable(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName) || BLACKLISTED_PACKAGES.contains(packageName)) {
            return false;
        }
        if (mRepository.isAppLocked(packageName, userId)) {
            return true;
        }
        LauncherApps launcherApps = getLauncherApps();
        if (launcherApps != null) {
            try {
                List<LauncherActivityInfo> activities = launcherApps.getActivityList(
                        packageName, UserHandle.of(userId));
                if (activities != null && !activities.isEmpty()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            PackageManager pm = mContext.getPackageManager();
            return pm != null && pm.getLaunchIntentForPackage(packageName) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public void unlockApp(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        markSessionUnlocked(packageName, userId);
        clearPendingUnlock(packageName, userId);
    }

    public void promptUnlock(String packageName, int userId) {
        if (TextUtils.isEmpty(packageName)) return;
        Intent intent = new Intent(getConfirmIntent());
        intent.putExtra(EXTRA_LOCKED_PACKAGE, packageName);
        intent.putExtra(EXTRA_LOCKED_UID, userId);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        long identity = Binder.clearCallingIdentity();
        try {
            mAtms.getActivityStartController()
                    .obtainStarter(intent, "promptUnlock->AxSandbox")
                    .setCallingUid(0)
                    .execute();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void lockTopApp(Task task, String reason) {
        if (task == null || !hasLockedPackages()) return;
        ActivityRecord r = task.topRunningActivityLocked();
        if (!isAppLocked(r)) return;
        if (startAuthPrompt(r, reason)) {
            hideBlockedTarget(r);
        }
    }

    public boolean checkLockApp(ActivityRecord prev, ActivityRecord next) {
        if (next == null || next.finishing || !next.canBeTopRunning() || !hasLockedPackages()) {
            return false;
        }
        // Only ON_LEAVE drops the other sessions when an app is opened. Doing this for every
        // behaviour made TIMEOUT / ON_SCREEN_OFF / ON_KILL behave like ON_LEAVE, and refreshing
        // the timestamps here also defeated the timeout.
        if (mRepository.getLockBehavior() == LOCK_BEHAVIOR_ON_LEAVE) {
            clearUnlockedApp(next);
        }
        if (!isAppLocked(next)) return false;
        // startAuthPrompt() hides the target only when it is already on screen. A target that is
        // just being launched has nothing to hide, and forcing it invisible here made the pending
        // transition wait for a window that would never draw.
        return startAuthPrompt(next, "lockApp->AxSandbox");
    }

    public boolean checkUnlockApp(ActivityRecord r, int resultCode, Intent data) {
        if (r.requestCode != mRequestCode || r.intent == null || !isAuthActivity(r.intent.getComponent())) {
            return false;
        }
        String packageName = getResultPackageName(r, data);
        int userId = getResultUserId(r, data, packageName);
        if (data == null) {
            ActivityRecord target = r.resultTo;
            if (target == null) {
                if (!TextUtils.isEmpty(packageName)) {
                    clearPendingUnlock(packageName, userId);
                }
                return true;
            }
            lockSession(packageName, userId);
            dismissLockedTarget(target, "applock-canceled");
            return true;
        }
        try {
            if (resultCode == Activity.RESULT_OK && !TextUtils.isEmpty(packageName)) {
                clearPendingUnlock(packageName, userId);
                markSessionUnlocked(packageName, userId);
            } else if (r.resultTo != null) {
                lockSession(packageName, userId);
                dismissLockedTarget(r.resultTo, "applock-canceled");
            } else {
                lockSession(packageName, userId);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getResultPackageName(ActivityRecord r, Intent data) {
        if (data != null) {
            String pkg = data.getStringExtra(EXTRA_LOCKED_PACKAGE);
            if (!TextUtils.isEmpty(pkg)) return pkg;
        }
        return r.intent != null ? r.intent.getStringExtra(EXTRA_LOCKED_PACKAGE) : null;
    }

    private int getResultUserId(ActivityRecord r, Intent data, String packageName) {
        if (data != null) {
            if (data.hasExtra(EXTRA_USER_ID)) {
                return data.getIntExtra(EXTRA_USER_ID, r.mUserId);
            }
            int uid = data.getIntExtra(EXTRA_LOCKED_UID, -1);
            if (uid >= 0) {
                return UserHandle.getUserId(uid);
            }
        }
        if (r.intent != null) {
            if (r.intent.hasExtra(EXTRA_USER_ID)) {
                return r.intent.getIntExtra(EXTRA_USER_ID, r.mUserId);
            }
            int uid = r.intent.getIntExtra(EXTRA_LOCKED_UID, -1);
            if (uid >= 0) {
                return UserHandle.getUserId(uid);
            }
        }
        return r.mUserId;
    }

    private boolean startAuthPrompt(ActivityRecord target, String reason) {
        if (target == null) return false;
        String pendingKey = sessionKey(target);

        if (isSessionUnlocked(target.packageName, target.mUserId)) {
            if (mRepository.isAppLocked(target.packageName, target.mUserId)) {
                Slog.d(TAG, "prompt skipped, session already unlocked: " + pendingKey);
            }
            return true;
        }

        ActivityRecord duplicateTarget = null;
        boolean hideTarget = false;
        if (!mPendingUnlocks.add(pendingKey)) {
            ActivityRecord pendingTarget = mPendingTargets.get(pendingKey);
            boolean hasLivePendingTarget = isLivePendingTarget(pendingTarget, pendingKey);
            if (hasLivePendingTarget || hasPendingAuthPrompt(target, pendingKey)) {
                if (hasLivePendingTarget && pendingTarget != target) {
                    duplicateTarget = target;
                } else {
                    hideTarget = true;
                }
            }
        }
        if (duplicateTarget == null && !hideTarget) {
            mPendingTargets.put(pendingKey, target);
        }
        if (duplicateTarget != null) {
            finishDuplicatePendingTarget(duplicateTarget);
            return true;
        }
        if (hideTarget) {
            hideBlockedTarget(target);
            return true;
        }

        try {
            Intent intent = new Intent(getConfirmIntent());
            intent.putExtra(EXTRA_LOCKED_UID, target.getUid());
            intent.putExtra(EXTRA_LOCKED_PACKAGE, target.packageName);
            intent.putExtra(EXTRA_USER_ID, target.mUserId);
            intent.putExtra(EXTRA_LOCKED_COMPONENT,
                    target.intent.getComponent() != null
                            ? target.intent.getComponent().flattenToString() : "");

            mAtms.getActivityStartController()
                    .obtainStarter(intent, reason)
                    .setCallingUid(0)
                    .setResultTo(target.token)
                    .setRequestCode(mRequestCode)
                    .execute();

            Slog.d(TAG, "prompting " + pendingKey + " reason=" + reason
                    + " display=" + target.getDisplayId() + " visible=" + target.isVisible());
            hideBlockedTarget(target);
            resumePromptAfterStart(target);
            return true;
        } catch (Exception e) {
            lockSession(target.packageName, target.mUserId);
            if (!target.finishing) {
                target.finishIfPossible("applock-prompt-failed", false);
            }
            return true;
        }
    }

    /**
     * Makes sure the prompt actually gets resumed when it was started for an app that is not on
     * the default display (freeform window on a virtual display, e.g. LMOFreeform).
     *
     * <p>The prompt is started from inside the resume pass of the target activity, so it cannot be
     * resumed by that same pass (Task#resumeTopActivityUncheckedLocked guards against recursion)
     * and we abort that pass right after. The default display always gets another pass; a task
     * living on a freeform/virtual display does not, so the prompt stayed un-resumed, its process
     * stayed frozen and the freeform window sat there black until the user expanded the app and
     * its task was moved to the default display.
     */
    private void resumePromptAfterStart(ActivityRecord target) {
        if (target.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return;
        }
        final Task rootTask = target.getRootTask();
        if (rootTask == null) {
            return;
        }
        mAtms.mH.post(() -> {
            synchronized (mAtms.mGlobalLock) {
                if (rootTask.isAttached()) {
                    mAtms.mRootWindowContainer.resumeFocusedTasksTopActivities(
                            rootTask, null /* target */);
                }
            }
        });
    }

    public void onAppFocusChanged(ActivityRecord newFocus, Task newTask) {
        if (!hasLockedPackages()) {
            mLastFocusedAppKey = null;
            mLastFocusedTaskId = INVALID_TASK_ID;
            return;
        }

        final String newKey = (newFocus != null) ? sessionKey(newFocus) : null;
        final int newTaskId = (newTask != null) ? newTask.mTaskId : INVALID_TASK_ID;

        if (isTransientFocusInUnlockedTask(newFocus, newKey, newTaskId)) {
            cancelTimeoutLock(mLastFocusedAppKey);
            mUnlockTimestamps.put(mLastFocusedAppKey, SystemClock.elapsedRealtime());
            lockTopApp(newTask, "onAppFocusChanged");
            return;
        }

        if (mLastFocusedAppKey != null && !mLastFocusedAppKey.equals(newKey)) {
            scheduleTimeoutLock(mLastFocusedAppKey);
            if (mRepository.getLockBehavior() == LOCK_BEHAVIOR_ON_LEAVE
                    && mUnlockedApps.contains(mLastFocusedAppKey)) {
                lockSessionKey(mLastFocusedAppKey);
            }
        }
        if (newKey != null) {
            cancelTimeoutLock(newKey);
            if (mUnlockedApps.contains(newKey)) {
                mUnlockTimestamps.put(newKey, SystemClock.elapsedRealtime());
            }
        }
        mLastFocusedAppKey = newKey;
        mLastFocusedTaskId = newTaskId;
        lockTopApp(newTask, "onAppFocusChanged");
    }

    private boolean isTransientFocusInUnlockedTask(ActivityRecord newFocus, String newKey,
            int newTaskId) {
        return newFocus != null
                && mLastFocusedAppKey != null
                && !mLastFocusedAppKey.equals(newKey)
                && newTaskId != INVALID_TASK_ID
                && newTaskId == mLastFocusedTaskId
                && mUnlockedApps.contains(mLastFocusedAppKey)
                && !hasAppLock(newFocus.packageName, newFocus.mUserId);
    }

    public void onAppDied(String packageName, int userId) {
        if (mRepository.getLockBehavior() == LOCK_BEHAVIOR_ON_KILL) {
            markSessionLocked(packageName, userId);
        }
    }

    /**
     * Dismisses a locked task without killing it: the task goes to the back and home comes
     * forward, so the process is kept alive and reopening the app is immediate.
     */
    private void dismissLockedTarget(ActivityRecord target, String reason) {
        if (target == null || target.finishing) return;
        Task task = target.getTask();
        if (task == null) {
            target.finishIfPossible(reason, false);
            return;
        }
        if (task.getDisplayId() != Display.DEFAULT_DISPLAY) {
            // A floating window on a secondary display has no home task behind it, and its owner
            // (e.g. LMOFreeform) only tears the window down once the task is gone. Closing the app
            // is what the user expects instead of leaving an empty black window behind.
            task.forAllActivities(ar -> {
                if (!ar.finishing) {
                    ar.finishIfPossible(reason, false);
                }
            });
            return;
        }
        TaskDisplayArea displayArea = task.getDisplayArea();
        task.moveToBack(reason, null);
        if (displayArea != null) {
            displayArea.moveHomeRootTaskToFront(reason);
        }
    }

    /**
     * Called when a task is started from recents. Only ON_LEAVE drops the other sessions;
     * doing it unconditionally made every behaviour relock like ON_LEAVE.
     */
    public void onStartActivityFromRecents(Task task) {
        if (mRepository.getLockBehavior() == LOCK_BEHAVIOR_ON_LEAVE) {
            clearUnlockedApp();
        }
        lockTopApp(task, "startActivityFromRecents");
    }

    /**
     * Called when an activity pauses. This is the reliable "left the app" signal: it fires for
     * the home button, gesture navigation, back and task switches alike, whereas the resume
     * path is skipped for some of them.
     */
    public void onActivityPaused(ActivityRecord r) {
        if (r == null || !hasLockedPackages()) return;
        if (mRepository.getLockBehavior() != LOCK_BEHAVIOR_ON_LEAVE) return;
        if (!mRepository.isAppLocked(r.packageName, r.mUserId)) return;
        final String packageName = r.packageName;
        final int userId = r.mUserId;
        // Re-evaluate once the next activity has resumed; at pause time the top may still be
        // the pausing activity.
        mAtms.mH.post(() -> {
            synchronized (mAtms.mGlobalLock) {
                ActivityRecord top = mAtms.mRootWindowContainer.getTopResumedActivity();
                // Our own credential screen is on top: keep the session unlocked.
                if (top != null && top.intent != null
                        && isAuthActivity(top.intent.getComponent())) {
                    return;
                }
                // The app is still in the foreground (in-app navigation).
                if (top != null && top.mUserId == userId
                        && TextUtils.equals(top.packageName, packageName)) {
                    return;
                }
                markSessionLocked(packageName, userId);
            }
        });
    }

    /**
     * Called when a transition involving recents finishes. The session reset is ON_LEAVE only;
     * running it unconditionally made ON_SCREEN_OFF / TIMEOUT / ON_KILL relock like ON_LEAVE.
     */
    public void onRecentsTransition(ActivityRecord focusedApp) {
        if (mRepository.getLockBehavior() == LOCK_BEHAVIOR_ON_LEAVE) {
            clearUnlockedApp(focusedApp);
        }
    }

    public boolean isTopAppLocked(ActivityManager.RecentTaskInfo rti, int topUserId) {
        if (!mRepository.isCheckRecentTasks() || rti == null || rti.baseActivity == null || !hasLockedPackages()) {
            return false;
        }
        return isAppLocked(rti.baseActivity.getPackageName(), topUserId, rti.baseActivity);
    }

    public void getRecentTasksCheck(int callingUid, int userId) {
        if (!mRepository.isCheckRecentTasks() || !hasLockedPackages()) return;
        if (mRepository.getLockBehavior() == LOCK_BEHAVIOR_ON_LEAVE) {
            lockAllSessionsAndNotify();
        }
    }

    public void clearUnlockedApp() {
        lockAllSessionsExcept(Collections.emptySet());
    }

    public void clearUnlockedApp(ActivityRecord r) {
        if (r == null) return;
        if (!r.occludesParent() && !r.isActivityTypeHomeOrRecents()) return;
        if (r.isActivityTypeHomeOrRecents() && r.mTransitionController.isTransientLaunch(r)) {
            return;
        }

        final Set<String> keep = new HashSet<>(2);
        final String key = sessionKey(r);
        if (mUnlockedApps.contains(key)) {
            keep.add(key);
        }
        final String ownerKey = getUnlockedTaskOwnerKey(r);
        if (ownerKey != null) {
            keep.add(ownerKey);
        }
        lockAllSessionsExcept(keep);

        // Refreshing the timestamps on every resume made the timeout look permanently fresh.
        // Only touch them for behaviours that do not measure idle time.
        if (mRepository.getLockBehavior() != LOCK_BEHAVIOR_TIMEOUT) {
            final long now = SystemClock.elapsedRealtime();
            for (String k : keep) {
                mUnlockTimestamps.put(k, now);
            }
        }
    }

    private String getUnlockedTaskOwnerKey(ActivityRecord r) {
        if (r.isActivityTypeHomeOrRecents()) return null;
        final Task task = r.getTask();
        if (task == null) return null;
        final String basePkg = task.getBasePackageName();
        if (TextUtils.isEmpty(basePkg) || basePkg.equals(r.packageName)) return null;
        if (hasAppLock(r.packageName, r.mUserId)) return null;
        final String ownerKey = sessionKey(task.mUserId, basePkg);
        return mUnlockedApps.contains(ownerKey) ? ownerKey : null;
    }

    private boolean isTransientTopOfTaskOwner(ActivityRecord top, String ownerKey) {
        return top != null && ownerKey.equals(getUnlockedTaskOwnerKey(top));
    }

    public void removeTask(Task task, String reason) {
        if (task == null || !hasLockedPackages()) return;
        String pkg = task.getBasePackageName();
        if (pkg != null && isAppLocked(pkg, task.mUserId)) {
            markSessionLocked(pkg, task.mUserId);
        }
    }

    public void cleanupPackage(String packageName, int userId) {
        removeLockedApp(packageName, userId);
        String targetKey = sessionKey(userId, packageName);
        mUnlockedApps.remove(targetKey);
        mUnlockTimestamps.remove(targetKey);
        cancelTimeoutLock(targetKey);
        clearPendingUnlock(packageName, userId);
    }

    public void cleanupPackage(String packageName) {
        if (mContext == null) {
            cleanupPackage(packageName, UserHandle.USER_SYSTEM);
            return;
        }
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            cleanupPackage(packageName, UserHandle.USER_SYSTEM);
            return;
        }
        for (UserHandle user : userManager.getUserProfiles()) {
            cleanupPackage(packageName, user.getIdentifier());
        }
    }

    public boolean isSandboxActivity(ComponentName componentName) {
        if (componentName == null) return false;
        return SANDBOX_PACKAGE.equals(componentName.getPackageName());
    }

    public boolean isAppLockerActivity(ComponentName componentName) {
        if (componentName == null) return false;
        return APPLOCKER_PACKAGE.equals(componentName.getPackageName());
    }

    public boolean isAuthActivity(ComponentName componentName) {
        return isSandboxActivity(componentName) || isAppLockerActivity(componentName);
    }

    public void registerAppLockStateListener(IAppLockStateListener listener) {
        if (listener != null) mAppLockStateListeners.register(listener);
    }

    public void unregisterAppLockStateListener(IAppLockStateListener listener) {
        if (listener != null) mAppLockStateListeners.unregister(listener);
    }

    public void registerAppSessionListener(IAppSessionListener listener) {
        if (listener != null) mAppSessionListeners.register(listener);
    }

    public void unregisterAppSessionListener(IAppSessionListener listener) {
        if (listener != null) mAppSessionListeners.unregister(listener);
    }

    public void notifyAppLockStateChanged(String packageName, boolean isLocked) {
        synchronized (mAppLockStateListeners) {
            int count = mAppLockStateListeners.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        mAppLockStateListeners.getBroadcastItem(i)
                                .onAppLockStateChanged(packageName, isLocked);
                    } catch (RemoteException ignored) {
                    }
                }
            } finally {
                mAppLockStateListeners.finishBroadcast();
            }
        }
    }

    private void notifyAppSessionChanged(String packageName, int userId, boolean isUnlocked) {
        synchronized (mAppSessionListeners) {
            int count = mAppSessionListeners.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    try {
                        if (isUnlocked) {
                            mAppSessionListeners.getBroadcastItem(i).onAppUnlocked(packageName, userId);
                        } else {
                            mAppSessionListeners.getBroadcastItem(i).onAppLocked(packageName, userId);
                        }
                    } catch (RemoteException ignored) {
                    }
                }
            } finally {
                mAppSessionListeners.finishBroadcast();
            }
        }
    }

    private boolean isSessionUnlocked(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        if (!mUnlockedApps.contains(key)) {
            return false;
        }
        if (mRepository.getLockBehavior() != LOCK_BEHAVIOR_TIMEOUT) {
            return true;
        }
        Long lastUsed = mUnlockTimestamps.get(key);
        if (lastUsed == null || (SystemClock.elapsedRealtime() - lastUsed) <= (mRepository.getLockTimeout() * 1000L)) {
            return true;
        }
        if (isActiveTopApp(packageName, userId)) {
            mUnlockTimestamps.put(key, SystemClock.elapsedRealtime());
            return true;
        }
        return false;
    }

    private boolean isActiveTopApp(String packageName, int userId) {
        synchronized (mAtms.mGlobalLock) {
            ActivityRecord top = mAtms.mRootWindowContainer.getTopResumedActivity();
            return top != null && top.mUserId == userId && TextUtils.equals(top.packageName, packageName);
        }
    }

    private void markSessionUnlocked(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        if (mUnlockedApps.add(key)) {
            mUnlockTimestamps.put(key, SystemClock.elapsedRealtime());
            notifyAppSessionChanged(packageName, userId, true);
        }
    }

    public void markSessionLocked(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        if (mUnlockedApps.remove(key)) {
            mUnlockTimestamps.remove(key);
            cancelTimeoutLock(key);
            notifyAppSessionChanged(packageName, userId, false);
        }
    }

    private void lockSession(String packageName, int userId) {
        clearPendingUnlock(packageName, userId);
        markSessionLocked(packageName, userId);
    }

    private void lockAllSessionsAndNotify() {
        lockAllSessionsExcept(Collections.emptySet());
    }

    private void lockAllSessionsExcept(Set<String> keep) {
        for (String key : mUnlockedApps.toArray(new String[0])) {
            if (keep.contains(key)) {
                cancelTimeoutLock(key);
                continue;
            }
            if (mUnlockedApps.remove(key)) {
                mUnlockTimestamps.remove(key);
                cancelTimeoutLock(key);
                notifySessionKeyLocked(key);
            }
        }
        if (keep.isEmpty()) {
            clearAllTimeouts();
        }
        mUnlockTimestamps.keySet().retainAll(mUnlockedApps);
    }

    private void lockSessionKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon >= key.length() - 1) return;
        try {
            markSessionLocked(key.substring(colon + 1), Integer.parseInt(key.substring(0, colon)));
        } catch (NumberFormatException ignored) {
        }
    }

    private void notifySessionKeyLocked(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon >= key.length() - 1) return;
        try {
            notifyAppSessionChanged(key.substring(colon + 1),
                    Integer.parseInt(key.substring(0, colon)), false);
        } catch (NumberFormatException ignored) {
        }
    }

    private void scheduleTimeoutLock(String key) {
        if (mRepository.getLockBehavior() != LOCK_BEHAVIOR_TIMEOUT || !mUnlockedApps.contains(key)) {
            return;
        }
        cancelTimeoutLock(key);
        Runnable r = () -> {
            synchronized (mAtms.mGlobalLock) {
                ActivityRecord top = mAtms.mRootWindowContainer.getTopResumedActivity();
                String topKey = (top != null) ? sessionKey(top) : null;
                if (!key.equals(topKey) && !isTransientTopOfTaskOwner(top, key)) {
                    lockSessionKey(key);
                }
                mTimeoutRunnables.remove(key);
            }
        };
        mTimeoutRunnables.put(key, r);
        mAtms.mH.postDelayed(r, mRepository.getLockTimeout() * 1000L);
    }

    private void cancelTimeoutLock(String key) {
        Runnable r = mTimeoutRunnables.remove(key);
        if (r != null) {
            mAtms.mH.removeCallbacks(r);
        }
    }

    private void clearAllTimeouts() {
        for (Runnable r : mTimeoutRunnables.values()) {
            mAtms.mH.removeCallbacks(r);
        }
        mTimeoutRunnables.clear();
    }

    private boolean clearPendingUnlock(String packageName, int userId) {
        String key = sessionKey(userId, packageName);
        boolean pending = mPendingUnlocks.remove(key);
        mPendingTargets.remove(key);
        return pending;
    }

    private boolean hasPendingAuthPrompt(ActivityRecord target, String pendingKey) {
        if (hasAppLockerActivity(target.getTask(), pendingKey)) {
            return true;
        }
        DisplayContent dc = target.mDisplayContent != null
                ? target.mDisplayContent : mAtms.mWindowManager.getDefaultDisplayContentLocked();
        return dc != null && dc.getActivity(r -> isMatchingAuthPrompt(r, pendingKey)) != null;
    }

    private boolean hasAppLockerActivity(Task task, String pendingKey) {
        return task != null && task.getActivity(r -> isMatchingAuthPrompt(r, pendingKey)) != null;
    }

    private boolean isMatchingAuthPrompt(ActivityRecord r, String pendingKey) {
        if (r == null || r.finishing || r.intent == null || !isAuthActivity(r.intent.getComponent())) {
            return false;
        }
        String packageName = r.intent.getStringExtra(EXTRA_LOCKED_PACKAGE);
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        int userId = r.intent.hasExtra(EXTRA_USER_ID)
                ? r.intent.getIntExtra(EXTRA_USER_ID, UserHandle.USER_SYSTEM)
                : getUserIdFromLockedUidExtra(r.intent);
        return TextUtils.equals(pendingKey, sessionKey(userId, packageName));
    }

    private int getUserIdFromLockedUidExtra(Intent intent) {
        int uid = intent.getIntExtra(EXTRA_LOCKED_UID, -1);
        return uid >= 0 ? UserHandle.getUserId(uid) : UserHandle.USER_SYSTEM;
    }

    private boolean isLivePendingTarget(ActivityRecord target, String pendingKey) {
        if (target == null || target.finishing || target.getTask() == null) {
            return false;
        }
        return TextUtils.equals(pendingKey, sessionKey(target));
    }

    private void finishDuplicatePendingTarget(ActivityRecord target) {
        if (target == null || target.finishing) {
            return;
        }
        target.setVisibility(false);
        abortAnimation(target);
        target.finishIfPossible("applock-duplicate-pending", false);
    }

    /**
     * Hides a locked target that is already on screen so its content is not exposed while the
     * auth prompt comes up.
     *
     * <p>Only targets that are actually visible need this. Calling
     * {@link ActivityRecord#setVisibility(boolean)} on an activity that is still waiting for its
     * first frame puts it into the pending transition as an occluded participant, and since it
     * will never draw, the transition blocks until the sync timeout (2s for a change transition).
     * That is what froze the screen for 2-3s when switching between two locked apps: the launch
     * animation was held back instead of playing into the prompt.
     */
    private void hideBlockedTarget(ActivityRecord target) {
        if (target == null || target.finishing || !target.isVisible()) {
            return;
        }
        target.setVisibility(false);
        abortAnimation(target);
    }

    private void abortAnimation(ActivityRecord r) {
        if (r == null) return;
        try {
            if (r.getOptions() != null && r.getOptions().getRemoteAnimationAdapter() != null) {
                r.getOptions().getRemoteAnimationAdapter().getRunner().onAnimationCancelled();
            }
        } catch (Exception ignored) {
        }
        r.abortAndClearOptionsAnimation();
    }

    /**
     * Resolves the credential activity once, while the system is booting, instead of lazily on
     * the first app launch (which used to run a PackageManager query under the window manager
     * lock).
     */
    public void init() {
        getConfirmIntent();
    }

    private Intent getConfirmIntent() {
        if (mConfirmIntent == null) {
            Intent appLockerIntent = new Intent("com.android.applocker.action.SYSTEM_UNLOCK");
            appLockerIntent.setClassName(APPLOCKER_PACKAGE, APPLOCKER_ACTIVITY);
            appLockerIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            PackageManager pm = (mContext != null) ? mContext.getPackageManager() : null;
            if (pm != null) {
                try {
                    if (pm.resolveActivity(appLockerIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                        mConfirmIntent = appLockerIntent;
                        return mConfirmIntent;
                    }
                } catch (Exception ignored) {
                }
            }

            Intent sandboxIntent = new Intent(ACTION_SYSTEM_UNLOCK);
            sandboxIntent.setClassName(SANDBOX_PACKAGE, "com.android.axion.sandbox.AuthenticateActivity");
            sandboxIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

            if (pm != null) {
                mConfirmIntent = sandboxIntent;
            }
            return (pm != null) ? sandboxIntent : appLockerIntent;
        }
        return mConfirmIntent;
    }

    private int getPackageUid(String packageName) {
        try {
            return mContext.getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static String sessionKey(int userId, String packageName) {
        return userId + ":" + packageName;
    }

    private static String sessionKey(ActivityRecord r) {
        return sessionKey(r.mUserId, r.packageName);
    }
}
