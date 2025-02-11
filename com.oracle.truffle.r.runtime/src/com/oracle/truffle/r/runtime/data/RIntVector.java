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
package com.oracle.truffle.r.runtime.data;

import java.util.Arrays;

import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.RType;
import com.oracle.truffle.r.runtime.data.NativeDataAccess.NativeMirror;
import com.oracle.truffle.r.runtime.data.closures.RClosures;
import com.oracle.truffle.r.runtime.data.model.RAbstractContainer;
import com.oracle.truffle.r.runtime.data.model.RAbstractIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.data.nodes.FastPathVectorAccess.FastPathFromIntAccess;
import com.oracle.truffle.r.runtime.data.nodes.SlowPathVectorAccess.SlowPathFromIntAccess;
import com.oracle.truffle.r.runtime.data.nodes.VectorAccess;
import com.oracle.truffle.r.runtime.ops.na.NACheck;
import com.oracle.truffle.r.runtime.data.RSharingAttributeStorage.Shareable;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector.RMaterializedVector;

public final class RIntVector extends RAbstractIntVector implements RMaterializedVector, Shareable {

    private int[] data;

    RIntVector(int[] data, boolean complete) {
        super(complete);
        this.data = data;
        assert RAbstractVector.verifyVector(this);
    }

    RIntVector(int[] data, boolean complete, int[] dims, RStringVector names, RList dimNames) {
        this(data, complete);
        initDimsNamesDimNames(dims, names, dimNames);
    }

    private RIntVector() {
        super(false);
    }

    static RIntVector fromNative(long address, int length) {
        RIntVector result = new RIntVector();
        NativeDataAccess.toNative(result);
        NativeDataAccess.setNativeContents(result, address, length);
        return result;
    }

    @Override
    public RAbstractVector castSafe(RType type, ConditionProfile isNAProfile, boolean keepAttributes) {
        switch (type) {
            case Integer:
                return this;
            case Double:
                return RClosures.createToDoubleVector(this, keepAttributes);
            case Complex:
                return RClosures.createToComplexVector(this, keepAttributes);
            case Character:
                return RClosures.createToStringVector(this, keepAttributes);
            case List:
                return RClosures.createToListVector(this, keepAttributes);
            default:
                return null;
        }
    }

    @Override
    public int[] getInternalStore() {
        return data;
    }

    @Override
    public int getDataAt(int index) {
        return NativeDataAccess.getData(this, data, index);
    }

    @Override
    public int getDataAt(Object store, int index) {
        assert data == store;
        return NativeDataAccess.getData(this, (int[]) store, index);
    }

    @Override
    public void setDataAt(Object store, int index, int value) {
        assert data == store;
        NativeDataAccess.setData(this, (int[]) store, index, value);
    }

    public RIntVector copyResetData(int[] newData) {
        boolean isComplete = true;
        for (int i = 0; i < newData.length; i++) {
            if (RRuntime.isNA(newData[i])) {
                isComplete = false;
                break;
            }
        }
        RIntVector result = new RIntVector(newData, isComplete);
        setAttributes(result);
        return result;
    }

    @Override
    public int getLength() {
        return NativeDataAccess.getDataLength(this, data);
    }

    @Override
    public void setLength(int l) {
        try {
            NativeDataAccess.setDataLength(this, data, l);
        } finally {
            data = null;
            complete = false;
        }
    }

    @Override
    public int getTrueLength() {
        return NativeDataAccess.getTrueDataLength(this);
    }

    @Override
    public void setTrueLength(int l) {
        NativeDataAccess.setTrueDataLength(this, l);
    }

    @Override
    public int[] getDataCopy() {
        if (data != null) {
            return Arrays.copyOf(data, data.length);
        } else {
            return NativeDataAccess.copyIntNativeData(getNativeMirror());
        }
    }

    @Override
    public int[] getInternalManagedData() {
        return data;
    }

    @Override
    public int[] getReadonlyData() {
        if (data != null) {
            return data;
        } else {
            return NativeDataAccess.copyIntNativeData(getNativeMirror());
        }
    }

    private RIntVector updateDataAt(int index, int value, NACheck valueNACheck) {
        assert !this.isShared();

        NativeDataAccess.setData(this, data, index, value);
        if (valueNACheck.check(value)) {
            setComplete(false);
        }
        assert !isComplete() || !RRuntime.isNA(value);
        return this;
    }

    @Override
    public RIntVector updateDataAtAsObject(int i, Object o, NACheck naCheck) {
        return updateDataAt(i, (Integer) o, naCheck);
    }

    @Override
    public RIntVector materialize() {
        return this;
    }

    @Override
    public void transferElementSameType(int toIndex, RAbstractVector fromVector, int fromIndex) {
        NativeDataAccess.setData(this, data, toIndex, ((RAbstractIntVector) fromVector).getDataAt(fromIndex));
    }

    @Override
    public Object getDataAtAsObject(int index) {
        return getDataAt(index);
    }

    @Override
    public void setElement(int index, Object value) {
        NativeDataAccess.setData(this, data, index, (int) value);
    }

    public long allocateNativeContents() {
        try {
            return NativeDataAccess.allocateNativeContents(this, data, getLength());
        } finally {
            data = null;
            complete = false;
        }
    }

    private static final class FastPathAccess extends FastPathFromIntAccess {

        FastPathAccess(RAbstractContainer value) {
            super(value);
        }

        @Override
        public int getIntImpl(AccessIterator accessIter, int index) {
            return hasStore ? ((int[]) accessIter.getStore())[index] : NativeDataAccess.getIntNativeMirrorData((NativeMirror) accessIter.getStore(), index);
        }

        @Override
        protected void setIntImpl(AccessIterator accessIter, int index, int value) {
            if (hasStore) {
                ((int[]) accessIter.getStore())[index] = value;
            } else {
                NativeDataAccess.setNativeMirrorIntData((NativeMirror) accessIter.getStore(), index, value);
            }
        }
    }

    @Override
    public VectorAccess access() {
        return new FastPathAccess(this);
    }

    private static final SlowPathFromIntAccess SLOW_PATH_ACCESS = new SlowPathFromIntAccess() {
        @Override
        public int getIntImpl(AccessIterator accessIter, int index) {
            RIntVector vector = (RIntVector) accessIter.getStore();
            return NativeDataAccess.getData(vector, vector.data, index);
        }

        @Override
        protected void setIntImpl(AccessIterator accessIter, int index, int value) {
            RIntVector vector = (RIntVector) accessIter.getStore();
            NativeDataAccess.setData(vector, vector.data, index, value);
        }
    };

    @Override
    public VectorAccess slowPathAccess() {
        return SLOW_PATH_ACCESS;
    }
}
