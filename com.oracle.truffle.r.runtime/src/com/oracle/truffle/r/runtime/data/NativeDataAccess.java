/*
 * Copyright (c) 2017, 2019, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.truffle.r.runtime.RLogger.LOGGER_RFFI;

import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.RLogger;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.Utils;
import com.oracle.truffle.r.runtime.context.FastROptions;
import com.oracle.truffle.r.runtime.context.RContext;
import com.oracle.truffle.r.runtime.context.TruffleRLanguage;
import com.oracle.truffle.r.runtime.data.NativeDataAccessFactory.ToNativeNodeGen;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector.RMaterializedVector;
import com.oracle.truffle.r.runtime.data.nodes.ShareObjectNode;
import com.oracle.truffle.r.runtime.ffi.FFIMaterializeNode;
import com.oracle.truffle.r.runtime.nodes.RSyntaxNode;

import sun.misc.Unsafe;

abstract class InteropRootNode extends RootNode {
    InteropRootNode() {
        super(RContext.getInstance().getLanguage());
    }

    @Override
    public final SourceSection getSourceSection() {
        return RSyntaxNode.INTERNAL;
    }
}

class UnsafeAdapter {
    public static final Unsafe UNSAFE = initUnsafe();

    private static Unsafe initUnsafe() {
        try {
            return Unsafe.getUnsafe();
        } catch (SecurityException se) {
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(Unsafe.class);
            } catch (Exception e) {
                throw new RuntimeException("exception while trying to get Unsafe", e);
            }
        }
    }
}

/**
 * Provides API to work with objects returned by {@link RBaseObject#getNativeMirror()}. The native
 * mirror represents what on the native side is SEXP, but not directly the raw data of the vector.
 * Use {@link #toNative(RBaseObject)} to assign a native mirror object to the given vector. The raw
 * data in native memory for a vector that already has a native mirror object assigned can be
 * allocated using e.g. {@link #allocateNativeContents(RIntVector, int[], int)} .
 *
 * There is a registry of weak references to all native mirrors ever assigned to some vector object.
 * We use the finalizer to free the native memory (if allocated).
 */
public final class NativeDataAccess {
    private NativeDataAccess() {
        // no instances
    }

    public interface CustomNativeMirror {
        long getCustomMirrorAddress();
    }

    public interface Releasable {
        void release();
    }

    /**
     * @see RLogger#LOGGER_RFFI
     */
    private static final TruffleLogger LOGGER = RLogger.getLogger(LOGGER_RFFI);

    private static final boolean TRACE_MIRROR_ALLOCATION_SITES = false;

    private static final AtomicLong emptyDataAddress;
    static {
        emptyDataAddress = new AtomicLong(0);
    }

    private static final ReferenceQueue<Object> nativeRefQueue = new ReferenceQueue<>();

    private static final AtomicReference<Thread> nativeRefQueueThread = new AtomicReference<>(null);

    private static long getEmptyDataAddress() {
        long addr = emptyDataAddress.get();
        if (addr == 0L) {
            addr = allocateNativeMemory(8);
            if (!emptyDataAddress.compareAndSet(0L, addr)) {
                freeNativeMemory(addr);
            }
        }
        return emptyDataAddress.get();
    }

    private static void initNativeRefQueueThread() {
        Thread thread = nativeRefQueueThread.get();
        if (thread == null) {
            createNativeRefQueueThread();
        }
    }

    @TruffleBoundary
    private static void createNativeRefQueueThread() {
        Thread thread;
        thread = new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    while (true) {
                                        Reference<?> ref = nativeRefQueue.remove();
                                        if (ref instanceof Releasable) {
                                            ((Releasable) ref).release();
                                        }
                                    }
                                } catch (InterruptedException ex) {
                                }
                            }
                        },
                        "Native-Reference-Queue-Worker");
        if (nativeRefQueueThread.compareAndSet(null, thread)) {
            thread.setDaemon(true);
            thread.start();
        }
    }

    public static ReferenceQueue<Object> nativeReferenceQueue() {
        initNativeRefQueueThread();
        return nativeRefQueue;
    }

    @ExportLibrary(InteropLibrary.class)
    public static final class NativeMirror extends WeakReference<RBaseObject> implements Releasable, TruffleObject {
        /**
         * ID of the mirror, this will be used as the value for SEXP. When native up-calls to Java,
         * we get this value and find the corresponding object for it.
         */
        private long id;
        /**
         * Address of the start of the native memory array. Zero if not allocated yet.
         */
        private long dataAddress;
        /**
         * Length of the native data array. E.g. for CHARSXP this is not just the length of the Java
         * String.
         */
        private long length;

        /**
         * The truly allocated length of the native data array. Expected to be either kept at zero
         * if not used, or to be a value &gt;= length.<br>
         *
         * Expected usage in native code:
         *
         * <pre>
         * SEXP vector = allocVector(STRSXP, 1024);
         * SETLENGTH(newnames, 10);
         * SET_TRUELENGTH(newnames, 1024);
         * </pre>
         */
        private long truelength;

        /**
         * It maintains the <code>1-?</code> relationship between this object and its native wrapper
         * through which the native code accesses it. For instance, Sulong implements the "pointer"
         * equality of two objects that are not pointers (i.e. <code>IS_POINTER</code> returns
         * <code>false</code>) as the reference equality of the objects. It follows that the pointer
         * comparison would fail if the same <code>RBaseObject</code> instance were wrapped by two
         * different native wrappers.
         */
        private NativeWrapperReference nativeWrapperRef;

        /**
         * Indicates that the address points to memory not allocated by FastR.
         */
        private boolean external;

        /**
         * Creates a new mirror with a specified native address as both ID and address. The buffer
         * will be freed when the Java object is collected.
         */
        NativeMirror(RBaseObject ownerVec, long address) {
            // address == 0 means no nativeMirrors registration and no release() call
            super(ownerVec, nativeReferenceQueue());
            if (address != 0) {
                this.id = address;
                setDataAddress(address);
                nativeMirrors.put(id, this);
            }
        }

        private void initMirror() {
            assert id == 0;
            this.id = counter.addAndGet(2);
            nativeMirrors.put(id, this);
        }

        private void initMirror(long address) {
            assert id == 0;
            assert address != 0;
            this.id = address;
            setDataAddress(address);
            nativeMirrors.put(id, this);
        }

        @ExportMessage
        public boolean isPointer() {
            return id != 0;
        }

        @ExportMessage
        public long asPointer(@Cached("createBinaryProfile()") ConditionProfile isPointer) throws UnsupportedMessageException {
            if (isPointer.profile(isPointer())) {
                return id;
            }
            throw UnsupportedMessageException.create();
        }

        @ExportMessage
        public void toNative(@Cached() ToNativeNode toNative) {
            toNative.execute(this);
        }

        @TruffleBoundary
        long setDataAddress(long dataAddress) {
            this.dataAddress = dataAddress;
            if (dataAddressToNativeMirrors != null) {
                dataAddressToNativeMirrors.put(dataAddress, this);
            }
            return dataAddress;
        }

        @TruffleBoundary
        void allocateNative(Object source, int len, int trueLen, int elementBase, int elementSize) {
            assert dataAddress == 0;
            if (len != 0) {
                long bytesCount = trueLen * (long) elementSize;
                setDataAddress(allocateNativeMemory(bytesCount));
                UnsafeAdapter.UNSAFE.copyMemory(source, elementBase, null, dataAddress, bytesCount);
            } else {
                setDataAddress(getEmptyDataAddress());
            }
            this.length = len;

            // ensure that marker address is not used
            assert this.length == 0 || dataAddress != getEmptyDataAddress();
        }

        @TruffleBoundary
        void allocateNativeString(byte[] bytes) {
            assert dataAddress == 0;
            setDataAddress(allocateNativeMemory(bytes.length + 1));
            UnsafeAdapter.UNSAFE.copyMemory(bytes, Unsafe.ARRAY_BYTE_BASE_OFFSET, null, dataAddress, bytes.length);
            UnsafeAdapter.UNSAFE.putByte(dataAddress + bytes.length, (byte) 0); // C strings
                                                                                // terminator
            this.length = bytes.length + 1;

            // ensure that marker address is not used
            assert this.length == 0 || dataAddress != getEmptyDataAddress();
        }

        @TruffleBoundary
        void allocateNative(CharSXPWrapper[] wrappers) {
            if (wrappers.length == 0) {
                setDataAddress(getEmptyDataAddress());
            } else {
                long addr = setDataAddress(allocateNativeMemory(wrappers.length * (long) Long.BYTES));
                for (int i = 0; i < wrappers.length; i++) {
                    UnsafeAdapter.UNSAFE.putLong(addr + (long) i * Long.BYTES, getPointer(wrappers[i]));
                }
            }
        }

        @TruffleBoundary
        void allocateNative(Object[] elements) {
            if (elements.length == 0) {
                setDataAddress(getEmptyDataAddress());
            } else {
                long addr = setDataAddress(allocateNativeMemory(elements.length * (long) Long.BYTES));
                for (int i = 0; i < elements.length; i++) {
                    Object element = elements[i];
                    Object materialized = FFIMaterializeNode.uncachedMaterialize(element);
                    if (element != materialized) {
                        elements[i] = ShareObjectNode.executeUncached(materialized);
                    }
                    if (element instanceof RBaseObject) {
                        UnsafeAdapter.UNSAFE.putLong(addr + (long) i * Long.BYTES, getPointer((RBaseObject) element));
                    } else {
                        throw RInternalError.shouldNotReachHere();
                    }
                }
            }
        }

        @Override
        public void release() {
            if (id != 0) {
                nativeMirrors.remove(id, this);
            }
            // We cannot use RFFILog here, as the gc thread may not have any Truffle context
            // attached to.

            // System.out.println(String.format("gc'ing %16x", id));
            // System.err.printf("gc'ing %16x (dataAddress=%16x)\n", id, dataAddress);
            if (dataAddress == getEmptyDataAddress()) {
                // System.err.printf("1. freeing data at %16x (id=%16x)\n", dataAddress, id);
                assert (setDataAddress(0xbadbad)) != 0;
            } else if (dataAddress != 0 && !external) {
                // System.err.printf("2. freeing data at %16x (id=%16x)\n", dataAddress, id);
                freeNativeMemory(dataAddress);
                if (dataAddressToNativeMirrors != null) {
                    dataAddressToNativeMirrors.remove(dataAddress);
                }
                assert (setDataAddress(0xbadbad)) != 0;
            }
            if (nativeMirrorInfo != null) {
                nativeMirrorInfo.remove(id); // Possible id(address)-clashing entries not handled
            }
        }

        @Override
        public String toString() {
            return "mirror: address=" + Long.toHexString(dataAddress) + ", id=" + Long.toHexString(id);
        }
    }

    // The counter is initialized to invalid address and incremented by 2 to always get invalid
    // address value
    private static final AtomicLong counter = new AtomicLong(0xdef000000000001L);
    private static final ConcurrentHashMap<Long, NativeMirror> nativeMirrors = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<Long, NativeMirror> dataAddressToNativeMirrors = System.getenv(FastROptions.NATIVE_DATA_INSPECTOR) != null ? new ConcurrentHashMap<>(512) : null;
    private static final ConcurrentHashMap<Long, RuntimeException> nativeMirrorInfo = TRACE_MIRROR_ALLOCATION_SITES ? new ConcurrentHashMap<>() : null;

    public static NativeMirror createNativeMirror(RBaseObject obj) {
        assert obj.getNativeMirror() == null;
        NativeMirror mirror = new NativeMirror(obj, 0);
        obj.setNativeMirror(mirror);
        return mirror;
    }

    /**
     * Assigns a native mirror object ID to the given RBaseObject object.
     */
    public static void toNative(RBaseObject obj) {
        NativeMirror mirror = obj.getNativeMirror();
        if (mirror == null) {
            createNativeMirror(obj);
            mirror = obj.getNativeMirror();
        }
        ToNativeNodeGen.getUncached().execute(mirror);
    }

    @ImportStatic(NativeDataAccess.class)
    @GenerateUncached
    abstract static class ToNativeNode extends Node {
        abstract void execute(NativeMirror mirror);

        @Specialization
        void toNative(NativeMirror mirror,
                        @Cached("createBinaryProfile()") ConditionProfile hasID,
                        @Cached("createBinaryProfile()") ConditionProfile isCustomNativeMirror,
                        @Cached("createBinaryProfile()") ConditionProfile isInNative,
                        @Cached BranchProfile refRegProfile,
                        @CachedContext(TruffleRLanguage.class) ContextReference<RContext> ctxRef) {
            if (hasID.profile(mirror.id == 0)) {
                RBaseObject obj = mirror.get();
                if (isCustomNativeMirror.profile(obj instanceof CustomNativeMirror)) {
                    mirror.initMirror(((CustomNativeMirror) obj).getCustomMirrorAddress());
                } else {
                    mirror.initMirror();
                }
                RContext rContext = ctxRef.get();
                if (isInNative.profile(rContext.getStateRFFI().getCallDepth() > 0)) {
                    rContext.getStateRFFI().registerReferenceUsedInNative(obj, refRegProfile);
                }
                logAndTrace(mirror.get(), mirror);
                assert mirror.id != 0;
            }
        }

    }

    private static long getPointer(RBaseObject obj) {
        toNative(obj);
        return obj.getNativeMirror().id;
    }

    @TruffleBoundary
    private static void logAndTrace(RBaseObject obj, NativeMirror mirror) {
        if (TRACE_MIRROR_ALLOCATION_SITES) {
            registerAllocationSite(obj, mirror);
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "NativeMirror: {0}->{1} ({2})", new Object[]{Long.toHexString(mirror.id), obj.getClass().getSimpleName(), Utils.getDebugInfo(obj)});
        }
    }

    @TruffleBoundary
    private static void registerAllocationSite(Object arg, NativeMirror mirror) {
        String argInfo;
        if (arg instanceof RMaterializedVector && ((RAbstractVector) arg).hasNativeMemoryData()) {
            // this must be vector created by fromNative factory method, it has data == null, but
            // does not have its address assigned yet
            argInfo = "[empty]";
        } else {
            argInfo = arg.toString();
        }
        nativeMirrorInfo.put(mirror.id, new RuntimeException(arg.getClass().getSimpleName() + " " + argInfo));
    }

    /**
     * For given native mirror ID returns the Java side object (vector). TruffleBoundary because it
     * calls into HashMap.
     */
    @TruffleBoundary
    public static Object lookup(long address) {
        NativeMirror nativeMirror = nativeMirrors.get(address);
        RBaseObject result = nativeMirror != null ? nativeMirror.get() : null;
        if (result == null) {
            CompilerDirectives.transferToInterpreter();
            throw reportDataAccessError(address);
        }
        return result;
    }

    private static RuntimeException reportDataAccessError(long address) {
        if (TRACE_MIRROR_ALLOCATION_SITES) {
            printDataAccessErrorLocation(address);
        }
        throw RInternalError.shouldNotReachHere("unknown native reference " + address + "L / 0x" + Long.toHexString(address) + " (current id count: " + Long.toHexString(counter.get()) + ")");
    }

    private static void printDataAccessErrorLocation(long address) {
        RuntimeException location = nativeMirrorInfo.get(address);
        if (location != null) {
            System.out.println("Location at which the native mirror was allocated:");
            location.printStackTrace();
        } else {
            System.out.println("Location at which the native mirror was allocated was not recorded.");
        }
    }

    // methods operating on the native mirror object directly:

    public static int getIntNativeMirrorData(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        return UnsafeAdapter.UNSAFE.getInt(address + (long) index * Unsafe.ARRAY_INT_INDEX_SCALE);
    }

    public static double getDoubleNativeMirrorData(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        return UnsafeAdapter.UNSAFE.getDouble(address + (long) index * Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
    }

    public static byte getLogicalNativeMirrorData(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        return RRuntime.int2logical(UnsafeAdapter.UNSAFE.getInt(address + (long) index * Unsafe.ARRAY_INT_INDEX_SCALE));
    }

    public static byte getRawNativeMirrorData(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        return UnsafeAdapter.UNSAFE.getByte(address + (long) index * Unsafe.ARRAY_BYTE_INDEX_SCALE);
    }

    public static RComplex getComplexNativeMirrorData(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        return RComplex.valueOf(UnsafeAdapter.UNSAFE.getDouble(address + index * 2L * Unsafe.ARRAY_DOUBLE_INDEX_SCALE),
                        UnsafeAdapter.UNSAFE.getDouble(address + (index * 2L + 1L) * Unsafe.ARRAY_DOUBLE_INDEX_SCALE));
    }

    public static double getComplexNativeMirrorDataR(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        return UnsafeAdapter.UNSAFE.getDouble(address + index * 2L * Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
    }

    public static double getComplexNativeMirrorDataI(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        return UnsafeAdapter.UNSAFE.getDouble(address + (index * 2L + 1L) * Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
    }

    public static CharSXPWrapper getStringNativeMirrorData(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        long elemAddr = UnsafeAdapter.UNSAFE.getLong(address + (long) index * Long.BYTES);
        assert elemAddr != 0L;
        return (CharSXPWrapper) NativeDataAccess.lookup(elemAddr);
    }

    public static Object getListElementNativeMirrorData(NativeMirror nativeMirror, int index) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        long elemAddr = UnsafeAdapter.UNSAFE.getLong(address + (long) index * Long.BYTES);
        assert elemAddr != 0L;
        return NativeDataAccess.lookup(elemAddr);
    }

    public static void setNativeMirrorDoubleData(NativeMirror nativeMirror, int index, double value) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        UnsafeAdapter.UNSAFE.putDouble(address + (long) index * Unsafe.ARRAY_DOUBLE_INDEX_SCALE, value);
    }

    public static void setNativeMirrorComplexRealPartData(NativeMirror nativeMirror, int index, double value) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        UnsafeAdapter.UNSAFE.putDouble(address + 2L * index * Unsafe.ARRAY_DOUBLE_INDEX_SCALE, value);
    }

    public static void setNativeMirrorComplexImaginaryPartData(NativeMirror nativeMirror, int index, double value) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        UnsafeAdapter.UNSAFE.putDouble(address + (2L * index + 1L) * Unsafe.ARRAY_DOUBLE_INDEX_SCALE, value);
    }

    public static void setNativeMirrorRawData(NativeMirror nativeMirror, int index, byte value) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        UnsafeAdapter.UNSAFE.putByte(address + (long) index * Unsafe.ARRAY_BYTE_INDEX_SCALE, value);
    }

    public static void setNativeMirrorIntData(NativeMirror nativeMirror, int index, int value) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        UnsafeAdapter.UNSAFE.putInt(address + (long) index * Unsafe.ARRAY_INT_INDEX_SCALE, value);
    }

    public static void setNativeMirrorLogicalData(NativeMirror nativeMirror, int index, byte logical) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;
        UnsafeAdapter.UNSAFE.putInt(address + (long) index * Unsafe.ARRAY_INT_INDEX_SCALE, RRuntime.logical2int(logical));
    }

    public static void setNativeMirrorStringData(NativeMirror nativeMirror, int index, CharSXPWrapper value) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;

        long asPointer = getPointer(value);
        UnsafeAdapter.UNSAFE.putLong(address + (long) index * Long.BYTES, asPointer);
    }

    public static void setNativeMirrorListData(NativeMirror nativeMirror, int index, Object value) {
        long address = nativeMirror.dataAddress;
        assert address != 0;
        assert index < nativeMirror.length;

        if (value instanceof RBaseObject) {
            long asPointer = getPointer((RBaseObject) value);
            UnsafeAdapter.UNSAFE.putLong(address + (long) index * Long.BYTES, asPointer);
        } else {
            throw RInternalError.shouldNotReachHere();
        }
    }

    public static double[] copyDoubleNativeData(NativeMirror mirrorObj) {
        NativeMirror mirror = mirrorObj;
        long address = mirror.dataAddress;
        assert address != 0;
        double[] data = new double[(int) mirror.length];
        UnsafeAdapter.UNSAFE.copyMemory(null, address, data, Unsafe.ARRAY_DOUBLE_BASE_OFFSET, (long) data.length * Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
        return data;
    }

    public static double[] copyComplexNativeData(NativeMirror mirrorObj) {
        NativeMirror mirror = mirrorObj;
        long address = mirror.dataAddress;
        assert address != 0;
        double[] data = new double[(int) (mirror.length << 1)];
        UnsafeAdapter.UNSAFE.copyMemory(null, address, data, Unsafe.ARRAY_DOUBLE_BASE_OFFSET, (long) data.length * Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
        return data;
    }

    public static int[] copyIntNativeData(NativeMirror mirrorObj) {
        NativeMirror mirror = mirrorObj;
        long address = mirror.dataAddress;
        assert address != 0;
        int[] data = new int[(int) mirror.length];
        UnsafeAdapter.UNSAFE.copyMemory(null, address, data, Unsafe.ARRAY_INT_BASE_OFFSET, (long) data.length * Unsafe.ARRAY_INT_INDEX_SCALE);
        return data;
    }

    @TruffleBoundary
    public static byte[] copyByteNativeData(NativeMirror mirrorObj) {
        NativeMirror mirror = mirrorObj;
        long address = mirror.dataAddress;
        assert address != 0;
        byte[] data = new byte[(int) mirror.length];
        UnsafeAdapter.UNSAFE.copyMemory(null, address, data, Unsafe.ARRAY_BYTE_BASE_OFFSET, (long) data.length * Unsafe.ARRAY_BYTE_INDEX_SCALE);
        return data;
    }

    public static String[] copyStringNativeData(NativeMirror mirrorObj) {
        NativeMirror mirror = mirrorObj;
        long address = mirror.dataAddress;
        assert address != 0;
        String[] data = new String[(int) mirror.length];
        for (int i = 0; i < mirror.length; i++) {
            long elemAddr = UnsafeAdapter.UNSAFE.getLong(address + (long) i * Long.BYTES);
            assert elemAddr != 0L;
            Object elem = lookup(elemAddr);
            assert elem instanceof CharSXPWrapper;
            data[i] = ((CharSXPWrapper) elem).getContents();
        }
        return data;
    }

    public static Object[] copyListNativeData(NativeMirror mirrorObj) {
        NativeMirror mirror = mirrorObj;
        long address = mirror.dataAddress;
        assert address != 0;
        Object[] data = new Object[(int) mirror.length];
        for (int i = 0; i < mirror.length; i++) {
            long elemAddr = UnsafeAdapter.UNSAFE.getLong(address + (long) i * Long.BYTES);
            assert elemAddr != 0L;
            Object elem = lookup(elemAddr);
            data[i] = elem;
        }
        return data;
    }

    // methods operating on vectors that may have a native mirror assigned:

    private static final Assumption noIntNative = Truffle.getRuntime().createAssumption("noIntNative");
    private static final Assumption noLogicalNative = Truffle.getRuntime().createAssumption("noLogicalNative");
    private static final Assumption noDoubleNative = Truffle.getRuntime().createAssumption("noDoubleNative");
    private static final Assumption noComplexNative = Truffle.getRuntime().createAssumption("noComplexNative");
    private static final Assumption noRawNative = Truffle.getRuntime().createAssumption("noRawNative");
    private static final Assumption noCharSXPNative = Truffle.getRuntime().createAssumption("noCharSXPNative");
    private static final Assumption noStringNative = Truffle.getRuntime().createAssumption("noStringNative");
    private static final Assumption noListNative = Truffle.getRuntime().createAssumption("noListNative");

    static int getData(RIntVector vector, int[] data, int index) {
        if (noIntNative.isValid() || data != null) {
            return data[index];
        } else {
            return getIntNativeMirrorData(vector.getNativeMirror(), index);
        }
    }

    static int getDataLength(RIntVector vector, int[] data) {
        if (noIntNative.isValid() || data != null) {
            return data.length;
        } else {
            return getDataLengthFromMirror(vector.getNativeMirror());
        }
    }

    static void setDataLength(RIntVector vector, int[] data, int length) {
        if (noIntNative.isValid() || data != null) {
            toNative(vector);
            allocateNativeContents(vector, data, length);
        } else {
            (vector.getNativeMirror()).length = length;
        }
    }

    static int getTrueDataLength(RIntVector vector) {
        if (vector.getNativeMirror() == null) {
            return 0;
        }
        return (int) vector.getNativeMirror().truelength;
    }

    static void setTrueDataLength(RIntVector vector, int truelength) {
        toNative(vector);
        vector.getNativeMirror().truelength = truelength;
    }

    private static int getDataLengthFromMirror(NativeMirror mirror) {
        return (int) mirror.length;
    }

    static void setData(RIntVector vector, int[] data, int index, int value) {
        if (noIntNative.isValid() || data != null) {
            data[index] = value;
        } else {
            long address = vector.getNativeMirror().dataAddress;
            assert address != 0;
            UnsafeAdapter.UNSAFE.putInt(address + (long) index * Unsafe.ARRAY_INT_INDEX_SCALE, value);
        }
    }

    static byte getData(RLogicalVector vector, byte[] data, int index) {
        if (noLogicalNative.isValid() || data != null) {
            return data[index];
        } else {
            long address = vector.getNativeMirror().dataAddress;
            assert address != 0;
            return RRuntime.int2logical(UnsafeAdapter.UNSAFE.getInt(address + (long) index * Unsafe.ARRAY_INT_INDEX_SCALE));
        }
    }

    static int getDataLength(RLogicalVector vector, byte[] data) {
        if (noLogicalNative.isValid() || data != null) {
            return data.length;
        } else {
            return (int) vector.getNativeMirror().length;
        }
    }

    static void setData(RLogicalVector vector, byte[] data, int index, byte value) {
        if (noLogicalNative.isValid() || data != null) {
            data[index] = value;
        } else {
            long address = vector.getNativeMirror().dataAddress;
            assert address != 0;
            UnsafeAdapter.UNSAFE.putInt(address + (long) index * Unsafe.ARRAY_INT_INDEX_SCALE, RRuntime.logical2int(value));
        }
    }

    static void setDataLength(RLogicalVector vector, byte[] data, int length) {
        if (noLogicalNative.isValid() || data != null) {
            toNative(vector);
            allocateNativeContents(vector, data, length);
        } else {
            vector.getNativeMirror().length = length;
        }
    }

    static int getTrueDataLength(RLogicalVector vector) {
        if (vector.getNativeMirror() == null) {
            return 0;
        }
        return (int) vector.getNativeMirror().truelength;
    }

    static void setTrueDataLength(RLogicalVector vector, int truelength) {
        toNative(vector);
        vector.getNativeMirror().truelength = truelength;
    }

    static byte getData(RRawVector vector, byte[] data, int index) {
        if (noRawNative.isValid() || data != null) {
            return data[index];
        } else {
            return getRawNativeMirrorData(vector.getNativeMirror(), index);
        }
    }

    static int getDataLength(RRawVector vector, byte[] data) {
        if (noRawNative.isValid() || data != null) {
            return data.length;
        } else {
            return (int) vector.getNativeMirror().length;
        }
    }

    static void setDataLength(RRawVector vector, byte[] data, int length) {
        if (noRawNative.isValid() || data != null) {
            toNative(vector);
            allocateNativeContents(vector, data, length);
        } else {
            vector.getNativeMirror().length = length;
        }
    }

    static int getTrueDataLength(RRawVector vector) {
        if (vector.getNativeMirror() == null) {
            return 0;
        }
        return (int) vector.getNativeMirror().truelength;
    }

    static void setTrueDataLength(RRawVector vector, int truelength) {
        toNative(vector);
        vector.getNativeMirror().truelength = truelength;
    }

    static void setData(RRawVector vector, byte[] data, int index, byte value) {
        if (noRawNative.isValid() || data != null) {
            data[index] = value;
        } else {
            long address = vector.getNativeMirror().dataAddress;
            assert address != 0;
            UnsafeAdapter.UNSAFE.putInt(address + (long) index * Unsafe.ARRAY_BYTE_INDEX_SCALE, value);
        }
    }

    static double getData(RDoubleVector vector, double[] data, int index) {
        if (noDoubleNative.isValid() || data != null) {
            return data[index];
        } else {
            return getDoubleNativeMirrorData(vector.getNativeMirror(), index);
        }
    }

    static int getDataLength(RDoubleVector vector, double[] data) {
        if (noDoubleNative.isValid() || data != null) {
            return data.length;
        } else {
            return (int) vector.getNativeMirror().length;
        }
    }

    static void setDataLength(RDoubleVector vector, double[] data, int length) {
        if (noDoubleNative.isValid() || data != null) {
            toNative(vector);
            allocateNativeContents(vector, data, length);
        } else {
            vector.getNativeMirror().length = length;
        }
    }

    static int getTrueDataLength(RDoubleVector vector) {
        if (vector.getNativeMirror() == null) {
            return 0;
        }
        return (int) vector.getNativeMirror().truelength;
    }

    static void setTrueDataLength(RDoubleVector vector, int truelength) {
        toNative(vector);
        vector.getNativeMirror().truelength = truelength;
    }

    static void setData(RDoubleVector vector, double[] data, int index, double value) {
        if (noDoubleNative.isValid() || data != null) {
            data[index] = value;
        } else {
            setNativeMirrorDoubleData(vector.getNativeMirror(), index, value);
        }
    }

    static RComplex getData(RComplexVector vector, double[] data, int index) {
        if (noComplexNative.isValid() || data != null) {
            return RComplex.valueOf(data[index * 2], data[index * 2 + 1]);
        } else {
            return getComplexNativeMirrorData(vector.getNativeMirror(), index);
        }
    }

    static double getRawComplexData(RComplexVector vector, double[] data, int index) {
        if (noComplexNative.isValid() || data != null) {
            return data[index];
        } else {
            return getDoubleNativeMirrorData(vector.getNativeMirror(), index);
        }
    }

    static double getDataR(RComplexVector vector, double[] data, int index) {
        if (noComplexNative.isValid() || data != null) {
            return data[index * 2];
        } else {
            return getComplexNativeMirrorDataR(vector.getNativeMirror(), index);
        }
    }

    static double getDataI(RComplexVector vector, double[] data, int index) {
        if (noComplexNative.isValid() || data != null) {
            return data[index * 2 + 1];
        } else {
            return getComplexNativeMirrorDataI(vector.getNativeMirror(), index);
        }
    }

    static double getComplexPart(RComplexVector vector, double[] data, int index) {
        if (noComplexNative.isValid() || data != null) {
            return data[index];
        } else {
            return getDoubleNativeMirrorData(vector.getNativeMirror(), index);
        }
    }

    static int getDataLength(RComplexVector vector, double[] data) {
        if (noComplexNative.isValid() || data != null) {
            return data.length >> 1;
        } else {
            return (int) vector.getNativeMirror().length;
        }
    }

    static void setDataLength(RComplexVector vector, double[] data, int length) {
        if (noComplexNative.isValid() || data != null) {
            toNative(vector);
            allocateNativeContents(vector, data, length);
        } else {
            vector.getNativeMirror().length = length;
        }
    }

    static int getTrueDataLength(RComplexVector vector) {
        if (vector.getNativeMirror() == null) {
            return 0;
        }
        return (int) vector.getNativeMirror().truelength;
    }

    static void setTrueDataLength(RComplexVector vector, int truelength) {
        toNative(vector);
        vector.getNativeMirror().truelength = truelength;
    }

    static void setData(RComplexVector vector, double[] data, int index, double re, double im) {
        if (noComplexNative.isValid() || data != null) {
            data[index * 2] = re;
            data[index * 2 + 1] = im;
        } else {
            long address = vector.getNativeMirror().dataAddress;
            assert address != 0;
            UnsafeAdapter.UNSAFE.putDouble(address + index * 2L * Unsafe.ARRAY_DOUBLE_INDEX_SCALE, re);
            UnsafeAdapter.UNSAFE.putDouble(address + (index * 2L + 1L) * Unsafe.ARRAY_DOUBLE_INDEX_SCALE, im);
        }
    }

    static void setData(RComplexVector vector, double[] data, int index, double value) {
        if (noComplexNative.isValid() || data != null) {
            data[index] = value;
        } else {
            long address = vector.getNativeMirror().dataAddress;
            assert address != 0;
            UnsafeAdapter.UNSAFE.putDouble(address + (long) index * Unsafe.ARRAY_DOUBLE_INDEX_SCALE, value);
        }
    }

    static void setDataLength(RStringVector vector, CharSXPWrapper[] data, int length) {
        if (noStringNative.isValid() || data != null) {
            toNative(vector);
            allocateNativeContents(vector, data, length);
        } else {
            vector.getNativeMirror().length = length;
        }
    }

    static int getTrueDataLength(RStringVector vector) {
        if (vector.getNativeMirror() == null) {
            return 0;
        }
        return (int) vector.getNativeMirror().truelength;
    }

    static void setTrueDataLength(RStringVector vector, int truelength) {
        toNative(vector);
        vector.getNativeMirror().truelength = truelength;
    }

    static int getTrueDataLength(CharSXPWrapper charsxp) {
        if (charsxp.getNativeMirror() == null) {
            return 0;
        }
        return (int) charsxp.getNativeMirror().truelength;
    }

    static void setTrueDataLength(CharSXPWrapper charsxp, int truelength) {
        toNative(charsxp);
        charsxp.getNativeMirror().truelength = truelength;
    }

    static Object getData(RList list, Object[] data, int index) {
        if (noListNative.isValid() || data != null) {
            return data[index];
        } else {
            return getListElementNativeMirrorData(list.getNativeMirror(), index);
        }
    }

    static void setData(RList list, Object[] data, int index, Object value) {
        assert data != null;
        data[index] = value;
        if (!noListNative.isValid() && list.isNativized()) {
            NativeDataAccess.setNativeMirrorListData(list.getNativeMirror(), index, value);
        }
    }

    static int getDataLength(RList vector, Object[] data) {
        if (noListNative.isValid() || data != null) {
            return data.length;
        } else {
            return getDataLengthFromMirror(vector.getNativeMirror());
        }
    }

    static void setDataLength(RList list, Object[] data, int length) {
        if (noListNative.isValid() || data != null) {
            toNative(list);
            allocateNativeContents(list, data, length);
        } else {
            list.getNativeMirror().length = length;
        }
    }

    static int getTrueDataLength(RList list) {
        if (list.getNativeMirror() == null) {
            return 0;
        }
        return (int) list.getNativeMirror().truelength;
    }

    static void setTrueDataLength(RList list, int truelength) {
        toNative(list);
        list.getNativeMirror().truelength = truelength;
    }

    static String getData(CharSXPWrapper charSXPWrapper, String data) {
        if (noCharSXPNative.isValid() || data != null) {
            return data;
        } else {
            NativeMirror mirror = charSXPWrapper.getNativeMirror();
            long address = mirror.dataAddress;
            assert address != 0;
            int length = 0;
            while (length < mirror.length && UnsafeAdapter.UNSAFE.getByte(address + length) != 0) {
                length++;
            }
            byte[] bytes = new byte[length];
            UnsafeAdapter.UNSAFE.copyMemory(null, address, bytes, Unsafe.ARRAY_BYTE_BASE_OFFSET, length);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static byte getDataAt(CharSXPWrapper charSXPWrapper, byte[] data, int index) {
        if (noCharSXPNative.isValid() || data != null) {
            return data[index];
        } else {
            NativeMirror mirror = charSXPWrapper.getNativeMirror();
            long address = mirror.dataAddress;
            assert address != 0;
            assert index < mirror.length;
            return UnsafeAdapter.UNSAFE.getByte(address + index);
        }
    }

    static int getDataLength(CharSXPWrapper charSXPWrapper, byte[] data) {
        if (noCharSXPNative.isValid() || data != null) {
            return data.length;
        } else {
            NativeMirror mirror = charSXPWrapper.getNativeMirror();
            long address = mirror.dataAddress;
            assert address != 0;
            int length = 0;
            while (length < mirror.length && UnsafeAdapter.UNSAFE.getByte(address + length) != 0) {
                length++;
            }
            return length;
        }
    }

    static int getDataLength(RStringVector vector, Object[] data) {
        if (noStringNative.isValid() || data != null) {
            return data.length;
        } else {
            return getDataLengthFromMirror(vector.getNativeMirror());
        }
    }

    static String getData(RStringVector vector, Object data, int index) {
        if (noStringNative.isValid() || data != null) {
            Object localData = data;
            if (RStringVector.noWrappedStrings.isValid() || localData instanceof String[]) {
                return ((String[]) localData)[index];
            }
            assert data instanceof CharSXPWrapper[] : localData;
            assert ((CharSXPWrapper[]) localData)[index] != null;
            return ((CharSXPWrapper[]) localData)[index].getContents();
        } else {
            return getStringNativeMirrorData(vector.getNativeMirror(), index).getContents();
        }
    }

    static void setData(RStringVector vector, Object data, int index, String value) {
        assert data != null;
        if (RStringVector.noWrappedStrings.isValid() || data instanceof String[]) {
            assert !vector.isNativized();
            ((String[]) data)[index] = value;
        } else {
            assert data instanceof CharSXPWrapper[] : data;
            CharSXPWrapper elem = CharSXPWrapper.create(value);
            ((CharSXPWrapper[]) data)[index] = elem;

            if (!noStringNative.isValid() && vector.isNativized()) {
                NativeDataAccess.setNativeMirrorStringData(vector.getNativeMirror(), index, elem);
            }
        }
    }

    static void setData(RStringVector vector, CharSXPWrapper[] data, int index, CharSXPWrapper value) {
        assert data != null;
        data[index] = value;
        if (!noStringNative.isValid() && vector.isNativized()) {
            NativeDataAccess.setNativeMirrorStringData(vector.getNativeMirror(), index, value);
        }
    }

    public static long getNativeDataAddress(RBaseObject obj) {
        NativeMirror mirror = obj.getNativeMirror();
        return mirror == null ? 0 : mirror.dataAddress;
    }

    static boolean isAllocated(RStringVector obj) {
        if (!noStringNative.isValid()) {
            NativeMirror mirror = obj.getNativeMirror();
            return mirror != null && mirror.dataAddress != 0;
        }
        return false;
    }

    static boolean isAllocated(CharSXPWrapper obj) {
        if (!noCharSXPNative.isValid()) {
            NativeMirror mirror = obj.getNativeMirror();
            return mirror != null && mirror.dataAddress != 0;
        }
        return false;
    }

    static boolean isAllocated(RList obj) {
        if (!noListNative.isValid()) {
            NativeMirror mirror = obj.getNativeMirror();
            return mirror != null && mirror.dataAddress != 0;
        }
        return false;
    }

    static long allocateNativeContents(RLogicalVector vector, byte[] data, int length) {
        NativeMirror mirror = vector.getNativeMirror();
        assert mirror != null;
        assert mirror.dataAddress == 0 ^ data == null : "mirror.dataAddress=" + mirror.dataAddress;
        if (mirror.dataAddress == 0) {
            assert mirror.length == 0 && mirror.truelength == 0 : "mirror.length=" + mirror.length + ", mirror.truelength=" + mirror.truelength;
            int[] intArray = new int[data.length];
            for (int i = 0; i < data.length; i++) {
                intArray[i] = RRuntime.logical2int(data[i]);
            }
            noLogicalNative.invalidate();
            mirror.allocateNative(intArray, length, data.length, Unsafe.ARRAY_INT_BASE_OFFSET, Unsafe.ARRAY_INT_INDEX_SCALE);
        }
        return mirror.dataAddress;
    }

    static long allocateNativeContents(RIntVector vector, int[] data, int length) {
        NativeMirror mirror = vector.getNativeMirror();
        assert mirror != null;
        assert mirror.dataAddress == 0 ^ data == null : "mirror.dataAddress=" + mirror.dataAddress;
        if (mirror.dataAddress == 0) {
            assert mirror.length == 0 && mirror.truelength == 0 : "mirror.length=" + mirror.length + ", mirror.truelength=" + mirror.truelength;
            noIntNative.invalidate();
            mirror.allocateNative(data, length, data.length, Unsafe.ARRAY_INT_BASE_OFFSET, Unsafe.ARRAY_INT_INDEX_SCALE);
        }
        return mirror.dataAddress;
    }

    static long allocateNativeContents(RRawVector vector, byte[] data, int length) {
        NativeMirror mirror = vector.getNativeMirror();
        assert mirror != null;
        assert mirror.dataAddress == 0 ^ data == null : "mirror.dataAddress=" + mirror.dataAddress;
        if (mirror.dataAddress == 0) {
            assert mirror.length == 0 && mirror.truelength == 0 : "mirror.length=" + mirror.length + ", mirror.truelength=" + mirror.truelength;
            noRawNative.invalidate();
            mirror.allocateNative(data, length, data.length, Unsafe.ARRAY_BYTE_BASE_OFFSET, Unsafe.ARRAY_BYTE_INDEX_SCALE);
        }
        return mirror.dataAddress;
    }

    static long allocateNativeContents(RDoubleVector vector, double[] data, int length) {
        NativeMirror mirror = vector.getNativeMirror();
        assert mirror != null;
        assert mirror.dataAddress == 0 ^ data == null : "mirror.dataAddress=" + mirror.dataAddress;
        if (mirror.dataAddress == 0) {
            assert mirror.length == 0 && mirror.truelength == 0 : "mirror.length=" + mirror.length + ", mirror.truelength=" + mirror.truelength;
            noDoubleNative.invalidate();
            mirror.allocateNative(data, length, data.length, Unsafe.ARRAY_DOUBLE_BASE_OFFSET, Unsafe.ARRAY_DOUBLE_INDEX_SCALE);
        }
        return mirror.dataAddress;
    }

    static long allocateNativeContents(RComplexVector vector, double[] data, int length) {
        NativeMirror mirror = vector.getNativeMirror();
        assert mirror != null;
        assert mirror.dataAddress == 0 ^ data == null : "mirror.dataAddress=" + mirror.dataAddress;
        if (mirror.dataAddress == 0) {
            assert mirror.length == 0 && mirror.truelength == 0 : "mirror.length=" + mirror.length + ", mirror.truelength=" + mirror.truelength;
            noComplexNative.invalidate();
            mirror.allocateNative(data, length, data.length, Unsafe.ARRAY_DOUBLE_BASE_OFFSET, Unsafe.ARRAY_DOUBLE_INDEX_SCALE * 2);
        }
        return mirror.dataAddress;
    }

    static long allocateNativeContents(RStringVector vector, CharSXPWrapper[] charSXPdata, int length) {
        NativeMirror mirror = vector.getNativeMirror();
        assert mirror != null;
        assert mirror.dataAddress == 0 ^ charSXPdata == null : "mirror.dataAddress=" + mirror.dataAddress;
        if (mirror.dataAddress == 0) {
            noStringNative.invalidate();
            // Note: shall the character vector become writeable and not only read-only, we should
            // crate assumption like for other vector types
            mirror.allocateNative(charSXPdata);
            mirror.length = length;
        }
        return mirror.dataAddress;
    }

    static long allocateNativeContents(CharSXPWrapper vector, byte[] data) {
        NativeMirror mirror = vector.getNativeMirror();
        assert mirror != null;
        assert mirror.dataAddress == 0 ^ data == null;
        if (mirror.dataAddress == 0) {
            noCharSXPNative.invalidate();
            mirror.allocateNativeString(data);
        }
        return mirror.dataAddress;
    }

    static long allocateNativeContents(RList list, Object[] elements, int length) {
        NativeMirror mirror = list.getNativeMirror();
        assert mirror != null;
        if (mirror.dataAddress == 0) {
            noListNative.invalidate();
            // Note: shall the list become writeable and not only read-only, we should
            // crate assumption like for other vector types
            mirror.allocateNative(elements);
            mirror.length = length;
        }
        return mirror.dataAddress;
    }

    @TruffleBoundary
    public static long allocateNativeStringArray(String[] data) {
        // We allocate contiguous memory that we'll use to store both the array of pointers (char**)
        // and the arrays of characters (char*). Given vector of size N, we allocate memory for N
        // addresses (long) and after those we put individual strings character by character, the
        // pointers from the first segment of this memory will be pointing to the starts of those
        // strings.
        int length = data.length;
        int size = data.length * Long.BYTES;
        byte[][] bytes = new byte[data.length][];
        for (int i = 0; i < length; i++) {
            String element = data[i];
            bytes[i] = element.getBytes(StandardCharsets.US_ASCII);
            size += bytes[i].length + 1;
        }
        long dataAddress = allocateNativeMemory(size);
        long ptr = dataAddress + length * Long.BYTES; // start of the actual character data
        for (int i = 0; i < length; i++) {
            UnsafeAdapter.UNSAFE.putLong(dataAddress + i * 8L, ptr);
            UnsafeAdapter.UNSAFE.copyMemory(bytes[i], Unsafe.ARRAY_BYTE_BASE_OFFSET, null, ptr, bytes[i].length);
            ptr += bytes[i].length;
            UnsafeAdapter.UNSAFE.putByte(ptr++, (byte) 0);
        }
        assert ptr == dataAddress + size : "should have filled everything";
        return dataAddress;
    }

    @TruffleBoundary
    public static String[] releaseNativeStringArray(long address, int length) {
        assert address != 0;
        try {
            String[] data = new String[length];
            for (int i = 0; i < length; i++) {
                long ptr = UnsafeAdapter.UNSAFE.getLong(address + i * 8L);
                data[i] = readNativeString(ptr);
            }
            return data;
        } finally {
            freeNativeMemory(address);
        }
    }

    @TruffleBoundary
    public static String readNativeString(long addr) {
        int len;
        for (len = 0; UnsafeAdapter.UNSAFE.getByte(addr + len) != 0; len++) {
        }
        byte[] bytes = new byte[len];
        UnsafeAdapter.UNSAFE.copyMemory(null, addr, bytes, Unsafe.ARRAY_BYTE_BASE_OFFSET, len);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    public static void setNativeContents(RBaseObject obj, long address, int length) {
        assert obj.getNativeMirror() != null;
        if (noDoubleNative.isValid() && obj instanceof RDoubleVector) {
            noDoubleNative.invalidate();
        } else if (noComplexNative.isValid() && obj instanceof RComplexVector) {
            noComplexNative.invalidate();
        } else if (noIntNative.isValid() && obj instanceof RIntVector) {
            noIntNative.invalidate();
        } else if (noRawNative.isValid() && obj instanceof RRawVector) {
            noRawNative.invalidate();
        } else if (noLogicalNative.isValid() && obj instanceof RLogicalVector) {
            noLogicalNative.invalidate();
        } else if (noStringNative.isValid() && obj instanceof RStringVector) {
            noStringNative.invalidate();
        }
        NativeMirror mirror = obj.getNativeMirror();
        mirror.setDataAddress(address);
        mirror.length = length;

        mirror.external = true;
    }

    public static void setNativeWrapper(RBaseObject obj, Object wrapper) {
        NativeMirror mirror = obj.getNativeMirror();
        if (mirror == null) {
            mirror = new NativeMirror(obj, 0);
            obj.setNativeMirror(mirror);
        }
        mirror.nativeWrapperRef = new NativeWrapperReference(wrapper);
    }

    public static Object getNativeWrapper(RBaseObject obj) {
        NativeMirror mirror = obj.getNativeMirror();
        if (mirror == null) {
            return null;
        } else {
            Reference<?> ref = mirror.nativeWrapperRef;
            return (ref != null) ? ref.get() : null;
        }
    }

    private static long allocateNativeMemory(long bytes) {
        LOGGER.finest(() -> String.format("Going to allocate %d bytes of native memory", bytes));
        long result = UnsafeAdapter.UNSAFE.allocateMemory(bytes);
        LOGGER.finest(() -> String.format("Done allocating %d bytes of native memory, result: %x", bytes, result));
        return result;
    }

    private static void freeNativeMemory(long address) {
        // Uncomment for debugging, this cannot be logged via Truffle logger, because it runs on
        // dedicated thread without any Truffle context
        // System.out.printf("DEBUG: freeing %x\n", address);
        UnsafeAdapter.UNSAFE.freeMemory(address);
    }

    /**
     * This final class is needed so that the {@link Reference#get()} method can be inlined.
     */
    private static final class NativeWrapperReference extends WeakReference<Object> {
        NativeWrapperReference(Object nativeWrapper) {
            super(nativeWrapper);
        }
    }

    public interface NativeDataInspectorMBean {
        int getNativeMirrorsSize();

        String getObject(String idString);

        String getAttribute(String idString, String attrName);

        String getNativeIdFromAddress(String dataAddressString);
    }

    public static class NativeDataInspector implements NativeDataInspectorMBean {

        @Override
        public String getObject(String nativeIdString) {
            return lookup(Long.decode(nativeIdString)).toString();
        }

        @Override
        public String getAttribute(String idString, String attrName) {
            RBaseObject obj = (RBaseObject) lookup(Long.decode(idString));
            if (obj instanceof RAttributable) {
                return "" + ((RAttributable) obj).getAttr(attrName);
            } else {
                return "";
            }
        }

        @Override
        public int getNativeMirrorsSize() {
            return NativeDataAccess.nativeMirrors.size();
        }

        @Override
        public String getNativeIdFromAddress(String dataAddressString) {
            assert NativeDataAccess.dataAddressToNativeMirrors != null;
            NativeMirror nativeMirror = NativeDataAccess.dataAddressToNativeMirrors.get(Long.decode(dataAddressString));
            return nativeMirror == null ? "" : String.format("%16x", nativeMirror.id);
        }

    }

    static void initMBean() {
        if (System.getenv(FastROptions.NATIVE_DATA_INSPECTOR) != null) {
            try {
                MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
                ObjectName name = new ObjectName("FastR:type=JMX,name=NativeDataInspector");
                NativeDataInspector resource = new NativeDataInspector();
                mbs.registerMBean(resource, name);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static {
        initMBean();
    }
}
