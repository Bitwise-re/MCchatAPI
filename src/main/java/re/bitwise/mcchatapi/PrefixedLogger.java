package re.bitwise.mcchatapi;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;

// Logger abstraction to prefixe every message with a fixed identifier before handing it to the real logger.
final class PrefixedLogger extends AbstractLogger {
    private final Logger delegate;
    private final String prefix;

    PrefixedLogger(Logger _delegate, String _prefix) {
        delegate = _delegate;
        prefix = _prefix;
        name = delegate.getName();
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return delegate.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String message, Object[] arguments, Throwable throwable) {
        Object[] args = withThrowable(arguments, throwable);
        String prefixed = prefix + message;
        switch (level) {
            case TRACE -> delegate.trace(marker, prefixed, args);
            case DEBUG -> delegate.debug(marker, prefixed, args);
            case INFO -> delegate.info(marker, prefixed, args);
            case WARN -> delegate.warn(marker, prefixed, args);
            case ERROR -> delegate.error(marker, prefixed, args);
        }
    }

    /** SLF4J extracts a trailing Throwable from varargs and prints its stack trace. */
    private static Object[] withThrowable(Object[] arguments, Throwable throwable) {
        if (throwable == null) {
            return arguments;
        }
        int length = arguments == null ? 0 : arguments.length;
        if (length > 0 && arguments[length - 1] == throwable) {
            return arguments;
        }
        Object[] merged = new Object[length + 1];
        if (length > 0) {
            System.arraycopy(arguments, 0, merged, 0, length);
        }
        merged[length] = throwable;
        return merged;
    }

    @Override
    public boolean isTraceEnabled() {
        return delegate.isTraceEnabled();
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return delegate.isTraceEnabled(marker);
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return delegate.isDebugEnabled(marker);
    }

    @Override
    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return delegate.isInfoEnabled(marker);
    }

    @Override
    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return delegate.isWarnEnabled(marker);
    }

    @Override
    public boolean isErrorEnabled() {
        return delegate.isErrorEnabled();
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return delegate.isErrorEnabled(marker);
    }
}
