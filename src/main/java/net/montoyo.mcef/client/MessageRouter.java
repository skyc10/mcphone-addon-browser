package net.montoyo.mcef.client;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import net.montoyo.mcef.api.IJSQueryCallback;
import net.montoyo.mcef.api.IJSQueryHandler;

public class MessageRouter extends CefMessageRouterHandlerAdapter {

    private final IJSQueryHandler handler;

    public MessageRouter(IJSQueryHandler iqh) {
        handler = iqh;
    }

    @Override
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String query, boolean persistent, CefQueryCallback callback) {
        IJSQueryCallback cb = new QueryCallback(callback);

        if(browser instanceof CefBrowserOsr)
            return handler.handleQuery((CefBrowserOsr) browser, queryId, query, persistent, cb);
        else
            return handler.handleQuery(null, queryId, query, persistent, cb);
    }

    @Override
    public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
        if(browser instanceof CefBrowserOsr)
            handler.cancelQuery((CefBrowserOsr) browser, queryId);
        else
            handler.cancelQuery(null, queryId);
    }

}
