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
package com.oracle.truffle.r.nodes.access;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.r.nodes.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;

public class AccessArgumentNode extends RNode {

    private final int index;
    @Child private RNode defaultValue;
    private final boolean defaultValueIsMissing;
    @CompilationFinal private boolean neverSeenMissing = true;

    public AccessArgumentNode(int index, RNode defaultValue) {
        this.index = index;
        this.defaultValue = adoptChild(defaultValue);
        this.defaultValueIsMissing = (defaultValue instanceof ConstantNode && ((ConstantNode) defaultValue).getValue() == RMissing.instance);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        RArguments arguments = frame.getArguments(RArguments.class);
        if (index < arguments.getLength()) {
            Object argument = arguments.getArgument(index);
            if (defaultValueIsMissing || argument != RMissing.instance) {
                return argument;
            } else if (neverSeenMissing) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                neverSeenMissing = false;
            }
        }
        return defaultValue.execute(frame);
    }
}
