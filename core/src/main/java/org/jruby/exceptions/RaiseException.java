/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001 Alan Moore <alan_moore@gmx.net>
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Joey Gibson <joey@joeygibson.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2005 Charles O Nutter <headius@headius.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.exceptions;

import java.lang.reflect.Member;
import org.jruby.NativeException;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyException;
import org.jruby.RubyInstanceConfig;
import org.jruby.RubyString;
import org.jruby.runtime.JavaSites;
import org.jruby.runtime.RubyEvent;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.backtrace.RubyStackTraceElement;
import org.jruby.runtime.backtrace.TraceType;
import org.jruby.runtime.builtin.IRubyObject;

public class RaiseException extends JumpException {
    private static final long serialVersionUID = -7612079169559973951L;
    public static final String NO_MESSAGE_AVAILABLE = "No message available";

    private RubyException exception;
    private String providedMessage;

    /**
     * Construct a new RaiseException to wrap the given Ruby exception for Java-land
     * throwing purposes.
     *
     * This constructor will generate a backtrace using the Java
     * stack trace and the interpreted Ruby frames for the current thread.
     *
     * @param exception The Ruby exception to wrap
     */
    public RaiseException(RubyException exception) {
        this(exception.getMessageAsJavaString(), exception, null);
    }

    /**
     * Construct a new RaiseException to wrap the given Ruby exception for Java-land
     * throwing purposes.
     *
     * This constructor will not generate a backtrace and will instead use the
     * one specified by the
     *
     * @param exception The Ruby exception to wrap
     * @param backtrace
     */
    public RaiseException(RubyException exception, IRubyObject backtrace) {
        this(exception.getMessageAsJavaString(), exception, backtrace);
    }

    public RaiseException(Ruby runtime, RubyClass excptnClass, String msg) {
        this(runtime, excptnClass, msg, null);
    }

    public RaiseException(Ruby runtime, RubyClass excptnClass, String msg, IRubyObject backtrace) {
        super(msg = guardMessage(msg));

        ThreadContext context = runtime.getCurrentContext();

        this.providedMessage = formatMessage(excptnClass, msg);
        this.exception = constructRubyException(runtime, excptnClass, msg, context);

        preRaise(context, backtrace);
    }

    protected RaiseException(String providedMessage, RubyException exception, IRubyObject backtrace) {
        super(providedMessage);

        this.providedMessage = providedMessage;
        this.exception = exception;

        preRaise(exception.getRuntime().getCurrentContext(), backtrace);
    }

    private static RubyException constructRubyException(Ruby runtime, RubyClass excptnClass, String msg, ThreadContext context) {
        return (RubyException) sites(context)._new.call(context, excptnClass, excptnClass, RubyString.newUnicodeString(runtime, guardMessage(msg)));
    }

    @Override
    public String getMessage() {
        if (providedMessage == null) {
            providedMessage = buildMessageFromException(exception);
        }
        return providedMessage;
    }

    private static String buildMessageFromException(RubyException exception) {
        String baseName = exception.getMetaClass().getBaseName();
        ThreadContext currentContext = exception.getRuntime().getCurrentContext();
        String message = exception.message(currentContext).asJavaString();

        return formatMessage(baseName, message);
    }

    private static String formatMessage(RubyClass excptnClass, String msg) {
        return formatMessage(excptnClass.getName(), guardMessage(msg));
    }

    private static String guardMessage(String msg) {
        return msg==null ? NO_MESSAGE_AVAILABLE : msg;
    }

    private static String formatMessage(String baseName, String message) {
        return '(' + baseName + ") " + message;
    }

    /**
     * Gets the exception
     * @return Returns a RubyException
     */
    public final RubyException getException() {
        return exception;
    }

    private void preRaise(ThreadContext context, IRubyObject backtrace) {
        context.runtime.incrementExceptionCount();
        doSetLastError(context);
        doCallEventHook(context);

        if (RubyInstanceConfig.LOG_EXCEPTIONS) TraceType.logException(exception);

        // We can only omit backtraces of descendents of Standard error for 'foo rescue nil'
        if (requiresBacktrace(context)) {
            if (backtrace == null) {
                exception.prepareBacktrace(context);
            } else {
                exception.forceBacktrace(backtrace);
                if ( backtrace.isNil() ) return;
            }

            // call Throwable.setStackTrace so that when RaiseException appears nested inside another exception,
            // Ruby stack trace gets displayed
            // JRUBY-2673: if wrapping a NativeException, use the actual Java exception's trace as our Java trace
            if (exception instanceof NativeException) {
                setStackTrace(((NativeException) exception).getCause().getStackTrace());
            } else {
                setStackTrace(RaiseException.javaTraceFromRubyTrace(exception.getBacktraceElements()));
            }
        }
    }

    private boolean requiresBacktrace(ThreadContext context) {
        IRubyObject debugMode;
        // We can only omit backtraces of descendents of Standard error for 'foo rescue nil'
        return context.exceptionRequiresBacktrace ||
                ((debugMode = context.runtime.getGlobalVariables().get("$DEBUG")) != null && debugMode.isTrue()) ||
                ! context.runtime.getStandardError().isInstance(exception);
    }

    private static void doCallEventHook(final ThreadContext context) {
        if (context.runtime.hasEventHooks()) {
            context.runtime.callEventHooks(context, RubyEvent.RAISE, context.getFile(), context.getLine(), context.getFrameName(), context.getFrameKlazz());
        }
    }

    private void doSetLastError(final ThreadContext context) {
        context.runtime.getGlobalVariables().set("$!", exception);
    }

    public static StackTraceElement[] javaTraceFromRubyTrace(RubyStackTraceElement[] trace) {
        StackTraceElement[] newTrace = new StackTraceElement[trace.length];
        for (int i = 0; i < newTrace.length; i++) {
            newTrace[i] = trace[i].asStackTraceElement();
        }
        return newTrace;
    }

    private static JavaSites.RaiseExceptionSites sites(ThreadContext context) {
        return context.sites.RaiseException;
    }

    @Deprecated
    public RaiseException(Throwable cause, NativeException nativeException) {
        this(nativeException.getMessageAsJavaString(), cause, nativeException, null, true);
        preRaise(nativeException.getRuntime().getCurrentContext(), nativeException.getCause().getStackTrace());
        setStackTrace(RaiseException.javaTraceFromRubyTrace(exception.getBacktraceElements()));
    }

    @Deprecated
    private RaiseException(String providedMessage, Throwable cause, RubyException exception, IRubyObject backtrace, boolean nativeException) {
        super(providedMessage, cause);
        this.providedMessage = providedMessage;
        this.exception = exception;
        preRaise(exception.getRuntime().getCurrentContext(), backtrace);
    }

    @Deprecated
    public RaiseException(RubyException exception, IRubyObject backtrace, boolean ignored) {
        this(exception, backtrace);
    }

    @Deprecated
    public RaiseException(Ruby runtime, RubyClass excptnClass, String msg, boolean ignored) {
        this(runtime, excptnClass, msg);
    }

    @Deprecated
    public RaiseException(Ruby runtime, RubyClass excptnClass, String msg, IRubyObject backtrace, boolean ignored) {
        this(runtime, excptnClass, msg, backtrace);
    }

    @Deprecated
    public RaiseException(RubyException exception, boolean ignored) {
        this(exception);
    }

    @Deprecated
    public static RaiseException createNativeRaiseException(Ruby runtime, Throwable cause) {
        return createNativeRaiseException(runtime, cause, null);
    }

    @Deprecated
    public static RaiseException createNativeRaiseException(Ruby runtime, Throwable cause, Member target) {
        NativeException nativeException = new NativeException(runtime, runtime.getNativeException(), cause);

        // FIXME: someday, add back filtering of reflection/handle methods between JRuby and target

        return new RaiseException(cause, nativeException);
    }

    @Deprecated
    private void preRaise(ThreadContext context, StackTraceElement[] javaTrace) {
        context.runtime.incrementExceptionCount();
        doSetLastError(context);
        doCallEventHook(context);

        if (RubyInstanceConfig.LOG_EXCEPTIONS) TraceType.logException(exception);

        if (requiresBacktrace(context)) {
            exception.prepareIntegratedBacktrace(context, javaTrace);
        }
    }
}
