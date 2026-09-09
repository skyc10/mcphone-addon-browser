package net.montoyo.mcef.client;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import net.montoyo.mcef.api.IJSQueryHandler;

public class MessageRouter extends CefMessageRouterHandlerAdapter {

    private final IJSQueryHandler handler;

    public MessageRouter(IJSQueryHandler iqh) {
        handler = iqh;
    }

    @Override
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request, boolean persistent, CefQueryCallback callback) {
        if(browser instanceof CefBrowserOsr)
            return handler.handleQuery((CefBrowserOsr) browser, queryId, request, persistent, new QueryCallback(callback));
        else
            return handler.handleQuery(null, queryId, request, persistent, new QueryCallback(callback));
    }

    @Override
    public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
        if(browser instanceof CefBrowserOsr)
            handler.cancelQuery((CefBrowserOsr) browser, queryId);
        else
            handler.cancelQuery(null, queryId);
    }

}
