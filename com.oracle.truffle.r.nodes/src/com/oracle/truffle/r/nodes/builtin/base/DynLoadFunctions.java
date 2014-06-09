/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.nodes.builtin.RBuiltinKind.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.r.nodes.builtin.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.ffi.*;
import com.oracle.truffle.r.runtime.ffi.DLL.*;

public class DynLoadFunctions {

    private static final String DLLINFO_CLASS = "DLLInfo";
    private static final String DLLINFOLIST_CLASS = "DLLInfoList";

    @RBuiltin(name = "dyn.load", kind = INTERNAL)
    public abstract static class DynLoad extends RInvisibleBuiltinNode {
        @Specialization
        public RList doDynLoad(String lib, byte local, byte now, @SuppressWarnings("unused") String unused) {
            controlVisibility();
            try {
                DLLInfo info = DLL.load(lib, asBoolean(local), asBoolean(now));
                RList result = createDLLInfoList(info.toRValues());
                return result;
            } catch (DLLException ex) {
                CompilerDirectives.transferToInterpreter();
                throw RError.getGenericError(getEncapsulatingSourceSection(), ex.getMessage());
            }
        }

        private static boolean asBoolean(byte b) {
            return b == RRuntime.LOGICAL_TRUE ? true : false;
        }
    }

    @RBuiltin(name = "dyn.unload", kind = INTERNAL)
    public abstract static class DynUnload extends RInvisibleBuiltinNode {
        @Specialization
        public RNull doDynunload(String lib) {
            controlVisibility();
            try {
                DLL.unload(lib);
            } catch (DLLException ex) {
                CompilerDirectives.transferToInterpreter();
                throw RError.getGenericError(getEncapsulatingSourceSection(), ex.getMessage());
            }
            return RNull.instance;
        }

    }

    @RBuiltin(name = "getLoadedDLLs", kind = INTERNAL)
    public abstract static class GetLoadedDLLs extends RBuiltinNode {
        @Specialization
        public RList doGetLoadedDLLs() {
            controlVisibility();
            Object[][] dlls = DLL.getLoadedDLLs();
            String[] names = new String[dlls.length];
            Object[] data = new Object[dlls.length];
            for (int i = 0; i < dlls.length; i++) {
                Object[] dllRawInfo = dlls[i];
                RList dllInfo = createDLLInfoList(dllRawInfo);
                // name field is used a list element name
                names[i] = (String) dllRawInfo[0];
                data[i] = dllInfo;
            }
            RList result = RDataFactory.createList(data, RDataFactory.createStringVector(names, RDataFactory.COMPLETE_VECTOR));
            RVector.setClassAttr(result, RDataFactory.createStringVectorFromScalar(DLLINFOLIST_CLASS), null);
            return result;
        }
    }

    private static RList createDLLInfoList(Object[] data) {
        RList dllInfo = RDataFactory.createList(data, RDataFactory.createStringVector(DLLInfo.NAMES, RDataFactory.COMPLETE_VECTOR));
        RVector.setClassAttr(dllInfo, RDataFactory.createStringVectorFromScalar(DLLINFO_CLASS), null);
        return dllInfo;
    }

    @RBuiltin(name = "is.loaded", kind = INTERNAL)
    public abstract static class IsLoaded extends RBuiltinNode {
        @SuppressWarnings("unused")
        @Specialization
        public byte isLoaded(String symbol, String packageName, String type) {
            controlVisibility();
            // TODO Pay attention to packageName
            boolean found = DLL.findSymbolInfo(symbol, null) != null;
            return RRuntime.asLogical(found);
        }
    }

}
