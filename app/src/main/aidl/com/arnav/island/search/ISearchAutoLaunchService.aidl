package com.arnav.island.search;

import com.arnav.island.search.ISearchAutoLaunchCallback;

interface ISearchAutoLaunchService {
    void destroy() = 16777114;
    void setCallback(ISearchAutoLaunchCallback callback) = 1;
    void setTargetLaunchers(in String[] launcherPackages) = 2;
    void setInstalledApps(in String[] packages, in String[] labels) = 3;
    void configure(long delayMs, int minChars, boolean skipWhenDeleting) = 4;
    void setWatching(boolean watching) = 5;
    boolean isConnected() = 6;
    String getLastStatus() = 7;
}
