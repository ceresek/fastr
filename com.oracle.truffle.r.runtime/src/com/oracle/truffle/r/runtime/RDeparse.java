/*
 * Copyright (c) 1995-2012, The R Core Team
 * Copyright (c) 2003, The R Foundation
 * Copyright (c) 2013, 2019, Oracle and/or its affiliates
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, a copy is available at
 * https://www.R-project.org/Licenses/
 */
package com.oracle.truffle.r.runtime;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.context.RContext;
import com.oracle.truffle.r.runtime.data.CharSXPWrapper;
import com.oracle.truffle.r.runtime.data.RAttributable;
import com.oracle.truffle.r.runtime.data.RAttributesLayout;
import com.oracle.truffle.r.runtime.data.RAttributesLayout.RAttribute;
import com.oracle.truffle.r.runtime.data.RBaseObject;
import com.oracle.truffle.r.runtime.data.RComplex;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.REmpty;
import com.oracle.truffle.r.runtime.data.RExpression;
import com.oracle.truffle.r.runtime.data.RExternalPtr;
import com.oracle.truffle.r.runtime.data.RFunction;
import com.oracle.truffle.r.runtime.data.RIntSequence;
import com.oracle.truffle.r.runtime.data.RList;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.RPairList;
import com.oracle.truffle.r.runtime.data.RPromise;
import com.oracle.truffle.r.runtime.data.RPromise.EagerPromise;
import com.oracle.truffle.r.runtime.data.RRaw;
import com.oracle.truffle.r.runtime.data.RS4Object;
import com.oracle.truffle.r.runtime.data.RStringVector;
import com.oracle.truffle.r.runtime.data.RSymbol;
import com.oracle.truffle.r.runtime.data.closures.RClosure;
import com.oracle.truffle.r.runtime.data.closures.RToComplexVectorClosure;
import com.oracle.truffle.r.runtime.data.closures.RToDoubleVectorClosure;
import com.oracle.truffle.r.runtime.data.closures.RToIntVectorClosure;
import com.oracle.truffle.r.runtime.data.closures.RToStringVectorClosure;
import com.oracle.truffle.r.runtime.data.model.RAbstractAtomicVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractComplexVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractListVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractRawVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.env.REnvironment;
import com.oracle.truffle.r.runtime.interop.TruffleObjectConverter;
import com.oracle.truffle.r.runtime.nodes.RCodeBuilder.Argument;
import com.oracle.truffle.r.runtime.nodes.RSyntaxCall;
import com.oracle.truffle.r.runtime.nodes.RSyntaxConstant;
import com.oracle.truffle.r.runtime.nodes.RSyntaxElement;
import com.oracle.truffle.r.runtime.nodes.RSyntaxFunction;
import com.oracle.truffle.r.runtime.nodes.RSyntaxLookup;
import com.oracle.truffle.r.runtime.nodes.RSyntaxNode;
import com.oracle.truffle.r.runtime.nodes.RSyntaxVisitor;

/**
 * Deparsing R objects.
 */
public class RDeparse {

    public static final int KEEPINTEGER = 1;
    public static final int QUOTEEXPRESSIONS = 2;
    public static final int SHOWATTRIBUTES = 4;
    public static final int USESOURCE = 8;
    public static final int WARNINCOMPLETE = 16;
    public static final int DELAYPROMISES = 32;
    public static final int KEEPNA = 64;
    public static final int S_COMPAT = 128;
    /* common combinations of the above */
    public static final int SIMPLEDEPARSE = 0;
    public static final int DEFAULTDEPARSE = 1089; /*
                                                    * KEEPINTEGER | KEEPNA | NICE_NAMES, used for
                                                    * calls
                                                    */
    public static final int NICE_NAMES = 1024;

    public static final int MIN_CUTOFF = 20;
    public static final int MAX_CUTOFF = 500;
    public static final int DEFAULT_CUTOFF = 60;
    public static final char BACKTICK = '`';
    public static final char DQUOTE = '"';

    public enum PP {
        FUNCALL,
        RETURN,
        BINARY,
        BINARY2,
        UNARY,
        IF,
        WHILE,
        FOR,
        BREAK,
        NEXT,
        REPEAT,
        FUNCTION,
        ASSIGN,
        CURLY,
        PAREN,
        SUBSET,
        DOLLAR
    }

    // TODO for consistency make an enum
    public static final int PREC_FN = 0;
    public static final int PREC_LEFT = 1;
    public static final int PREC_EQ = 2;
    public static final int PREC_RIGHT = 3;
    public static final int PREC_TILDE = 4;
    public static final int PREC_OR = 5;
    public static final int PREC_AND = 6;
    public static final int PREC_NOT = 7;
    public static final int PREC_COMPARE = 8;
    public static final int PREC_SUM = 9;
    public static final int PREC_PROD = 10;
    public static final int PREC_PERCENT = 11;
    public static final int PREC_COLON = 12;
    public static final int PREC_SIGN = 13;
    public static final int PREC_POWER = 14;
    public static final int PREC_DOLLAR = 15;
    public static final int PREC_NS = 16;
    public static final int PREC_SUBSET = 17;

    public static class PPInfo {
        public final PP kind;
        public final int prec;
        public final boolean rightassoc;

        PPInfo(PP kind, int prec, boolean rightassoc) {
            this.kind = kind;
            this.prec = prec;
            this.rightassoc = rightassoc;
        }

        public PPInfo changePrec(int newPrec) {
            return new PPInfo(kind, newPrec, rightassoc);
        }
    }

    public static class Func {
        public final String op;
        public final String closeOp;
        public final PPInfo info;

        Func(String op, String closeOp, PPInfo info) {
            this.op = op;
            this.closeOp = closeOp;
            this.info = info;
        }
    }

    private static final Func[] FUNCTAB = new Func[]{
                    new Func("+", null, new PPInfo(PP.BINARY, PREC_SUM, false)),
                    new Func("-", null, new PPInfo(PP.BINARY, PREC_SUM, false)),
                    new Func("*", null, new PPInfo(PP.BINARY, PREC_PROD, false)),
                    new Func("/", null, new PPInfo(PP.BINARY2, PREC_PROD, false)),
                    new Func("^", null, new PPInfo(PP.BINARY2, PREC_POWER, false)),
                    new Func("%%", null, new PPInfo(PP.BINARY, PREC_PERCENT, false)),
                    new Func("%/%", null, new PPInfo(PP.BINARY, PREC_PERCENT, false)),
                    new Func("%*%", null, new PPInfo(PP.BINARY, PREC_PERCENT, false)),
                    new Func("==", null, new PPInfo(PP.BINARY, PREC_COMPARE, false)),
                    new Func("!=", null, new PPInfo(PP.BINARY, PREC_COMPARE, false)),
                    new Func("<", null, new PPInfo(PP.BINARY, PREC_COMPARE, false)),
                    new Func("<=", null, new PPInfo(PP.BINARY, PREC_COMPARE, false)),
                    new Func(">=", null, new PPInfo(PP.BINARY, PREC_COMPARE, false)),
                    new Func(">", null, new PPInfo(PP.BINARY, PREC_COMPARE, false)),
                    new Func("&", null, new PPInfo(PP.BINARY, PREC_AND, false)),
                    new Func("|", null, new PPInfo(PP.BINARY, PREC_OR, false)),
                    new Func("!", null, new PPInfo(PP.BINARY, PREC_NOT, false)),
                    new Func("&&", null, new PPInfo(PP.BINARY, PREC_AND, false)),
                    new Func("||", null, new PPInfo(PP.BINARY, PREC_OR, false)),
                    new Func(":", null, new PPInfo(PP.BINARY2, PREC_COLON, false)),
                    new Func("~", null, new PPInfo(PP.BINARY, PREC_TILDE, false)),

                    new Func("if", null, new PPInfo(PP.IF, PREC_FN, true)),
                    new Func("while", null, new PPInfo(PP.WHILE, PREC_FN, false)),
                    new Func("for", null, new PPInfo(PP.FOR, PREC_FN, false)),
                    new Func("repeat", null, new PPInfo(PP.REPEAT, PREC_FN, false)),
                    new Func("break", null, new PPInfo(PP.BREAK, PREC_FN, false)),
                    new Func("next", null, new PPInfo(PP.NEXT, PREC_FN, false)),
                    new Func("return", null, new PPInfo(PP.RETURN, PREC_FN, false)),
                    new Func("function", null, new PPInfo(PP.FUNCTION, PREC_FN, false)),
                    new Func("{", "}", new PPInfo(PP.CURLY, PREC_FN, false)),
                    new Func("(", ")", new PPInfo(PP.PAREN, PREC_FN, false)),
                    new Func("<-", null, new PPInfo(PP.ASSIGN, PREC_LEFT, true)),
                    new Func("=", null, new PPInfo(PP.ASSIGN, PREC_LEFT, true)),
                    new Func("<<-", null, new PPInfo(PP.ASSIGN, PREC_LEFT, true)),
                    new Func("[", "]", new PPInfo(PP.SUBSET, PREC_SUBSET, false)),
                    new Func("[[", "]]", new PPInfo(PP.SUBSET, PREC_SUBSET, false)),
                    new Func("$", null, new PPInfo(PP.DOLLAR, PREC_DOLLAR, false)),
                    new Func("@", null, new PPInfo(PP.DOLLAR, PREC_DOLLAR, false)),
    };

    private static final PPInfo USERBINOP = new PPInfo(PP.BINARY, PREC_PERCENT, false);

    public static Func getFunc(String op) {
        for (Func func : FUNCTAB) {
            if (func.op.equals(op)) {
                return func;
            }
        }
        // user binary op?
        if (isUserBinop(op)) {
            return new Func(op, null, USERBINOP);
        }
        return null;
    }

    private static boolean isUserBinop(String op) {
        int len = op.length();
        return len > 0 && op.charAt(0) == '%' && op.charAt(len - 1) == '%';
    }

    private static Func isInfixOperatorNode(RSyntaxElement element) {
        if (element instanceof RSyntaxCall) {
            RSyntaxElement lhs = ((RSyntaxCall) element).getSyntaxLHS();
            if (lhs instanceof RSyntaxLookup) {
                String name = ((RSyntaxLookup) lhs).getIdentifier();
                Func func = RDeparse.getFunc(name);
                if (func == null) {
                    return null;
                } else {
                    return func.info.kind == PP.RETURN ? null : func;
                }
            }
        }
        return null;
    }

    private interface C extends AutoCloseable {
        // this interface is used to get a shorter name and remove the checked exception
        @Override
        void close();
    }

    private static final class SourceSectionElement {
        public final RSyntaxElement element;
        public final int start;
        public final int length;

        SourceSectionElement(RSyntaxElement element, int start, int length) {
            this.element = element;
            this.start = start;
            this.length = length;
        }
    }

    private static final class DeparseVisitor {

        private final Visitor visitor = new Visitor();

        private final StringBuilder sb = new StringBuilder();

        private final ArrayList<SourceSectionElement> sources;

        private final int listCutoff;
        private final int debugCutoff;
        private final boolean backtick;
        private int opts;
        private final int nlines;

        private int inCurly = 0;
        private int inList = 0;
        private int indent = 0;
        private int lastLineStart = 0;
        private int curLine = 1;

        DeparseVisitor(boolean storeSource, int cutoff, boolean backtick, int opts, int nlines) {
            this(storeSource, cutoff, backtick, opts, nlines, -1);
        }

        DeparseVisitor(boolean storeSource, int cutoff, boolean backtick, int opts, int nlines, int debugCutoff) {
            this.listCutoff = cutoff;
            this.backtick = backtick;
            this.opts = opts;
            this.nlines = nlines;
            this.sources = storeSource ? new ArrayList<>() : null;
            this.debugCutoff = debugCutoff;
        }

        public String getContents() {
            // strip surplus newlines
            int length = sb.length();
            while (length > 1) {
                char c = sb.charAt(length - 1);
                if (c != '\n' && c != ' ') {
                    break;
                }
                length--;
            }
            sb.setLength(length);
            return sb.toString();
        }

        private boolean showAttributes() {
            return (opts & SHOWATTRIBUTES) != 0;
        }

        private boolean niceNames() {
            return (opts & NICE_NAMES) != 0;
        }

        boolean quoteExpressions() {
            return (opts & QUOTEEXPRESSIONS) != 0;
        }

        private void checkLength(int nchar) {
            if (debugCutoff >= 0 && sb.length() + nchar > debugCutoff) {
                throw new MaxLengthReachedException();
            }
        }

        private DeparseVisitor append(char ch) {
            assert ch != '\n';
            checkLength(1);
            sb.append(ch);
            return this;
        }

        private DeparseVisitor append(String str) {
            assert !str.contains("\n");
            checkLength(str.length());
            sb.append(str);
            return this;
        }

        private C withContext(RSyntaxElement... context) {
            if (sources == null) {
                return () -> {
                };
            } else {
                int startIndex = sb.length();
                return () -> {
                    for (RSyntaxElement element : context) {
                        sources.add(new SourceSectionElement(element, startIndex, sb.length() - startIndex));
                    }
                };
            }
        }

        public void fixupSources() {
            RootNode rootNode = getRootNode();
            String name = rootNode != null ? rootNode.getName() : null;
            String text = sb.toString();
            if (name != null && !name.isEmpty() && !name.equals("<no source>")) {
                name = name.replace(File.separatorChar, '_') + ".r";
            } else {
                name = "unknown.r";
            }
            Source source = RSource.fromText(text, name);
            for (SourceSectionElement s : sources) {
                s.element.setSourceSection(source.createSection(s.start, s.length));
            }
        }

        private RootNode getRootNode() {
            // the last element in the list is the top-most one
            RSyntaxElement n = sources.get(sources.size() - 1).element;
            if (n instanceof RootNode) {
                return (RootNode) n;
            }
            return null;
        }

        @SuppressWarnings("try")
        private DeparseVisitor append(String str, RSyntaxElement... context) {
            try (C c = withContext(context)) {
                append(str);
            }
            return this;
        }

        @SuppressWarnings("try")
        public DeparseVisitor append(RSyntaxElement element) {
            try (C c = withContext(element)) {
                visitor.accept(element);
            } catch (AbortDeparsingException e) {
                // stop deparsing; indicate that there is something missing
                sb.append("...");
            }
            return this;
        }

        private void printline() {
            sb.append("\n");
            curLine++;
            lastLineStart = sb.length();
            if (nlines > 0 && curLine >= nlines) {
                throw new MaxLinesReachedException();
            }
            for (int i = 0; i < indent; i++) {
                sb.append(i < 4 ? "    " : "  ");
            }
        }

        private static boolean isSequence(RSyntaxElement element) {
            if (element instanceof RSyntaxCall) {
                RSyntaxElement lhs = ((RSyntaxCall) element).getSyntaxLHS();
                if (lhs instanceof RSyntaxLookup) {
                    RSyntaxLookup lookup = (RSyntaxLookup) lhs;
                    return "{".equals(lookup.getIdentifier());
                }
            }
            return false;
        }

        private static String isConstantString(RSyntaxElement element) {
            if (element instanceof RSyntaxConstant) {
                return RRuntime.asStringLengthOne(((RSyntaxConstant) element).getValue());
            }
            return null;
        }

        private boolean listLinebreak(boolean lbreak) {
            boolean result = lbreak;
            if ((sb.length() - lastLineStart) > listCutoff) {
                if (!lbreak) {
                    result = true;
                    indent++;
                }
                printline();
            }
            return result;
        }

        private C indent() {
            indent++;
            return new C() {
                @Override
                public void close() {
                    indent--;
                }
            };
        }

        private C inCurly() {
            inCurly++;
            return new C() {
                @Override
                public void close() {
                    inCurly--;
                }
            };
        }

        private final class Visitor extends RSyntaxVisitor<Void> {

            @Override
            @SuppressWarnings("try")
            protected Void visit(RSyntaxCall call) {
                RSyntaxElement lhs = call.getSyntaxLHS();
                RSyntaxElement[] args = call.getSyntaxArguments();
                if (lhs instanceof RSyntaxLookup) {
                    String symbol = ((RSyntaxLookup) lhs).getIdentifier();
                    if ("function".equals(symbol)) {
                        return visitFunctionFunction(args);
                    }
                    RDeparse.Func func = RDeparse.getFunc(symbol);
                    if (func != null) {
                        PPInfo info = func.info;
                        if (args.length == 0) {
                            switch (info.kind) {
                                case BREAK:
                                case NEXT:
                                    append(func.op, call, lhs);
                                    return null;
                            }
                        } else if (args.length == 1) {
                            switch (info.kind) {
                                case BINARY:
                                case BINARY2:
                                    append(func.op, lhs);
                                    appendWithParens(args[0], info, false);
                                    return null;
                                case REPEAT:
                                    append("repeat", lhs).append(' ').append(args[0]);
                                    return null;
                                case PAREN:
                                    append(func.op, lhs).append(args[0]).append(func.closeOp);
                                    return null;
                                case ASSIGN:
                                    appendWithParens(args[0], info, true);
                                    append(" <- NULL");
                                    return null;
                                case UNARY:
                                    append(func.op, lhs);
                                    append(args[0]);

                            }
                        } else if (args.length == 2) {
                            switch (info.kind) {
                                case ASSIGN:
                                case BINARY:
                                case BINARY2:
                                    appendWithParens(args[0], info, true);
                                    if (info.kind != PP.BINARY2) {
                                        append(' ').append(func.op, lhs).append(' ');
                                    } else {
                                        append(func.op);
                                    }
                                    appendWithParens(args[1], info, false);
                                    return null;
                                case DOLLAR:
                                    appendWithParens(args[0], info, true);
                                    append(func.op, lhs);
                                    String name = isConstantString(args[1]);
                                    if (name != null && isValidName(name)) {
                                        append(name, args[1]);
                                    } else {
                                        appendWithParens(args[1], info, false);
                                    }
                                    return null;
                                case IF:
                                    append("if", lhs).append(" (").append(args[0]).append(")");
                                    if (inCurly > 0 && inList == 0 && !isSequence(args[1])) {
                                        try (C c = indent()) {
                                            printline();
                                            append(args[1]);
                                        }
                                    } else {
                                        append(" ").append(args[1]);
                                    }
                                    return null;
                                case WHILE:
                                    append("while", lhs).append(" (").append(args[0]).append(") ").append(args[1]);
                                    return null;
                            }
                        } else if (args.length == 3) {
                            switch (symbol) {
                                case "for":
                                    append("for", lhs).append(" (").append(args[0]).append(" in ").append(args[1]).append(") ").append(args[2]);
                                    return null;
                                case "if":
                                    append("if", lhs).append(" (").append(args[0]).append(")");
                                    if (inCurly > 0 && inList == 0 && !isSequence(args[1])) {
                                        try (C c = indent()) {
                                            printline();
                                            append(args[1]).printline();
                                        }
                                    } else {
                                        append(" ").append(args[1]);
                                        if (inCurly > 0 && inList == 0) {
                                            printline();
                                        } else {
                                            append(' ');
                                        }
                                    }
                                    append("else ").append(args[2]);
                                    return null;
                            }
                        }
                        switch (info.kind) {
                            case CURLY:
                                append("{", lhs);
                                try (C i = indent(); C c = inCurly()) {
                                    for (RSyntaxElement statement : args) {
                                        printline();
                                        append(statement);
                                    }
                                }
                                printline();
                                append('}');
                                return null;
                            case SUBSET:
                                if (args.length > 0) {
                                    appendWithParens(args[0], info, true);
                                    append(func.op, lhs).appendArgs(call.getSyntaxSignature(), args, 1, false).append(func.closeOp);
                                    return null;
                                }
                                break;
                        }
                    }
                    if ("::".equals(symbol) || ":::".equals(symbol)) {
                        if (args.length == 0) {
                            append("NULL").append(symbol).append("NULL");
                        } else if (args.length == 1) {
                            append(args[0]).append(symbol).append("NULL");
                        } else {
                            // FIXME use call syntax until parser fixed to accept literals
                            if (args[1] instanceof RSyntaxConstant) {
                                append('`').append(symbol).append('`').append('(').append(args[0]).append(", ").append(args[1]).append(')');
                            } else {
                                append(args[0]).append(symbol).append(args[1]);
                            }
                        }
                        return null;
                    }
                }

                PPInfo info = new PPInfo(PP.FUNCALL, PREC_FN, false);
                appendWithParens(lhs, info, true);
                append('(').appendArgs(call.getSyntaxSignature(), args, 0, false).append(')');
                return null;
            }

            @Override
            protected Void visit(RSyntaxConstant constant) {
                // coerce scalar values to vectors and unwrap data frames and factors:
                appendConstant(constant.getValue());
                return null;
            }

            @Override
            protected Void visit(RSyntaxLookup lookup) {
                if (!backtick || isValidName(lookup.getIdentifier())) {
                    append(lookup.getIdentifier());
                } else {
                    append(quotify(lookup.getIdentifier(), BACKTICK));
                }
                return null;
            }

            @Override
            protected Void visit(RSyntaxFunction function) {
                return visitFunctionExpr(function.getSyntaxSignature(), function.getSyntaxArgumentDefaults(), function.getSyntaxBody());
            }

            private Void visitFunctionExpr(ArgumentsSignature signature, RSyntaxElement[] argsDefaults, RSyntaxElement body) {
                append("function(");
                appendArgs(signature, argsDefaults, 0, true);
                append(") ");
                appendFunctionBody(body);
                return null;
            }

            private Void visitFunctionFunction(RSyntaxElement[] args) {
                if (args.length > 0 && !(args[0] instanceof RSyntaxConstant)) {
                    throw RError.error(RError.SHOW_CALLER2, Message.BAD_FUNCTION_EXPR);
                }
                Object funArgsValue = args.length > 0 ? ((RSyntaxConstant) args[0]).getValue() : RNull.instance;
                List<Argument<RSyntaxNode>> syntaxArgs = RContext.getASTBuilder().getFunctionExprArgs(funArgsValue);
                String[] names = new String[syntaxArgs.size()];
                RSyntaxNode[] values = new RSyntaxNode[syntaxArgs.size()];
                for (int i = 0; i < syntaxArgs.size(); i++) {
                    names[i] = syntaxArgs.get(i).name;
                    values[i] = syntaxArgs.get(i).value;
                }
                Object body = args.length <= 1 ? RNull.instance : args[1];
                if (!(body instanceof RSyntaxElement)) {
                    body = RContext.getASTBuilder().constant(RSyntaxNode.SOURCE_UNAVAILABLE, body);
                }
                visitFunctionExpr(ArgumentsSignature.get(names), values, (RSyntaxElement) body);
                return null;
            }
        }

        private void appendWithParens(RSyntaxElement arg, PPInfo mainOp, boolean isLeft) {
            Func func = isInfixOperatorNode(arg);
            boolean lbreak = false;
            boolean shouldbreak = true;
            boolean needsParens = false;
            if (func == null) {
                // put parens around complex values
                needsParens = !isLeft && arg instanceof RSyntaxConstant && ((RSyntaxConstant) arg).getValue() instanceof RAbstractComplexVector;
                if (arg instanceof RSyntaxConstant) {
                    shouldbreak = false;
                }
            } else {
                PPInfo arginfo = func.info;
                switch (arginfo.kind) {
                    case ASSIGN:
                        needsParens = true;
                        break;
                    case BINARY:
                    case BINARY2:
                        RSyntaxElement[] subArgs = ((RSyntaxCall) arg).getSyntaxArguments();
                        if (subArgs.length == 1) {
                            shouldbreak = false;
                            if (!isLeft && (arginfo.prec != RDeparse.PREC_NOT || mainOp.prec != RDeparse.PREC_NOT)) {
                                needsParens = false;
                                break;
                            }
                            if (arginfo.prec == RDeparse.PREC_SUM) {
                                arginfo = arginfo.changePrec(RDeparse.PREC_SIGN);
                            }
                        } else if (subArgs.length == 2) {
                            if (mainOp.prec == PREC_COMPARE && arginfo.prec == PREC_COMPARE) {
                                needsParens = true;
                                break;
                            }
                        } else if (subArgs.length > 2) {
                            needsParens = false;
                            break;
                        }
                        needsParens = mainOp.prec > arginfo.prec || (mainOp.prec == arginfo.prec && (mainOp.prec != RDeparse.PREC_NOT && isLeft == mainOp.rightassoc));
                        break;
                    case FUNCTION:
                        needsParens = true;
                        break;
                    default:
                        shouldbreak = false;
                        break;
                }
            }
            if (needsParens) {
                append('(');
                append(arg);
                append(')');
            } else {
                if (shouldbreak) {
                    listLinebreak(lbreak);
                }
                append(arg);
            }
        }

        @SuppressWarnings("try")
        private DeparseVisitor appendConstant(Object originalValue) {
            Object value = RRuntime.convertScalarVectors(originalValue);
            if (value instanceof RExpression) {
                append("expression(").appendListContents((RExpression) value).append(')');
            } else if (value instanceof RAbstractListVector) {
                RAbstractListVector obj = (RAbstractListVector) value;
                try (C c = withAttributes(obj)) {
                    append("list(").appendListContents(obj).append(')');
                }
            } else if (value instanceof RAbstractAtomicVector) {
                RAbstractVector obj = (RAbstractAtomicVector) value;
                try (C c = withAttributes(obj)) {
                    appendVector((RAbstractAtomicVector) value);
                }
            } else if (value instanceof RNull) {
                append("NULL");
            } else if (value instanceof RFunction) {
                RFunction f = (RFunction) value;
                if (f.isBuiltin()) {
                    append(".Primitive(\"").append(f.getName()).append("\")");
                } else {
                    RSyntaxFunction function = (RSyntaxFunction) f.getRootNode();
                    append("function (");
                    appendArgs(function.getSyntaxSignature(), function.getSyntaxArgumentDefaults(), 0, true);
                    append(") ");
                    appendFunctionBody(function.getSyntaxBody());
                }
            } else if ((value instanceof RPairList && !((RPairList) value).isLanguage())) {
                RPairList arglist = (RPairList) value;
                RPairList arg = arglist;
                boolean missing = false;
                while (arg != null) {
                    if (arg.car() instanceof RSymbol && ((RSymbol) arg.car()).isMissing()) {
                        missing = true;
                        break;
                    }
                    arg = next(arg);
                }
                if (missing) {
                    append("as.pairlist(alist(");
                } else {
                    append("pairlist(");
                }
                int i = 0;
                boolean lbreak = false;
                while (arglist != null) {
                    if (i++ > 0) {
                        append(", ");
                    }
                    lbreak = listLinebreak(lbreak);
                    if (arglist.getTag() != RNull.instance) {
                        String argName = ((RSymbol) arglist.getTag()).getName();
                        if (!argName.isEmpty()) {
                            append(argName).append(" = ");
                        }
                    }
                    appendValue(arglist.car());
                    arglist = next(arglist);
                }
                if (missing) {
                    append("))");
                } else {
                    append(')');
                }
            } else if (value instanceof RS4Object) {
                RS4Object s4Obj = (RS4Object) value;
                Object clazz = s4Obj.getAttr("class");
                String className = clazz == null ? "S4" : RRuntime.toString(RRuntime.asStringLengthOne(clazz));
                append("new(\"").append(className).append('\"');
                try (C c = indent()) {
                    printline();
                    if (s4Obj.getAttributes() != null) {
                        for (RAttribute att : RAttributesLayout.asIterable(s4Obj.getAttributes())) {
                            if (!"class".equals(att.getName())) {
                                append(", ").append(att.getName()).append(" = ").appendValue(att.getValue()).printline();
                            }
                        }
                    }
                }
                append(')');
            } else if (value instanceof RExternalPtr) {
                append(((RExternalPtr) value).toString());
            } else if (value instanceof REnvironment) {
                append("<environment>");
            } else if (value instanceof REmpty) {
                append("alist()");
            } else if (value instanceof EagerPromise) {
                return appendConstant(((EagerPromise) value).getEagerValue());
            } else if (value instanceof RPromise) {
                RPromise promise = (RPromise) value;
                if (promise.isEvaluated()) {
                    return appendConstant(promise.getValue());
                } else {
                    append("<unevaluated>");
                }
            } else if (value instanceof TruffleObject) {
                Object rObject = TruffleObjectConverter.convert((TruffleObject) value);
                if (rObject == value) {
                    append("<foreign object>");
                } else if (rObject == null) {
                    append("<foreign object: null>");
                } else {
                    append("<foreign object: ");
                    appendConstant(rObject);
                    append('>');
                }
            } else {
                throw RInternalError.shouldNotReachHere("unexpected type while deparsing constant: " + value == null ? "null" : value.getClass().getSimpleName());
            }
            return this;
        }

        private DeparseVisitor appendFunctionBody(RSyntaxElement body) {
            boolean newline = true;
            if (body instanceof RSyntaxCall) {
                RSyntaxCall c = (RSyntaxCall) body;
                if (c.getSyntaxLHS() instanceof RSyntaxLookup) {
                    RSyntaxLookup l = (RSyntaxLookup) c.getSyntaxLHS();
                    if ("{".equals(l.getIdentifier())) {
                        newline = false;
                    }
                }
            }
            if (newline) {
                printline();
            }
            return append(body);
        }

        private DeparseVisitor appendArgs(ArgumentsSignature signature, RSyntaxElement[] args, int start, boolean formals) {
            boolean lbreak = false;
            for (int i = start; i < args.length; i++) {
                if (i > start) {
                    append(", ");
                }
                lbreak = listLinebreak(lbreak);
                RSyntaxElement argument = args[i];
                String name = signature.getName(i);

                if (name != null && !name.isEmpty()) {
                    if (isValidName(name)) {
                        append(name);
                    } else {
                        append(quotify(name, BACKTICK));
                    }
                    if ("...".equals(name) && argument instanceof RSyntaxLookup && "...".equals(((RSyntaxLookup) argument).getIdentifier())) {
                        continue;
                    }
                    if (!formals || argument != null) {
                        append(" = ");
                    }
                }
                if (argument != null) {
                    if (argument instanceof RSyntaxLookup && ((RSyntaxLookup) argument).getIdentifier().isEmpty()) {
                        continue;
                    }
                    if (argument instanceof RSyntaxConstant && ((RSyntaxConstant) argument).getValue() instanceof REmpty) {
                        continue;
                    }
                    append(argument);
                }
            }
            if (lbreak) {
                indent--;
            }
            return this;
        }

        private DeparseVisitor appendValue(Object v) {
            assert v != null;
            assert !(v instanceof RSyntaxElement) : v.getClass();

            if (RRuntime.isForeignObject(v)) {
                sb.append("[polyglot.value]");
                return this;
            }

            if (v instanceof CharSXPWrapper) {
                sb.append("<CHARSXP: \"").append(((CharSXPWrapper) v).getContents()).append("\">");
                return this;
            }

            Object value = RRuntime.convertScalarVectors(v);
            assert value instanceof RBaseObject : v.getClass();

            try {
                RSyntaxElement element;
                if (value instanceof RSymbol) {
                    element = RSyntaxLookup.createDummyLookup(RSyntaxNode.INTERNAL, ((RSymbol) value).getName(), false);
                } else if ((value instanceof RPairList && ((RPairList) value).isLanguage())) {
                    element = ((RPairList) value).getSyntaxElement();
                } else if (value instanceof RMissing) {
                    element = RSyntaxLookup.createDummyLookup(null, "", false);
                } else {
                    return appendConstant(value);
                }
                if (!quoteExpressions() || element instanceof RSyntaxConstant) {
                    append(element);
                } else {
                    append("quote(");
                    append(element);
                    append(')');
                }
            } catch (AbortDeparsingException e) {
                // stop deparsing; indicate that there is something missing
                sb.append("...");
            }
            return this;
        }

        private static RPairList next(RPairList pairlist) {
            if (pairlist.cdr() == RNull.instance) {
                return null;
            } else {
                return (RPairList) pairlist.cdr();
            }
        }

        private static boolean containNames(DynamicObject dobj) {
            if (dobj != null) {
                List<Property> properties = dobj.getShape().getPropertyList();
                for (int i = 0; i < properties.size(); i++) {
                    String name = (String) properties.get(i).getKey();
                    if (name.equals(RRuntime.NAMES_ATTR_KEY)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void appendVector(RAbstractAtomicVector vec) {
            assert vec != null;
            boolean lbreak = true;
            boolean usablename = usableNiceNames(vec.getAttributes());
            boolean showname = showAttributes() && usablename && containNames(vec.getAttributes());
            boolean nicename = niceNames() && usablename;
            int len = vec.getLength();
            if (len == 0) {
                append(vec.getRType().getClazz() + "(0)");
            } else if (vec instanceof RAbstractRawVector) {
                append("as.raw(c(");
                for (int i = 0; i < len; i++) {
                    if (nicename || showname) {
                        append(vec.getNames().getDataAt(i));
                        append(" = ");
                        vecElement2buff(vec.getDataAtAsObject(i), false);
                    } else {
                        vecElement2buff(vec.getDataAtAsObject(i), false);
                    }
                    if (i < (len - 1)) {
                        append(", ");
                    }
                    lbreak = listLinebreak(lbreak);
                }
                append("))");
            } else if (len == 1) {
                if (nicename || showname) {
                    append("c(");
                    RStringVector name = vec.getNames();
                    if (name != null) {
                        append(name.getDataAt(0));
                        append(" = ");
                    }
                    vecElement2buff(vec.getDataAtAsObject(0), true);
                    append(')');
                } else {
                    vecElement2buff(vec.getDataAtAsObject(0), true);
                }

            } else {
                RIntSequence sequence = asIntSequence(vec);
                if (sequence != null) {
                    append(RRuntime.intToStringNoCheck(sequence.getStart())).append(':').append(RRuntime.intToStringNoCheck(sequence.getEnd()));
                } else {
                    if (vec instanceof RClosure) {
                        append(closureToCoercionFunction((RClosure) vec));
                    }
                    // TODO COMPAT?
                    append("c(");

                    for (int i = 0; i < len; i++) {
                        RStringVector names = vec.getNames();
                        if (names != null) {
                            String name = names.getDataAt(i);
                            if (name.equals(RRuntime.NA_HEADER)) {
                                if (niceNames()) {
                                    append(name);
                                    append(" = ");
                                }
                            } else {
                                append(name);
                                append(" = ");
                            }
                        }
                        Object elem;
                        if (vec instanceof RClosure) {
                            elem = ((RClosure) vec).getDelegateDataAt(i);
                        } else {
                            elem = vec.getDataAtAsObject(i);
                        }
                        vecElement2buff(elem, false);
                        if (i < (len - 1)) {
                            append(", ");
                        }
                        lbreak = listLinebreak(lbreak);
                    }
                    append(')');
                    if (vec instanceof RClosure) {
                        append(")");
                    }
                }
            }
        }

        private static String closureToCoercionFunction(RClosure vec) {
            if (vec instanceof RToComplexVectorClosure) {
                return "as.complex(";
            } else if (vec instanceof RToDoubleVectorClosure) {
                return "as.double(";
            } else if (vec instanceof RToIntVectorClosure) {
                return "as.integer(";
            } else if (vec instanceof RToStringVectorClosure) {
                return "as.character(";
            }
            throw RInternalError.shouldNotReachHere("unhandled closure type " + vec.getClass().getSimpleName());
        }

        private static RIntSequence asIntSequence(RAbstractVector vec) {
            if (!(vec instanceof RAbstractIntVector) || vec instanceof RToIntVectorClosure) {
                return null;
            }
            RAbstractIntVector intVec = (RAbstractIntVector) vec;
            if (vec instanceof RIntSequence) {
                return (RIntSequence) vec;
            }
            assert vec.getLength() >= 2;
            int start = intVec.getDataAt(0);
            if (RRuntime.isNA(start)) {
                return null;
            }
            int stride = start < intVec.getDataAt(1) ? 1 : -1;
            for (int i = 1; i < vec.getLength(); i++) {
                int next = intVec.getDataAt(i);
                if (RRuntime.isNA(next)) {
                    return null;
                } else if (stride == 1 && next != start + i) {
                    return null;
                } else if (stride == -1 && next != start - i) {
                    return null;
                }
            }
            return RDataFactory.createIntSequence(start, stride, intVec.getLength());
        }

        private DeparseVisitor vecElement2buff(Object element, boolean singleElement) {
            if (element instanceof String) {
                String s = (String) element;
                append(RRuntime.isNA(s) ? (singleElement ? "NA_character_" : "NA") : RRuntime.escapeString(s, true, true));
            } else if (element instanceof Byte) {
                // simply "NA" is already the logical NA, so no special handling needed
                append(RRuntime.logicalToString((byte) element));
            } else if (element instanceof Double) {
                double d = (double) element;
                append(RRuntime.isNA(d) ? (singleElement ? "NA_real_" : "NA") : RContext.getRRuntimeASTAccess().encodeDouble(d));
            } else if (element instanceof Integer) {
                int i = (int) element;
                if (RRuntime.isNA(i)) {
                    append((singleElement ? "NA_integer_" : "NA"));
                } else {
                    append(RRuntime.intToStringNoCheck(i));
                    if ((opts & KEEPINTEGER) != 0) {
                        append('L');
                    }
                }
            } else if (element instanceof RComplex) {
                RComplex c = (RComplex) element;
                if (RRuntime.isNA(c)) {
                    append((singleElement ? "NA_complex_" : "NA"));
                } else {
                    append(RContext.getRRuntimeASTAccess().encodeComplex(c));
                }
            } else if (element instanceof RRaw) {
                append(Utils.stringFormat("0x%02x", ((RRaw) element).getValue()));
            } else {
                throw RInternalError.shouldNotReachHere("unexpected vector element type during deparsing: " + (element == null ? "null" : element.getClass().getSimpleName()));
            }
            return this;
        }

        /**
         * Handles {@link RList}, (@link RExpression}. Method name same as GnuR.
         */
        private DeparseVisitor appendListContents(RAbstractVector v) {
            int n = v.getLength();
            boolean lbreak = false;
            RStringVector names = v.getNames();
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    append(", ");
                }
                lbreak = listLinebreak(lbreak);
                if (names != null && niceNames()) {
                    append(quotify(names.getDataAt(i), '\"'));
                    append(" = ");
                }
                appendValue(v.getDataAtAsObject(i));
            }
            if (lbreak) {
                indent--;
            }
            return this;
        }

        private static boolean hasAttributes(Object obj) {
            // TODO check (and ignore) function source attribute
            if (obj instanceof RAttributable) {
                DynamicObject attrs = ((RAttributable) obj).getAttributes();
                return attrs != null && !attrs.isEmpty();
            } else {
                return false;
            }
        }

        public static boolean usableNiceNames(DynamicObject attr) {
            if (attr != null) {
                Shape shape = attr.getShape();
                List<Property> properties = shape.getPropertyList();
                Object vec = RRuntime.asAbstractVector(attr);
                for (int i = 0; i < properties.size(); i++) {
                    Property p = properties.get(i);
                    String name = (String) p.getKey();
                    if (RRuntime.isNA(name)) {
                        return false;
                    }
                    if (vec instanceof RAbstractAtomicVector) {
                        if (name.equalsIgnoreCase("recursive") || name.equalsIgnoreCase("use.names")) {
                            return false;
                        }
                    }
                }
                return true;
            }
            return false;
        }

        private C withAttributes(Object obj) {
            boolean showAttri = true;
            if (hasAttributes(obj)) {
                DynamicObject attrs = ((RAttributable) obj).getAttributes();
                assert attrs != null;
                Iterator<RAttributesLayout.RAttribute> itera = RAttributesLayout.asIterable(attrs).iterator();
                while (itera.hasNext()) {
                    RAttributesLayout.RAttribute attr = itera.next();
                    String attrName = attr.getName();
                    if (attrName == null || RRuntime.isNA(attrName) || attrName.equals(RRuntime.NAMES_ATTR_KEY)) {
                        showAttri = false;
                    } else if (showAttributes() && !attrName.equals(RRuntime.R_SRCREF)) {
                        showAttri = true;
                    }
                }
                if (!(usableNiceNames(attrs))) {
                    if (!(showAttributes())) {
                        return () -> {
                        };
                    }
                } else if (showAttributes() && showAttri) {
                    append("structure(");
                    return () -> {
                        Iterator<RAttributesLayout.RAttribute> iter = RAttributesLayout.asIterable(attrs).iterator();
                        while (iter.hasNext()) {
                            RAttributesLayout.RAttribute attr = iter.next();
                            String attrName = attr.getName();
                            if (attrName.equals(RRuntime.NAMES_ATTR_KEY) && niceNames()) {
                                continue;
                            }
                            append(", ");
                            String dotName = null;
                            switch (attrName) {
                                case "dimnames":
                                    dotName = ".Dimnames";
                                    break;
                                case "dim":
                                    dotName = ".Dim";
                                    break;
                                case "names":
                                    dotName = ".Names";
                                    break;
                                case "tsp":
                                    dotName = ".Tsp";
                                    break;
                                case "levels":
                                    dotName = ".Label";
                                    break;

                                default: {
                                    if (attrName.contains(" ")) {
                                        append('"');
                                        append(attrName);
                                        append('"');
                                    } else {
                                        append(attrName);
                                    }
                                }
                            }
                            if (dotName != null) {
                                append(dotName);
                            }
                            append(" = ");
                            appendValue(attr.getValue());
                        }
                        append(')');
                    };
                } else {
                    return () -> {
                    };
                }
            }
            return () -> {
            };
        }
    }

    @TruffleBoundary
    public static String deparseSyntaxElement(RSyntaxElement element) {
        return new DeparseVisitor(false, RDeparse.MAX_CUTOFF, true, KEEPINTEGER, -1).append(element).getContents();
    }

    @TruffleBoundary
    public static String deparse(Object value) {
        return new DeparseVisitor(false, RDeparse.MAX_CUTOFF, true, KEEPINTEGER, -1).appendValue(value).getContents();
    }

    @TruffleBoundary
    public static String deparse(Object expr, int cutoff, boolean backtick, int opts, int nlines) {
        return new DeparseVisitor(false, cutoff, backtick, opts, nlines).appendValue(expr).getContents();
    }

    @TruffleBoundary
    public static String deparse(Object expr, int cutoff, boolean backtick, int opts, int nlines, int debugCutoff) {
        return new DeparseVisitor(false, cutoff, backtick, opts, nlines, debugCutoff).appendValue(expr).getContents();
    }

    /**
     * Ensure that {@code node} has a {@link SourceSection} by deparsing if necessary.
     */
    @TruffleBoundary
    public static void ensureSourceSection(RSyntaxNode node) {
        SourceSection ss = node.getLazySourceSection();
        if (ss == RSyntaxNode.LAZY_DEPARSE) {
            RSyntaxElement nodeToFixup = node;
            // try to generate the source from the root node and hopefully it includes this node
            new DeparseVisitor(true, RDeparse.MAX_CUTOFF, false, -1, 0).append(nodeToFixup).fixupSources();

            // if not, we have to deparse the node in isolation
            if (node.getLazySourceSection() == RSyntaxNode.LAZY_DEPARSE) {
                new DeparseVisitor(true, RDeparse.MAX_CUTOFF, false, -1, 0).append(node).fixupSources();
            }
            assert node.getLazySourceSection() != RSyntaxNode.LAZY_DEPARSE;
        }
    }

    private static String quotify(String name, char qc) {
        if (isValidName(name) || name.length() == 0) {
            return name;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(qc);
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                if (ch == '\\' || ch == '`') {
                    sb.append(ch);
                }
                sb.append(ch);
            }
            sb.append(qc);
            return sb.toString();
        }
    }

    private static final HashSet<String> keywords = new HashSet<>(Arrays.asList("NULL", "NA", "TRUE", "FALSE", "Inf", "NaN", "NA_integer_", "NA_real_", "NA_character_", "NA_complex_", "function",
                    "while", "repeat", "for", "if", "in", "else", "next", "break", "..."));

    public static boolean isValidName(String name) {
        char ch = safeCharAt(name, 0);
        if (ch != '.' && !Character.isLetter(ch)) {
            return false;
        }
        if (ch == '.' && Character.isDigit(safeCharAt(name, 1))) {
            return false;
        }
        int i = 1;
        ch = safeCharAt(name, i);
        while ((ch != '?' && Character.isAlphabetic(ch)) || Character.isDigit(ch) || ch == '.' || ch == '_') {
            i++;
            ch = safeCharAt(name, i);
        }
        if (ch != 0) {
            return false;
        }
        if (name.equals("...")) {
            return true;
        }
        return !keywords.contains(name);
    }

    private static char safeCharAt(String s, int i) {
        if (i < s.length()) {
            return s.charAt(i);
        } else {
            return 0;
        }
    }

    @SuppressWarnings("serial")
    private static class AbortDeparsingException extends RuntimeException {
    }

    @SuppressWarnings("serial")
    private static final class MaxLinesReachedException extends AbortDeparsingException {
    }

    @SuppressWarnings("serial")
    private static final class MaxLengthReachedException extends AbortDeparsingException {
    }
}
