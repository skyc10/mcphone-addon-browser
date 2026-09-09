package net.montoyo.mcef.client;

import java.util.LinkedList;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;

import net.montoyo.mcef.api.EventData;
import net.montoyo.mcef.api.IDisplayHandler;

public class DisplayHandler extends CefDisplayHandlerAdapter {

    private static final int QUEUE_CAP = 512;

    private final LinkedList<EventData> queue = new LinkedList<EventData>();
    private final java.util.List<IDisplayHandler> idh = new java.util.ArrayList<IDisplayHandler>();

    public void addHandler(IDisplayHandler h) {
        idh.add(h);
    }

    public void update() {
        while(!queue.isEmpty()) {
            EventData dat = queue.removeFirst();
            dat.call();
        }
    }

    private void add(EventData dat) {
        // Cap the queue: if nobody drains it (addon never registers a display
        // handler), we must not grow without bound over long sessions.
        if(queue.size() < QUEUE_CAP)
            queue.addLast(dat);
    }

    @Override
    public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
        if(!(browser instanceof CefBrowserOsr))
            return;

        final CefBrowserOsr b = (CefBrowserOsr) browser;
        add(new EventData() {
            @Override
            public void call() {
                for(IDisplayHandler h: idh)
                    h.onAddressChange(b, url);
            }
        });
    }

    @Override
    public void onTitleChange(CefBrowser browser, String title) {
        if(!(browser instanceof CefBrowserOsr))
            return;

        final CefBrowserOsr b = (CefBrowserOsr) browser;
        add(new EventData() {
            @Override
            public void call() {
                for(IDisplayHandler h: idh)
                    h.onTitleChange(b, title);
            }
        });
    }

    @Override
    public boolean onTooltip(CefBrowser browser, String text) {
        if(!(browser instanceof CefBrowserOsr))
            return false;

        final CefBrowserOsr b = (CefBrowserOsr) browser;
        final String t = text;
        add(new EventData() {
            @Override
            public void call() {
                for(IDisplayHandler h: idh)
                    h.onTooltip(b, t);
            }
        });

        return false; // Let CEF display its own tooltip rendering
    }

    @Override
    public void onStatusMessage(CefBrowser browser, String value) {
        if(!(browser instanceof CefBrowserOsr))
            return;

        final CefBrowserOsr b = (CefBrowserOsr) browser;
        add(new EventData() {
            @Override
            public void call() {
                for(IDisplayHandler h: idh)
                    h.onStatusMessage(b, value);
            }
        });
    }

}
