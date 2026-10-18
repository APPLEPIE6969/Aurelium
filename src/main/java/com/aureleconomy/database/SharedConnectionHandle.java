package com.aureleconomy.database;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * A borrow handle onto the one shared connection, whose {@code close()} does nothing.
 *
 * <p>Every caller used to receive the same {@link Connection} object and close it when
 * its statement closed. Closing a statement does not close a connection, but closing
 * the connection it was prepared from killed it for everyone still holding one:
 * {@code TradeVolumeTracker} does exactly that from a scheduled task, which is how
 * servers ended up logging "The database has been closed" while an unrelated economy
 * write was in flight.
 *
 * <p>This is the ownership seam for that, and it is deliberately temporary. Under the
 * shared connection a borrow cannot mean "release", so close is neutralised here and
 * the call sites take real ownership of a connection by naming it in their
 * try-with-resources header. That makes every borrow correct by construction instead of
 * correct by luck, without touching the seventeen sites that already close one.
 *
 * <p>Deleted by the second half of H1, where HikariCP supplies pooled connections whose
 * {@code close()} already means "return to the pool" natively. Nothing in the plugin
 * should depend on this class.
 *
 * <p>Public only so a test that stands in for {@link DatabaseManager} can hand out
 * borrows with the same semantics production does. A harness that returns a raw
 * connection instead is testing a provider this plugin does not have, and will close
 * the handle out from under its own assertions.
 *
 * <p>The handle is bound per borrow to whichever physical connection existed at that
 * moment, because {@code SQLiteDatabase} rebuilds its handle whenever it finds the
 * previous one closed. Every other method delegates verbatim, so {@code isClosed()}
 * still reports the truth and a stale handle is still detected rather than hidden.
 * {@link DatabaseManager#close()} and plugin disable are unaffected: they reach the
 * physical connection directly through the dialect's {@code disconnect()}.
 */
public final class SharedConnectionHandle {

    private SharedConnectionHandle() {
    }

    /**
     * Wraps {@code delegate}, or returns null for a null delegate so a failed
     * reconnection still reads as null rather than as a usable handle.
     */
    public static Connection wrap(Connection delegate) {
        if (delegate == null) {
            return null;
        }
        return (Connection) Proxy.newProxyInstance(
                SharedConnectionHandle.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InertClose(delegate));
    }

    private static final class InertClose implements InvocationHandler {

        private final Connection delegate;

        InertClose(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                return null;
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                // Surface what the driver actually threw, not the reflection wrapper,
                // so a SQLException here still reads as a SQLException in a log.
                throw e.getCause();
            }
        }
    }
}