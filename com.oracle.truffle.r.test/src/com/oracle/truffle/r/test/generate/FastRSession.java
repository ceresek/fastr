/*
 * Copyright (c) 2013, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.test.generate;

import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.r.launcher.RCmdOptions;
import com.oracle.truffle.r.launcher.RCmdOptions.Client;
import com.oracle.truffle.r.launcher.REPL;
import com.oracle.truffle.r.launcher.RStartParams;
import com.oracle.truffle.r.launcher.StringConsoleHandler;
import com.oracle.truffle.r.runtime.ExitException;
import com.oracle.truffle.r.runtime.JumpToTopLevelException;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.RLogger;
import com.oracle.truffle.r.runtime.RSource;
import com.oracle.truffle.r.runtime.Utils;
import com.oracle.truffle.r.runtime.context.ChildContextInfo;
import com.oracle.truffle.r.runtime.context.Engine.IncompleteSourceException;
import com.oracle.truffle.r.runtime.context.Engine.ParseException;
import com.oracle.truffle.r.runtime.context.FastROptions;
import static com.oracle.truffle.r.runtime.context.FastROptions.PrintErrorStacktraces;
import static com.oracle.truffle.r.runtime.context.FastROptions.PrintErrorStacktracesToFile;
import com.oracle.truffle.r.runtime.context.RContext;
import com.oracle.truffle.r.runtime.context.RContext.ContextKind;
import com.oracle.truffle.r.test.TestBase;
import com.oracle.truffle.r.test.engine.interop.VectorInteropTest;

public final class FastRSession implements RSession {

    public static final Source GET_CONTEXT = createSource("invisible(.fastr.context.get())", RSource.Internal.GET_CONTEXT.string);

    private static final String TEST_TIMEOUT_PROPERTY = "fastr.test.timeout";
    private static int timeoutValue = 10000;

    private static FastRSession singleton;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final TestByteArrayInputStream input = new TestByteArrayInputStream();

    private Engine mainEngine;
    private Context mainContext;
    private RContext mainRContext;

    private static final class TestByteArrayInputStream extends ByteArrayInputStream {

        TestByteArrayInputStream() {
            super(new byte[0]);
        }

        public void setContents(String data) {
            this.buf = data.getBytes(StandardCharsets.UTF_8);
            this.count = this.buf.length;
            this.pos = 0;
        }

        @Override
        public synchronized int read() {
            return super.read();
        }
    }

    public static FastRSession create() {
        if (singleton == null) {
            singleton = new FastRSession();
        }
        return singleton;
    }

    public static Source createSource(String txt, String name) {
        return Source.newBuilder("R", txt, name).internal(true).interactive(true).buildLiteral();
    }

    public FastRContext createContext(ContextKind contextKind) {
        return createContext(contextKind, true);
    }

    public FastRContext createContext(ContextKind contextKind, @SuppressWarnings("unused") boolean allowHostAccess) {
        RStartParams params = new RStartParams(RCmdOptions.parseArguments(new String[]{Client.R.argumentName(), "--vanilla", "--slave", "--silent", "--no-restore"}, false), false);
        Map<String, String> env = new HashMap<>();
        env.put("TZ", "GMT");
        ChildContextInfo info = ChildContextInfo.create(params, env, contextKind, contextKind == ContextKind.SHARE_NOTHING ? null : mainRContext, input, output, output);
        return FastRContext.create(mainContext, info);
    }

    public static Context.Builder getContextBuilder(String... languages) {
        Context.Builder builder = Context.newBuilder(languages).allowExperimentalOptions(true);
        setCLIOptions(builder);
        builder.allowAllAccess(true);
        builder.option(FastROptions.getName(PrintErrorStacktraces), "true");
        // no point in printing errors to file when running tests (that contain errors on purpose)
        builder.option(FastROptions.getName(PrintErrorStacktracesToFile), "false");
        return builder;
    }

    private static boolean cliOptionSet = false;

    private static void setCLIOptions(Context.Builder builder) {
        if (cliOptionSet) {
            return;
        }
        cliOptionSet = true;
        for (Map.Entry<String, String> entry : TestBase.options.entrySet()) {
            builder.option(entry.getKey(), entry.getValue());
            System.out.println("Setting option " + entry.getKey() + "=" + entry.getValue());
        }
    }

    private FastRSession() {
        String timeOutProp = System.getProperty(TEST_TIMEOUT_PROPERTY);
        if (timeOutProp != null) {
            if (timeOutProp.length() == 0) {
                timeoutValue = Integer.MAX_VALUE;
            } else {
                int timeoutGiven = Integer.parseInt(timeOutProp);
                timeoutValue = timeoutGiven * 1000;
                // no need to scale longTimeoutValue
            }
        }
        try {
            RStartParams params = new RStartParams(RCmdOptions.parseArguments(new String[]{Client.R.argumentName(), "--vanilla", "--slave", "--silent", "--no-restore"}, false), false);
            ChildContextInfo info = ChildContextInfo.create(params, null, ContextKind.SHARE_NOTHING, null, input, output, output);
            RContext.childInfo = info;
            mainEngine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.UseConservativeContextReferences", "true").in(input).out(output).err(output).build();
            mainContext = getContextBuilder("R", "llvm").engine(mainEngine).build();
            mainRContext = mainContext.eval(GET_CONTEXT).asHostObject();
        } finally {
            try {
                System.out.print(output.toString("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

    {
        decoder.onMalformedInput(CodingErrorAction.IGNORE);
        decoder.onUnmappableCharacter(CodingErrorAction.IGNORE);
    }

    public RContext getContext() {
        return mainRContext;
    }

    private String readLine() {
        /*
         * We cannot use an InputStreamReader because it buffers characters internally, whereas
         * readLine() should not buffer across newlines.
         */

        ByteBuffer bytes = ByteBuffer.allocate(16);
        CharBuffer chars = CharBuffer.allocate(16);
        StringBuilder str = new StringBuilder();
        decoder.reset();
        boolean initial = true;
        while (true) {
            int inputByte = input.read();
            if (inputByte == -1) {
                return initial ? null : str.toString();
            }
            initial = false;
            bytes.put((byte) inputByte);
            bytes.flip();
            decoder.decode(bytes, chars, false);
            chars.flip();
            while (chars.hasRemaining()) {
                char c = chars.get();
                if (c == '\n' || c == '\r') {
                    return str.toString();
                }
                str.append(c);
            }
            bytes.compact();
            chars.clear();
        }
    }

    @Override
    public String eval(TestBase testClass, String expression, ContextKind contextKind, long timeout) throws Throwable {
        return eval(testClass, expression, contextKind, timeout, true);
    }

    public String eval(TestBase testClass, String expression, ContextKind contextKind, long timeout, boolean allowHostAccess) throws Throwable {
        assert contextKind != null;
        Timer timer = null;
        output.reset();
        input.setContents(expression);
        try (FastRContext evalContext = createContext(contextKind, allowHostAccess)) {
            // set up some interop objects used by fastr-specific tests:
            if (testClass != null) {
                testClass.addPolyglotSymbols(evalContext);
            }
            timer = scheduleTimeBoxing(evalContext.getEngine(), timeout == USE_DEFAULT_TIMEOUT ? timeoutValue : timeout);
            String consoleInput = readLine();
            while (consoleInput != null) {
                try {
                    try {
                        Source src = createSource(consoleInput, RSource.Internal.UNIT_TEST.string);
                        evalContext.eval(src);
                        // checked exceptions are wrapped in PolyglotException
                    } catch (PolyglotException e) {
                        // TODO see bellow - need the wrapped exception for special handling of
                        // ParseException, etc
                        Throwable wt = getWrappedThrowable(e);
                        if (wt instanceof RError) {
                            REPL.handleError(null, evalContext.getContext(), e);
                        }
                        throw wt;
                    }
                    consoleInput = readLine();
                } catch (IncompleteSourceException e) {
                    String additionalInput = readLine();
                    if (additionalInput == null) {
                        throw e;
                    }
                    consoleInput += "\n" + additionalInput;
                }
            }
        } catch (ParseException e) {
            e.report(output);
        } catch (ExitException | JumpToTopLevelException e) {
            // exit and jumpToTopLevel exceptions are legitimate if a test case calls "q()" or "Q"
            // during debugging
        } catch (RError e) {
            // nothing to do
        } catch (Throwable t) {
            if (!TestBase.ProcessFailedTests || TestBase.ShowFailedTestsResults) {
                if (t instanceof RInternalError) {
                    RInternalError.reportError(t, mainRContext);
                }
                t.printStackTrace();
            }
            throw t;
        } finally {
            if (timer != null) {
                timer.cancel();
            }
        }
        try {
            return output.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return "<exception>";
        }
    }

    public String evalInREPL(TestBase testClass, String expression, ContextKind contextKind, long timeout, boolean allowHostAccess) throws Throwable {
        assert contextKind != null;
        Timer timer = null;
        output.reset();
        input.setContents(expression);
        try (FastRContext evalContext = createContext(contextKind, allowHostAccess)) {
            // set up some interop objects used by fastr-specific tests:
            if (testClass != null) {
                testClass.addPolyglotSymbols(evalContext);
            }
            timer = scheduleTimeBoxing(evalContext.getEngine(), timeout == USE_DEFAULT_TIMEOUT ? timeoutValue : timeout);
            REPL.readEvalPrint(evalContext.getContext(), new StringConsoleHandler(Arrays.asList(expression.split("\n")), output), null, false);
            String consoleInput = readLine();
            while (consoleInput != null) {
                consoleInput = readLine();
            }
        } finally {
            if (timer != null) {
                timer.cancel();
            }
        }
        try {
            return output.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return "<exception>";
        }
    }

    private static Throwable getWrappedThrowable(PolyglotException e) {
        Object f = getField(e, "impl");
        return (Throwable) getField(f, "exception");
    }

    private static Timer scheduleTimeBoxing(Engine engine, long timeout) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                Instrument i = engine.getInstruments().get("debugger");
                Debugger debugger = i.lookup(Debugger.class);
                debugger.startSession(new SuspendedCallback() {
                    @Override
                    public void onSuspend(SuspendedEvent event) {
                        // print diagnostic info
                        Thread.dumpStack();
                        System.out.println(Utils.createStackTrace(true));

                        event.prepareKill();
                    }
                }).suspendNextExecution();
            }
        }, timeout);
        return timer;
    }

    @Override
    public String name() {
        return "FastR";
    }

    public static Object getReceiver(Value value) {
        return getField(value, "receiver");
    }

    // Copied from ReflectionUtils.
    // TODO we need better support to access the TruffleObject in Value
    private static Object getField(Object value, String name) {
        try {
            Field f = value.getClass().getDeclaredField(name);
            setAccessible(f, true);
            return f.get(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final boolean Java8OrEarlier = System.getProperty("java.specification.version").compareTo("1.9") < 0;

    private static void setAccessible(Field field, boolean flag) {
        if (!Java8OrEarlier) {
            openForReflectionTo(field.getDeclaringClass(), FastRSession.class);
        }
        field.setAccessible(flag);
    }

    /**
     * Opens {@code declaringClass}'s package to allow a method declared in {@code accessor} to call
     * {@link AccessibleObject#setAccessible(boolean)} on an {@link AccessibleObject} representing a
     * field or method declared by {@code declaringClass}.
     */
    private static void openForReflectionTo(Class<?> declaringClass, Class<?> accessor) {
        try {
            Method getModule = Class.class.getMethod("getModule");
            Class<?> moduleClass = getModule.getReturnType();
            Class<?> modulesClass = Class.forName("jdk.internal.module.Modules");
            Method addOpens = maybeGetAddOpensMethod(moduleClass, modulesClass);
            if (addOpens != null) {
                Object moduleToOpen = getModule.invoke(declaringClass);
                Object accessorModule = getModule.invoke(accessor);
                if (moduleToOpen != accessorModule) {
                    addOpens.invoke(null, moduleToOpen, declaringClass.getPackage().getName(), accessorModule);
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Method maybeGetAddOpensMethod(Class<?> moduleClass, Class<?> modulesClass) {
        try {
            return modulesClass.getDeclaredMethod("addOpens", moduleClass, String.class, moduleClass);
        } catch (NoSuchMethodException e) {
            // This method was introduced by JDK-8169069
            return null;
        }
    }

    public static void execInContext(FastRContext context, Callable<Object> c) {
        execInContext(context, c, (Class<?>[]) null);
    }

    public static <E extends Exception> void execInContext(FastRContext context, Callable<Object> c, Class<?>... acceptExceptions) {
        context.eval(FastRSession.GET_CONTEXT); // ping creation of TruffleRLanguage
        context.getPolyglotBindings().putMember("testSymbol", (ProxyExecutable) (Value... args) -> {
            try {
                c.call();
            } catch (Exception ex) {
                if (acceptExceptions != null) {
                    for (Class<?> cs : acceptExceptions) {
                        if (cs.isAssignableFrom(ex.getClass())) {
                            return null;
                        }
                    }
                }
                RLogger.getLogger(VectorInteropTest.class.getName()).log(Level.SEVERE, null, ex);
                fail();
            }
            return null;
        });
        context.getPolyglotBindings().getMember("testSymbol").execute();
    }

}
