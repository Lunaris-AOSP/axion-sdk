package com.android.server.wm.sandbox.isolation;

import java.util.List;

public class SandboxIsolationService {
    private static volatile SandboxIsolationService sInstance;

    public static SandboxIsolationService getInstance() {
        return sInstance;
    }

    private final SandboxIsolationRepository mRepository;

    public SandboxIsolationService(SandboxIsolationRepository repository) {
        mRepository = repository;
        sInstance = this;
    }

    public boolean isPackageSandboxed(String packageName) {
        return mRepository.isPackageSandboxed(packageName);
    }

    public void addSandboxedPackage(String packageName) {
        mRepository.setPackageSandboxed(packageName, true);
    }

    public void removeSandboxedPackage(String packageName) {
        mRepository.setPackageSandboxed(packageName, false);
    }

    public List<String> getSandboxedPackages() {
        return mRepository.getSandboxedPackages();
    }

    public void setRestrictedGids(String packageName, int[] gids) {
        mRepository.setRestrictedGids(packageName, gids);
    }

    public int[] getRestrictedGids(String packageName) {
        return mRepository.getRestrictedGids(packageName);
    }

    public boolean isSandboxDataIsolationEnabled(String packageName) {
        return mRepository.isDataIsolationEnabled(packageName);
    }

    public void setSandboxDataIsolationEnabled(String packageName, boolean enabled) {
        mRepository.setDataIsolationEnabled(packageName, enabled);
    }

    public boolean isDevOptionsHidden(String packageName) {
        return mRepository.isDevOptionsHidden(packageName);
    }

    public void setDevOptionsHidden(String packageName, boolean hidden) {
        mRepository.setDevOptionsHidden(packageName, hidden);
    }

    public List<String> getDevOptionsHiddenPackages() {
        return mRepository.getDevOptionsHiddenPackages();
    }
}
