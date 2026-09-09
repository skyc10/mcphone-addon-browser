package net.montoyo.mcef.client;

import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefStringVisitor;
import net.montoyo.mcef.api.IStringVisitor;

public class StringVisitor implements CefStringVisitor {

    private IStringVisitor isv;

    public StringVisitor(IStringVisitor isv) {
        this.isv = isv;
    }

    @Override
    public void visit(String str) {
        isv.visit(str);
    }

}
