package com.arnav.island.search;

oneway interface ISearchAutoLaunchCallback {
    void onAppAutoLaunched(String packageName, String appLabel, String query, boolean clickedByNode);
    void onStatusChanged(String status);
}
