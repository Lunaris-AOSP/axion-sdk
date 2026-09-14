package com.android.server.wm;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.UserHandle;

import com.android.internal.app.HiddenNotificationInfo;
import com.android.internal.app.IAppLockStateListener;
import com.android.internal.app.IAppSessionListener;
import com.android.internal.app.IHiddenNotificationListener;
import com.android.server.SystemService;
import com.android.server.wm.ActivityRecord;
import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.Task;
import com.android.server.wm.sandbox.apphide.AppHideRepository;
import com.android.server.wm.sandbox.apphide.AppHideService;
import com.android.server.wm.sandbox.apphide.HiddenNotificationController;
import com.android.server.wm.sandbox.applock.AppLockRepository;
import com.android.server.wm.sandbox.isolation.SandboxIsolationRepository;
import com.android.server.wm.sandbox.isolation.SandboxIsolationService;
import com.android.server.wm.sandbox.spoof.SettingsSpoofRepository;
import com.android.server.wm.sandbox.spoof.SettingsSpoofService;

import java.util.List;
import java.util.Set;

public final class AxSandboxService extends SystemService {

    public static final String SANDBOX_PACKAGE = AppLockService.SANDBOX_PACKAGE;
    public static final String APPLOCKER_PACKAGE = AppLockService.APPLOCKER_PACKAGE;
    public static final Set<String> BLACKLISTED_PACKAGES = AppLockService.BLACKLISTED_PACKAGES;

    private final ActivityTaskManagerService mAtms;
    private final Context mContext;

    private final AppLockRepository mAppLockRepository;
    private final AppHideRepository mAppHideRepository;
    private final SandboxIsolationRepository mIsolationRepository;
    private final SettingsSpoofRepository mSpoofRepository;

    private final AppLockService mAppLockService;
    private final AppHideService mAppHideService;
    private final SandboxIsolationService mIsolationService;
    private final SettingsSpoofService mSpoofService;

    private final BroadcastReceiver mPackageRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                return;
            }
            Uri data = intent.getData();
            if (data == null) return;
            String packageName = data.getSchemeSpecificPart();
            if (packageName == null) return;

            mAppLockService.removeLockedApp(packageName);
            mAppHideService.setPackageHidden(packageName, false);
            mAppHideService.clearNotificationsForPackage(packageName);
            mIsolationService.removeSandboxedPackage(packageName);
        }
    };

    private static volatile AxSandboxService sInstance;

    public static AxSandboxService get() {
        return sInstance;
    }

    public static AxSandboxService getInstance() {
        return sInstance;
    }

    public AxSandboxService(Context context, ActivityTaskManagerService atms) {
        super(context);
        mContext = context;
        mAtms = atms;
        sInstance = this;

        mAppLockRepository = new AppLockRepository(mContext);
        mAppHideRepository = new AppHideRepository(mContext);
        mIsolationRepository = new SandboxIsolationRepository(mContext);
        mSpoofRepository = new SettingsSpoofRepository(mContext);

        HiddenNotificationController notifController = new HiddenNotificationController();
        mAppHideService = new AppHideService(mContext, mAppHideRepository, notifController);
        mAppLockService = new AppLockService(mContext, mAtms, mAppLockRepository);
        mIsolationService = new SandboxIsolationService(mIsolationRepository);
        mSpoofService = new SettingsSpoofService(mSpoofRepository);
    }

    @Override
    public void onStart() {
        sInstance = this;
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            systemReadyInternal();
        }
    }

    public void systemReadyInternal() {
        mAppLockRepository.init();
        mAppHideRepository.init();
        mIsolationRepository.init();
        mSpoofRepository.init();

        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiverAsUser(mPackageRemovedReceiver, UserHandle.ALL, filter, null, mAtms.mH);
        } catch (Exception ignored) {
        }
    }

    public boolean isAppLocked(String packageName) {
        return mAppLockService.isAppLocked(packageName);
    }

    public boolean isAppLocked(ActivityRecord r) {
        return mAppLockService.isAppLocked(r);
    }

    public boolean isAppLocked(String packageName, int uid, ComponentName component) {
        return mAppLockService.isAppLocked(packageName, uid, component);
    }

    public int getAppLockState(String packageName) {
        return mAppLockService.getAppLockState(packageName);
    }

    public int getAppLockStateForUser(String packageName, int userId) {
        return mAppLockService.getAppLockStateForUser(packageName, userId);
    }

    public boolean hasAppLock(String packageName) {
        return mAppLockService.hasAppLock(packageName);
    }

    public void addLockedApp(String packageName) {
        mAppLockService.addLockedApp(packageName);
    }

    public void removeLockedApp(String packageName) {
        mAppLockService.removeLockedApp(packageName);
    }

    public List<String> getLockedPackages() {
        return mAppLockService.getLockedPackages();
    }

    public List<String> getLockablePackages() {
        return mAppLockService.getLockablePackages();
    }

    public boolean isPackageLockable(String packageName) {
        return mAppLockService.isPackageLockable(packageName);
    }

    public void unlockApp(String packageName, int userId) {
        mAppLockService.unlockApp(packageName, userId);
    }

    public void promptUnlock(String packageName, int userId) {
        mAppLockService.promptUnlock(packageName, userId);
    }

    public boolean checkLockApp(ActivityRecord prev, ActivityRecord next) {
        return mAppLockService.checkLockApp(prev, next);
    }

    public boolean checkUnlockApp(ActivityRecord r, int resultCode, Intent resultData) {
        return mAppLockService.checkUnlockApp(r, resultCode, resultData);
    }

    public void lockTopApp(Task task, String reason) {
        mAppLockService.lockTopApp(task, reason);
    }

    public void onAppFocusChanged(ActivityRecord newFocus, Task newTask) {
        mAppLockService.onAppFocusChanged(newFocus, newTask);
    }

    public void onWindowingModeChanged(Task task, int prevWindowingMode) {
        mAppLockService.onWindowingModeChanged(task, prevWindowingMode);
    }

    public void onAppDied(String packageName, int userId) {
        mAppLockService.onAppDied(packageName, userId);
    }

    public boolean isTopAppLocked(ActivityManager.RecentTaskInfo rti, int topUserId) {
        return mAppLockService.isTopAppLocked(rti, topUserId);
    }

    public void getRecentTasksCheck(int callingUid, int userId) {
        mAppLockService.getRecentTasksCheck(callingUid, userId);
    }

    public void clearUnlockedApp() {
        mAppLockService.clearUnlockedApp();
    }

    public void clearUnlockedApp(ActivityRecord r) {
        mAppLockService.clearUnlockedApp(r);
    }

    public void removeTask(Task task, String reason) {
        mAppLockService.removeTask(task, reason);
    }

    public void setKeyguardDoneLocked(boolean done) {
        mAppLockService.setKeyguardDone(done);
    }

    public boolean isSandboxActivity(ComponentName componentName) {
        return mAppLockService.isSandboxActivity(componentName);
    }

    public boolean isAppLockerActivity(ComponentName componentName) {
        return mAppLockService.isAppLockerActivity(componentName);
    }

    public void registerAppLockStateListener(IAppLockStateListener listener) {
        mAppLockService.registerAppLockStateListener(listener);
    }

    public void unregisterAppLockStateListener(IAppLockStateListener listener) {
        mAppLockService.unregisterAppLockStateListener(listener);
    }

    public void registerAppSessionListener(IAppSessionListener listener) {
        mAppLockService.registerAppSessionListener(listener);
    }

    public void unregisterAppSessionListener(IAppSessionListener listener) {
        mAppLockService.unregisterAppSessionListener(listener);
    }

    public boolean isPackageHidden(String packageName) {
        return mAppHideService.isPackageHidden(packageName);
    }

    public void setPackageHidden(String packageName, boolean hidden) {
        mAppHideService.setPackageHidden(packageName, hidden);
    }

    public boolean isPackageHiddenFromLauncher(String packageName) {
        return mAppHideService.isPackageHiddenFromLauncher(packageName);
    }

    public void setPackageHiddenFromLauncher(String packageName, boolean hidden) {
        mAppHideService.setPackageHiddenFromLauncher(packageName, hidden);
    }

    public List<String> getHiddenPackages() {
        return mAppHideService.getHiddenPackages();
    }

    public List<String> getHiddenFromLauncherPackages() {
        return mAppHideService.getHiddenFromLauncherPackages();
    }

    public void registerHiddenNotificationListener(IHiddenNotificationListener listener) {
        mAppHideService.registerHiddenNotificationListener(listener);
    }

    public void unregisterHiddenNotificationListener(IHiddenNotificationListener listener) {
        mAppHideService.unregisterHiddenNotificationListener(listener);
    }

    public List<HiddenNotificationInfo> getHiddenNotifications() {
        return mAppHideService.getHiddenNotifications();
    }

    public void onHiddenNotificationPosted(HiddenNotificationInfo info) {
        mAppHideService.onHiddenNotificationPosted(info);
    }

    public void onHiddenNotificationRemoved(String key) {
        mAppHideService.onHiddenNotificationRemoved(key);
    }

    public boolean isPackageSandboxed(String packageName) {
        return mIsolationService.isPackageSandboxed(packageName);
    }

    public void addSandboxedPackage(String packageName) {
        mIsolationService.addSandboxedPackage(packageName);
    }

    public void removeSandboxedPackage(String packageName) {
        mIsolationService.removeSandboxedPackage(packageName);
    }

    public List<String> getSandboxedPackages() {
        return mIsolationService.getSandboxedPackages();
    }

    public void setRestrictedGids(String packageName, int[] gids) {
        mIsolationService.setRestrictedGids(packageName, gids);
    }

    public int[] getRestrictedGids(String packageName) {
        return mIsolationService.getRestrictedGids(packageName);
    }

    public boolean isSandboxDataIsolationEnabled(String packageName) {
        return mIsolationService.isSandboxDataIsolationEnabled(packageName);
    }

    public void setSandboxDataIsolationEnabled(String packageName, boolean enabled) {
        mIsolationService.setSandboxDataIsolationEnabled(packageName, enabled);
    }

    public boolean isSpoofSettingEnabled(String packageName, String settingKey) {
        return mSpoofService.isSpoofSettingEnabled(packageName, settingKey);
    }

    public void setSpoofSettingEnabled(String packageName, String settingKey, boolean enabled) {
        mSpoofService.setSpoofSettingEnabled(packageName, settingKey, enabled);
    }

    public List<String> getEnabledSpoofSettings(String packageName) {
        return mSpoofService.getEnabledSpoofSettings(packageName);
    }

    public String getSpoofedSetting(String callingPackage, String settingName) {
        return mSpoofService.getSpoofedSetting(callingPackage, settingName);
    }
}
