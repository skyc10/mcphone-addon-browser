package net.montoyo.mcef.api;

/**
 * An event to be dispatched on the main Minecraft thread.
 * The compat layer queues CEF callbacks (which arrive on CEF/OS threads)
 * and drains them from the render tick via ClientProxy.onTick.
 */
public interface EventData {

    void call();

}
