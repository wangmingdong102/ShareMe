package com.leaves.sdk.wifidirect.listener;

import android.net.wifi.p2p.WifiP2pDeviceList;

/**
 * 设备连接发生变化
 * Created by leaves on 17-1-24.
 */
public interface ErrorHandler {
    void error(int reason);
}
