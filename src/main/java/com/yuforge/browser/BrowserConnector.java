package com.yuforge.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
