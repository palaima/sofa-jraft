/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

/**
 *
 * @author jiachun.fjc
 */
public final class UnsafeHelper {

    private static final Logger          LOG             = LoggerFactory.getLogger(UnsafeHelper.class);

    private static final IUnsafeAccessor UNSAFE_ACCESSOR = getUnsafeAccessor0();

    public static boolean supportSignal() {
        return UNSAFE_ACCESSOR != null;
    }

    public static boolean unmap(final MappedByteBuffer cb) {
        if (UNSAFE_ACCESSOR != null) {
            UNSAFE_ACCESSOR.unmap(cb);
            return true;
        }
        return false;
    }

    private static IUnsafeAccessor getUnsafeAccessor0() {
        final boolean oldJdk = hasUnsafeOldJdk();
        final boolean hasUnsafe = hasUnsafe0();
        final boolean hasInternalUnsafe = hasUnsafe1();
        if (oldJdk) {
            return new UnsafeOldJdkAccessor();
        } else if (hasUnsafe) {
            return new UnsafeAccessor();
        } else if (hasInternalUnsafe) {
            return new UnsafeInternalAccessor();
        } else {
            return null;
        }
    }

    private static boolean hasUnsafe0() {
        try {
            Class.forName("sun.misc.Unsafe");
            return true;
        } catch (final Throwable t) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("sun.misc.Unsafe: unavailable.", t);
            }
        }
        return false;
    }

    private static boolean hasUnsafe1() {
        try {
            Class.forName("jdk.internal.misc.Unsafe");
            return true;
        } catch (final Throwable t) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("jdk.internal.misc.Unsafe: unavailable.", t);
            }
        }
        return false;
    }

    private static boolean hasUnsafeOldJdk() {
        return System.getProperty("java.specification.version", "99").startsWith("1.");
    }

    private UnsafeHelper() {
    }

    static class UnsafeAccessor implements IUnsafeAccessor {

        @Override
        public void unmap(final MappedByteBuffer cb) {
            try {
                final Class unsafeClass = Class.forName("sun.misc.Unsafe");
                final Method clean = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                clean.setAccessible(true);
                final Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafeField.setAccessible(true);
                final Object theUnsafe = theUnsafeField.get(null);
                clean.invoke(theUnsafe, cb);
            } catch (final Throwable ex) {
                LOG.error("Fail to un-mapped segment file.", ex);
            }
        }
    }

    static class UnsafeInternalAccessor implements IUnsafeAccessor {

        @Override
        public void unmap(final MappedByteBuffer cb) {
            try {
                final Class unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
                final Method clean = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                clean.setAccessible(true);
                final Field theUnsafeField = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafeField.setAccessible(true);
                final Object theUnsafe = theUnsafeField.get(null);
                clean.invoke(theUnsafe, cb);
            } catch (final Throwable ex) {
                LOG.error("Fail to un-mapped segment file.", ex);
            }
        }
    }

    static class UnsafeOldJdkAccessor implements IUnsafeAccessor {

        @Override
        public void unmap(final MappedByteBuffer cb) {
            try {
                final Method cleaner = cb.getClass().getMethod("cleaner");
                cleaner.setAccessible(true);
                final Method clean = Class.forName("sun.misc.Cleaner").getMethod("clean");
                clean.setAccessible(true);
                clean.invoke(cleaner.invoke(cb));
            } catch (final Throwable ex) {
                LOG.error("Fail to un-mapped segment file.", ex);
            }
        }
    }

    interface IUnsafeAccessor {
        void unmap(final MappedByteBuffer cb);
    }
}
