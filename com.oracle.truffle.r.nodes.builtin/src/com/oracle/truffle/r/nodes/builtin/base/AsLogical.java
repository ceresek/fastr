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
package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.emptyLogicalVector;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.missingValue;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.nullValue;
import static com.oracle.truffle.r.runtime.RDispatch.INTERNAL_GENERIC;
import static com.oracle.truffle.r.runtime.builtins.RBehavior.PURE;
import static com.oracle.truffle.r.runtime.builtins.RBuiltinKind.PRIMITIVE;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.nodes.builtin.RBuiltinNode;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.builtins.RBuiltin;
import com.oracle.truffle.r.runtime.data.RArgsValuesAndNames;
import com.oracle.truffle.r.runtime.data.RLogicalVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractLogicalVector;
import com.oracle.truffle.r.runtime.data.nodes.VectorReuse;

@RBuiltin(name = "as.logical", kind = PRIMITIVE, dispatch = INTERNAL_GENERIC, parameterNames = {"x", "..."}, behavior = PURE)
public abstract class AsLogical extends RBuiltinNode.Arg2 {

    static {
        Casts casts = new Casts(AsLogical.class);
        casts.arg("x").defaultWarningContext(RError.NO_CALLER).returnIf(missingValue().or(nullValue()), emptyLogicalVector()).asLogicalVector();
    }

    @Specialization(guards = "reuseTemporaryNode.supports(v)", limit = "getVectorAccessCacheSize()")
    protected RAbstractLogicalVector asLogical(RAbstractLogicalVector v, @SuppressWarnings("unused") RArgsValuesAndNames dotdotdot,
                    @Cached("createTemporary(v)") VectorReuse reuseTemporaryNode,
                    @Cached("createBinaryProfile()") ConditionProfile noAttributes) {
        if (noAttributes.profile(v.getAttributes() == null)) {
            return v;
        } else {
            RLogicalVector res = (RLogicalVector) reuseTemporaryNode.getMaterializedResult(v);
            res.resetAllAttributes(true);
            return res;
        }
    }

    @Specialization(replaces = "asLogical")
    protected RAbstractLogicalVector asLogicalGeneric(RAbstractLogicalVector v, RArgsValuesAndNames dotdotdot,
                    @Cached("createTemporaryGeneric()") VectorReuse reuseTemporaryNode,
                    @Cached("createBinaryProfile()") ConditionProfile noAttributes) {
        return asLogical(v, dotdotdot, reuseTemporaryNode, noAttributes);
    }
}
