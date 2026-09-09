package net.montoyo.mcef.client;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import net.montoyo.mcef.api.IJSQueryCallback;
import net.montoyo.mcef.api.IJSQueryHandler;

public class QueryCallback implements IJSQueryCallback {

    private final CefQueryCallback cb;

    public QueryCallback(CefQueryCallback cb) {
        this.cb = cb;
    }

    @Override
    public void success(String response) {
        cb.success(response);
    }

    @Override
    public void failure(int errId, String errMsg) {
        cb.failure(errId, errMsg);
    }

}
