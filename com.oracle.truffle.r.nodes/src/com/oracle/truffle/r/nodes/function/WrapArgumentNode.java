/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.nodes.function;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;
import com.oracle.truffle.api.utilities.*;
import com.oracle.truffle.r.nodes.*;
import com.oracle.truffle.r.nodes.access.*;
import com.oracle.truffle.r.runtime.*;
import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.env.*;

/**
 * A {@link WrapArgumentNode} is used to wrap all arguments to function calls to implement correct
 * copy semantics for vectors. As such it is not really a syntax node, but it is created during
 * parsing and therefore forms part of the syntactic backbone.
 *
 */
public final class WrapArgumentNode extends RNode implements RSyntaxNode {

    @Child private RNode operand;

    private final BranchProfile everSeenVector;
    private final BranchProfile everSeenDataFrame;
    private final BranchProfile everSeenFactor;

    private final BranchProfile everSeenShared;
    private final BranchProfile everSeenTemporary;
    private final BranchProfile everSeenNonTemporary;

    private final boolean modeChange;

    private WrapArgumentNode(RNode operand, boolean modeChange) {
        this.operand = operand;
        this.modeChange = modeChange;
        if (modeChange) {
            everSeenVector = BranchProfile.create();
            everSeenDataFrame = BranchProfile.create();
            everSeenFactor = BranchProfile.create();
            everSeenShared = BranchProfile.create();
            everSeenTemporary = BranchProfile.create();
            everSeenNonTemporary = BranchProfile.create();
        } else {
            everSeenVector = null;
            everSeenDataFrame = null;
            everSeenFactor = null;
            everSeenShared = null;
            everSeenTemporary = null;
            everSeenNonTemporary = null;
        }
    }

    @Override
    public NodeCost getCost() {
        return modeChange ? NodeCost.MONOMORPHIC : NodeCost.NONE;
    }

    public RNode getOperand() {
        return operand;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object result = operand.execute(frame);
        if (modeChange) {
            RVector vector = null;
            if (result instanceof RVector) {
                everSeenVector.enter();
                vector = (RVector) result;
            } else if (result instanceof RDataFrame) {
                everSeenDataFrame.enter();
                vector = ((RDataFrame) result).getVector();
            } else if (result instanceof RFactor) {
                everSeenFactor.enter();
                vector = ((RFactor) result).getVector();
            }

            if (vector != null) {
                // mark vector as wrapped only if changing its mode to shared; otherwise make sure
                // that it can be seen as "truly" shared by marking vector unwrapped
                if (vector.isShared()) {
                    everSeenShared.enter();
                } else if (vector.isTemporary()) {
                    everSeenTemporary.enter();
                    vector.markNonTemporary();
                } else {
                    everSeenNonTemporary.enter();
                    vector.makeShared();
                }
            }
        }
        return result;
    }

    @Override
    public byte executeByte(VirtualFrame frame) throws UnexpectedResultException {
        return operand.executeByte(frame);
    }

    @Override
    public int executeInteger(VirtualFrame frame) throws UnexpectedResultException {
        return operand.executeInteger(frame);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return operand.executeDouble(frame);
    }

    @Override
    public RMissing executeMissing(VirtualFrame frame) throws UnexpectedResultException {
        return operand.executeMissing(frame);
    }

    @Override
    public RNull executeNull(VirtualFrame frame) throws UnexpectedResultException {
        return operand.executeNull(frame);
    }

    public static RNode create(RNode operand, boolean modeChange) {
        if (operand instanceof WrapArgumentNode || operand instanceof ConstantNode) {
            return operand;
        } else {
            WrapArgumentNode wan = new WrapArgumentNode(operand, modeChange);
            wan.assignSourceSection(operand.getSourceSection());
            return wan;
        }
    }

    @Override
    public boolean isBackbone() {
        return true;
    }

    @Override
    public void deparse(RDeparse.State state) {
        RSyntaxNode.cast(getOperand()).deparse(state);
    }

    @Override
    public void serialize(RSerialize.State state) {
        RSyntaxNode.cast(getOperand()).serialize(state);
    }

    @Override
    public RSyntaxNode substitute(REnvironment env) {
        RNode sub = RSyntaxNode.cast(getOperand()).substitute(env).asRNode();
        if (sub instanceof RASTUtils.DotsNode) {
            return (RASTUtils.DotsNode) sub;
        } else {
            return RSyntaxNode.cast(create(sub, modeChange));
        }
    }
}
