/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.nodes.builtin;

import com.oracle.truffle.api.dsl.*;
import com.oracle.truffle.r.nodes.builtin.RBuiltin.*;

public class RBuiltinFactory {

    private final NodeFactory<RBuiltinNode> factory;
    private String[] builtinNames;
    private LastParameterKind lastParameterKind;
    private Object[] constantArguments;

    public RBuiltinFactory(String[] names, LastParameterKind lastParameterKind, NodeFactory<RBuiltinNode> factory, Object[] constantArguments) {
        this.lastParameterKind = lastParameterKind;
        this.builtinNames = names;
        this.factory = factory;
        this.constantArguments = constantArguments;
    }

    void setBuiltinNames(String[] builtinNames) {
        this.builtinNames = builtinNames;
    }

    void setConstantArguments(Object[] constantArguments) {
        this.constantArguments = constantArguments;
    }

    void setLastParameterKind(LastParameterKind lastParameterKind) {
        this.lastParameterKind = lastParameterKind;
    }

    public Object[] getConstantArguments() {
        return constantArguments;
    }

    public LastParameterKind getLastParameterKind() {
        return lastParameterKind;
    }

    public String[] getBuiltinNames() {
        return builtinNames;
    }

    public NodeFactory<RBuiltinNode> getFactory() {
        return factory;
    }

}
